// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * An Atari 400/800/XL/XE as a terminal (ATASCII), as Ice-T and BobTerm
 * are: the screen editor's 40x24 in GRAPHICS 0's two shades of one hue,
 * the ATASCII set (or the XL's international one), 0x80-0xff the same
 * characters in inverse video.
 */
open class AtariProfile : TerminalProfile(), ByteMachine {
    override val id get() = "atari"
    override val label get() = "Atari 8-bit"
    override val term get() = "atascii"
    override val telnetType get() = "ATASCII"
    override val glyphs: BitmapFont? get() = LegacyCharsets.GLYPHS

    override val columns get() = 40
    override val rows get() = 24
    /** GRAPHICS 0: the background (COLOR2, $94) and the text in a lighter shade of its blue (COLOR1's luminance) */
    override val palette: IntArray get() = intArrayOf(0xff1d5dae.toInt(), 0xffa8d8ff.toInt())
    override val defaultColor get() = 1
    override val background get() = 0

    /** the character set (0x00-0x7f's code points; 0x80-0xff are them reversed) */
    open val atasciiSet: IntArray get() = LegacyCharsets.ATARI_GRAPHICS

    override val keyBar: List<KeyBarPage> get() = listOf(KeyBars.ATARI)

    /** an [AtasciiInterpreter] */
    override fun interpreter(t: TerminalEmulator): TerminalInterpreter = AtasciiInterpreter(t, this)
}

/** An Atari XL/XE with its international set (accented letters where the graphics were) */
open class AtariInternationalProfile : AtariProfile() {
    override val id get() = "atari-intl"
    override val label get() = "Atari 8-bit (international)"
    override val atasciiSet: IntArray get() = LegacyCharsets.ATARI_INTERNATIONAL
}

/**
 * The Atari's screen editor: its cursor keys (1C-1F, wrapping round the
 * screen, not scrolling), CLEAR (7D), BACK S (7E), TAB (7F), EOL (9B),
 * delete and insert line (9C, 9D) and character (FE, FF), tab stops (9E,
 * 9F), the buzzer (FD), and ESC to show the next code's character instead
 * of doing it. Any other code is a character, reversed from 0x80.
 */
open class AtasciiInterpreter(t: TerminalEmulator, private val m: AtariProfile) : ByteControlInterpreter(t, m) {
    /** an ESC came: the next code is shown, not done */
    private var literal = false

    /** as at the start: no ESC pending */
    override fun reset() {
        super.reset()
        literal = false
    }

    /** an octet: shown after an ESC, else done if it is a control, else shown */
    override fun process(cp: Int) {
        if (literal) {
            literal = false
            show(cp)
            return
        }
        if (!control(cp)) show(cp)
    }

    /** code [b]'s character, reversed from 0x80 */
    private fun show(b: Int) {
        val inverse = b >= 0x80
        if (inverse != reverse) reverseTo(inverse)
        put(charOf(b))
    }

    /** code [b]'s character (a reversed one's plain one) */
    override fun charOf(b: Int): Int = m.atasciiSet[b and 0x7f]

    /** the screen editor's control codes: true if [b] was one */
    override fun control(b: Int): Boolean {
        when (b) {
            0x1b -> literal = true
            0x1c -> t.moveCursor(t.cursorX, if (t.cursorY == 0) t.rows - 1 else t.cursorY - 1)
            0x1d -> t.moveCursor(t.cursorX, if (t.cursorY == t.rows - 1) 0 else t.cursorY + 1)
            0x1e -> t.moveCursor(if (t.cursorX == 0) t.cols - 1 else t.cursorX - 1, t.cursorY)
            0x1f -> t.moveCursor(if (t.cursorX == t.cols - 1) 0 else t.cursorX + 1, t.cursorY)
            0x7d -> clear()
            0x7e -> backSpace()
            0x7f -> tab()
            0x9b -> newLine()
            0x9c -> t.deleteLines(1)
            0x9d -> t.insertLines(1)
            0x9e -> tabStop(false)
            0x9f -> tabStop(true)
            0xfd -> bell()
            0xfe -> t.deleteChars(1)
            0xff -> insertBlank()
            else -> return false
        }
        return true
    }

    /** BACK S: the cursor left, the character there blanked (the rest of the line stays) */
    private fun backSpace() {
        if (t.cursorX == 0) return
        t.moveCursor(t.cursorX - 1, t.cursorY)
        t.style = Style.erased(look())
        t.eraseChars(1)
        t.style = look()
    }

    /** an Atari's keys: the cursor keys, RETURN as EOL, BACK S, DELETE and INSERT (with SHIFT the line), TAB, ESC, SHIFT+HOME as CLEAR */
    override fun keyOctets(k: Key, shift: Boolean, alt: Boolean, ctrl: Boolean): ByteArray? {
        val b = when (k) {
            Key.UP -> 0x1c
            Key.DOWN -> 0x1d
            Key.LEFT -> 0x1e
            Key.RIGHT -> 0x1f
            Key.ENTER, Key.KP_ENTER -> 0x9b
            Key.BACKSPACE -> if (shift) 0x9c else 0x7e
            Key.DELETE -> if (shift) 0x9c else 0xfe
            Key.INSERT -> if (shift) 0x9d else 0xff
            Key.TAB -> if (shift) 0x9f else 0x7f
            Key.ESCAPE -> 0x1b
            Key.HOME -> if (shift) 0x7d else return ByteArray(0)
            in Key.KP_0..Key.KP_9 -> '0'.code + (k.ordinal - Key.KP_0.ordinal)
            Key.KP_MINUS -> '-'.code
            Key.KP_COMMA -> ','.code
            Key.KP_PERIOD -> '.'.code
            else -> return ByteArray(0)
        }
        return byteArrayOf(b.toByte())
    }

    /** a character typed or pasted as its ATASCII code, reversed with the inverse key (Alt) */
    override fun charOctets(cp: Int, alt: Boolean, ctrl: Boolean): ByteArray? {
        val b = code(cp, ctrl) ?: return ByteArray(0)
        // (the inverse key, as Alt: the character reversed)
        return byteArrayOf((if (alt && b !in CONTROLS) b or 0x80 else b).toByte())
    }

    /** what the keyboard sends for [cp], with CTRL its graphic */
    private fun code(cp: Int, ctrl: Boolean): Int? {
        if (ctrl) {
            val upper = if (cp in 'a'.code..'z'.code) cp - 32 else cp
            when {
                upper in 'A'.code..'Z'.code -> return upper - '@'.code
                cp == ','.code -> return 0x00
                cp == '.'.code -> return 0x60
                cp == ';'.code -> return 0x7b
            }
        }
        return when (cp) {
            '\r'.code, '\n'.code -> 0x9b
            0x08, 0x7f -> 0x7e
            0x1b -> 0x1b
            else -> codeIn(m.atasciiSet, cp, 0x20..0x7c) ?: codeIn(m.atasciiSet, cp, 0x00..0x1a) ?: '?'.code
        }
    }

    companion object {
        /** the codes that are the editor's controls, not characters */
        private val CONTROLS = setOf(0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x7d, 0x7e, 0x7f, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f, 0xfd, 0xfe, 0xff)
    }
}
