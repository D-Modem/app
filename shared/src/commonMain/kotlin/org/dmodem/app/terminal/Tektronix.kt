// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * A Tektronix 4010/4014 storage tube as a terminal: a 4096x3120 screen
 * (the 4010's 1024x780 is its high ten bits) of vectors (GS: graph mode,
 * the first address a dark move), points (FS), incremental plots (RS:
 * a pen and eight directions) and alpha text (US) in four sizes, with
 * ESC FF to clear, the line styles, and the beam's place reported (ESC
 * ENQ). Drawn into a [Raster] over the screen. As a VT240's or xterm's
 * Tek mode (DECSET 38) [onExit] is given, and ESC ETX goes back.
 */
open class TektronixInterpreter(t: TerminalEmulator, private val onExit: (() -> Unit)? = null) : TerminalInterpreter(t) {
    private enum class Mode { ALPHA, GRAPH, POINT, INCREMENTAL }
    private var mode = Mode.ALPHA
    private val layer = PictureLayer(t, WIDTH, HEIGHT) { it.color = INK }
    private val raster get() = layer.raster

    // the beam, in Tek's units (0,0 bottom left)
    private var x = 0
    private var y = TOP
    /** the next address in graph mode is a dark move */
    private var dark = true
    /** the incremental plot's pen down */
    private var pen = false
    /** the character size, 0 (the largest) to 3 */
    private var size = 0
    private var escaped = false

    // an address being read: its high and low bytes, the 4014's extra byte
    private var hiX = 0
    private var hiY = 0
    private var loX = 0
    private var loY = 0
    private var extra = 0
    private var lastLoY = false

    init {
        home()
    }

    /** the beam to the first line's start, in alpha mode */
    private fun home() {
        mode = Mode.ALPHA
        x = 0
        y = TOP - CHAR_H[size]
    }

    /** a code point: the rest of an ESC sequence, a control, an address in the plotting modes, or text in alpha mode */
    override fun process(cp: Int) {
        val c = cp and 0x7f
        if (escaped) {
            escaped = false
            esc(c)
            return
        }
        when (c) {
            0x1b -> escaped = true
            0x1d -> { mode = Mode.GRAPH; dark = true }
            0x1c -> mode = Mode.POINT
            0x1e -> { mode = Mode.INCREMENTAL; pen = false }
            0x1f -> mode = Mode.ALPHA
            0x07 -> t.listener?.onBell()
            0x0d -> { mode = Mode.ALPHA; x = 0 }
            else -> when (mode) {
                Mode.ALPHA -> alpha(c)
                Mode.GRAPH, Mode.POINT -> if (c >= 0x20) address(c)
                Mode.INCREMENTAL -> incremental(c)
            }
        }
    }

    /** the code after ESC: clear (FF), the beam's place asked (ENQ), a character size, a line style, back to the VT (ETX) */
    private fun esc(c: Int) {
        when (c) {
            0x0c -> {
                raster.clear()
                home()
            }
            0x05 -> report()
            in '8'.code..';'.code -> size = c - '8'.code
            in 0x60..0x67 -> raster.pattern = STYLES[(c - 0x60) and 7]
            0x03 -> onExit?.invoke()
            else -> if (c < 0x20) process(c)
        }
    }

    /** ESC ENQ's answer: the status (alpha or graph mode), the beam's x and y as two bytes each (10-bit), CR */
    private fun report() {
        val sx = x shr 2
        val sy = y shr 2
        /** five bits as an address byte */
        fun b(v: Int, tag: Int) = (tag or (v and 0x1f)).toChar()
        val status = if (mode == Mode.ALPHA) 0x34 else 0x30
        t.reply("${status.toChar()}${b(sx shr 5, 0x20)}${b(sx, 0x20)}${b(sy shr 5, 0x20)}${b(sy, 0x20)}\r")
    }

    /** text at the beam (its bottom left), the beam on; BS, HT, LF, VT moving it a character */
    private fun alpha(c: Int) {
        val cw = CHAR_W[size]
        val ch = CHAR_H[size]
        when (c) {
            0x08 -> x = (x - cw).coerceAtLeast(0)
            0x09 -> x += cw
            0x0a -> { y -= ch; if (y < 0) y = TOP - ch }
            0x0b -> y = (y + ch).coerceAtMost(TOP - ch)
            in 0x20..0x7e -> {
                if (x + cw > WIDTH) { x = 0; y -= ch; if (y < 0) y = TOP - ch }
                if (c != 0x20) raster.text(x, row(y + ch), c.toChar().toString(), LegacyCharsets.VGA, cw, ch)
                x += cw
            }
        }
    }

    /** an address byte: high (x or y), low y (or the 4014's extra byte before it), low x (the address done) */
    private fun address(c: Int) {
        val v = c and 0x1f
        when (c shr 5) {
            1 -> { if (lastLoY) hiX = v else hiY = v; lastLoY = false }
            3 -> { if (lastLoY) extra = loY; loY = v; lastLoY = true }
            2 -> {
                loX = v
                lastLoY = false
                val nx = (hiX shl 7) or (loX shl 2) or (extra and 3)
                val ny = (hiY shl 7) or (loY shl 2) or ((extra shr 2) and 3)
                extra = 0
                to(nx, ny)
            }
        }
    }

    /** the beam to ([nx], [ny]): a point plotted, a vector drawn, or (graph mode's first) a dark move */
    private fun to(nx: Int, ny: Int) {
        when {
            mode == Mode.POINT -> raster.point(nx, row(ny))
            dark -> dark = false
            else -> raster.line(x, row(y), nx, row(ny))
        }
        x = nx
        y = ny
    }

    /** an incremental plot's code: the pen up or down, or a step in one of eight directions (drawing with the pen down) */
    private fun incremental(c: Int) {
        val (dx, dy) = when (c.toChar()) {
            ' ' -> { pen = false; return }
            'P' -> { pen = true; return }
            'D' -> 0 to 1
            'E' -> 1 to 1
            'A' -> 1 to 0
            'I' -> 1 to -1
            'H' -> 0 to -1
            'J' -> -1 to -1
            'B' -> -1 to 0
            'F' -> -1 to 1
            else -> return
        }
        val nx = (x + dx * STEP).coerceIn(0, WIDTH - 1)
        val ny = (y + dy * STEP).coerceIn(0, TOP - 1)
        if (pen) raster.line(x, row(y), nx, row(ny))
        x = nx
        y = ny
    }

    /** Tek's y (from the bottom) as the raster's row (from the top) */
    private fun row(ty: Int): Int = TOP - 1 - ty

    /** what was drawn put on the screen */
    override fun flush() {
        layer.flush()
    }

    /** RIS: the screen cleared, the beam home, the largest characters */
    override fun reset() {
        layer.reset()
        size = 0
        escaped = false
        home()
    }

    companion object {
        /** the 4014's addressable screen, in its units */
        const val WIDTH = 4096
        const val HEIGHT = 3120
        private const val TOP = HEIGHT
        /** an incremental plot's step (the 4010's one point) */
        private const val STEP = 4
        /** the characters' cell in each size (74 to 133 columns, 35 to 64 lines) */
        private val CHAR_W = intArrayOf(56, 51, 34, 31)
        private val CHAR_H = intArrayOf(88, 82, 53, 48)
        /** the line styles: solid, dotted, dot-dashed, short-dashed, long-dashed (and the 4014's others as solid) */
        private val STYLES = intArrayOf(0xff, 0xaa, 0xe4, 0xcc, 0xf0, 0xff, 0xff, 0xff)
        /** the phosphor's green-white */
        private const val INK = 0xffc8ffc8.toInt()
    }
}

/** A Tektronix 4014 on its own: the terminal is the storage tube */
open class Tek4014Profile : TerminalProfile() {
    override val id get() = "tek4014"
    override val label get() = "Tektronix 4014"
    override val term get() = "tek4014"
    override val telnetType get() = "TEK4014"

    /** a [TektronixInterpreter] that never leaves */
    override fun interpreter(t: TerminalEmulator): TerminalInterpreter = TektronixInterpreter(t)
}
