// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * A videotex terminal from a phone company (Prestel's, the Minitel): 40
 * columns of text and 2x3 block mosaics in eight colours, its octets read
 * as they come. What they share: the palette, the mosaics drawn from the
 * app's bitmaps ([Mosaic]), a cursor that wraps round the screen rather
 * than scrolling it.
 */
abstract class VideotexProfile : TerminalProfile(), ByteMachine {
    override val columns get() = 40
    /** teletext's eight: black, red, green, yellow, blue, magenta, cyan, white */
    override val palette: IntArray get() = TELETEXT
    override val defaultColor get() = 7
    override val background get() = 0
    /** a capture as the frames' octets (.vdt, as viewdata and Minitel editors keep them) */
    override val captureExtension: String? get() = ".vdt"

    companion object {
        val TELETEXT = intArrayOf(
            0xff000000.toInt(), 0xffff0000.toInt(), 0xff00ff00.toInt(), 0xffffff00.toInt(),
            0xff0000ff.toInt(), 0xffff00ff.toInt(), 0xff00ffff.toInt(), 0xffffffff.toInt(),
        )
    }
}

/** What both videotex terminals' interpreters share: the mosaics' glyphs on the emulator, and the cursor's moves round the screen */
abstract class VideotexInterpreter(t: TerminalEmulator, m: VideotexProfile) : ByteControlInterpreter(t, m) {
    init {
        t.softGlyphs = Mosaic.font
    }

    /** the mosaics' glyphs gone with the profile */
    override fun detach() {
        super.detach()
        t.softGlyphs = null
    }

    /** as at the start, the mosaics' glyphs back on the emulator (RIS took them) */
    override fun reset() {
        super.reset()
        t.softGlyphs = Mosaic.font
    }

    /** the first row the cursor moves in (a Minitel's row 0 is its status row) */
    protected open val firstRow: Int get() = 0

    /** the cursor to ([x], [y]) */
    protected fun at(x: Int, y: Int) {
        t.moveCursor(x, y)
    }

    /** the cursor left (from a row's start the one above's end, from the first row's the last's) */
    protected fun back() {
        when {
            t.cursorX > 0 -> at(t.cursorX - 1, t.cursorY)
            else -> at(t.cols - 1, if (t.cursorY > firstRow) t.cursorY - 1 else t.rows - 1)
        }
    }

    /** the cursor right (from a row's end the next one's start) */
    protected fun forward() {
        if (t.cursorX < t.cols - 1) at(t.cursorX + 1, t.cursorY) else { at(0, t.cursorY); lineDown() }
    }

    /** the cursor down a row (from the last, the first: the screen doesn't scroll) */
    protected open fun lineDown() {
        at(t.cursorX, if (t.cursorY < t.rows - 1) t.cursorY + 1 else firstRow)
    }

    /** the cursor up a row (from the first, the last) */
    protected fun lineUp() {
        at(t.cursorX, if (t.cursorY > firstRow) t.cursorY - 1 else t.rows - 1)
    }
}

/**
 * Viewdata as Prestel had it (and the revival services, Telstar among
 * them): 40x24 of teletext Level 1. Its attributes are spacing
 * characters, each taking a cell and changing the rest of its row (alpha
 * or mosaic colours, flash, conceal, contiguous or separated mosaics,
 * hold, new and black background; double height drawn single), so the
 * screen is kept as the codes that came and each row drawn again from
 * them when one of its cells changes. # is the key that sends.
 */
open class ViewdataProfile : VideotexProfile() {
    override val id get() = "viewdata"
    override val label get() = "Viewdata (Prestel)"
    override val term get() = "viewdata"
    override val telnetType get() = "VIEWDATA"
    override val rows get() = 24

    override val keyBar: List<KeyBarPage> get() = listOf(KeyBars.VIEWDATA)

    /** a [ViewdataInterpreter] */
    override fun interpreter(t: TerminalEmulator): TerminalInterpreter = ViewdataInterpreter(t, this)
}

/** Prestel's controls and teletext's attributes over a screen of codes */
open class ViewdataInterpreter(t: TerminalEmulator, private val m: ViewdataProfile) : VideotexInterpreter(t, m) {
    /** the codes on the screen (0x00-0x1f the attributes, else characters) */
    private var cells = Array(m.rows) { IntArray(m.columns) { ' '.code } }
    /** an ESC came: the next code is an attribute */
    private var escaped = false

    /** as at the start: the screen's codes blank */
    override fun reset() {
        super.reset()
        cells = Array(m.rows) { IntArray(m.columns) { ' '.code } }
        escaped = false
    }

    /** an octet, without its parity bit: after ESC an attribute, else a control or a character */
    override fun process(cp: Int) {
        val b = cp and 0x7f
        if (escaped) {
            escaped = false
            if (b in 0x40..0x5f) store(b - 0x40)
            return
        }
        if (!control(b) && b >= 0x20) store(b)
    }

    /** Prestel's controls: BS, HT, LF, VT, FF (clear), CR, RS (home), cursor on and off, ENQ, ESC */
    override fun control(b: Int): Boolean {
        when (b) {
            0x08 -> back()
            0x09 -> forward()
            0x0a -> lineDown()
            0x0b -> lineUp()
            0x0c -> {
                for (r in cells) r.fill(' '.code)
                for (y in 0 until minOf(t.rows, cells.size)) draw(y)
                home()
            }
            0x0d -> at(0, t.cursorY)
            0x1e -> home()
            0x11 -> t.cursorVisible = true
            0x14 -> t.cursorVisible = false
            0x1b -> escaped = true
            else -> return b < 0x20
        }
        return true
    }

    /** (characters are [store]d, not drawn as they come) */
    override fun charOf(b: Int): Int = b

    /** code [c] put at the cursor, its row drawn again, the cursor on */
    private fun store(c: Int) {
        val y = t.cursorY
        val x = t.cursorX
        if (y in cells.indices && x in cells[y].indices) {
            cells[y][x] = c
            draw(y)
        }
        forward()
    }

    /** row [y] drawn from its codes, as a teletext decoder does: each attribute applied at or after its cell */
    private fun draw(y: Int) {
        if (y >= t.rows) return
        val line = t.line(y)
        var fg = 7
        var bg = 0
        var mosaic = false
        var separated = false
        var hold = false
        var held = ' '.code
        var flash = false
        var conceal = false
        for (x in 0 until minOf(t.cols, cells[y].size)) {
            val c = cells[y][x]
            var shown: Int
            if (c < 0x20) {
                // set-at
                when (c) {
                    0x09 -> flash = false
                    0x0c -> held = ' '.code
                    0x18 -> conceal = true
                    0x19 -> separated = false
                    0x1a -> separated = true
                    0x1c -> bg = 0
                    0x1d -> bg = fg
                    0x1e -> hold = true
                }
                shown = if (hold && mosaic) held else ' '.code
                line.set(x, shown, look(fg, bg, flash, conceal))
                // set-after
                when (c) {
                    in 0x01..0x07 -> { fg = c; if (mosaic) held = ' '.code; mosaic = false; conceal = false }
                    in 0x11..0x17 -> { fg = c - 0x10; mosaic = true; conceal = false }
                    0x08 -> flash = true
                    0x1f -> hold = false
                }
                continue
            }
            shown = if (mosaic && (c < 0x40 || c >= 0x60)) {
                Mosaic.code(Mosaic.bits(c), separated).also { held = it }
            } else {
                G0.getOrNull(c - 0x20) ?: c
            }
            line.set(x, shown, look(fg, bg, flash, conceal))
        }
    }

    /** a cell's look: colours [fg] and [bg], flashing, concealed */
    private fun look(fg: Int, bg: Int, flash: Boolean, conceal: Boolean): Long =
        Style.of(fg, bg, (if (flash) Style.BLINK else 0) or (if (conceal) Style.INVISIBLE else 0))

    /** Enter and # send #, Prestel's 0x5f; Backspace BS */
    override fun keyOctets(k: Key, shift: Boolean, alt: Boolean, ctrl: Boolean): ByteArray? = when (k) {
        Key.ENTER -> byteArrayOf(0x5f)
        Key.BACKSPACE -> byteArrayOf(0x08)
        else -> null
    }

    /** what's typed as Prestel's codes: # as 0x5f, £ as 0x23 */
    override fun charOctets(cp: Int, alt: Boolean, ctrl: Boolean): ByteArray? = when (cp) {
        '#'.code -> byteArrayOf(0x5f)
        '£'.code -> byteArrayOf(0x23)
        else -> if (cp in 0x20..0x7e) byteArrayOf(cp.toByte()) else null
    }

    companion object {
        /** teletext's English set, 0x20-0x7f: £ for #, arrows, fractions, # for _, a dash, ‖, ÷ and a block */
        private val G0 = IntArray(0x60) { 0x20 + it }.also {
            for ((c, u) in listOf(0x23 to '£', 0x5b to '←', 0x5c to '½', 0x5d to '→', 0x5e to '↑', 0x5f to '#',
                0x60 to '—', 0x7b to '¼', 0x7c to '‖', 0x7d to '¾', 0x7e to '÷', 0x7f to '■')) it[c - 0x20] = u.code
        }
    }
}
