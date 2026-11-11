// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * A terminal's screen: the main and alternate screens, the scrollback,
 * the cursor and the modes the keys and the view read, with the
 * operations every kind of terminal is made of (print, scroll, erase,
 * insert, move the cursor). What the remote's characters mean is the
 * [interpreter]'s, chosen by [profile]: DEC's VT line and xterm (with
 * what BBSes expect of ANSI.SYS: CSI s/u, ESC[2J homing the cursor, CP437
 * through [TermCharset]), and later the other families.
 *
 * What the remote sends goes to [append]; the screen is [line] (rows 0
 * until [rows]) with the scrollback above it (rows -1 down to
 * -[historySize]); answers the remote asks for (the cursor's position,
 * the device attributes) go to [output]. Not thread safe: the caller
 * locks it (the view reads it on the UI thread while the modem's output
 * comes on another).
 */
class TerminalEmulator(
    cols: Int,
    rows: Int,
    /** the scrollback's lines at most */
    var historyMax: Int = 2000,
    profile: TerminalProfile = TerminalProfiles.DEFAULT,
    private val output: (ByteArray) -> Unit = {},
) {
    interface Listener {
        /** OSC 0/2: the remote set the window's title */
        fun onTitle(title: String) {}
        /** BEL: the remote rang the bell */
        fun onBell() {}
        /** OSC 52: the remote puts text on the clipboard */
        fun onClipboard(text: String) {}
        /** what the remote sent to the printer (printer controller mode, auto print, print screen) */
        fun onPrint(text: String) {}
        /** ANSI music: notes to play, after any still playing */
        fun onMusic(notes: List<MusicNote>) {}
    }

    /** which mouse events are reported (DECSET 9, 1000, 1002, 1003) */
    enum class MouseMode { NONE, X10, NORMAL, BUTTON, ANY }

    var listener: Listener? = null

    var cols = cols
        private set
    var rows = rows
        private set

    val decoder = TermDecoder(TermCharset.UTF8)
    /** sent for ENQ */
    var answerback = ""

    /** changed by every change of the screen (the view redraws when it moves) */
    var revision = 0L
        private set

    // the screens
    private val history = ArrayDeque<Line>()
    private var main = Array(rows) { Line(cols) }
    private var alt = Array(rows) { Line(cols) }
    private var screen = main
    val altScreen: Boolean get() = screen === alt
    val historySize: Int get() = if (altScreen) 0 else history.size

    // the cursor
    var cursorX = 0
        private set
    var cursorY = 0
        private set
    internal var pendingWrap = false
    /** the rendition the next characters are written in */
    internal var style = Style.NORMAL

    // modes (set by the interpreter, read by the keys and the view)
    var cursorVisible = true
        internal set
    var appCursorKeys = false
        internal set
    var appKeypad = false
        internal set
    var bracketedPaste = false
        internal set
    var reverseVideo = false
        internal set
    var mouseMode = MouseMode.NONE
        internal set
    /** the mouse's reports in SGR's form (1006), urxvt's (1015) or with UTF-8 coordinates (1005) */
    internal var mouseSgr = false
    internal var mouseUrxvt = false
    internal var mouseUtf8 = false
    /** DECSET 1004: focus in and out reported */
    var focusEvents = false
        internal set
    /** DECSCUSR: 0 the user's choice; 1, 2 block; 3, 4 underline; 5, 6 bar (odd ones blinking) */
    var cursorStyle = 0
        internal set
    /** sixel images placed at the cursor, scrolling (DECSDM, 80, reset); else at the top left */
    internal var sixelScrolling = true
    /** 8452: the cursor to the image's right, not below it */
    internal var sixelCursorRight = false

    /** a cell's size in pixels, as the view draws it: sixel images are measured in it, and reported */
    var cellPixelWidth = 10
        private set
    var cellPixelHeight = 20
        private set

    /** a cell's size in pixels as the view draws it (at least 1 each way) */
    fun setCellPixels(w: Int, h: Int) {
        cellPixelWidth = w.coerceAtLeast(1)
        cellPixelHeight = h.coerceAtLeast(1)
    }

    /** the colours as the view shows them (for OSC 4, 10, 11 and 12's questions): 0-255, 256 fg, 257 bg, 258 cursor */
    var defaultColor: (Int) -> Int = { xtermColor(it) }

    /** the remote's own colours (OSC 4, 10, 11, 12): ARGB, or -1 for the view's */
    private val colorOverrides = IntArray(259) { -1 }

    /** a colour the remote set (0-255, 256 fg, 257 bg, 258 cursor), or -1 */
    fun colorOverride(i: Int): Int = colorOverrides.getOrElse(i) { -1 }

    /** colour [i] (as [colorOverride] numbers them) set by the remote to [argb] */
    internal fun setColorOverride(i: Int, argb: Int) {
        if (i in colorOverrides.indices) colorOverrides[i] = argb
    }

    /** the remote's colours in [range] back to the view's */
    internal fun resetColors(range: IntRange) {
        for (i in range) if (i in colorOverrides.indices) colorOverrides[i] = -1
    }

    /** the columns the view should fit across its width (DECCOLM's 80 or 132, a Commodore's 40); 0: as many as the font gives */
    var requestedColumns = 0
        internal set

    /** the rows the screen should have at most (a Commodore's 25); 0: as many as fit */
    var requestedRows = 0
        internal set

    /** the remote turned on iCE colours (blink as a bright background: SyncTERM's CSI ? 33 h), over the user's setting */
    var iceColors = false
        internal set

    /** the line speed to show the remote's text at (CTerm's CSI * r, for ANSI animations), bits a second; 0: as it comes */
    var emulatedBps = 0
        internal set

    /** the keyboard's four LEDs (DECLL), L1 the lowest bit */
    var leds = 0
        internal set

    /** the keys are a VT52's (ESC A for up, ESC ? p for the keypad's 0) */
    var vt52Keys = false
        internal set

    /** OSC 8: the link the next characters carry */
    internal var link: String? = null
    /** the last cluster ended with a zero-width joiner: the next character joins it */
    private var joinNext = false
    internal var autowrap = true
    internal var originMode = false
    internal var insertMode = false
    internal var newlineMode = false

    // the scrolling region (rows top..bottom, inclusive)
    internal var top = 0
        private set
    internal var bottom = rows - 1
        private set

    private var tabs = BooleanArray(cols) { it % 8 == 0 && it > 0 }

    /** the last character printed (REP repeats it) */
    internal var lastChar = ' '.code
        private set

    private class Saved(
        val x: Int, val y: Int, val style: Long, val wrap: Boolean,
        val origin: Boolean, val charsets: Any?,
    )
    private var savedMain: Saved? = null
    private var savedAlt: Saved? = null

    /** ESC[2J homes the cursor as ANSI.SYS did (BBS screens expect it); the profile's to begin with */
    var homeOnClear = profile.homeOnClear

    /** the kind of terminal it is: a new one swaps the interpreter (the screen kept, the modes reset) */
    var profile: TerminalProfile = profile
        set(v) {
            if (v === field) return
            field = v
            interpreter.detach()
            interpreter = v.interpreter(this)
            homeOnClear = v.homeOnClear
            softReset()
            revision++
        }

    /** what reads the remote's characters: the profile's */
    private var interpreter: TerminalInterpreter = profile.interpreter(this)

    private val sink = TermDecoder.Sink { cp -> interpreter.process(cp) }

    /** the remote's octets, decoded in [decoder]'s set (or as they are, to an interpreter that reads octets) */
    fun append(data: ByteArray, length: Int = data.size) {
        if (interpreter.readsOctets) {
            for (i in 0 until length) interpreter.process(data[i].toInt() and 0xff)
        } else {
            decoder.decode(data, length, sink)
        }
        revision++
    }

    /** what key [k] sends, as octets: the interpreter's own, else [keys]' sequence in the character set */
    fun keyOctets(keys: KeyEncoder, k: Key, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): ByteArray =
        interpreter.keyOctets(k, shift, alt, ctrl)
            ?: decoder.charset.encode(keys.key(k, this, shift, alt, ctrl))

    /** what a character typed (or pasted) sends, as octets: the interpreter's own, else [keys]' in the character set */
    fun charOctets(keys: KeyEncoder, cp: Int, alt: Boolean = false, ctrl: Boolean = false): ByteArray =
        interpreter.charOctets(cp, alt, ctrl) ?: decoder.charset.encode(keys.char(cp, alt, ctrl))

    /** whether the remote's octets are read as they come, in the terminal's own set (a Commodore's), not the user's */
    val readsOctets: Boolean get() = interpreter.readsOctets

    /** the glyphs its cells are drawn with, a bitmap font the profile's machine had (null: the view's font) */
    val glyphs: BitmapFont? get() = profile.glyphs

    /** the soft characters the host loaded (DECDLD), their code points [SoftFont.BASE]'s; null for none */
    var softGlyphs: BitmapFont? = null
        internal set

    /** the bitmap glyph [cp] is drawn with: a soft character's, the profile's, or null for the view's font */
    fun glyphFor(cp: Int): BitmapFont? =
        softGlyphs?.takeIf { cp >= SoftFont.BASE && it.has(cp) } ?: glyphs?.takeIf { it.has(cp) }

    /** DECSSDT's status line: 0 none, 1 the terminal's indicators, 2 the host's */
    var statusType = 0
        internal set

    /** the host-writable status line's text and cursor */
    internal val statusLine = StatusLine(cols)

    /** what the status line shows ("" for none): the cursor's place as the indicators have it, or the host's text */
    fun statusText(): String = when (statusType) {
        1 -> "${(cursorY + 1).toString().padStart(4, '0')},${(minOf(cursorX, cols - 1) + 1).toString().padStart(3, '0')}"
        2 -> statusLine.line.text().ifEmpty { " " }
        else -> ""
    }

    /** text, as code points (the tests, local echo) */
    fun appendText(s: String) {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAtIndex(i)
            interpreter.process(cp)
            i += charCountOf(cp)
        }
        revision++
    }

    /** a line of the screen (0 until rows) or of the scrollback (-1 the last of it) */
    fun line(row: Int): Line =
        if (row >= 0) screen[row] else history[history.size + row]

    /** replies' controls in their 8-bit forms (S8C1T: CSI as 9B, DCS as 90, ST as 9C) */
    internal var eightBitReplies = false

    /** [s] to the remote, as Latin-1 octets (its ESC Fe's as C1 controls, when [eightBitReplies]) */
    internal fun reply(s: String) {
        output((if (eightBitReplies) ESC_FE.replace(s) { (it.groupValues[1][0].code + 0x40).toChar().toString() } else s).latin1())
    }

    // ------------------------------------------------------------------
    // the mouse and focus, reported

    /**
     * A mouse event as the remote asked for it, or null when it doesn't
     * want this one: [button] 0-2 (left, middle, right), 64 and 65 the
     * wheel; [col] and [row] on the screen (from 0); [mods] shift 4, alt
     * 8, ctrl 16.
     */
    fun mouseEvent(button: Int, col: Int, row: Int, press: Boolean, motion: Boolean = false, mods: Int = 0): ByteArray? {
        val mode = mouseMode
        if (mode == MouseMode.NONE) return null
        if (motion && mode != MouseMode.BUTTON && mode != MouseMode.ANY) return null
        val wheel = button >= 64
        if (!press && (mode == MouseMode.X10 || wheel)) return null
        var code = button + (if (motion) 32 else 0) + (if (mode == MouseMode.X10) 0 else mods)
        val x = col.coerceIn(0, cols - 1) + 1
        val y = row.coerceIn(0, rows - 1) + 1
        val s = when {
            mouseSgr -> "\u001b[<$code;$x;$y${if (press) 'M' else 'm'}"
            mouseUrxvt -> {
                if (!press) code = 3 + (if (motion) 32 else 0) + mods
                "\u001b[${code + 32};$x;${y}M"
            }
            else -> {
                if (!press) code = 3 + (if (motion) 32 else 0) + mods
                if (!mouseUtf8 && (x > 223 || y > 223)) return null
                val sb = StringBuilder("\u001b[M")
                sb.appendCp(32 + code)
                sb.appendCp(32 + x)
                sb.appendCp(32 + y)
                return if (mouseUtf8) sb.toString().encodeToByteArray() else sb.toString().latin1()
            }
        }
        return s.latin1()
    }

    /** the focus's change, when the remote asked to be told (1004) */
    fun focusReport(focused: Boolean): ByteArray? =
        if (focusEvents) (if (focused) "\u001b[I" else "\u001b[O").encodeToByteArray() else null

    // ------------------------------------------------------------------
    // images

    /**
     * A sixel image put on the screen as tiles of cells (so that it scrolls
     * with the text): at the cursor, scrolling the screen as it needs, the
     * cursor then on the line below it (or to its right, 8452); or, with
     * DECSDM, at the top left, the cursor left where it was.
     */
    internal fun placeImage(img: TermImage) {
        val tilesW = (img.width + img.cellWidth - 1) / img.cellWidth
        val tilesH = (img.height + img.cellHeight - 1) / img.cellHeight
        /** cell [x] of [line] made the image's tile ([tx], [ty]) */
        fun tile(line: Line, x: Int, tx: Int, ty: Int) {
            line.set(x, 0, Style.erased(style))
            line.extraFor(x).image = ImageTile(img, tx, ty)
        }
        if (!sixelScrolling) {
            for (ty in 0 until minOf(tilesH, rows)) for (tx in 0 until minOf(tilesW, cols)) tile(screen[ty], tx, tx, ty)
            return
        }
        if (pendingWrap) { carriageReturn(); lineFeed() }
        val x0 = cursorX
        for (ty in 0 until tilesH) {
            val line = screen[cursorY]
            for (tx in 0 until tilesW) {
                val x = x0 + tx
                if (x >= cols) break
                tile(line, x, tx, ty)
            }
            if (ty < tilesH - 1) index()
        }
        if (sixelCursorRight) {
            cursorX = (x0 + tilesW).coerceAtMost(cols - 1)
        } else {
            index()
            cursorX = x0
        }
        pendingWrap = false
    }

    // ------------------------------------------------------------------
    // the screen

    /** a character written at the cursor (the interpreter's character sets already applied): combining, wrapping, insert mode and wide characters as they come, the cursor moved past it */
    internal fun print(cp: Int) {
        val w = if (cp < 0x300) 1 else Wcwidth.width(cp)
        // a combining mark, or what a zero-width joiner joins: with the character before
        if (w == 0 || joinNext) {
            joinNext = false
            combine(cp)
            return
        }
        lastChar = cp
        if (pendingWrap) {
            if (autowrap) {
                screen[cursorY].wrapped = true
                carriageReturn()
                lineFeed()
            }
            pendingWrap = false
        }
        // (a line of double width holds half as many)
        val lc = lineWidth(cursorY)
        if (cursorX >= lc) cursorX = lc - 1
        // a wide character with one column left: to the next line (the last cell left blank)
        if (w == 2 && cursorX == lc - 1) {
            if (!autowrap || lc < 2) return
            screen[cursorY].clear(cursorX, cols, style)
            screen[cursorY].wrapped = true
            carriageReturn()
            lineFeed()
        }
        val line = screen[cursorY]
        if (insertMode) line.insertCells(cursorX, w, style)
        // (half of a wide character written over: its other half blanked)
        unpair(line, cursorX)
        if (w == 2) unpair(line, cursorX + 1)
        line.set(cursorX, cp, style)
        if (w == 2) line.set(cursorX + 1, Line.WIDE_TAIL, style)
        link?.let { l -> for (k in 0 until w) line.extraFor(cursorX + k).link = l }
        if (cursorX + w >= lineWidth(cursorY)) {
            cursorX = lineWidth(cursorY) - 1
            pendingWrap = true
        } else {
            cursorX += w
        }
    }

    /** the columns line [y] holds: half the screen's on a line of double width */
    internal fun lineWidth(y: Int): Int =
        if (screen[y].size.doubleWidth) maxOf(1, cols / 2) else cols

    /** DECSWL, DECDWL, DECDHL: the cursor's line made [size] (the cursor kept within it) */
    internal fun setLineSize(size: LineSize) {
        screen[cursorY].size = size
        if (cursorX >= lineWidth(cursorY)) cursorX = lineWidth(cursorY) - 1
    }

    /**
     * DECCOLM: the screen 132 columns wide ([wide]) or 80, as the remote
     * asked: the view fits that many (it can't be told to), the screen
     * cleared, the margins reset and the cursor home as a VT100 does.
     */
    internal fun setColumnMode(wide: Boolean) {
        requestedColumns = if (wide) 132 else 80
        for (l in screen) l.blank(Style.NORMAL)
        top = 0
        bottom = rows - 1
        cursorX = 0
        cursorY = 0
        pendingWrap = false
    }

    /** a cell about to be written: the wide character it was half of, blanked */
    internal fun unpair(line: Line, x: Int) {
        if (x >= cols) return
        if (line.chars[x] == Line.WIDE_TAIL && x > 0) line.chars[x - 1] = 0
        if (x + 1 < cols && line.chars[x + 1] == Line.WIDE_TAIL && line.chars[x] != Line.WIDE_TAIL) line.chars[x + 1] = 0
    }

    /** a zero-width code point added to the cell written last */
    internal fun combine(cp: Int) {
        val line = screen[cursorY]
        var x = if (pendingWrap) cursorX else cursorX - 1
        if (x < 0) return
        if (line.chars[x] == Line.WIDE_TAIL && x > 0) x--
        if (line.chars[x] == 0) return
        val e = line.extraFor(x)
        val old = e.combining
        // (a few marks at most: enough for any real cluster)
        if (old != null && old.size >= 16) return
        e.combining = if (old == null) intArrayOf(cp) else old + cp
        if (cp == 0x200D) joinNext = true
    }

    /** the cursor to the line's start */
    internal fun carriageReturn() {
        cursorX = 0
        pendingWrap = false
    }

    /** the cursor back one (a wrap pending: only that undone) */
    internal fun backspace() {
        if (pendingWrap) {
            pendingWrap = false
        } else if (cursorX > 0) {
            cursorX--
        }
    }

    /** LF: as [index] */
    internal fun lineFeed() {
        index()
    }

    /** the cursor down a line, the region scrolled up at its bottom */
    internal fun index() {
        pendingWrap = false
        if (cursorY == bottom) {
            scrollUp(top, bottom)
        } else if (cursorY < rows - 1) {
            cursorY++
        }
    }

    /** the cursor up a line, the region scrolled down at its top */
    internal fun reverseIndex() {
        pendingWrap = false
        if (cursorY == top) {
            scrollDown(top, bottom)
        } else if (cursorY > 0) {
            cursorY--
        }
    }

    /** the region's lines up one, the top one to the scrollback (the whole main screen's) */
    internal fun scrollUp(t: Int, b: Int) {
        val gone = screen[t]
        if (screen === main && t == 0 && b == rows - 1 && historyMax > 0) {
            val kept = Line(cols)
            kept.copyFrom(gone)
            history.addLast(kept)
            while (history.size > historyMax) history.removeFirst()
        }
        for (y in t until b) screen[y] = screen[y + 1]
        gone.blank(Style.erased(style))
        screen[b] = gone
    }

    /** lines [t] to [b] down one, a blank one at the top (the bottom one gone) */
    internal fun scrollDown(t: Int, b: Int) {
        val gone = screen[b]
        for (y in b downTo t + 1) screen[y] = screen[y - 1]
        gone.blank(Style.erased(style))
        screen[t] = gone
    }

    /** the cursor to ([x], [y]) on the screen, or within the scrolling region if [clampToRegion] and it is in it */
    internal fun moveCursor(x: Int, y: Int, clampToRegion: Boolean = false) {
        val (lo, hi) = if (clampToRegion && cursorY in top..bottom) top to bottom else 0 to rows - 1
        cursorY = y.coerceIn(lo, hi)
        cursorX = x.coerceIn(0, lineWidth(cursorY) - 1)
        pendingWrap = false
    }

    /** CUP: the cursor to ([x], [y]), from the region's top in origin mode */
    internal fun cursorPosition(x: Int, y: Int) {
        cursorY = if (originMode) (y + top).coerceIn(top, bottom) else y.coerceIn(0, rows - 1)
        cursorX = x.coerceIn(0, lineWidth(cursorY) - 1)
        pendingWrap = false
    }

    /** DECSTBM: the scrolling region lines [t] to [b] (ignored unless top < bottom), the cursor home */
    internal fun setRegion(t: Int, b: Int) {
        val tt = t.coerceIn(0, rows - 1)
        val bb = b.coerceIn(0, rows - 1)
        if (tt >= bb) return
        top = tt
        bottom = bb
        cursorPosition(0, 0)
    }

    /** the cursor on [n] tab stops (the last column at most) */
    internal fun tab(n: Int) {
        val lc = lineWidth(cursorY)
        repeat(n) {
            var x = cursorX + 1
            while (x < lc - 1 && !tabs[x]) x++
            cursorX = x.coerceAtMost(lc - 1)
        }
        pendingWrap = false
    }

    /** the cursor back [n] tab stops (the first column at least) */
    internal fun backTab(n: Int) {
        repeat(n) {
            var x = cursorX - 1
            while (x > 0 && !tabs[x]) x--
            cursorX = x.coerceAtLeast(0)
        }
        pendingWrap = false
    }

    /** HTS: a tab stop at the cursor's column */
    internal fun setTab() {
        if (cursorX < cols) tabs[cursorX] = true
    }

    /** TBC 0: the tab stop at the cursor's column cleared */
    internal fun clearTab() {
        if (cursorX < cols) tabs[cursorX] = false
    }

    /** the columns with tab stops (from 0), for DECTABSR */
    internal fun tabStops(): List<Int> = tabs.indices.filter { tabs[it] }

    /** TBC 3: every tab stop cleared */
    internal fun clearTabs() {
        tabs.fill(false)
    }

    /** cells [from] until [to] of [l] erased in [e]: all of them, or (DECSED, DECSEL: [selective]) those DECSCA didn't protect */
    private fun erase(l: Line, from: Int, to: Int, e: Long, selective: Boolean) {
        if (!selective) {
            if (from <= 0 && to >= l.cols) l.blank(e) else l.clear(from, to, e)
            return
        }
        for (x in from.coerceAtLeast(0) until to.coerceAtMost(l.cols)) {
            if (Style.attrs(l.styles[x]) and Style.PROTECTED == 0) l.clear(x, x + 1, e)
        }
    }

    /** ED (DECSED if [selective]): the screen erased below the cursor (0), above it (1), all of it (2), or the scrollback (3) */
    internal fun eraseDisplay(mode: Int, selective: Boolean = false) {
        val e = Style.erased(style)
        when (mode) {
            0 -> {
                erase(screen[cursorY], cursorX, cols, e, selective)
                for (y in cursorY + 1 until rows) erase(screen[y], 0, cols, e, selective)
            }
            1 -> {
                for (y in 0 until cursorY) erase(screen[y], 0, cols, e, selective)
                erase(screen[cursorY], 0, cursorX + 1, e, selective)
            }
            2 -> {
                for (y in 0 until rows) erase(screen[y], 0, cols, e, selective)
                // ANSI.SYS homes the cursor with ESC[2J; BBSes count on it
                if (homeOnClear) { cursorX = 0; cursorY = 0 }
            }
            3 -> history.clear()
        }
        pendingWrap = false
    }

    /** EL (DECSEL if [selective]): the line erased right of the cursor (0), left of it (1), or all of it (2) */
    internal fun eraseLine(mode: Int, selective: Boolean = false) {
        val e = Style.erased(style)
        val l = screen[cursorY]
        when (mode) {
            0 -> erase(l, cursorX, cols, e, selective)
            1 -> erase(l, 0, cursorX + 1, e, selective)
            2 -> erase(l, 0, cols, e, selective)
        }
        if (mode != 1) l.wrapped = false
        pendingWrap = false
    }

    /** ECH: [n] cells erased from the cursor */
    internal fun eraseChars(n: Int) {
        screen[cursorY].clear(cursorX, cursorX + n, Style.erased(style))
        pendingWrap = false
    }

    /** ICH: [n] blank cells inserted at the cursor */
    internal fun insertChars(n: Int) {
        screen[cursorY].insertCells(cursorX, n, Style.erased(style))
        pendingWrap = false
    }

    /** DCH: [n] cells deleted at the cursor */
    internal fun deleteChars(n: Int) {
        screen[cursorY].deleteCells(cursorX, n, Style.erased(style))
        pendingWrap = false
    }

    /** IL: [n] blank lines inserted at the cursor's, within the region */
    internal fun insertLines(n: Int) {
        if (cursorY !in top..bottom) return
        repeat(n.coerceAtMost(bottom - cursorY + 1)) { scrollDown(cursorY, bottom) }
        cursorX = 0
        pendingWrap = false
    }

    /** DL: [n] lines deleted at the cursor's, blank ones in at the region's bottom */
    internal fun deleteLines(n: Int) {
        if (cursorY !in top..bottom) return
        repeat(n.coerceAtMost(bottom - cursorY + 1)) {
            // (not to the scrollback: the region starts at the cursor)
            val gone = screen[cursorY]
            for (y in cursorY until bottom) screen[y] = screen[y + 1]
            gone.blank(Style.erased(style))
            screen[bottom] = gone
        }
        cursorX = 0
        pendingWrap = false
    }

    /** the alternate screen on or off (cleared first if [clear]) */
    internal fun setAltScreen(on: Boolean, clear: Boolean) {
        if (on == altScreen) return
        screen = if (on) alt else main
        if (on && clear) for (l in alt) { l.blank(Style.NORMAL) }
    }

    /** DECSC: the cursor, rendition, origin mode and character sets saved, the screen's own */
    internal fun saveCursor() {
        val s = Saved(cursorX, cursorY, style, pendingWrap, originMode, interpreter.charsetState())
        if (altScreen) savedAlt = s else savedMain = s
    }

    /** DECRC: what [saveCursor] saved for this screen back (none: the cursor home) */
    internal fun restoreCursor() {
        val s = (if (altScreen) savedAlt else savedMain)
        if (s == null) {
            cursorX = 0; cursorY = 0; pendingWrap = false
            interpreter.restoreCharsetState(null)
            return
        }
        cursorX = s.x.coerceIn(0, cols - 1)
        cursorY = s.y.coerceIn(0, rows - 1)
        style = s.style
        pendingWrap = s.wrap
        originMode = s.origin
        interpreter.restoreCharsetState(s.charsets)
    }

    /** DECALN: the screen filled with E (its lines single size), the cursor home, the margins gone */
    internal fun alignmentTest() {
        for (l in screen) {
            for (x in 0 until cols) l.set(x, 'E'.code, Style.NORMAL)
            l.size = LineSize.SINGLE
            l.wrapped = false
        }
        top = 0
        bottom = rows - 1
        cursorPosition(0, 0)
    }

    /** DECSTR: the modes, region, rendition, character sets and saved cursors back to their defaults */
    internal fun softReset() {
        cursorVisible = true
        appCursorKeys = false
        appKeypad = false
        autowrap = true
        originMode = false
        insertMode = false
        top = 0
        bottom = rows - 1
        style = Style.NORMAL
        interpreter.softReset()
        savedMain = null
        savedAlt = null
    }

    /** everything as it was at the start (RIS), the scrollback kept */
    fun reset() {
        softReset()
        statusType = 0
        statusLine.clear()
        softGlyphs = null
        screen = main
        for (l in main) { l.blank(Style.NORMAL) }
        for (l in alt) { l.blank(Style.NORMAL) }
        cursorX = 0
        cursorY = 0
        pendingWrap = false
        bracketedPaste = false
        reverseVideo = false
        newlineMode = false
        mouseMode = MouseMode.NONE
        mouseSgr = false
        mouseUrxvt = false
        mouseUtf8 = false
        focusEvents = false
        cursorStyle = 0
        requestedColumns = 0
        requestedRows = 0
        iceColors = false
        emulatedBps = 0
        leds = 0
        vt52Keys = false
        sixelScrolling = true
        sixelCursorRight = false
        colorOverrides.fill(-1)
        link = null
        joinNext = false
        tabs = BooleanArray(cols) { it % 8 == 0 && it > 0 }
        interpreter.reset()
        decoder.reset()
        revision++
    }

    /** the screen and the scrollback cleared, the cursor home */
    fun clearAll() {
        history.clear()
        for (l in screen) { l.blank(Style.erased(style)) }
        cursorX = 0
        cursorY = 0
        pendingWrap = false
        revision++
    }

    /**
     * A new size. The lines keep their text (cut, or padded); a screen
     * with fewer rows sends its top lines to the scrollback so that the
     * cursor's line stays, one with more takes them back from it.
     */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols < 1 || newRows < 1 || (newCols == cols && newRows == rows)) return
        val wasAlt = altScreen
        for (l in history) l.resize(newCols)
        main = resizeScreen(main, newCols, newRows, isMain = true, active = !wasAlt)
        alt = resizeScreen(alt, newCols, newRows, isMain = false, active = wasAlt)
        screen = if (wasAlt) alt else main
        cols = newCols
        rows = newRows
        statusLine.resize(cols)
        top = 0
        bottom = rows - 1
        tabs = BooleanArray(cols) { it % 8 == 0 && it > 0 }
        cursorX = cursorX.coerceIn(0, cols - 1)
        pendingWrap = false
        revision++
    }

    /** a screen's lines at the new size: excess lines dropped from the bottom (blank ones) then the top (to the scrollback, the main's), more taken back from it; the cursor kept on its line when [active] */
    internal fun resizeScreen(old: Array<Line>, newCols: Int, newRows: Int, isMain: Boolean, active: Boolean): Array<Line> {
        for (l in old) l.resize(newCols)
        val list = ArrayList<Line>(old.toList())
        var cy = cursorY
        if (newRows < list.size) {
            // the lines below the cursor go first (blank ones at the bottom), then the top ones
            var excess = list.size - newRows
            while (excess > 0 && list.size - 1 > (if (active) cy else -1) && list.last().text().isEmpty()) {
                list.removeAt(list.size - 1); excess--
            }
            while (excess > 0) {
                val l = list.removeAt(0)
                if (isMain && historyMax > 0) {
                    history.addLast(l)
                    while (history.size > historyMax) history.removeFirst()
                }
                if (active) cy--
                excess--
            }
        } else if (newRows > list.size) {
            var more = newRows - list.size
            while (more > 0 && isMain && history.isNotEmpty() && active) {
                list.add(0, history.removeLast())
                cy++
                more--
            }
            while (more > 0) { list.add(Line(newCols)); more-- }
        }
        if (active) cursorY = cy.coerceIn(0, newRows - 1)
        return list.toTypedArray()
    }

    /**
     * The text from (row, col) to (row2, col2), inclusive, rows as [line]
     * has them: lines that wrapped joined, the others ended with a newline.
     */
    fun text(row: Int, col: Int, row2: Int, col2: Int): String {
        val sb = StringBuilder()
        for (r in row..row2) {
            if (r < -historySize || r >= rows) continue
            val l = line(r)
            val from = if (r == row) col else 0
            val to = if (r == row2) col2 + 1 else cols
            sb.append(l.text(from.coerceIn(0, cols), to.coerceIn(0, cols)))
            if (r != row2 && !l.wrapped) sb.append('\n')
        }
        return sb.toString()
    }

    companion object {
        /** the largest sixel image (pixels a side) */
        const val MAX_IMAGE = 2048

        /** an ESC Fe (ESC [, ESC P, ST's ESC and backslash ...): what has a C1 form */
        private val ESC_FE = Regex("\u001b([\\x40-\\x5f])")

        /** xterm's colours: the 16, the 6x6x6 cube, the greys; default fg light grey, bg black */
        fun xtermColor(i: Int): Int {
            val sixteen = intArrayOf(
                0x000000, 0xcd0000, 0x00cd00, 0xcdcd00, 0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
                0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00, 0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff,
            )
            val rgb = when {
                i < 16 -> sixteen[i.coerceAtLeast(0)]
                i < 232 -> {
                    val n = i - 16
                    val steps = intArrayOf(0, 95, 135, 175, 215, 255)
                    (steps[n / 36] shl 16) or (steps[(n / 6) % 6] shl 8) or steps[n % 6]
                }
                i < 256 -> (8 + (i - 232) * 10).let { (it shl 16) or (it shl 8) or it }
                i == 257 -> 0x000000
                else -> 0xe5e5e5
            }
            return (0xff shl 24) or rgb
        }

        /** an X colour spec: rgb:r/g/b (1 to 4 hex digits each), #rgb, #rrggbb, #rrrrggggbbbb */
        fun parseColor(spec: String): Int? {
            val s = spec.trim()
            /** a spec's 1 to 4 hex digits as 0-255, or null */
            fun scale(h: String): Int? {
                val v = h.toIntOrNull(16) ?: return null
                return when (h.length) {
                    1 -> v * 17
                    2 -> v
                    3 -> v shr 4
                    4 -> v shr 8
                    else -> null
                }
            }
            val parts: List<String> = when {
                s.startsWith("rgb:") -> s.substring(4).split('/')
                s.startsWith("#") && (s.length - 1) % 3 == 0 && s.length > 1 -> {
                    val n = (s.length - 1) / 3
                    listOf(s.substring(1, 1 + n), s.substring(1 + n, 1 + 2 * n), s.substring(1 + 2 * n))
                }
                else -> return null
            }
            if (parts.size != 3) return null
            val r = scale(parts[0]) ?: return null
            val g = scale(parts[1]) ?: return null
            val b = scale(parts[2]) ?: return null
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** DEC special graphics (ESC ( 0): the line drawing set, for 0x5f..0x7e */
        private const val DEC_GRAPHICS =
            " ◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"

        /** [cp] in DEC special graphics: 0x5f..0x7e as line drawing, the rest as they are */
        fun decGraphics(cp: Int): Int =
            if (cp in 0x5f..0x7e) DEC_GRAPHICS[cp - 0x5f].code else cp
    }
}
