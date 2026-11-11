// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * Avatar, FidoNet's compact codes (AVT/0, FSC-0025, and AVT/0+, FSC-0037)
 * over ANSI-BBS: what a BBS set to Avatar sends, and the ANSI it mixes in.
 */
open class AvatarProfile : AnsiBbsProfile() {
    override val id get() = "avatar"
    override val label get() = "Avatar (AVT/0+)"
    override val term get() = "avatar"
    // (BBSes find ANSI by it; Avatar is chosen on the BBS)
    override val telnetType get() = "ANSI"

    /** an [AvatarInterpreter] */
    override fun interpreter(t: TerminalEmulator): TerminalInterpreter = AvatarInterpreter(t, this)
}

/**
 * Avatar's codes taken before ANSI-BBS's parser sees them: ^L (clear,
 * cyan), ^Y (repeat a character), and ^V's commands: a PC attribute
 * byte, blink, the cursor's moves and place, clear to the line's end, and
 * AVT/0+'s insert mode, area scrolls, clears and fills, character delete
 * and pattern repeat.
 */
open class AvatarInterpreter(t: TerminalEmulator, profile: AvatarProfile) : AnsiBbsInterpreter(t, profile) {
    private val rectangles = Rectangles(t)
    /** the command whose argument bytes are being gathered (^Y: [REPEAT], ^V's: its code), -1 none; [LEAD]: ^V's code to come */
    private var command = NONE
    private val args = ArrayList<Int>()
    /** the arguments the command takes (^V ^Y's grows once its length comes) */
    private var need = 0
    /** ^V ^A's DLE: the next byte the attribute, whatever it is */
    private var quoted = false

    /** an Avatar code, or one of its arguments: taken; else ANSI-BBS's (its music first) */
    override fun intercept(cp: Int): Boolean {
        if (command != NONE) {
            argument(octet(cp))
            return true
        }
        if (super.intercept(cp)) return true
        when (cp) {
            0x0c -> {
                t.insertMode = false
                attribute(3)
                t.eraseDisplay(2)
                t.moveCursor(0, 0)
            }
            0x19 -> expect(REPEAT, 2)
            0x16 -> command = LEAD
            else -> return false
        }
        return true
    }

    /** a code point as the octet it came as (the CP437 the profile reads) */
    private fun octet(cp: Int): Int = Cp437.encode(cp)

    /** command [c]'s [n] arguments to gather */
    private fun expect(c: Int, n: Int) {
        command = c
        need = n
        args.clear()
        quoted = false
        if (n == 0) run()
    }

    /** one byte after ^V or ^Y: a command's code, or one of its arguments (its last running it) */
    private fun argument(b: Int) {
        if (command == LEAD) {
            // (any ^V command but insert mode's own ends insert mode)
            if (b != 0x09) t.insertMode = false
            expect(b, ARGS[b] ?: 0)
            return
        }
        if (command == 0x01 && b == 0x10 && !quoted) {
            quoted = true
            return
        }
        args.add(b)
        // ^V ^Y: its pattern's length, the pattern, then the count
        if (command == 0x19 && args.size == 1) need = 1 + b + 1
        if (args.size >= need) run()
    }

    /** the gathered command carried out */
    private fun run() {
        val c = command
        command = NONE
        val a = args
        when (c) {
            REPEAT -> repeat(Cp437.decode(a[0]), a[1])
            0x01 -> attribute(a[0])
            0x02 -> t.style = Style.withAttrs(t.style, Style.attrs(t.style) or Style.BLINK)
            0x03 -> t.moveCursor(t.cursorX, t.cursorY - 1)
            0x04 -> t.moveCursor(t.cursorX, t.cursorY + 1)
            0x05 -> t.moveCursor(t.cursorX - 1, t.cursorY)
            0x06 -> t.moveCursor(t.cursorX + 1, t.cursorY)
            0x07 -> t.eraseLine(0)
            0x08 -> t.moveCursor(a[1] - 1, a[0] - 1)
            0x09 -> t.insertMode = true
            0x0a, 0x0b -> scroll(up = c == 0x0a, n = a[0], top = a[1], left = a[2], bottom = a[3], right = a[4])
            0x0c -> area(a[0], ' '.code, a[1], a[2])
            0x0d -> area(a[0], Cp437.decode(a[1]), a[2], a[3])
            0x0e -> t.deleteChars(1)
            0x19 -> {
                val n = a[0]
                val pattern = a.subList(1, 1 + n).map { Cp437.decode(it) }
                repeat(a[1 + n]) { pattern.forEach(t::print) }
            }
        }
    }

    /** character [cp] printed [n] times */
    private fun repeat(cp: Int, n: Int) {
        repeat(n) { t.print(cp) }
    }

    /** a PC attribute byte as the style: its colours (CGA order to ANSI's), bit 7 blink */
    private fun attribute(a: Int) {
        val fg = PC_TO_ANSI[a and 7] + (a and 8)
        val bg = PC_TO_ANSI[(a shr 4) and 7]
        t.style = Style.of(fg, bg, if (a and 0x80 != 0) Style.BLINK else 0)
    }

    /** AVT/0+'s scroll of the area ([top], [left], [bottom], [right], from 1) [n] lines up or down (0: the area blanked), the lines it leaves blank */
    private fun scroll(up: Boolean, n: Int, top: Int, left: Int, bottom: Int, right: Int) {
        val y0 = (top - 1).coerceIn(0, t.rows - 1)
        val y1 = (bottom - 1).coerceIn(0, t.rows - 1)
        val x0 = (left - 1).coerceIn(0, t.cols - 1)
        val x1 = (right - 1).coerceIn(0, t.cols - 1)
        if (y0 > y1 || x0 > x1) return
        val lines = y1 - y0 + 1
        val k = if (n == 0) lines else n.coerceAtMost(lines)
        if (k < lines) {
            if (up) rectangles.copy(Rectangles.Area(y0 + k, x0, y1, x1), y0, x0)
            else rectangles.copy(Rectangles.Area(y0, x0, y1 - k, x1), y0 + k, x0)
        }
        val blank = if (up) Rectangles.Area(y1 - k + 1, x0, y1, x1) else Rectangles.Area(y0, x0, y0 + k - 1, x1)
        rectangles.erase(blank, selective = false)
    }

    /** AVT/0+'s area at the cursor, [rows] by [cols] (each counted on from the cursor's), filled with [cp] in attribute [attr], which stays */
    private fun area(attr: Int, cp: Int, rows: Int, cols: Int) {
        attribute(attr)
        val a = Rectangles.Area(
            t.cursorY, t.cursorX,
            (t.cursorY + rows).coerceAtMost(t.rows - 1), (t.cursorX + cols).coerceAtMost(t.cols - 1),
        )
        if (cp == ' '.code) rectangles.erase(a, selective = false) else rectangles.fill(a, cp)
    }

    /** RIS: no Avatar code under way */
    override fun reset() {
        super.reset()
        command = NONE
        args.clear()
    }

    companion object {
        private const val NONE = -1
        private const val LEAD = -2
        private const val REPEAT = -3
        /** each ^V command's argument count (^V ^Y's first: its length) */
        private val ARGS = mapOf(0x01 to 1, 0x08 to 2, 0x0a to 5, 0x0b to 5, 0x0c to 3, 0x0d to 4, 0x19 to 1)
        /** a CGA colour number (blue first) as ANSI's (red first) */
        private val PC_TO_ANSI = intArrayOf(0, 4, 2, 6, 1, 5, 3, 7)
    }
}
