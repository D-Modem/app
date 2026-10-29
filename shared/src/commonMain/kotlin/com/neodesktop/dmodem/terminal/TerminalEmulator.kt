// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.terminal

/**
 * A terminal: VT100/VT220 with xterm's extensions (256 and 24-bit colours
 * and the palette's changes, wide and combining characters, underline
 * styles, the alternate screen, bracketed paste, mouse reporting, focus
 * events, hyperlinks, the clipboard, sixel graphics and the reports
 * programs ask for) and what BBSes expect of ANSI.SYS (CSI s/u, bold as
 * the bright colours, CP437 through [TermCharset]).
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
        /** OSC 0/2: the remote set the window's title */
        fun onTitle(title: String) {}
        /** BEL: the remote rang the bell */
        fun onBell() {}
        /** OSC 52: the remote puts text on the clipboard */
        fun onClipboard(text: String) {}
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
    var mouseMode = MouseMode.NONE
        private set
    /** the mouse's reports in SGR's form (1006), urxvt's (1015) or with UTF-8 coordinates (1005) */
    private var mouseSgr = false
    private var mouseUrxvt = false
    private var mouseUtf8 = false
    /** DECSET 1004: focus in and out reported */
    var focusEvents = false
        private set
    /** DECSCUSR: 0 the user's choice; 1, 2 block; 3, 4 underline; 5, 6 bar (odd ones blinking) */
    var cursorStyle = 0
        private set
    /** sixel images placed at the cursor, scrolling (DECSDM, 80, reset); else at the top left */
    private var sixelScrolling = true
    /** 8452: the cursor to the image's right, not below it */
    private var sixelCursorRight = false

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

    /** OSC 8: the link the next characters carry */
    private var link: String? = null
    /** the last cluster ended with a zero-width joiner: the next character joins it */
    private var joinNext = false
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
    private enum class State { GROUND, ESC, ESC_INTER, CSI, OSC, OSC_ESC, STRING, STRING_ESC, DCS, DCS_PASS, DCS_ESC }
    private var state = State.GROUND
    private val params = IntArray(32)
    private var nParams = 0
    private var paramStarted = false
    private var colon = BooleanArray(32)
    private var privateMarker = 0
    private var intermediate = 0
    private val osc = StringBuilder()
    /** the OSC ended with BEL: answered so */
    private var oscBel = false

    // a DCS's handler: a sixel image, or a request's text
    private var sixel: Sixel? = null
    private var dcsText: StringBuilder? = null
    private var dcsKind = 0

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
            val cp = s.codePointAtIndex(i)
            process(cp)
            i += charCountOf(cp)
        }
        revision++
    }

    /** a line of the screen (0 until rows) or of the scrollback (-1 the last of it) */
    fun line(row: Int): Line =
        if (row >= 0) screen[row] else history[history.size + row]

    // ------------------------------------------------------------------
    // the parser

    /** one code point through the parser, by the state it is in (text, ESC, CSI, OSC, a string, a DCS) */
    private fun process(cp: Int) {
        when (state) {
            State.GROUND -> ground(cp)
            State.ESC -> esc(cp)
            State.ESC_INTER -> escInter(cp)
            State.CSI -> csi(cp)
            State.OSC -> when (cp) {
                0x07 -> { oscBel = true; osc(); state = State.GROUND }
                0x1b -> state = State.OSC_ESC
                0x18, 0x1a -> state = State.GROUND
                else -> if (osc.length < 4096) osc.appendCp(cp)
            }
            State.OSC_ESC -> {
                // ST (ESC \) ends it; anything else too, then goes on as ESC
                oscBel = false
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
            State.DCS -> dcsEntry(cp)
            State.DCS_PASS -> when (cp) {
                0x1b -> state = State.DCS_ESC
                0x9c -> { dcsEnd(); state = State.GROUND }
                0x18, 0x1a -> { dcsAbort(); state = State.GROUND }
                else -> dcsPut(cp)
            }
            State.DCS_ESC -> {
                state = State.GROUND
                if (cp == '\\'.code) {
                    dcsEnd()
                } else {
                    dcsAbort()
                    state = State.ESC
                    esc(cp)
                }
            }
        }
    }

    /** a code point outside any sequence: a control, a C1 control as its ESC form, else printed */
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
                0x90 -> startDcs()
                0x98, 0x9e, 0x9f -> state = State.STRING
                0x84 -> index()
                0x85 -> { carriageReturn(); index() }
                0x8d -> reverseIndex()
                0x88 -> setTab()
            }
            return
        }
        print(cp)
    }

    /** a C0 control: ENQ's answerback, the bell, BS, HT, LF/VT/FF, CR, SO/SI, CAN/SUB, ESC */
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

    /** the character after ESC: a sequence begun, or its one-character command done */
    private fun esc(cp: Int) {
        state = State.GROUND
        when (cp.toChar()) {
            '[' -> startCsi()
            ']' -> { osc.setLength(0); state = State.OSC }
            'P' -> startDcs()
            'X', '^', '_' -> state = State.STRING
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

    /** the character after ESC and an intermediate: G0/G1's DEC graphics on or off, DECALN */
    private fun escInter(cp: Int) {
        state = State.GROUND
        val graphics = cp == '0'.code
        when (intermediate.toChar()) {
            '(' -> g0Graphics = graphics
            ')' -> g1Graphics = graphics
            '#' -> if (cp == '8'.code) alignmentTest()
        }
    }

    /** a CSI begun: its parameters, private marker and intermediate cleared */
    private fun startCsi() {
        state = State.CSI
        nParams = 0
        paramStarted = false
        privateMarker = 0
        intermediate = 0
        params.fill(0)
        colon.fill(false)
    }

    /** a CSI's character: a parameter's digit, a separator, a marker or intermediate, or its final (dispatched) */
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

    /** parameter [i], or [default] when it is missing or 0 */
    private fun p(i: Int, default: Int): Int =
        if (i < nParams && params[i] != 0) params[i] else default

    /** a CSI ended by [final] carried out: DEC private modes and questions, DA2/DA3, DECSTR, DECSCUSR, then the ANSI ones */
    private fun dispatchCsi(final: Int) {
        val c = final.toChar()
        if (privateMarker == '?'.code) {
            if (intermediate == '$'.code && c == 'p') {
                // DECRQM: a DEC mode asked about
                reply("\u001b[?${p(0, 0)};${decModeState(p(0, 0))}\$y")
                return
            }
            when (c) {
                'h' -> for (i in 0 until maxOf(nParams, 1)) decMode(params[i], true)
                'l' -> for (i in 0 until maxOf(nParams, 1)) decMode(params[i], false)
                'J' -> eraseDisplay(p(0, 0))
                'K' -> eraseLine(p(0, 0))
                'S' -> graphicsAttributes(p(0, 0), p(1, 0))
            }
            return
        }
        if (privateMarker == '>'.code) {
            when (c) {
                'c' -> reply("\u001b[>41;390;0c")
                // XTVERSION
                'q' -> reply("\u001bP>|D-Modem 1.0\u001b\\")
            }
            return
        }
        if (privateMarker == '='.code) {
            // DA3: a unit id
            if (c == 'c') reply("\u001bP!|00000000\u001b\\")
            return
        }
        if (privateMarker != 0) return
        if (intermediate != 0) {
            when {
                // DECSTR: a soft reset
                intermediate == '!'.code && c == 'p' -> softReset()
                // DECSCUSR: the cursor's shape
                intermediate == ' '.code && c == 'q' -> cursorStyle = p(0, 0).coerceIn(0, 6)
                // DECRQM: an ANSI mode asked about
                intermediate == '$'.code && c == 'p' -> reply("\u001b[${p(0, 0)};${ansiModeState(p(0, 0))}\$y")
            }
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
            // DA1: a VT220 with sixel graphics (4) and ANSI colour (22)
            'c' -> if (p(0, 0) == 0) reply("\u001b[?62;4;22c")
            'r' -> setRegion(p(0, 1) - 1, p(1, rows) - 1)
            's' -> saveCursor()
            'u' -> restoreCursor()
            't' -> windowReport(p(0, 0))
        }
    }

    /** DEC private mode [m] set or reset (DECSET/DECRST) */
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
            9 -> mouseMode = if (on) MouseMode.X10 else MouseMode.NONE
            1000 -> mouseMode = if (on) MouseMode.NORMAL else MouseMode.NONE
            1002 -> mouseMode = if (on) MouseMode.BUTTON else MouseMode.NONE
            1003 -> mouseMode = if (on) MouseMode.ANY else MouseMode.NONE
            1004 -> focusEvents = on
            1005 -> mouseUtf8 = on
            1006 -> mouseSgr = on
            1015 -> mouseUrxvt = on
            80 -> sixelScrolling = !on
            8452 -> sixelCursorRight = on
        }
    }

    /** DECRQM's answer for a DEC mode: 1 set, 2 reset, 0 not one known */
    private fun decModeState(m: Int): Int {
        /** DECRQM's 1 for set, 2 for reset */
        fun b(v: Boolean) = if (v) 1 else 2
        return when (m) {
            1 -> b(appCursorKeys)
            5 -> b(reverseVideo)
            6 -> b(originMode)
            7 -> b(autowrap)
            25 -> b(cursorVisible)
            47, 1047, 1049 -> b(altScreen)
            2004 -> b(bracketedPaste)
            9 -> b(mouseMode == MouseMode.X10)
            1000 -> b(mouseMode == MouseMode.NORMAL)
            1002 -> b(mouseMode == MouseMode.BUTTON)
            1003 -> b(mouseMode == MouseMode.ANY)
            1004 -> b(focusEvents)
            1005 -> b(mouseUtf8)
            1006 -> b(mouseSgr)
            1015 -> b(mouseUrxvt)
            80 -> b(!sixelScrolling)
            8452 -> b(sixelCursorRight)
            else -> 0
        }
    }

    /** DECRQM's answer for an ANSI mode (IRM 4, LNM 20): 1 set, 2 reset, 0 not one known */
    private fun ansiModeState(m: Int): Int = when (m) {
        4 -> if (insertMode) 1 else 2
        20 -> if (newlineMode) 1 else 2
        else -> 0
    }

    /** XTWINOPS' reports: the text area's and a cell's size in pixels, the screen's in characters */
    private fun windowReport(op: Int) {
        when (op) {
            14 -> reply("\u001b[4;${rows * cellPixelHeight};${cols * cellPixelWidth}t")
            16 -> reply("\u001b[6;$cellPixelHeight;${cellPixelWidth}t")
            18 -> reply("\u001b[8;$rows;${cols}t")
            19 -> reply("\u001b[9;$rows;${cols}t")
        }
    }

    /** XTSMGRAPHICS: the colour registers (1) and the largest sixel image (2) */
    private fun graphicsAttributes(item: Int, action: Int) {
        when (item) {
            1 -> reply("\u001b[?1;0;256S")
            2 -> if (action == 1 || action == 4) {
                reply("\u001b[?2;0;${minOf(cols * cellPixelWidth, MAX_IMAGE)};${minOf(rows * cellPixelHeight, MAX_IMAGE)}S")
            } else {
                reply("\u001b[?2;3;0S")
            }
            else -> reply("\u001b[?$item;1;0S")
        }
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

    /** ANSI mode [m] set or reset: insert (4), newline (20) */
    private fun ansiMode(m: Int, on: Boolean) {
        when (m) {
            4 -> insertMode = on
            20 -> newlineMode = on
        }
    }

    /** SGR: the CSI's parameters applied to the current rendition (none: back to normal) */
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
                4 -> {
                    a = a and Style.ANY_UNDERLINE.inv()
                    // 4:n, the underline's style: none, single, double, curly, dotted, dashed
                    val sub = if (i + 1 < nParams && colon[i + 1]) params[++i] else 1
                    a = a or when (sub) {
                        0 -> 0
                        2 -> Style.DOUBLE_UNDERLINE
                        3, 4, 5 -> Style.CURLY_UNDERLINE
                        else -> Style.UNDERLINE
                    }
                }
                21 -> a = (a and Style.ANY_UNDERLINE.inv()) or Style.DOUBLE_UNDERLINE
                5, 6 -> a = a or Style.BLINK
                7 -> a = a or Style.INVERSE
                8 -> a = a or Style.INVISIBLE
                9 -> a = a or Style.STRIKE
                22 -> a = a and (Style.BOLD or Style.FAINT).inv()
                23 -> a = a and Style.ITALIC.inv()
                24 -> a = a and Style.ANY_UNDERLINE.inv()
                53 -> a = a or Style.OVERLINE
                55 -> a = a and Style.OVERLINE.inv()
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
                58 -> {
                    // the underline's colour: read past (drawn in the text's)
                    if (i + 1 < nParams) {
                        when (params[i + 1]) {
                            5 -> i += 2
                            2 -> i += if (colon.getOrElse(i + 1) { false } && i + 5 < nParams && colon[i + 5]) 5 else 4
                            else -> i = nParams
                        }
                    }
                }
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
            if (v != 0 && v != 38 && v != 48 && v != 58 && v !in 30..49 && v !in 90..107) s = Style.withAttrs(s, a)
            i++
        }
        style = s
    }

    /** an OSC ended: the title, colours set, asked or reset, the clipboard, a hyperlink */
    private fun osc() {
        val s = osc.toString()
        val semi = s.indexOf(';')
        val cmd = (if (semi < 0) s else s.substring(0, semi)).toIntOrNull() ?: return
        val arg = if (semi < 0) "" else s.substring(semi + 1)
        when (cmd) {
            0, 2 -> listener?.onTitle(arg)
            4 -> {
                // pairs of index;colour (or ? to be told it)
                val f = arg.split(';')
                var i = 0
                while (i + 1 < f.size) {
                    val n = f[i].toIntOrNull()
                    if (n != null && n in 0..255) colorSpec(n, f[i + 1], "4;$n;")
                    i += 2
                }
            }
            10, 11, 12 -> {
                // the default foreground, background, cursor: each following one takes the next
                var n = cmd
                for (spec in arg.split(';')) {
                    if (n > 12) break
                    colorSpec(256 + n - 10, spec, "$n;")
                    n++
                }
            }
            104 -> if (arg.isEmpty()) colorOverrides.fill(-1, 0, 256) else arg.split(';').forEach { it.toIntOrNull()?.takeIf { n -> n in 0..255 }?.let { n -> colorOverrides[n] = -1 } }
            110 -> colorOverrides[256] = -1
            111 -> colorOverrides[257] = -1
            112 -> colorOverrides[258] = -1
            52 -> {
                // the clipboard set (never read: the remote isn't told what's on it)
                val data = arg.substringAfter(';', "")
                if (data != "?" && data.isNotEmpty()) {
                    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                    runCatching { kotlin.io.encoding.Base64.Mime.decode(data) }.getOrNull()
                        ?.let { listener?.onClipboard(it.decodeToString()) }
                }
            }
            8 -> {
                // a hyperlink: params;uri, an empty uri ends it
                val uri = arg.substringAfter(';', "")
                link = uri.takeIf { it.isNotEmpty() && it.length <= 2048 }
            }
        }
    }

    /** a colour set from its spec, or told (OSC's answer) when the spec is "?" */
    private fun colorSpec(i: Int, spec: String, prefix: String) {
        if (spec == "?") {
            val c = colorOverrides[i].takeIf { it != -1 } ?: defaultColor(i)
            /** a colour channel (0-255) as X's four hex digits */
            fun h(v: Int) = (v * 0x101).toString(16).padStart(4, '0')
            val end = if (oscBel) "\u0007" else "\u001b\\"
            reply("\u001b]$prefix" + "rgb:${h((c shr 16) and 0xff)}/${h((c shr 8) and 0xff)}/${h(c and 0xff)}$end")
            return
        }
        parseColor(spec)?.let { colorOverrides[i] = it }
    }

    /** [s] to the remote, as Latin-1 octets */
    private fun reply(s: String) {
        output(s.latin1())
    }

    // ------------------------------------------------------------------
    // DCS: sixel images, and requests (DECRQSS)

    /** a DCS begun: its parameters read as a CSI's */
    private fun startDcs() {
        startCsi()
        state = State.DCS
    }

    /** a DCS's parameters and intermediates, until its final character */
    private fun dcsEntry(cp: Int) {
        when (cp) {
            in '0'.code..'9'.code, ';'.code, ':'.code -> csi(cp).also { state = State.DCS }
            in 0x3c..0x3f -> if (nParams == 0 && intermediate == 0) privateMarker = cp
            in 0x20..0x2f -> intermediate = cp
            in 0x40..0x7e -> {
                state = State.DCS_PASS
                dcsStart(cp)
            }
            0x1b -> state = State.ESC
            0x18, 0x1a -> state = State.GROUND
        }
    }

    /** a DCS's final [final]: a sixel image begun, or DECRQSS's or XTGETTCAP's text collected */
    private fun dcsStart(final: Int) {
        sixel = null
        dcsText = null
        dcsKind = 0
        when {
            final == 'q'.code && intermediate == 0 && privateMarker == 0 -> {
                // P2 = 1: the pixels not drawn left transparent
                val maxW = minOf(MAX_IMAGE, cols * cellPixelWidth * 2)
                val maxH = minOf(MAX_IMAGE, rows * cellPixelHeight * 4)
                sixel = Sixel(transparent = p(1, 0) == 1, maxWidth = maxW, maxHeight = maxH)
            }
            // DECRQSS, XTGETTCAP: their text collected
            final == 'q'.code && (intermediate == '$'.code || intermediate == '+'.code) -> {
                dcsText = StringBuilder()
                dcsKind = intermediate
            }
        }
    }

    /** a DCS's data character: to the sixel image, or to the request's text */
    private fun dcsPut(cp: Int) {
        sixel?.let { it.feed(cp); return }
        dcsText?.let { if (it.length < 1024) it.appendCp(cp) }
    }

    /** the DCS cancelled: its image or text dropped */
    private fun dcsAbort() {
        sixel = null
        dcsText = null
    }

    /** the DCS ended: its sixel image placed, or its request answered */
    private fun dcsEnd() {
        sixel?.let { sx ->
            sixel = null
            sx.finish(cellPixelWidth, cellPixelHeight)?.let { placeImage(it) }
        }
        dcsText?.let { t ->
            dcsText = null
            if (dcsKind == '$'.code) statusString(t.toString()) else reply("\u001bP0+r\u001b\\")
        }
    }

    /** DECRQSS: the graphic rendition, the scrolling region, the cursor's style */
    private fun statusString(q: String) {
        val ok = when (q) {
            "m" -> sgrString() + "m"
            "r" -> "${top + 1};${bottom + 1}r"
            " q" -> "${cursorStyle.coerceAtLeast(1)} q"
            "\"q" -> "0\"q"
            else -> null
        }
        reply(if (ok != null) "\u001bP1\$r$ok\u001b\\" else "\u001bP0\$r\u001b\\")
    }

    /** the current rendition as SGR's parameters */
    private fun sgrString(): String {
        val sb = StringBuilder("0")
        val a = Style.attrs(style)
        if (a and Style.BOLD != 0) sb.append(";1")
        if (a and Style.FAINT != 0) sb.append(";2")
        if (a and Style.ITALIC != 0) sb.append(";3")
        if (a and Style.UNDERLINE != 0) sb.append(";4")
        if (a and Style.DOUBLE_UNDERLINE != 0) sb.append(";21")
        if (a and Style.CURLY_UNDERLINE != 0) sb.append(";4:3")
        if (a and Style.BLINK != 0) sb.append(";5")
        if (a and Style.INVERSE != 0) sb.append(";7")
        if (a and Style.INVISIBLE != 0) sb.append(";8")
        if (a and Style.STRIKE != 0) sb.append(";9")
        if (a and Style.OVERLINE != 0) sb.append(";53")
        /** colour [c]'s parameters appended: [base] + n for 0-7, [bright] + n - 8 for 8-15, else 5;n or 2;r;g;b */
        fun color(c: Int, base: Int, bright: Int) {
            when {
                c == Style.DEFAULT_FG || c == Style.DEFAULT_BG -> {}
                c and Style.RGB != 0 -> sb.append(";${base + 8};2;${(c shr 16) and 0xff};${(c shr 8) and 0xff};${c and 0xff}")
                c < 8 -> sb.append(";${base + c}")
                c < 16 -> sb.append(";${bright + c - 8}")
                else -> sb.append(";${base + 8};5;$c")
            }
        }
        color(Style.fg(style), 30, 90)
        color(Style.bg(style), 40, 100)
        return sb.toString()
    }

    /**
     * A sixel image put on the screen as tiles of cells (so that it scrolls
     * with the text): at the cursor, scrolling the screen as it needs, the
     * cursor then on the line below it (or to its right, 8452); or, with
     * DECSDM, at the top left, the cursor left where it was.
     */
    private fun placeImage(img: TermImage) {
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

    /** a character written at the cursor: DEC graphics, combining, wrapping, insert mode and wide characters as they come, the cursor moved past it */
    private fun print(c: Int) {
        var cp = c
        if (if (shiftOut) g1Graphics else g0Graphics) cp = decGraphics(cp)
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
        // a wide character with one column left: to the next line (the last cell left blank)
        if (w == 2 && cursorX == cols - 1) {
            if (!autowrap || cols < 2) return
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
        if (cursorX + w >= cols) {
            cursorX = cols - 1
            pendingWrap = true
        } else {
            cursorX += w
        }
    }

    /** a cell about to be written: the wide character it was half of, blanked */
    private fun unpair(line: Line, x: Int) {
        if (x >= cols) return
        if (line.chars[x] == Line.WIDE_TAIL && x > 0) line.chars[x - 1] = 0
        if (x + 1 < cols && line.chars[x + 1] == Line.WIDE_TAIL && line.chars[x] != Line.WIDE_TAIL) line.chars[x + 1] = 0
    }

    /** a zero-width code point added to the cell written last */
    private fun combine(cp: Int) {
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
    private fun carriageReturn() {
        cursorX = 0
        pendingWrap = false
    }

    /** the cursor back one (a wrap pending: only that undone) */
    private fun backspace() {
        if (pendingWrap) {
            pendingWrap = false
        } else if (cursorX > 0) {
            cursorX--
        }
    }

    /** LF: as [index] */
    private fun lineFeed() {
        index()
    }

    /** the cursor down a line, the region scrolled up at its bottom */
    private fun index() {
        pendingWrap = false
        if (cursorY == bottom) {
            scrollUp(top, bottom)
        } else if (cursorY < rows - 1) {
            cursorY++
        }
    }

    /** the cursor up a line, the region scrolled down at its top */
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

    /** lines [t] to [b] down one, a blank one at the top (the bottom one gone) */
    private fun scrollDown(t: Int, b: Int) {
        val gone = screen[b]
        for (y in b downTo t + 1) screen[y] = screen[y - 1]
        gone.clear(0, cols, Style.erased(style))
        gone.wrapped = false
        screen[t] = gone
    }

    /** the cursor to ([x], [y]) on the screen, or within the scrolling region if [clampToRegion] and it is in it */
    private fun moveCursor(x: Int, y: Int, clampToRegion: Boolean = false) {
        val (lo, hi) = if (clampToRegion && cursorY in top..bottom) top to bottom else 0 to rows - 1
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = y.coerceIn(lo, hi)
        pendingWrap = false
    }

    /** CUP: the cursor to ([x], [y]), from the region's top in origin mode */
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

    /** DECSTBM: the scrolling region lines [t] to [b] (ignored unless top < bottom), the cursor home */
    private fun setRegion(t: Int, b: Int) {
        val tt = t.coerceIn(0, rows - 1)
        val bb = b.coerceIn(0, rows - 1)
        if (tt >= bb) return
        top = tt
        bottom = bb
        cursorPosition(0, 0)
    }

    /** the cursor on [n] tab stops (the last column at most) */
    private fun tab(n: Int) {
        repeat(n) {
            var x = cursorX + 1
            while (x < cols - 1 && !tabs[x]) x++
            cursorX = x.coerceAtMost(cols - 1)
        }
        pendingWrap = false
    }

    /** the cursor back [n] tab stops (the first column at least) */
    private fun backTab(n: Int) {
        repeat(n) {
            var x = cursorX - 1
            while (x > 0 && !tabs[x]) x--
            cursorX = x.coerceAtLeast(0)
        }
        pendingWrap = false
    }

    /** HTS: a tab stop at the cursor's column */
    private fun setTab() {
        if (cursorX < cols) tabs[cursorX] = true
    }

    /** ED: the screen erased below the cursor (0), above it (1), all of it (2), or the scrollback (3) */
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

    /** EL: the line erased right of the cursor (0), left of it (1), or all of it (2) */
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

    /** ECH: [n] cells erased from the cursor */
    private fun eraseChars(n: Int) {
        screen[cursorY].clear(cursorX, cursorX + n, Style.erased(style))
        pendingWrap = false
    }

    /** ICH: [n] blank cells inserted at the cursor */
    private fun insertChars(n: Int) {
        screen[cursorY].insertCells(cursorX, n, Style.erased(style))
        pendingWrap = false
    }

    /** DCH: [n] cells deleted at the cursor */
    private fun deleteChars(n: Int) {
        screen[cursorY].deleteCells(cursorX, n, Style.erased(style))
        pendingWrap = false
    }

    /** IL: [n] blank lines inserted at the cursor's, within the region */
    private fun insertLines(n: Int) {
        if (cursorY !in top..bottom) return
        repeat(n.coerceAtMost(bottom - cursorY + 1)) { scrollDown(cursorY, bottom) }
        cursorX = 0
        pendingWrap = false
    }

    /** DL: [n] lines deleted at the cursor's, blank ones in at the region's bottom */
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

    /** the alternate screen on or off (cleared first if [clear]) */
    private fun setAltScreen(on: Boolean, clear: Boolean) {
        if (on == altScreen) return
        screen = if (on) alt else main
        if (on && clear) for (l in alt) { l.clear(0, cols, Style.NORMAL); l.wrapped = false }
    }

    /** DECSC: the cursor, rendition, origin mode and character sets saved, the screen's own */
    private fun saveCursor() {
        val s = Saved(cursorX, cursorY, style, pendingWrap, originMode, g0Graphics, g1Graphics, shiftOut)
        if (altScreen) savedAlt = s else savedMain = s
    }

    /** DECRC: what [saveCursor] saved for this screen back (none: the cursor home) */
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

    /** DECALN: the screen filled with E, the cursor home */
    private fun alignmentTest() {
        for (l in screen) for (x in 0 until cols) l.set(x, 'E'.code, Style.NORMAL)
        cursorPosition(0, 0)
    }

    /** DECSTR: the modes, region, rendition, character sets and saved cursors back to their defaults */
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
        mouseMode = MouseMode.NONE
        mouseSgr = false
        mouseUrxvt = false
        mouseUtf8 = false
        focusEvents = false
        cursorStyle = 0
        sixelScrolling = true
        sixelCursorRight = false
        colorOverrides.fill(-1)
        link = null
        joinNext = false
        sixel = null
        dcsText = null
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

    /** a screen's lines at the new size: excess lines dropped from the bottom (blank ones) then the top (to the scrollback, the main's), more taken back from it; the cursor kept on its line when [active] */
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
        /** the largest sixel image (pixels a side) */
        const val MAX_IMAGE = 2048

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
