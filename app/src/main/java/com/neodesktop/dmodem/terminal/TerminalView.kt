// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.net.toUri
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import com.neodesktop.dmodem.settings.CursorShape
import com.neodesktop.dmodem.settings.TerminalSettings
import com.neodesktop.dmodem.settings.VolumeKeys
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The terminal's screen: draws the session's emulator, takes the keys
 * (the soft keyboard's and a hardware keyboard's) to it, scrolls back,
 * zooms with a pinch, selects and copies text, and opens links.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var session: TerminalSession? = null
        set(v) {
            field?.onChange = null
            field = v
            v?.onChange = { postRedraw() }
            resizeEmulator()
            invalidate()
        }

    var settings = TerminalSettings()
        private set
    private var theme = TermTheme.VGA

    /** the user pinched: the new size (sp), to keep (fixed columns are turned off by it) */
    var onFontSizeChanged: ((Float) -> Unit)? = null

    // the text's metrics
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    // (shapes on whole pixels, not anti-aliased: no seams between cells)
    private val fillPaint = Paint().apply { isAntiAlias = false }
    private var fontPx = 0f
    private var cellW = 1f
    private var cellH = 1f
    private var ascent = 0f

    /** the rows scrolled back into the history (0: the live screen) */
    private var scrollRows = 0
    private var scrollRemainder = 0f
    private val scroller = OverScroller(context)

    // blinking
    private var blinkOn = true
    private val blink = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    // the selection: rows as the emulator's line() has them
    private var selecting = false
    private var selStartRow = 0
    private var selStartCol = 0
    private var selEndRow = 0
    private var selEndCol = 0
    private var actionMode: ActionMode? = null
    private var linkAtPress: String? = null

    @Volatile
    private var redrawPosted = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "Terminal"
        applySettings(settings)
    }

    private var regularFace: Typeface = Typeface.MONOSPACE
    private var boldFace: Typeface? = null

    fun applySettings(s: TerminalSettings) {
        val fontChanged = s.font != settings.font || s.ligatures != settings.ligatures || boldFace == null
        val sizeChanged = fontChanged || s.fontSize != settings.fontSize || s.fixedColumns != settings.fixedColumns
        settings = s
        if (fontChanged) {
            regularFace = FontCache.typeface(context, s.font, false)
            boldFace = if (s.font.hasBold) FontCache.typeface(context, s.font, true) else null
            textPaint.typeface = regularFace
            // (ligatures off: one glyph per cell, as a terminal has it)
            textPaint.fontFeatureSettings = if (s.ligatures) null else "'liga' 0, 'calt' 0, 'clig' 0, 'dlig' 0"
        }
        theme = TermTheme.byId(s.themeId)
        keepScreenOn = s.keepScreenOn
        session?.let {
            // (the keys and the echo are the modem session's from the settings; a
            // connection has its entry's)
            if (it.kind == TerminalSession.Kind.MODEM) {
                it.keys.backspace = s.backspace
                it.keys.enter = s.enter
                it.localEcho = s.localEcho
            }
            synchronized(it.emulator) {
                it.emulator.historyMax = s.scrollback
                it.emulator.answerback = s.answerback
            }
        }
        if (sizeChanged || fontPx == 0f) measureFont()
        resizeEmulator()
        restartBlink()
        invalidate()
    }

    // ------------------------------------------------------------------
    // size

    private fun measureFont() {
        val w = width
        fontPx = if (settings.fixedColumns > 0 && w > 0) {
            // the size whose cells fit the columns in the width
            textPaint.textSize = 100f
            val per = textPaint.measureText("M") / 100f
            w / (settings.fixedColumns * per)
        } else {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, settings.fontSize, resources.displayMetrics)
        }
        fontPx = fontPx.coerceIn(4f, 200f)
        textPaint.textSize = fontPx
        cellW = textPaint.measureText("M")
        // the font's ascent and descent, or its glyphs' ink where that goes beyond them
        // (some fonts declare less than they draw: OpenDyslexic)
        val fm = textPaint.fontMetrics
        val ink = Rect()
        textPaint.getTextBounds(METRIC_SAMPLE, 0, METRIC_SAMPLE.length, ink)
        val up = max(-fm.ascent, -ink.top.toFloat())
        val down = max(fm.descent, ink.bottom.toFloat())
        cellH = floor(up + down + 0.5f).coerceAtLeast(1f)
        ascent = up
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        measureFont()
        resizeEmulator()
    }

    private fun resizeEmulator() {
        val s = session ?: return
        if (width <= 0 || height <= 0) return
        val cols = max(1, (width / cellW).toInt())
        val rows = max(1, (height / cellH).toInt())
        s.resize(cols, rows)
        scrollRows = scrollRows.coerceIn(0, historySize())
    }

    private fun historySize(): Int = session?.let { synchronized(it.emulator) { it.emulator.historySize } } ?: 0

    // ------------------------------------------------------------------
    // drawing

    private fun postRedraw() {
        if (redrawPosted) return
        redrawPosted = true
        postOnAnimation {
            redrawPosted = false
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        // (the view's own bounds: drawColor() would fill whatever clip it was given)
        fillPaint.color = if (reverse()) theme.fgArgb else theme.bgArgb
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        val s = session ?: return
        val e = s.emulator
        synchronized(e) {
            scrollRows = scrollRows.coerceIn(0, e.historySize)
            val rev = e.reverseVideo
            for (r in 0 until e.rows) {
                val row = r - scrollRows
                if (row < -e.historySize) continue
                drawLine(canvas, e.line(row), row, r * cellH, rev)
            }
            if (scrollRows == 0 && e.cursorVisible && (blinkOn || !settings.cursorBlink || !hasFocus())) {
                drawCursor(canvas, e)
            }
        }
    }

    private fun reverse(): Boolean = session?.let { synchronized(it.emulator) { it.emulator.reverseVideo } } ?: false

    private val runText = StringBuilder()

    private fun drawLine(c: Canvas, line: Line, row: Int, y: Float, rev: Boolean) {
        val cols = min(line.cols, (width / cellW).toInt() + 1)
        var x = 0
        while (x < cols) {
            val st = line.styles[x]
            val sel = isSelected(row, x)
            resolve(st, rev xor sel)
            val fg = resolvedFg
            // a run of the same look
            var end = x + 1
            while (end < cols && line.styles[end] == st && isSelected(row, end) == sel) end++
            fillPaint.color = resolvedBg
            c.drawRect(px(x), y, px(end), y + cellH, fillPaint)
            drawText(c, line, x, end, y, st, fg)
            x = end
        }
    }

    private var resolvedFg = 0
    private var resolvedBg = 0

    /** a cell's colours (ARGB) into resolvedFg and resolvedBg: bold as bright, iCE colours, inverse */
    private fun resolve(st: Long, invert: Boolean) {
        val a = Style.attrs(st)
        var fg = Style.fg(st)
        var bg = Style.bg(st)
        if (a and Style.BOLD != 0 && settings.boldBright && fg in 0..7) fg += 8
        if (settings.iceColors && a and Style.BLINK != 0 && bg in 0..7) bg += 8
        var f = theme.argb(fg, true)
        var b = theme.argb(bg, false)
        if ((a and Style.INVERSE != 0) != invert) {
            val t = f; f = b; b = t
        }
        if (a and Style.FAINT != 0) f = (f and 0x00ffffff) or (0x99 shl 24)
        resolvedFg = f
        resolvedBg = b
    }

    private fun drawText(c: Canvas, line: Line, from: Int, to: Int, y: Float, st: Long, fg: Int) {
        val a = Style.attrs(st)
        if (a and Style.INVISIBLE != 0) return
        if (a and Style.BLINK != 0 && !settings.iceColors && !blinkOn) return
        textPaint.color = fg
        fillPaint.color = fg
        // bold glyphs (not only bright colours): the font's bold face, or the paint's
        val heavy = a and Style.BOLD != 0 && !(settings.boldBright && Style.fg(st) in 0..7)
        val bf = boldFace
        textPaint.typeface = if (heavy && bf != null) bf else regularFace
        textPaint.isFakeBoldText = heavy && bf == null
        textPaint.textSkewX = if (a and Style.ITALIC != 0) -0.2f else 0f
        runText.setLength(0)
        var runStart = from
        var runCells = 0
        fun flush(at: Int) {
            if (runText.isNotEmpty()) drawRun(c, runText, runStart, runCells, y)
            runText.setLength(0)
            runCells = 0
            runStart = at
        }
        for (x in from until to) {
            val cp = line.chars[x]
            if (cp == 0 || cp == ' '.code) {
                flush(x + 1)
                continue
            }
            if (settings.drawBoxes && BoxDrawing.handles(cp)) {
                flush(x + 1)
                BoxDrawing.draw(c, cp, px(x), y, px(x + 1) - px(x), cellH, fillPaint)
                continue
            }
            if (runText.isEmpty()) runStart = x
            runText.appendCodePoint(cp)
            runCells++
        }
        flush(to)
        textPaint.typeface = regularFace
        textPaint.isFakeBoldText = false
        if (a and Style.UNDERLINE != 0) {
            c.drawRect(px(from), y + cellH - max(1f, floor(cellH / 14f)), px(to), y + cellH, fillPaint)
        }
        if (a and Style.STRIKE != 0) {
            c.drawRect(px(from), floor(y + cellH / 2), px(to), floor(y + cellH / 2) + max(1f, floor(cellH / 14f)), fillPaint)
        }
    }

    /**
     * cells' text from a column: as one run when its glyphs are the cells'
     * width (the font's own), else each glyph in its cell (a fallback
     * font's, wider or narrower), narrowed to fit it if need be
     */
    private fun drawRun(c: Canvas, text: CharSequence, col: Int, cells: Int, y: Float) {
        val w = textPaint.measureText(text, 0, text.length)
        if (abs(w - cells * cellW) < 0.5f) {
            c.drawText(text, 0, text.length, col * cellW, y + ascent, textPaint)
            return
        }
        var i = 0
        var x = col
        while (i < text.length) {
            val n = Character.charCount(Character.codePointAt(text, i))
            val gw = textPaint.measureText(text, i, i + n)
            val scale = if (gw > cellW) cellW / gw else 1f
            textPaint.textScaleX = scale
            val left = x * cellW + (cellW - gw * scale) / 2
            c.drawText(text, i, i + n, left, y + ascent, textPaint)
            textPaint.textScaleX = 1f
            i += n
            x++
        }
    }

    /** a column's left edge, on a whole pixel */
    private fun px(col: Int): Float = kotlin.math.round(col * cellW)

    private fun drawCursor(c: Canvas, e: TerminalEmulator) {
        val col = min(e.cursorX, e.cols - 1)
        val x = px(col)
        val cw = px(col + 1) - x
        val y = e.cursorY * cellH
        fillPaint.color = theme.cursorArgb
        when (settings.cursor) {
            CursorShape.BLOCK -> {
                if (hasFocus()) {
                    c.drawRect(x, y, x + cw, y + cellH, fillPaint)
                    val l = e.line(e.cursorY)
                    val cp = l.chars[min(e.cursorX, e.cols - 1)]
                    if (cp != 0 && cp != ' '.code) {
                        textPaint.color = theme.bgArgb
                        textPaint.isFakeBoldText = false
                        textPaint.textSkewX = 0f
                        if (settings.drawBoxes && BoxDrawing.handles(cp)) {
                            fillPaint.color = theme.bgArgb
                            BoxDrawing.draw(c, cp, x, y, cw, cellH, fillPaint)
                        } else {
                            c.drawText(String(Character.toChars(cp)), x, y + ascent, textPaint)
                        }
                    }
                } else {
                    fillPaint.style = Paint.Style.STROKE
                    fillPaint.strokeWidth = max(1f, floor(cw / 10f))
                    c.drawRect(x, y, x + cw, y + cellH, fillPaint)
                    fillPaint.style = Paint.Style.FILL
                }
            }
            CursorShape.UNDERLINE -> c.drawRect(x, y + cellH - max(2f, cellH / 8f), x + cw, y + cellH, fillPaint)
            CursorShape.BAR -> c.drawRect(x, y, x + max(2f, floor(cw / 6f)), y + cellH, fillPaint)
        }
    }

    private fun restartBlink() {
        removeCallbacks(blink)
        blinkOn = true
        if (isAttachedToWindow) postDelayed(blink, BLINK_MS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartBlink()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    // ------------------------------------------------------------------
    // touch: tap shows the keyboard, drag scrolls back, pinch zooms, long press selects

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (actionMode != null) {
                actionMode?.finish()
                return true
            }
            performClick()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (selecting) return true
            scrollBy(dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (selecting) return true
            val max = historySize() * cellH
            scroller.fling(0, (scrollRows * cellH).toInt(), 0, vy.toInt(), 0, 0, 0, max.toInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (scale.isInProgress) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startSelection(e.x, e.y)
        }
    })

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var startSp = 0f
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            startSp = fontPx / resources.displayMetrics.scaledDensity()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val sp = (fontPx / resources.displayMetrics.scaledDensity() * detector.scaleFactor).coerceIn(MIN_SP, MAX_SP)
            settings = settings.copy(fontSize = sp, fixedColumns = 0)
            measureFont()
            resizeEmulator()
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (startSp != settings.fontSize) onFontSizeChanged?.invoke(settings.fontSize)
        }
    })

    private fun android.util.DisplayMetrics.scaledDensity(): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, this)

    // (a tap's performClick() is the gesture detector's onSingleTapUp)
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        if (selecting && event.actionMasked == MotionEvent.ACTION_MOVE && !scale.isInProgress) {
            val (row, col) = cellAt(event.x, event.y)
            selEndRow = row
            selEndCol = col
            invalidate()
            return true
        }
        if (selecting && event.actionMasked == MotionEvent.ACTION_UP) {
            selecting = false
            actionMode?.invalidateContentRect()
            actionMode?.invalidate()
        }
        if (!scale.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    /** a click (a tap, or an accessibility service's): the keyboard up */
    override fun performClick(): Boolean {
        super.performClick()
        requestFocus()
        showKeyboard()
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val rows = (scroller.currY / cellH).toInt().coerceIn(0, historySize())
            if (rows != scrollRows) {
                scrollRows = rows
                invalidate()
            }
            postInvalidateOnAnimation()
        }
    }

    private fun scrollBy(dy: Float) {
        scrollRemainder += -dy / cellH
        val n = scrollRemainder.toInt()
        if (n != 0) {
            scrollRemainder -= n
            scrollRows = (scrollRows + n).coerceIn(0, historySize())
            invalidate()
        }
    }

    /** back to the live screen */
    fun scrollToBottom() {
        if (scrollRows != 0) {
            scrollRows = 0
            invalidate()
        }
    }

    /** the cell at a point: (row as line() has it, col) */
    private fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val s = session ?: return 0 to 0
        val e = s.emulator
        synchronized(e) {
            val r = (y / cellH).toInt().coerceIn(0, e.rows - 1) - scrollRows
            val c = (x / cellW).toInt().coerceIn(0, e.cols - 1)
            return r to c
        }
    }

    // ------------------------------------------------------------------
    // the selection

    private fun isSelected(row: Int, col: Int): Boolean {
        if (actionMode == null && !selecting) return false
        val startFirst = selStartRow < selEndRow || (selStartRow == selEndRow && selStartCol <= selEndCol)
        val r1 = if (startFirst) selStartRow else selEndRow
        val c1 = if (startFirst) selStartCol else selEndCol
        val r2 = if (startFirst) selEndRow else selStartRow
        val c2 = if (startFirst) selEndCol else selStartCol
        if (row < r1 || row > r2) return false
        if (row == r1 && col < c1) return false
        if (row == r2 && col > c2) return false
        return true
    }

    private fun ordered(): List<Int> =
        if (selStartRow < selEndRow || (selStartRow == selEndRow && selStartCol <= selEndCol)) {
            listOf(selStartRow, selStartCol, selEndRow, selEndCol)
        } else {
            listOf(selEndRow, selEndCol, selStartRow, selStartCol)
        }

    private fun startSelection(x: Float, y: Float) {
        val s = session ?: return
        val (row, col) = cellAt(x, y)
        // the word under the finger
        synchronized(s.emulator) {
            val l = s.emulator.line(row)
            var a = col
            var b = col
            fun word(i: Int) = l.chars[i] != 0 && l.chars[i] != ' '.code
            if (word(col)) {
                while (a > 0 && word(a - 1)) a--
                while (b < l.cols - 1 && word(b + 1)) b++
            }
            selStartRow = row; selStartCol = a
            selEndRow = row; selEndCol = b
            linkAtPress = UrlFinder.at(l.text(), col)
        }
        selecting = true
        actionMode = startActionMode(selectionCallback, ActionMode.TYPE_FLOATING)
        invalidate()
    }

    private fun selectedText(): String {
        val s = session ?: return ""
        val (r1, c1, r2, c2) = ordered()
        return synchronized(s.emulator) { s.emulator.text(r1, c1, r2, c2) }
    }

    private val selectionCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, ID_COPY, 0, "Copy")
            menu.add(0, ID_PASTE, 1, "Paste")
            menu.add(0, ID_ALL, 2, "Select all")
            menu.add(0, ID_LINK, 3, "Open link")
            menu.add(0, ID_SHARE, 4, "Share")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val link = linkAtPress ?: UrlFinder.all(selectedText()).firstOrNull()
            menu.findItem(ID_LINK)?.isVisible = link != null
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                ID_COPY -> {
                    copy(selectedText())
                    mode.finish()
                }
                ID_PASTE -> {
                    pasteClipboard()
                    mode.finish()
                }
                ID_ALL -> {
                    session?.let { s ->
                        synchronized(s.emulator) {
                            selStartRow = -s.emulator.historySize; selStartCol = 0
                            selEndRow = s.emulator.rows - 1; selEndCol = s.emulator.cols - 1
                        }
                    }
                    invalidate()
                    mode.invalidate()
                }
                ID_LINK -> {
                    val link = linkAtPress ?: UrlFinder.all(selectedText()).firstOrNull()
                    if (link != null) openLink(link)
                    mode.finish()
                }
                ID_SHARE -> {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, selectedText())
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    mode.finish()
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            selecting = false
            linkAtPress = null
            invalidate()
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val (r1, _, r2, _) = ordered()
            val top = ((r1 + scrollRows) * cellH).toInt()
            val bottom = ((r2 + scrollRows + 1) * cellH).toInt()
            outRect.set(0, top.coerceAtLeast(0), width, bottom.coerceAtMost(height))
        }
    }

    private fun copy(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    /** the clipboard's text to the remote */
    fun pasteClipboard() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) {
            scrollToBottom()
            session?.paste(text)
        }
    }

    private fun openLink(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
        }
    }

    // ------------------------------------------------------------------
    // the keyboard

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    fun toggleKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        val insets = rootWindowInsets
        val visible = insets != null && android.os.Build.VERSION.SDK_INT >= 30 &&
            insets.isVisible(android.view.WindowInsets.Type.ime())
        if (visible) imm.hideSoftInputFromWindow(windowToken, 0) else showKeyboard()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // no suggestions, no corrections, no full screen editor: keys straight through
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val ed = editable
                if (ed == null || ed.isEmpty()) {
                    // what was typed is the remote's now: a backspace for each
                    repeat(max(1, beforeLength)) { typed(Key.BACKSPACE) }
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }

            private fun flush() {
                val ed = editable ?: return
                val text = ed.toString()
                ed.clear()
                if (text.isNotEmpty()) {
                    scrollToBottom()
                    session?.sendTyped(text)
                }
            }
        }
    }

    private fun typed(k: Key, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) {
        scrollToBottom()
        session?.sendKey(k, shift, alt, ctrl)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                return when (settings.volumeKeys) {
                    VolumeKeys.SYSTEM -> super.onKeyDown(keyCode, event)
                    VolumeKeys.FONT_SIZE -> {
                        val sp = ((fontPx / resources.displayMetrics.scaledDensity()) + if (up) 1f else -1f).coerceIn(MIN_SP, MAX_SP)
                        settings = settings.copy(fontSize = sp, fixedColumns = 0)
                        measureFont(); resizeEmulator(); invalidate()
                        onFontSizeChanged?.invoke(sp)
                        true
                    }
                    VolumeKeys.CTRL_ALT -> {
                        if (up) s.alt.value = !s.alt.value else s.ctrl.value = !s.ctrl.value
                        true
                    }
                }
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HOME -> return super.onKeyDown(keyCode, event)
        }
        val shift = event.isShiftPressed
        val alt = event.isAltPressed
        val ctrl = event.isCtrlPressed
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Key.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> Key.HOME
            KeyEvent.KEYCODE_MOVE_END -> Key.END
            KeyEvent.KEYCODE_PAGE_UP -> Key.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> Key.PAGE_DOWN
            KeyEvent.KEYCODE_INSERT -> Key.INSERT
            KeyEvent.KEYCODE_FORWARD_DEL -> Key.DELETE
            KeyEvent.KEYCODE_DEL -> Key.BACKSPACE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Key.ENTER
            KeyEvent.KEYCODE_TAB -> Key.TAB
            KeyEvent.KEYCODE_ESCAPE -> Key.ESCAPE
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Key.entries[Key.F1.ordinal + keyCode - KeyEvent.KEYCODE_F1]
            else -> null
        }
        if (key != null) {
            // Shift+PgUp/PgDn scroll the screen back, as in xterm
            if (shift && (key == Key.PAGE_UP || key == Key.PAGE_DOWN)) {
                val e = s.emulator
                val page = synchronized(e) { e.rows }
                scrollRows = (scrollRows + if (key == Key.PAGE_UP) page else -page).coerceIn(0, historySize())
                invalidate()
                return true
            }
            typed(key, shift, alt, ctrl)
            return true
        }
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val cp = event.getUnicodeChar(meta)
        if (cp > 0 && cp and KeyCharacterMap_COMBINING == 0) {
            scrollToBottom()
            s.sendChar(cp, alt, ctrl)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** a flash of the screen (the bell) */
    fun flash() {
        flashing = true
        invalidate()
        postDelayed({ flashing = false; invalidate() }, FLASH_MS)
    }

    private var flashing = false

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (flashing) {
            fillPaint.color = 0x55ffffff
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        }
    }

    companion object {
        /** glyphs whose ink the cells' height must hold */
        private const val METRIC_SAMPLE = "MHWgjpqy|_[]{}()"
        private const val BLINK_MS = 530L
        private const val FLASH_MS = 120L
        const val MIN_SP = 5f
        const val MAX_SP = 40f
        private const val ID_COPY = 1
        private const val ID_PASTE = 2
        private const val ID_ALL = 3
        private const val ID_LINK = 4
        private const val ID_SHARE = 5
        private const val KeyCharacterMap_COMBINING = 0x80000000.toInt()
    }
}
