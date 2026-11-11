// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import org.dmodem.app.terminal.TerminalEmulator.MouseMode

/**
 * The DEC VT line's sequences (and xterm's, where the profile has them):
 * a parser of ESC, CSI, OSC, DCS and the strings, as DEC's terminals
 * read them, and what each sequence does on the screen. What the
 * terminal answers and which extensions it knows come from [profile];
 * the levels that read more extend this class.
 */
open class VtInterpreter(t: TerminalEmulator, val profile: DecProfile) : TerminalInterpreter(t) {
    /** G0-G3, GL and GR (a VT100 uses G0, G1 and SO/SI of them) */
    protected val sets = CharsetShifting()

    /** the keys the host loaded (DECUDK, a VT220's and later) */
    protected val userKeys = UserKeys()

    /** the level DECSCL set it to work at (at most the profile's) */
    protected var operatingLevel = profile.level
        private set

    /** a designation's intermediates after the first (ESC ( % 5's %) */
    private var designation = ""

    /** VT52 mode (DECANM reset): its interpreter, until ESC < */
    private var vt52: Vt52Interpreter? = null

    /** the printer port (a VT102's and later), ended by MC 4 */
    private val printer = PrinterPort(t, listOf("\u001b[4i", "\u009b4i"))

    /** DECSET 40: xterm lets the remote change the columns (DECCOLM); a DEC terminal always does */
    private var allowColumns = false

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
    /** what the DCS's text is for: its intermediate and final, as "$q" (DECRQSS), "|" (DECUDK) */
    private var dcsKind = ""
    /** the DCS's first two parameters, as it began */
    private var dcsP0 = 0
    private var dcsP1 = 0

    /** xterm's extensions known */
    protected val xterm: Boolean get() = profile.has(VtFeature.XTERM)

    /** one code point through the parser, by the state it is in (text, ESC, CSI, OSC, a string, a DCS) */
    override fun process(cp: Int) {
        vt52?.let { it.process(cp); return }
        if (intercept(cp)) return
        if (printer.controller) {
            printer.feed(cp)
            return
        }
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
                0x84 -> t.index()
                0x8e -> if (operatingLevel >= 2) sets.singleShift(2)
                0x8f -> if (operatingLevel >= 2) sets.singleShift(3)
                0x85 -> { t.carriageReturn(); t.index() }
                0x8d -> t.reverseIndex()
                0x88 -> t.setTab()
            }
            return
        }
        t.print(sets.map(cp, t.decoder.charset == TermCharset.LATIN1))
    }

    /** a C0 control: ENQ's answerback, the bell, BS, HT, LF/VT/FF, CR, SO/SI, CAN/SUB, ESC */
    private fun control(cp: Int) {
        when (cp) {
            0x05 -> if (t.answerback.isNotEmpty()) t.reply(t.answerback)
            0x07 -> t.listener?.onBell()
            0x08 -> t.backspace()
            0x09 -> t.tab(1)
            0x0a, 0x0b, 0x0c -> {
                if (printer.auto) printer.printLine(t.cursorY)
                t.index()
                if (t.newlineMode) t.carriageReturn()
            }
            0x0d -> t.carriageReturn()
            0x0e -> sets.lockGl(1)
            0x0f -> sets.lockGl(0)
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
            '(', ')', '*', '+', '-', '.', '/', '#', ' ', '%' -> {
                intermediate = cp
                designation = ""
                state = State.ESC_INTER
            }
            '7' -> t.saveCursor()
            '8' -> t.restoreCursor()
            'D' -> t.index()
            'E' -> { t.carriageReturn(); t.index() }
            'M' -> t.reverseIndex()
            'H' -> t.setTab()
            'c' -> t.reset()
            // DECID: as DA1
            'Z' -> t.reply(profile.primaryDa)
            // the locking shifts (LS2, LS3, LS1R-LS3R) and single shifts (SS2, SS3): a VT220's
            'n', 'o', '~', '}', '|', 'N', 'O' -> if (operatingLevel >= 2) shift(cp.toChar())
            '=' -> t.appKeypad = true
            '>' -> t.appKeypad = false
            '\\' -> {}
            else -> if (cp < 0x20) control(cp)
        }
    }

    /** ESC [c]'s shift: GL to G2 or G3, GR to G1-G3, or the next character from G2 or G3 */
    private fun shift(c: Char) {
        when (c) {
            'n' -> sets.lockGl(2)
            'o' -> sets.lockGl(3)
            '~' -> sets.lockGr(1)
            '}' -> sets.lockGr(2)
            '|' -> sets.lockGr(3)
            'N' -> sets.singleShift(2)
            'O' -> sets.singleShift(3)
        }
    }

    /** whether this terminal (at the level it works at) has [set] */
    private fun knows(set: GSet): Boolean = xterm || set.level <= operatingLevel

    /** G[slot] given the 94- or 96-character set [designator] names (< the preferred supplemental set), if this terminal has it */
    private fun designate(slot: Int, designator: String, is96: Boolean) {
        if (slot >= 2 && operatingLevel < 2 && !xterm) return
        val set = if (designator == "<" && !is96) sets.preferred else GSet.byDesignator(designator, is96) ?: return
        if (knows(set)) sets.designate(slot, set)
    }

    /** the character after ESC and an intermediate: a set designated (ESC ( to ESC /), a line's size, DECALN, S7C1T/S8C1T */
    private fun escInter(cp: Int) {
        val i = intermediate.toChar()
        if (cp in 0x20..0x2f && i in "()*+-./" && designation.isEmpty()) {
            // a designator of two characters (DEC Supplemental's % 5)
            designation = cp.toChar().toString()
            return
        }
        state = State.GROUND
        val d = designation + cp.toChar()
        when (i) {
            '(', ')', '*', '+' -> designate("()*+".indexOf(i), d, is96 = false)
            '-', '.', '/' -> designate("-./".indexOf(i) + 1, d, is96 = true)
            ' ' -> when (cp.toChar()) {
                'F' -> t.eightBitReplies = false
                'G' -> if (operatingLevel >= 2) t.eightBitReplies = true
            }
            '#' -> when (cp.toChar()) {
                '3' -> t.setLineSize(LineSize.DOUBLE_TOP)
                '4' -> t.setLineSize(LineSize.DOUBLE_BOTTOM)
                '5' -> t.setLineSize(LineSize.SINGLE)
                '6' -> t.setLineSize(LineSize.DOUBLE_WIDTH)
                '8' -> t.alignmentTest()
            }
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

    /** a code point a subclass takes before the parser does (ANSI music's notes): true if it took it */
    protected open fun intercept(cp: Int): Boolean = false

    /**
     * A CSI a subclass carries out instead of (or as well as) this one:
     * its [final], private [marker] and [inter]mediate (0 for none), the
     * parameters through [p] and [paramCount]. True if it was done.
     */
    protected open fun extraCsi(final: Int, marker: Int, inter: Int): Boolean = false

    /** how many parameters the CSI has */
    protected val paramCount: Int get() = nParams

    /** parameter [i] as it came (0 when missing) */
    protected fun param(i: Int): Int = if (i < nParams) params[i] else 0

    /** parameter [i], or [default] when it is missing or 0 */
    protected fun p(i: Int, default: Int): Int =
        if (i < nParams && params[i] != 0) params[i] else default

    /** a CSI ended by [final] carried out: DEC private modes and questions, DA2/DA3, DECSTR, DECSCUSR, then the ANSI ones */
    private fun dispatchCsi(final: Int) {
        if (extraCsi(final, privateMarker, intermediate)) return
        val c = final.toChar()
        if (privateMarker == '?'.code) {
            if (intermediate == '$'.code && c == 'p') {
                // DECRQM: a DEC mode asked about
                t.reply("\u001b[?${p(0, 0)};${decModeState(p(0, 0))}\$y")
                return
            }
            when (c) {
                'h' -> for (i in 0 until maxOf(nParams, 1)) decMode(params[i], true)
                'l' -> for (i in 0 until maxOf(nParams, 1)) decMode(params[i], false)
                // DECSED and DECSEL: what DECSCA didn't protect erased (a VT220's)
                'J' -> t.eraseDisplay(p(0, 0), selective = operatingLevel >= 2)
                'K' -> t.eraseLine(p(0, 0), selective = operatingLevel >= 2)
                'n' -> decStatus(p(0, 0))
                'S' -> if (xterm) graphicsAttributes(p(0, 0), p(1, 0))
                'i' -> if (profile.has(VtFeature.PRINTER)) when (p(0, 0)) {
                    1 -> printer.printLine(t.cursorY)
                    4 -> printer.auto = false
                    5 -> printer.auto = true
                }
            }
            return
        }
        if (privateMarker == '>'.code) {
            when (c) {
                'c' -> profile.secondaryDa?.let { t.reply(it) }
                // XTVERSION
                'q' -> if (xterm) t.reply("\u001bP>|D-Modem 1.0\u001b\\")
            }
            return
        }
        if (privateMarker == '='.code) {
            // DA3: a unit id
            if (c == 'c' && (profile.level >= 4 || xterm)) t.reply("\u001bP!|00000000\u001b\\")
            return
        }
        if (privateMarker != 0) return
        if (intermediate != 0) {
            when {
                // DECSTR: a soft reset
                intermediate == '!'.code && c == 'p' -> t.softReset()
                // DECSCUSR: the cursor's shape
                intermediate == ' '.code && c == 'q' -> t.cursorStyle = p(0, 0).coerceIn(0, 6)
                // DECRQM: an ANSI mode asked about
                intermediate == '$'.code && c == 'p' -> t.reply("\u001b[${p(0, 0)};${ansiModeState(p(0, 0))}\$y")
                // DECSCA: the characters written from now protected from DECSED and DECSEL (1) or not
                intermediate == '"'.code && c == 'q' && operatingLevel >= 2 -> {
                    val a = Style.attrs(t.style) and Style.PROTECTED.inv()
                    t.style = Style.withAttrs(t.style, if (p(0, 0) == 1) a or Style.PROTECTED else a)
                }
                // DECSCL: the level to work at (61 a VT100 ... 6n this one's), and 7-bit (1) or 8-bit replies
                intermediate == '"'.code && c == 'p' && profile.level >= 2 -> conformance(p(0, 0), param(1))
                // DECRQPSR: the cursor's state (DECCIR) or the tab stops (DECTABSR)
                intermediate == '$'.code && c == 'w' && (operatingLevel >= 3 || xterm) -> presentationState(p(0, 0))
                // DECRQUPSS: the user-preferred supplemental set
                intermediate == '&'.code && c == 'u' && (operatingLevel >= 3 || xterm) -> t.reply(preferredSetString())
            }
            return
        }
        when (c) {
            'A' -> t.moveCursor(t.cursorX, t.cursorY - p(0, 1), clampToRegion = true)
            'B', 'e' -> t.moveCursor(t.cursorX, t.cursorY + p(0, 1), clampToRegion = true)
            'C', 'a' -> t.moveCursor(t.cursorX + p(0, 1), t.cursorY)
            'D' -> t.moveCursor(t.cursorX - p(0, 1), t.cursorY)
            'E' -> t.moveCursor(0, t.cursorY + p(0, 1), clampToRegion = true)
            'F' -> t.moveCursor(0, t.cursorY - p(0, 1), clampToRegion = true)
            'G', '`' -> t.moveCursor(p(0, 1) - 1, t.cursorY)
            'H', 'f' -> t.cursorPosition(p(1, 1) - 1, p(0, 1) - 1)
            'd' -> t.cursorPosition(t.cursorX, p(0, 1) - 1)
            'I' -> t.tab(p(0, 1))
            'Z' -> t.backTab(p(0, 1))
            'J' -> t.eraseDisplay(p(0, 0))
            'K' -> t.eraseLine(p(0, 0))
            'L' -> t.insertLines(p(0, 1))
            'M' -> t.deleteLines(p(0, 1))
            '@' -> t.insertChars(p(0, 1))
            'P' -> t.deleteChars(p(0, 1))
            'X' -> t.eraseChars(p(0, 1))
            'S' -> repeat(p(0, 1).coerceAtMost(t.rows)) { t.scrollUp(t.top, t.bottom) }
            'T' -> repeat(p(0, 1).coerceAtMost(t.rows)) { t.scrollDown(t.top, t.bottom) }
            'b' -> repeat(p(0, 1).coerceAtMost(t.cols * t.rows)) { t.print(t.lastChar) }
            'g' -> when (p(0, 0)) {
                0 -> t.clearTab()
                3 -> t.clearTabs()
            }
            'h' -> for (i in 0 until maxOf(nParams, 1)) ansiMode(params[i], true)
            'l' -> for (i in 0 until maxOf(nParams, 1)) ansiMode(params[i], false)
            'm' -> sgr()
            'n' -> when (p(0, 0)) {
                5 -> t.reply("\u001b[0n")
                6 -> {
                    val y = if (t.originMode) t.cursorY - t.top else t.cursorY
                    t.reply("\u001b[${y + 1};${minOf(t.cursorX, t.cols - 1) + 1}R")
                }
            }
            'c' -> if (p(0, 0) == 0) t.reply(profile.primaryDa)
            'r' -> t.setRegion(p(0, 1) - 1, p(1, t.rows) - 1)
            's' -> t.saveCursor()
            'u' -> t.restoreCursor()
            't' -> if (xterm) windowReport(p(0, 0))
            // DECREQTPARM: no parity, 8 bits, 38400 baud each way (sent unasked: 2, asked: 3)
            'x' -> if (p(0, 0) <= 1 && nParams <= 1) t.reply("\u001b[${(if (nParams == 0) 0 else params[0]) + 2};1;1;128;128;1;0x")
            // DECLL: the keyboard's LEDs (0 all off, 1-4 on); DECTST (y): no tests to run
            'q' -> for (i in 0 until maxOf(nParams, 1)) {
                val n = params[i]
                t.leds = if (n == 0) 0 else if (n in 1..4) t.leds or (1 shl (n - 1)) else t.leds
            }
            // MC: print the screen (0), printer controller mode on (5) and off (4)
            'i' -> if (profile.has(VtFeature.PRINTER)) when (p(0, 0)) {
                0 -> printer.printScreen()
                5 -> printer.startController()
            }
        }
    }

    /** DEC private mode [m] set or reset (DECSET/DECRST) */
    private fun decMode(m: Int, on: Boolean) {
        when (m) {
            1 -> t.appCursorKeys = on
            // DECANM reset: VT52 mode, with this terminal's graphics, until ESC <
            2 -> if (!on) enterVt52()
            // DECCOLM: 132 columns or 80
            3 -> if (!xterm || allowColumns) t.setColumnMode(on)
            5 -> t.reverseVideo = on
            6 -> { t.originMode = on; t.cursorPosition(0, 0) }
            7 -> t.autowrap = on
            25 -> t.cursorVisible = on
            80 -> if (profile.has(VtFeature.SIXEL)) t.sixelScrolling = !on
        }
        if (!xterm) return
        when (m) {
            47, 1047 -> t.setAltScreen(on, clear = m == 1047)
            1048 -> if (on) t.saveCursor() else t.restoreCursor()
            1049 -> {
                if (on) { t.saveCursor(); t.setAltScreen(true, clear = true) } else { t.setAltScreen(false, clear = false); t.restoreCursor() }
            }
            2004 -> t.bracketedPaste = on
            40 -> allowColumns = on
            9 -> t.mouseMode = if (on) MouseMode.X10 else MouseMode.NONE
            1000 -> t.mouseMode = if (on) MouseMode.NORMAL else MouseMode.NONE
            1002 -> t.mouseMode = if (on) MouseMode.BUTTON else MouseMode.NONE
            1003 -> t.mouseMode = if (on) MouseMode.ANY else MouseMode.NONE
            1004 -> t.focusEvents = on
            1005 -> t.mouseUtf8 = on
            1006 -> t.mouseSgr = on
            1015 -> t.mouseUrxvt = on
            8452 -> t.sixelCursorRight = on
        }
    }

    /** DECRQM's answer for a DEC mode: 1 set, 2 reset, 0 not one known */
    private fun decModeState(m: Int): Int {
        /** DECRQM's 1 for set, 2 for reset */
        fun b(v: Boolean) = if (v) 1 else 2
        val dec = when (m) {
            1 -> b(t.appCursorKeys)
            // (asked in ANSI mode: it is in ANSI mode)
            2 -> 1
            3 -> b(t.requestedColumns == 132)
            5 -> b(t.reverseVideo)
            6 -> b(t.originMode)
            7 -> b(t.autowrap)
            25 -> b(t.cursorVisible)
            80 -> if (profile.has(VtFeature.SIXEL)) b(!t.sixelScrolling) else 0
            else -> 0
        }
        if (dec != 0 || !xterm) return dec
        return when (m) {
            47, 1047, 1049 -> b(t.altScreen)
            2004 -> b(t.bracketedPaste)
            9 -> b(t.mouseMode == MouseMode.X10)
            1000 -> b(t.mouseMode == MouseMode.NORMAL)
            1002 -> b(t.mouseMode == MouseMode.BUTTON)
            1003 -> b(t.mouseMode == MouseMode.ANY)
            40 -> b(allowColumns)
            1004 -> b(t.focusEvents)
            1005 -> b(t.mouseUtf8)
            1006 -> b(t.mouseSgr)
            1015 -> b(t.mouseUrxvt)
            8452 -> b(t.sixelCursorRight)
            else -> 0
        }
    }

    /** DECRQM's answer for an ANSI mode (IRM 4, LNM 20): 1 set, 2 reset, 0 not one known */
    private fun ansiModeState(m: Int): Int = when (m) {
        4 -> if (t.insertMode) 1 else 2
        20 -> if (t.newlineMode) 1 else 2
        else -> 0
    }

    /** ANSI mode [m] set or reset: insert (4), newline (20) */
    private fun ansiMode(m: Int, on: Boolean) {
        when (m) {
            4 -> t.insertMode = on
            20 -> t.newlineMode = on
        }
    }

    /** XTWINOPS' reports: the text area's and a cell's size in pixels, the screen's in characters */
    private fun windowReport(op: Int) {
        when (op) {
            14 -> t.reply("\u001b[4;${t.rows * t.cellPixelHeight};${t.cols * t.cellPixelWidth}t")
            16 -> t.reply("\u001b[6;${t.cellPixelHeight};${t.cellPixelWidth}t")
            18 -> t.reply("\u001b[8;${t.rows};${t.cols}t")
            19 -> t.reply("\u001b[9;${t.rows};${t.cols}t")
        }
    }

    /** XTSMGRAPHICS: the colour registers (1) and the largest sixel image (2) */
    private fun graphicsAttributes(item: Int, action: Int) {
        val max = TerminalEmulator.MAX_IMAGE
        when (item) {
            1 -> t.reply("\u001b[?1;0;256S")
            2 -> if (action == 1 || action == 4) {
                t.reply("\u001b[?2;0;${minOf(t.cols * t.cellPixelWidth, max)};${minOf(t.rows * t.cellPixelHeight, max)}S")
            } else {
                t.reply("\u001b[?2;3;0S")
            }
            else -> t.reply("\u001b[?$item;1;0S")
        }
    }

    /** SGR: the CSI's parameters applied to the current rendition (none: back to normal); colours only if the profile has them */
    private fun sgr() {
        // (DECSCA's protection isn't a rendition: SGR leaves it)
        val kept = Style.attrs(t.style) and Style.PROTECTED
        if (nParams == 0) {
            t.style = Style.withAttrs(Style.NORMAL, kept)
            return
        }
        val color = profile.has(VtFeature.COLOR)
        var i = 0
        var s = t.style
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
                in 30..37 -> if (color) s = Style.withFg(s, v - 30)
                39 -> s = Style.withFg(s, Style.DEFAULT_FG)
                in 40..47 -> if (color) s = Style.withBg(s, v - 40)
                49 -> s = Style.withBg(s, Style.DEFAULT_BG)
                in 90..97 -> if (color) s = Style.withFg(s, v - 90 + 8)
                in 100..107 -> if (color) s = Style.withBg(s, v - 100 + 8)
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
                    var c = -1
                    if (i + 1 < nParams) {
                        when (params[i + 1]) {
                            5 -> if (i + 2 < nParams) { c = params[i + 2] and 0xff; i += 2 }
                            2 -> {
                                var j = i + 2
                                if (colon.getOrElse(i + 1) { false } && i + 5 < nParams && colon[i + 5]) j++
                                if (j + 2 < nParams) {
                                    c = Style.RGB or ((params[j] and 0xff) shl 16) or
                                        ((params[j + 1] and 0xff) shl 8) or (params[j + 2] and 0xff)
                                    i = j + 2
                                } else {
                                    i = nParams
                                }
                            }
                            else -> i = nParams
                        }
                    }
                    if (c >= 0 && color) s = if (v == 38) Style.withFg(s, c) else Style.withBg(s, c)
                }
            }
            if (v != 0 && v != 38 && v != 48 && v != 58 && v !in 30..49 && v !in 90..107) s = Style.withAttrs(s, a)
            i++
        }
        t.style = Style.withAttrs(s, Style.attrs(s) or kept)
    }

    /** an OSC ended: the title, colours set, asked or reset, the clipboard, a hyperlink (xterm's: a DEC terminal ignores them) */
    private fun osc() {
        if (!xterm) return
        val s = osc.toString()
        val semi = s.indexOf(';')
        val cmd = (if (semi < 0) s else s.substring(0, semi)).toIntOrNull() ?: return
        val arg = if (semi < 0) "" else s.substring(semi + 1)
        when (cmd) {
            0, 2 -> t.listener?.onTitle(arg)
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
            104 -> if (arg.isEmpty()) t.resetColors(0 until 256) else arg.split(';').forEach { it.toIntOrNull()?.takeIf { n -> n in 0..255 }?.let { n -> t.resetColors(n..n) } }
            110 -> t.resetColors(256..256)
            111 -> t.resetColors(257..257)
            112 -> t.resetColors(258..258)
            52 -> {
                // the clipboard set (never read: the remote isn't told what's on it)
                val data = arg.substringAfter(';', "")
                if (data != "?" && data.isNotEmpty()) {
                    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                    runCatching { kotlin.io.encoding.Base64.Mime.decode(data) }.getOrNull()
                        ?.let { t.listener?.onClipboard(it.decodeToString()) }
                }
            }
            8 -> {
                // a hyperlink: params;uri, an empty uri ends it
                val uri = arg.substringAfter(';', "")
                t.link = uri.takeIf { it.isNotEmpty() && it.length <= 2048 }
            }
        }
    }

    /** a colour set from its spec, or told (OSC's answer) when the spec is "?" */
    private fun colorSpec(i: Int, spec: String, prefix: String) {
        if (spec == "?") {
            val c = t.colorOverride(i).takeIf { it != -1 } ?: t.defaultColor(i)
            /** a colour channel (0-255) as X's four hex digits */
            fun h(v: Int) = (v * 0x101).toString(16).padStart(4, '0')
            val end = if (oscBel) "\u0007" else "\u001b\\"
            t.reply("\u001b]$prefix" + "rgb:${h((c shr 16) and 0xff)}/${h((c shr 8) and 0xff)}/${h(c and 0xff)}$end")
            return
        }
        TerminalEmulator.parseColor(spec)?.let { t.setColorOverride(i, it) }
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
        dcsKind = ""
        dcsP0 = param(0)
        dcsP1 = param(1)
        val max = TerminalEmulator.MAX_IMAGE
        when {
            final == 'q'.code && intermediate == 0 && privateMarker == 0 && profile.has(VtFeature.SIXEL) -> {
                // P2 = 1: the pixels not drawn left transparent
                val maxW = minOf(max, t.cols * t.cellPixelWidth * 2)
                val maxH = minOf(max, t.rows * t.cellPixelHeight * 4)
                sixel = Sixel(transparent = p(1, 0) == 1, maxWidth = maxW, maxHeight = maxH)
            }
            // DECRQSS (VT320 and later, xterm), XTGETTCAP: their text collected
            final == 'q'.code && intermediate == '$'.code && (operatingLevel >= 3 || xterm) ||
                final == 'q'.code && intermediate == '+'.code && xterm ||
                // DECUDK (a VT220's), DECAUPSS (a VT320's)
                final == '|'.code && intermediate == 0 && operatingLevel >= 2 ||
                final == 'u'.code && intermediate == '!'.code && (operatingLevel >= 3 || xterm) -> {
                dcsText = StringBuilder()
                dcsKind = (if (intermediate != 0) intermediate.toChar().toString() else "") + final.toChar()
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
            sx.finish(t.cellPixelWidth, t.cellPixelHeight)?.let { t.placeImage(it) }
        }
        dcsText?.let { q ->
            dcsText = null
            when (dcsKind) {
                "\$q" -> statusString(q.toString())
                "|" -> userKeys.load(dcsP0, dcsP1, q.toString())
                "!u" -> assignPreferred(dcsP0, q.toString())
                else -> t.reply("\u001bP0+r\u001b\\")
            }
        }
    }

    /** DECRQSS: the graphic rendition, the scrolling region, the cursor's style */
    private fun statusString(q: String) {
        val ok = when (q) {
            "m" -> sgrString() + "m"
            "r" -> "${t.top + 1};${t.bottom + 1}r"
            " q" -> "${t.cursorStyle.coerceAtLeast(1)} q"
            "\"q" -> "${if (Style.attrs(t.style) and Style.PROTECTED != 0) 1 else 0}\"q"
            "\"p" -> "${60 + operatingLevel};${if (t.eightBitReplies) 0 else 1}\"p"
            // the lines a page has (DECSLPP), the columns (DECSCPP), the lines on the screen (DECSNLS)
            "t" -> "${t.rows}t"
            "\$|" -> "${t.cols}\$|"
            "*|" -> "${t.rows}*|"
            else -> null
        }
        t.reply(if (ok != null) "\u001bP1\$r$ok\u001b\\" else "\u001bP0\$r\u001b\\")
    }

    /** the current rendition as SGR's parameters */
    private fun sgrString(): String {
        val style = t.style
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

    // ------------------------------------------------------------------
    // the interpreter's own state

    /** G0-G3 and the shifts for DECSC */
    override fun charsetState(): Any = sets.save()

    /** G0-G3 and the shifts as DECSC saved them (none: as at the start) */
    override fun restoreCharsetState(s: Any?) {
        sets.restore(s as? CharsetShifting.Saved)
    }

    /** DECSCL: [level] 61-65 (a VT100 to a VT5xx, at most this one), [c1] 1 for 7-bit replies; a soft reset after */
    private fun conformance(level: Int, c1: Int) {
        if (level !in 61..65) return
        operatingLevel = minOf(level - 60, profile.level)
        t.eightBitReplies = operatingLevel >= 2 && c1 != 1
        t.softReset()
    }

    /** DSR with ?: the printer (15), the user-defined keys (25), the keyboard (26), the cursor with its page (6) */
    private fun decStatus(n: Int) {
        if (n == 6) {
            val y = if (t.originMode) t.cursorY - t.top else t.cursorY
            t.reply("\u001b[?${y + 1};${minOf(t.cursorX, t.cols - 1) + 1};1R")
            return
        }
        if (operatingLevel < 2) return
        when (n) {
            15 -> t.reply(if (profile.has(VtFeature.PRINTER)) "\u001b[?10n" else "\u001b[?13n")
            25 -> t.reply(if (userKeys.locked) "\u001b[?21n" else "\u001b[?20n")
            // North American, ready, an LK201
            26 -> t.reply("\u001b[?27;1;0;0n")
        }
    }

    /** DECRQPSR's answers: the cursor (1, DECCIR) or the tab stops (2, DECTABSR) */
    private fun presentationState(n: Int) {
        when (n) {
            1 -> t.reply("\u001bP1\$u${cursorInformation()}\u001b\\")
            2 -> t.reply("\u001bP2\$u${t.tabStops().joinToString("/") { (it + 1).toString() }}\u001b\\")
        }
    }

    /** DECCIR's: the cursor's row, column and page; its rendition, protection and flags; GL, GR and the sets' sizes and designators */
    private fun cursorInformation(): String {
        val a = Style.attrs(t.style)
        /** 0x40 with the bits the flags given set, as DECCIR's characters are */
        fun bits(vararg f: Boolean): Char = (0x40 + f.withIndex().sumOf { (i, on) -> if (on) 1 shl i else 0 }).toChar()
        val rend = bits(a and Style.BOLD != 0, a and Style.ANY_UNDERLINE != 0, a and Style.BLINK != 0, a and Style.INVERSE != 0)
        val att = bits(a and Style.PROTECTED != 0)
        val flag = bits(t.originMode, sets.single == 2, sets.single == 3, t.pendingWrap)
        val css = bits(sets.is96(0), sets.is96(1), sets.is96(2), sets.is96(3))
        val desig = sets.g.joinToString("") { it.designator ?: "B" }
        val y = if (t.originMode) t.cursorY - t.top else t.cursorY
        return "${y + 1};${minOf(t.cursorX, t.cols - 1) + 1};1;$rend;$att;$flag;${sets.gl};${sets.gr};$css;$desig"
    }

    /** DECRQUPSS's answer: the preferred supplemental set (0 for a 94-character one, 1 for 96) and its designator */
    private fun preferredSetString(): String {
        val s = sets.preferred
        return "\u001bP${if (s.is96) 1 else 0}!u${s.designator}\u001b\\"
    }

    /** DECAUPSS: the preferred supplemental set made [designator]'s ([size] 0 a 94-character set, 1 a 96-character one): DEC Supplemental or Latin-1 */
    private fun assignPreferred(size: Int, designator: String) {
        val s = GSet.byDesignator(designator, is96 = size == 1) ?: return
        if (s == GSet.DEC_SUPPLEMENTAL || s == GSet.LATIN1) sets.preferred = s
    }

    /** with Shift, F6-F20 send what the host loaded into them (DECUDK), if it did */
    override fun keyOctets(k: Key, shift: Boolean, alt: Boolean, ctrl: Boolean): ByteArray? =
        if (shift && !alt && !ctrl && operatingLevel >= 2) userKeys.forKey(k) else null

    /** VT52 mode: a [Vt52Interpreter] reads what comes, with DEC Special Graphics, until its ESC < */
    private fun enterVt52() {
        vt52 = Vt52Interpreter(t, GSet.DEC_GRAPHICS, "\u001b/Z") {
            vt52 = null
            t.vt52Keys = false
        }
    }

    /** DECSTR: the character sets back to ASCII */
    override fun softReset() {
        restoreCharsetState(null)
    }

    /** RIS: the parser in its ground state, no DCS under way, the level the profile's, the keys and preferred set cleared */
    override fun reset() {
        operatingLevel = profile.level
        t.eightBitReplies = false
        userKeys.reset()
        sets.preferred = GSet.LATIN1
        softReset()
        vt52 = null
        t.vt52Keys = false
        printer.reset()
        allowColumns = false
        sixel = null
        dcsText = null
        state = State.GROUND
    }
}
