// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.terminal

/**
 * A terminal: VT100/VT220 with xterm's extensions (256 and 24-bit colours,
 * the alternate screen, bracketed paste) and what BBSes expect of ANSI.SYS
 * (CSI s/u, bold as the bright colours, CP437 through [TermCharset]).
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
    private val output: (ByteArray) -> Unit = {},
) {
    interface Listener {
        fun onTitle(title: String) {}
        fun onBell() {}
    }

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
    private var pendingWrap = false
    private var style = Style.NORMAL

    // modes
    var cursorVisible = true
        private set
    var appCursorKeys = false
        private set
    var appKeypad = false
        private set
    var bracketedPaste = false
        private set
    var reverseVideo = false
        private set
    private var autowrap = true
    private var originMode = false
    private var insertMode = false
    private var newlineMode = false

    // the scrolling region (rows top..bottom, inclusive)
    private var top = 0
    private var bottom = rows - 1

    private var tabs = BooleanArray(cols) { it % 8 == 0 && it > 0 }

    // G0, G1 and which of them is in use: DEC special graphics or not
    private var g0Graphics = false
    private var g1Graphics = false
    private var shiftOut = false

    private var lastChar = ' '.code

    private class Saved(
        val x: Int, val y: Int, val style: Long, val wrap: Boolean,
        val origin: Boolean, val g0: Boolean, val g1: Boolean, val so: Boolean,
    )
    private var savedMain: Saved? = null
    private var savedAlt: Saved? = null

    // the parser
    private enum class State { GROUND, ESC, ESC_INTER, CSI, OSC, OSC_ESC, STRING, STRING_ESC }
    private var state = State.GROUND
    private val params = IntArray(32)
    private var nParams = 0
    private var paramStarted = false
    private var colon = BooleanArray(32)
    private var privateMarker = 0
    private var intermediate = 0
    private val osc = StringBuilder()

    private val sink = TermDecoder.Sink { cp -> process(cp) }

    /** the remote's octets, decoded in [decoder]'s set */
    fun append(data: ByteArray, length: Int = data.size) {
        decoder.decode(data, length, sink)
        revision++
    }

    /** text, as code points (the tests, local echo) */
    fun appendText(s: String) {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            process(cp)
            i += Character.charCount(cp)
        }
        revision++
    }

    /** a line of the screen (0 until rows) or of the scrollback (-1 the last of it) */
    fun line(row: Int): Line =
        if (row >= 0) screen[row] else history[history.size + row]

    // ------------------------------------------------------------------
    // the parser

    private fun process(cp: Int) {
        when (state) {
            State.GROUND -> ground(cp)
            State.ESC -> esc(cp)
            State.ESC_INTER -> escInter(cp)
            State.CSI -> csi(cp)
            State.OSC -> when (cp) {
                0x07 -> { osc(); state = State.GROUND }
                0x1b -> state = State.OSC_ESC
                0x18, 0x1a -> state = State.GROUND
                else -> if (osc.length < 4096) osc.appendCodePoint(cp)
            }
            State.OSC_ESC -> {
                // ST (ESC \) ends it; anything else too, then goes on as ESC
                osc()
                state = State.GROUND
                if (cp != '\\'.code) { state = State.ESC; esc(cp) }
            }
            State.STRING -> when (cp) {
                0x07, 0x18, 0x1a -> state = State.GROUND
                0x1b -> state = State.STRING_ESC
            }
            State.STRING_ESC -> {
                state = State.GROUND
                if (cp != '\\'.code) { state = State.ESC; esc(cp) }
            }
        }
    }

    private fun ground(cp: Int) {
        if (cp < 0x20 || cp == 0x7f) {
            control(cp)
            return
        }
        if (cp in 0x80..0x9f) {
            // C1 controls (8 bit): as their ESC forms
            when (cp) {
                0x9b -> startCsi()
                0x9d -> { osc.setLength(0); state = State.OSC }
                0x90, 0x98, 0x9e, 0x9f -> state = State.STRING
                0x84 -> index()
                0x85 -> { carriageReturn(); index() }
                0x8d -> reverseIndex()
                0x88 -> setTab()
            }
            return
        }
        print(cp)
    }

    private fun control(cp: Int) {
        when (cp) {
            0x05 -> if (answerback.isNotEmpty()) reply(answerback)
            0x07 -> listener?.onBell()
            0x08 -> backspace()
            0x09 -> tab(1)
            0x0a, 0x0b, 0x0c -> { lineFeed(); if (newlineMode) carriageReturn() }
            0x0d -> carriageReturn()
            0x0e -> shiftOut = true
            0x0f -> shiftOut = false
            0x18, 0x1a -> state = State.GROUND
            0x1b -> state = State.ESC
        }
    }

    private fun esc(cp: Int) {
        state = State.GROUND
        when (cp.toChar()) {
            '[' -> startCsi()
            ']' -> { osc.setLength(0); state = State.OSC }
            'P', 'X', '^', '_' -> state = State.STRING
            '(', ')', '*', '+', '#', ' ', '%' -> { intermediate = cp; state = State.ESC_INTER }
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> index()
            'E' -> { carriageReturn(); index() }
            'M' -> reverseIndex()
            'H' -> setTab()
            'c' -> reset()
            '=' -> appKeypad = true
            '>' -> appKeypad = false
            '\\' -> {}
            else -> if (cp < 0x20) control(cp)
        }
    }

    private fun escInter(cp: Int) {
        state = State.GROUND
        val graphics = cp == '0'.code
        when (intermediate.toChar()) {
            '(' -> g0Graphics = graphics
            ')' -> g1Graphics = graphics
            '#' -> if (cp == '8'.code) alignmentTest()
        }
    }

    private fun startCsi() {
        state = State.CSI
        nParams = 0
        paramStarted = false
        privateMarker = 0
        intermediate = 0
        params.fill(0)
        colon.fill(false)
    }

    private fun csi(cp: Int) {
        when (cp) {
            in '0'.code..'9'.code -> {
                if (nParams == 0) nParams = 1
                if (nParams <= params.size) {
                    val i = nParams - 1
                    params[i] = (params[i] * 10 + (cp - '0'.code)).coerceAtMost(99999)
                }
                paramStarted = true
            }
            ';'.code, ':'.code -> {
                if (nParams == 0) nParams = 1
                if (nParams < params.size) {
                    colon[nParams] = cp == ':'.code
                    nParams++
                }
            }
            in 0x3c..0x3f -> if (nParams == 0 && intermediate == 0) privateMarker = cp
            in 0x20..0x2f -> intermediate = cp
            in 0x40..0x7e -> {
                state = State.GROUND
                dispatchCsi(cp)
            }
            0x1b -> state = State.ESC
            0x18, 0x1a -> state = State.GROUND
            else -> if (cp < 0x20) control(cp)
        }
    }

    private fun p(i: Int, default: Int): Int =
        if (i < nParams && params[i] != 0) params[i] else default

    private fun dispatchCsi(final: Int) {
        val c = final.toChar()
        if (privateMarker == '?'.code) {
            when (c) {
                'h' -> for (i in 0 until maxOf(nParams, 1)) decMode(params[i], true)
                'l' -> for (i in 0 until maxOf(nParams, 1)) decMode(params[i], false)
                'J' -> eraseDisplay(p(0, 0))
                'K' -> eraseLine(p(0, 0))
            }
            return
        }
        if (privateMarker == '>'.code) {
            if (c == 'c') reply("\u001b[>0;10;0c")
            return
        }
        if (privateMarker != 0) return
        if (intermediate != 0) {
            // DECSTR (CSI ! p): a soft reset; DECSCUSR (CSI q) and the rest ignored
            if (intermediate == '!'.code && c == 'p') softReset()
            return
        }
        when (c) {
            'A' -> moveCursor(cursorX, cursorY - p(0, 1), clampToRegion = true)
            'B', 'e' -> moveCursor(cursorX, cursorY + p(0, 1), clampToRegion = true)
            'C', 'a' -> moveCursor(cursorX + p(0, 1), cursorY)
            'D' -> moveCursor(cursorX - p(0, 1), cursorY)
            'E' -> moveCursor(0, cursorY + p(0, 1), clampToRegion = true)
            'F' -> moveCursor(0, cursorY - p(0, 1), clampToRegion = true)
            'G', '`' -> moveCursor(p(0, 1) - 1, cursorY)
            'H', 'f' -> cursorPosition(p(1, 1) - 1, p(0, 1) - 1)
            'd' -> cursorPosition(cursorX, p(0, 1) - 1)
            'I' -> tab(p(0, 1))
            'Z' -> backTab(p(0, 1))
            'J' -> eraseDisplay(p(0, 0))
            'K' -> eraseLine(p(0, 0))
            'L' -> insertLines(p(0, 1))
            'M' -> deleteLines(p(0, 1))
            '@' -> insertChars(p(0, 1))
            'P' -> deleteChars(p(0, 1))
            'X' -> eraseChars(p(0, 1))
            'S' -> repeat(p(0, 1).coerceAtMost(rows)) { scrollUp(top, bottom) }
            'T' -> repeat(p(0, 1).coerceAtMost(rows)) { scrollDown(top, bottom) }
            'b' -> repeat(p(0, 1).coerceAtMost(cols * rows)) { print(lastChar) }
            'g' -> when (p(0, 0)) {
                0 -> if (cursorX < cols) tabs[cursorX] = false
                3 -> tabs.fill(false)
            }
            'h' -> for (i in 0 until maxOf(nParams, 1)) ansiMode(params[i], true)
            'l' -> for (i in 0 until maxOf(nParams, 1)) ansiMode(params[i], false)
            'm' -> sgr()
            'n' -> when (p(0, 0)) {
                5 -> reply("\u001b[0n")
                6 -> {
                    val y = if (originMode) cursorY - top else cursorY
                    reply("\u001b[${y + 1};${minOf(cursorX, cols - 1) + 1}R")
                }
            }
            'c' -> if (p(0, 0) == 0) reply("\u001b[?1;2c")
            'r' -> setRegion(p(0, 1) - 1, p(1, rows) - 1)
            's' -> saveCursor()
            'u' -> restoreCursor()
            't' -> {} // window operations: none
        }
    }

    private fun decMode(m: Int, on: Boolean) {
        when (m) {
            1 -> appCursorKeys = on
            5 -> reverseVideo = on
            6 -> { originMode = on; cursorPosition(0, 0) }
            7 -> autowrap = on
            25 -> cursorVisible = on
            47, 1047 -> setAltScreen(on, clear = m == 1047)
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> {
                if (on) { saveCursor(); setAltScreen(true, clear = true) } else { setAltScreen(false, clear = false); restoreCursor() }
            }
            2004 -> bracketedPaste = on
        }
    }

    private fun ansiMode(m: Int, on: Boolean) {
        when (m) {
            4 -> insertMode = on
            20 -> newlineMode = on
        }
    }

    private fun sgr() {
        if (nParams == 0) {
            style = Style.NORMAL
            return
        }
        var i = 0
        var s = style
        while (i < nParams) {
            val v = params[i]
            var a = Style.attrs(s)
            when (v) {
                0 -> s = Style.NORMAL
                1 -> a = a or Style.BOLD
                2 -> a = a or Style.FAINT
                3 -> a = a or Style.ITALIC
                4, 21 -> a = a or Style.UNDERLINE
                5, 6 -> a = a or Style.BLINK
                7 -> a = a or Style.INVERSE
                8 -> a = a or Style.INVISIBLE
                9 -> a = a or Style.STRIKE
                22 -> a = a and (Style.BOLD or Style.FAINT).inv()
                23 -> a = a and Style.ITALIC.inv()
                24 -> a = a and Style.UNDERLINE.inv()
                25 -> a = a and Style.BLINK.inv()
                27 -> a = a and Style.INVERSE.inv()
                28 -> a = a and Style.INVISIBLE.inv()
                29 -> a = a and Style.STRIKE.inv()
                in 30..37 -> s = Style.withFg(s, v - 30)
                39 -> s = Style.withFg(s, Style.DEFAULT_FG)
                in 40..47 -> s = Style.withBg(s, v - 40)
                49 -> s = Style.withBg(s, Style.DEFAULT_BG)
                in 90..97 -> s = Style.withFg(s, v - 90 + 8)
                in 100..107 -> s = Style.withBg(s, v - 100 + 8)
                38, 48 -> {
                    // 38;5;n / 38;2;r;g;b, with ; or : (38:2::r:g:b: an empty colour space)
                    var color = -1
                    if (i + 1 < nParams) {
                        when (params[i + 1]) {
                            5 -> if (i + 2 < nParams) { color = params[i + 2] and 0xff; i += 2 }
                            2 -> {
                                var j = i + 2
                                if (colon.getOrElse(i + 1) { false } && i + 5 < nParams && colon[i + 5]) j++
                                if (j + 2 < nParams) {
                                    color = Style.RGB or ((params[j] and 0xff) shl 16) or
                                        ((params[j + 1] and 0xff) shl 8) or (params[j + 2] and 0xff)
                                    i = j + 2
                                } else {
                                    i = nParams
                                }
                            }
                            else -> i = nParams
                        }
                    }
                    if (color >= 0) s = if (v == 38) Style.withFg(s, color) else Style.withBg(s, color)
                }
            }
            if (v != 0 && v != 38 && v != 48 && v !in 30..49 && v !in 90..107) s = Style.withAttrs(s, a)
            i++
        }
        style = s
    }

    private fun osc() {
        val s = osc.toString()
        val semi = s.indexOf(';')
        if (semi < 0) return
        when (s.substring(0, semi)) {
            "0", "2" -> listener?.onTitle(s.substring(semi + 1))
        }
    }

    private fun reply(s: String) {
        output(s.toByteArray(Charsets.ISO_8859_1))
    }

    // ------------------------------------------------------------------
    // the screen

    private fun print(c: Int) {
        var cp = c
        if (if (shiftOut) g1Graphics else g0Graphics) cp = decGraphics(cp)
        lastChar = cp
        if (pendingWrap) {
            if (autowrap) {
                screen[cursorY].wrapped = true
                carriageReturn()
                lineFeed()
            }
            pendingWrap = false
        }
        val line = screen[cursorY]
        if (insertMode) {
            System.arraycopy(line.chars, cursorX, line.chars, cursorX + 1, cols - cursorX - 1)
            System.arraycopy(line.styles, cursorX, line.styles, cursorX + 1, cols - cursorX - 1)
        }
        line.chars[cursorX] = cp
        line.styles[cursorX] = style
        if (cursorX == cols - 1) {
            pendingWrap = true
        } else {
            cursorX++
        }
    }

    private fun carriageReturn() {
        cursorX = 0
        pendingWrap = false
    }

    private fun backspace() {
        if (pendingWrap) {
            pendingWrap = false
        } else if (cursorX > 0) {
            cursorX--
        }
    }

    private fun lineFeed() {
        index()
    }

    private fun index() {
        pendingWrap = false
        if (cursorY == bottom) {
            scrollUp(top, bottom)
        } else if (cursorY < rows - 1) {
            cursorY++
        }
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (cursorY == top) {
            scrollDown(top, bottom)
        } else if (cursorY > 0) {
            cursorY--
        }
    }

    /** the region's lines up one, the top one to the scrollback (the whole main screen's) */
    private fun scrollUp(t: Int, b: Int) {
        val gone = screen[t]
        if (screen === main && t == 0 && b == rows - 1 && historyMax > 0) {
            val kept = Line(cols)
            kept.copyFrom(gone)
            history.addLast(kept)
            while (history.size > historyMax) history.removeFirst()
        }
        for (y in t until b) screen[y] = screen[y + 1]
        gone.clear(0, cols, Style.erased(style))
        gone.wrapped = false
        screen[b] = gone
    }

    private fun scrollDown(t: Int, b: Int) {
        val gone = screen[b]
        for (y in b downTo t + 1) screen[y] = screen[y - 1]
        gone.clear(0, cols, Style.erased(style))
        gone.wrapped = false
        screen[t] = gone
    }

    private fun moveCursor(x: Int, y: Int, clampToRegion: Boolean = false) {
        val (lo, hi) = if (clampToRegion && cursorY in top..bottom) top to bottom else 0 to rows - 1
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = y.coerceIn(lo, hi)
        pendingWrap = false
    }

    private fun cursorPosition(x: Int, y: Int) {
        if (originMode) {
            cursorX = x.coerceIn(0, cols - 1)
            cursorY = (y + top).coerceIn(top, bottom)
        } else {
            cursorX = x.coerceIn(0, cols - 1)
            cursorY = y.coerceIn(0, rows - 1)
        }
        pendingWrap = false
    }

    private fun setRegion(t: Int, b: Int) {
        val tt = t.coerceIn(0, rows - 1)
        val bb = b.coerceIn(0, rows - 1)
        if (tt >= bb) return
        top = tt
        bottom = bb
        cursorPosition(0, 0)
    }

    private fun tab(n: Int) {
        repeat(n) {
            var x = cursorX + 1
            while (x < cols - 1 && !tabs[x]) x++
            cursorX = x.coerceAtMost(cols - 1)
        }
        pendingWrap = false
    }

    private fun backTab(n: Int) {
        repeat(n) {
            var x = cursorX - 1
            while (x > 0 && !tabs[x]) x--
            cursorX = x.coerceAtLeast(0)
        }
        pendingWrap = false
    }

    private fun setTab() {
        if (cursorX < cols) tabs[cursorX] = true
    }

    private fun eraseDisplay(mode: Int) {
        val e = Style.erased(style)
        when (mode) {
            0 -> {
                screen[cursorY].clear(cursorX, cols, e)
                for (y in cursorY + 1 until rows) { screen[y].clear(0, cols, e); screen[y].wrapped = false }
            }
            1 -> {
                for (y in 0 until cursorY) { screen[y].clear(0, cols, e); screen[y].wrapped = false }
                screen[cursorY].clear(0, cursorX + 1, e)
            }
            2 -> {
                for (y in 0 until rows) { screen[y].clear(0, cols, e); screen[y].wrapped = false }
                // ANSI.SYS homes the cursor with ESC[2J; BBSes count on it
                if (homeOnClear) { cursorX = 0; cursorY = 0 }
            }
            3 -> history.clear()
        }
        pendingWrap = false
    }

    /** ESC[2J homes the cursor as ANSI.SYS did (BBS screens expect it) */
    var homeOnClear = true

    private fun eraseLine(mode: Int) {
        val e = Style.erased(style)
        val l = screen[cursorY]
        when (mode) {
            0 -> l.clear(cursorX, cols, e)
            1 -> l.clear(0, cursorX + 1, e)
            2 -> l.clear(0, cols, e)
        }
        if (mode != 1) l.wrapped = false
        pendingWrap = false
    }

    private fun eraseChars(n: Int) {
        screen[cursorY].clear(cursorX, cursorX + n, Style.erased(style))
        pendingWrap = false
    }

    private fun insertChars(n: Int) {
        val l = screen[cursorY]
        val k = n.coerceAtMost(cols - cursorX)
        System.arraycopy(l.chars, cursorX, l.chars, cursorX + k, cols - cursorX - k)
        System.arraycopy(l.styles, cursorX, l.styles, cursorX + k, cols - cursorX - k)
        l.clear(cursorX, cursorX + k, Style.erased(style))
        pendingWrap = false
    }

    private fun deleteChars(n: Int) {
        val l = screen[cursorY]
        val k = n.coerceAtMost(cols - cursorX)
        System.arraycopy(l.chars, cursorX + k, l.chars, cursorX, cols - cursorX - k)
        System.arraycopy(l.styles, cursorX + k, l.styles, cursorX, cols - cursorX - k)
        l.clear(cols - k, cols, Style.erased(style))
        pendingWrap = false
    }

    private fun insertLines(n: Int) {
        if (cursorY !in top..bottom) return
        repeat(n.coerceAtMost(bottom - cursorY + 1)) { scrollDown(cursorY, bottom) }
        cursorX = 0
        pendingWrap = false
    }

    private fun deleteLines(n: Int) {
        if (cursorY !in top..bottom) return
        repeat(n.coerceAtMost(bottom - cursorY + 1)) {
            // (not to the scrollback: the region starts at the cursor)
            val gone = screen[cursorY]
            for (y in cursorY until bottom) screen[y] = screen[y + 1]
            gone.clear(0, cols, Style.erased(style))
            gone.wrapped = false
            screen[bottom] = gone
        }
        cursorX = 0
        pendingWrap = false
    }

    private fun setAltScreen(on: Boolean, clear: Boolean) {
        if (on == altScreen) return
        screen = if (on) alt else main
        if (on && clear) for (l in alt) { l.clear(0, cols, Style.NORMAL); l.wrapped = false }
    }

    private fun saveCursor() {
        val s = Saved(cursorX, cursorY, style, pendingWrap, originMode, g0Graphics, g1Graphics, shiftOut)
        if (altScreen) savedAlt = s else savedMain = s
    }

    private fun restoreCursor() {
        val s = (if (altScreen) savedAlt else savedMain)
        if (s == null) {
            cursorX = 0; cursorY = 0; pendingWrap = false
            return
        }
        cursorX = s.x.coerceIn(0, cols - 1)
        cursorY = s.y.coerceIn(0, rows - 1)
        style = s.style
        pendingWrap = s.wrap
        originMode = s.origin
        g0Graphics = s.g0
        g1Graphics = s.g1
        shiftOut = s.so
    }

    private fun alignmentTest() {
        for (l in screen) for (x in 0 until cols) { l.chars[x] = 'E'.code; l.styles[x] = Style.NORMAL }
        cursorPosition(0, 0)
    }

    private fun softReset() {
        cursorVisible = true
        appCursorKeys = false
        appKeypad = false
        autowrap = true
        originMode = false
        insertMode = false
        top = 0
        bottom = rows - 1
        style = Style.NORMAL
        g0Graphics = false
        g1Graphics = false
        shiftOut = false
        savedMain = null
        savedAlt = null
    }

    /** everything as it was at the start (RIS), the scrollback kept */
    fun reset() {
        softReset()
        screen = main
        for (l in main) { l.clear(0, cols, Style.NORMAL); l.wrapped = false }
        for (l in alt) { l.clear(0, cols, Style.NORMAL); l.wrapped = false }
        cursorX = 0
        cursorY = 0
        pendingWrap = false
        bracketedPaste = false
        reverseVideo = false
        newlineMode = false
        tabs = BooleanArray(cols) { it % 8 == 0 && it > 0 }
        state = State.GROUND
        decoder.reset()
        revision++
    }

    /** the screen and the scrollback cleared, the cursor home */
    fun clearAll() {
        history.clear()
        for (l in screen) { l.clear(0, cols, Style.erased(style)); l.wrapped = false }
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
        top = 0
        bottom = rows - 1
        tabs = BooleanArray(cols) { it % 8 == 0 && it > 0 }
        cursorX = cursorX.coerceIn(0, cols - 1)
        pendingWrap = false
        revision++
    }

    private fun resizeScreen(old: Array<Line>, newCols: Int, newRows: Int, isMain: Boolean, active: Boolean): Array<Line> {
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
        /** DEC special graphics (ESC ( 0): the line drawing set, for 0x5f..0x7e */
        private const val DEC_GRAPHICS =
            " ◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"

        fun decGraphics(cp: Int): Int =
            if (cp in 0x5f..0x7e) DEC_GRAPHICS[cp - 0x5f].code else cp
    }
}
