// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * An Atari ST's terminal: TOS's VT52 with its own additions (colours,
 * the cursor on and off and saved, more erases, reverse, wrap, line
 * insert and delete) in the ST's character set. This one is medium
 * resolution's 80x25 in four colours (white, red, green, black: the
 * text black on white, as the desktop is); [AtariStLowProfile] is low
 * resolution's 40x25 in sixteen.
 */
open class AtariStProfile : TerminalProfile(), ByteMachine {
    override val id get() = "atari-st"
    override val label get() = "Atari ST (80 col.)"
    override val term get() = "st52"
    // (BBSes know it as the VT52 it is)
    override val telnetType get() = "VT52"

    override val columns get() = 80
    override val rows get() = 25
    override val palette: IntArray get() = TOS_PALETTE.copyOfRange(0, 3) + TOS_PALETTE[15]
    /** the highest pen on the first (white) */
    override val defaultColor get() = palette.size - 1
    override val background get() = 0
    /** the ST's own 8x8 */
    override val glyphs: BitmapFont? get() = MachineFonts.ATARI_ST

    /** the standard keys and the keypad */
    override val keyBar: List<KeyBarPage> get() = KeyBars.DEC

    /** an [AtariStInterpreter] */
    override fun interpreter(t: TerminalEmulator): TerminalInterpreter = AtariStInterpreter(t, this)

    companion object {
        /** TOS's sixteen colours at power-up (its 3-bit levels): white, red, green, yellow, blue, magenta, cyan, light grey, grey, light red, light green, light yellow, light blue, light magenta, light cyan, black */
        val TOS_PALETTE: IntArray = intArrayOf(
            0x777, 0x700, 0x070, 0x770, 0x007, 0x707, 0x077, 0x555,
            0x333, 0x733, 0x373, 0x773, 0x337, 0x737, 0x377, 0x000,
        ).map { c ->
            /** a 3-bit level as 0-255 */
            fun level(v: Int) = v * 255 / 7
            (0xff shl 24) or (level(c shr 8 and 7) shl 16) or (level(c shr 4 and 7) shl 8) or level(c and 7)
        }.toIntArray()
    }
}

/** An Atari ST in low resolution: 40x25 in TOS's sixteen colours */
open class AtariStLowProfile : AtariStProfile() {
    override val id get() = "atari-st-low"
    override val label get() = "Atari ST (40 col., 16 colours)"
    override val columns get() = 40
    override val palette: IntArray get() = TOS_PALETTE
}

/** TOS's VT52: the VT52's sequences and the ST's own, the octets in the ST's set */
open class AtariStInterpreter(t: TerminalEmulator, private val m: AtariStProfile) :
    Vt52Interpreter(t, GSet.VT52_GRAPHICS, "\u001b/K") {
    private var fg = m.defaultColor
    private var bg = m.background
    private var reverse = false

    init {
        attach()
    }

    override val readsOctets: Boolean get() = true

    /** the ST's screen and colours on the emulator, wrap on, the cursor shown */
    private fun attach() {
        t.useMachine(m)
        t.autowrap = true
        t.cursorVisible = true
        restyle()
    }

    /** what's written from now on: the pens, reversed or not */
    private fun restyle() {
        t.style = Style.of(fg, bg, if (reverse) Style.INVERSE else 0)
    }

    /** an octet in the ST's set */
    override fun charOf(cp: Int): Int = stChar(cp)

    /** the ST's own after ESC: E clear, d erase above, e and f the cursor, j and k its place saved, l and o line erases, p and q reverse, v and w wrap, L and M lines, b and c the colours */
    override fun esc(cp: Int) {
        when (cp.toChar()) {
            'E' -> {
                t.eraseDisplay(2)
                t.moveCursor(0, 0)
            }
            'd' -> t.eraseDisplay(1)
            'e' -> t.cursorVisible = true
            'f' -> t.cursorVisible = false
            'j' -> t.saveCursor()
            'k' -> t.restoreCursor()
            'l' -> {
                t.eraseLine(2)
                t.carriageReturn()
            }
            'o' -> t.eraseLine(1)
            'p' -> { reverse = true; restyle() }
            'q' -> { reverse = false; restyle() }
            'v' -> t.autowrap = true
            'w' -> t.autowrap = false
            'L' -> {
                t.insertLines(1)
                t.carriageReturn()
            }
            'M' -> {
                t.deleteLines(1)
                t.carriageReturn()
            }
            'b', 'c' -> takeArgument(cp)
            else -> super.esc(cp)
        }
    }

    /** ESC b's and ESC c's pen: its low bits (as many as the resolution has colours) */
    override fun escArgument(command: Int, arg: Int) {
        val pen = arg and (m.palette.size - 1)
        if (command == 'b'.code) fg = pen else bg = pen
        restyle()
    }

    /** RIS: the pens and reverse as at the start, the screen the ST's */
    override fun reset() {
        super.reset()
        fg = m.defaultColor
        bg = m.background
        reverse = false
        attach()
    }

    /** the view's size and the theme's colours back */
    override fun detach() {
        t.releaseMachine()
    }

    companion object {
        /** an octet in the ST's set as its code point */
        internal fun stChar(b: Int): Int = if (b in 0x80..0xff) HIGH[b - 0x80].code else b

        /** the ST's set from 0x80: Latin letters, Hebrew, Greek and mathematics */
        private const val HIGH =
            "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥ßƒáíóúñÑªº¿⌐¬½¼¡«»ãõØøœŒÀÃÕ¨´†¶©®™" +
                "ĳĲאבגדהוזחטיכלמנסעפצקרשתןךםףץ§∧∞αβΓπΣσµτΦΘΩδ∮ϕ∈∩≡±≥≤⌠⌡÷≈°•·√ⁿ²³¯"
    }
}
