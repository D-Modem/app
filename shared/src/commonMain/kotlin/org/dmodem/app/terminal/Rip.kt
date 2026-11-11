// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.math.roundToInt

/**
 * RIPscrip 1.54, TeleGrafix's graphics for BBSes: ANSI-BBS whose lines
 * that start with !| are drawing commands on a 640x350 EGA screen
 * (lines, rectangles, circles, ellipses, arcs, pies, Béziers, polygons,
 * flood and patterned fills, text, the clipboard) and mouse regions and
 * buttons that a tap sends the host. Ordinary text and ANSI go to the
 * text window, 80x43 of 8x8 cells.
 */
open class RipProfile : AnsiBbsProfile() {
    override val id get() = "rip"
    override val label get() = "RIPscrip"
    override val term get() = "rip"
    // (BBSes find ANSI by it, then ask for RIP with ESC [ !)
    override val telnetType get() = "ANSI"

    /** a [RipInterpreter] */
    override fun interpreter(t: TerminalEmulator): TerminalInterpreter = RipInterpreter(t, this)
}

/** RIPscrip's commands taken from the lines that start with !| (or ^A|, ^B|), the rest ANSI-BBS's */
open class RipInterpreter(t: TerminalEmulator, profile: RipProfile) : AnsiBbsInterpreter(t, profile) {
    private val layer = PictureLayer(t, WIDTH, HEIGHT)
    private val raster get() = layer.raster

    // reading: a line's start, a RIP line being gathered, an ESC [ … ! being held
    private var lineStart = true
    private var bang = false
    private var line: StringBuilder? = null
    private var escaped = false
    /** a CR ended a RIP line (or continued one): the LF after it is the line's too */
    private var swallowLf = false
    private val held = StringBuilder()
    /** the characters being given back to ANSI-BBS's parser (not taken again) */
    private var replaying = false
    /** RIP on (ESC [ 2 !) or off (ESC [ 1 !) */
    private var enabled = true

    // the drawing state
    private var color = 15
    private var x = 0
    private var y = 0
    private var xor = false
    private var linePattern = 0xff
    private var thick = 1
    private var fillPattern = 1
    private var userFill = IntArray(8) { 0xff }
    private var fillColor = 15
    private var font = 0
    private var fontSize = 1
    private var vertical = false
    private val palette = EGA.copyOf()
    private var viewport: IntArray? = null
    private var clipboard: Raster.Grab? = null

    /** a mouse region or button: where it is and what a tap on it sends */
    private class Region(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val send: String)
    private val regions = ArrayList<Region>()

    /** the buttons' look (1B): their colours and bevel */
    private class ButtonStyle(val bevel: Int, val fore: Int, val surface: Int, val bright: Int, val dark: Int)
    private var buttonStyle = ButtonStyle(2, 0, 7, 15, 8)

    /** a text block (1T) under way: its corners and the next line's top */
    private var block: IntArray? = null

    init {
        textScreen()
    }

    /** the text window's 80x43 of 8x8 cells */
    private fun textScreen() {
        t.requestedColumns = 80
        t.requestedRows = 43
    }

    /** a code point: a RIP line's, an ESC [ … ! query's, else ANSI-BBS's */
    override fun intercept(cp: Int): Boolean {
        if (replaying) return super.intercept(cp)
        if (swallowLf) {
            swallowLf = false
            if (cp == 0x0a) return true
        }
        val l = line
        if (l != null) {
            gather(l, cp)
            return true
        }
        if (held.isNotEmpty() || cp == 0x1b) return query(cp)
        if (bang) {
            bang = false
            if (cp == '|'.code) {
                line = StringBuilder()
                return true
            }
            giveBack("!")
        }
        if (enabled && lineStart && (cp == '!'.code || cp == 0x01 || cp == 0x02)) {
            bang = true
            return true
        }
        lineStart = cp == 0x0d || cp == 0x0a
        return super.intercept(cp)
    }

    /** [s] given to ANSI-BBS's parser after all */
    private fun giveBack(s: String) {
        replaying = true
        for (c in s) process(c.code)
        replaying = false
        lineStart = false
    }

    /** a RIP line's character: kept (a backslash's escape or continuation honoured) until the line ends, then run */
    private fun gather(l: StringBuilder, cp: Int) {
        if (escaped) {
            escaped = false
            when (cp) {
                0x0d -> swallowLf = true
                0x0a -> {}
                else -> { l.append('\\'); l.appendCp(cp) }
            }
            return
        }
        when (cp) {
            '\\'.code -> escaped = true
            0x0d, 0x0a -> {
                line = null
                lineStart = true
                swallowLf = cp == 0x0d
                runLine(l.toString())
            }
            else -> if (l.length < MAX_LINE) l.appendCp(cp)
        }
    }

    /** ESC [ !, ESC [ 0 !, 1 !, 2 !: held until it is one (answered: RIP's version; off; on), else given back */
    private fun query(cp: Int): Boolean {
        held.appendCp(cp)
        val h = held.toString()
        val full = h.length >= 3 && h.endsWith("!") && h.startsWith("\u001b[") && h.substring(2, h.length - 1).all { it in '0'..'2' }
        if (full) {
            held.setLength(0)
            when (h.substring(2, h.length - 1)) {
                "", "0" -> t.reply("RIPSCRIP015400")
                "1" -> enabled = false
                "2" -> enabled = true
            }
            return true
        }
        val partial = h == "\u001b" || h == "\u001b[" || (h.length == 3 && h[2] in '0'..'2')
        if (partial) return true
        held.setLength(0)
        giveBack(h)
        return true
    }

    /** a RIP line's commands, split at the |s that aren't escaped, each run */
    private fun runLine(s: String) {
        val cmd = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                cmd.append(s[i + 1])
                i += 2
                continue
            }
            if (c == '|') {
                run(cmd.toString())
                cmd.setLength(0)
            } else {
                cmd.append(c)
            }
            i++
        }
        run(cmd.toString())
    }

    /** one command: its level (a digit) and letter, then its MegaNums and text */
    private fun run(cmd: String) {
        if (cmd.isEmpty()) return
        val level = if (cmd[0] in '1'..'9') cmd[0] - '0' else 0
        val at = if (level > 0) 1 else 0
        if (at >= cmd.length) return
        val a = Args(cmd, at + 1)
        if (level == 1) level1(cmd[at], a) else if (level == 0) level0(cmd[at], a)
    }

    /** the level 0 commands: windows, colours, drawing, text, styles */
    private fun level0(c: Char, a: Args) {
        when (c) {
            'w' -> textWindow(a.n(), a.n(), a.n(), a.n())
            'v' -> {
                val x0 = a.n(); val y0 = a.n(); val x1 = a.n(); val y1 = a.n()
                viewport = intArrayOf(x0, y0, x1, y1)
            }
            '*' -> resetWindows()
            'e' -> {
                t.eraseDisplay(2)
                t.moveCursor(0, t.top)
            }
            'E' -> viewport.let { v -> if (v == null) raster.clearRect(0, 0, WIDTH - 1, HEIGHT - 1) else raster.clearRect(v[0], v[1], v[2], v[3]) }
            'g' -> { val gx = a.n(); val gy = a.n(); t.moveCursor(gx, t.top + gy) }
            'H' -> t.moveCursor(0, t.top)
            '>' -> t.eraseLine(0)
            'c' -> color = a.n() and 15
            'Q' -> for (i in 0 until 16) palette[i] = ega(a.n())
            'a' -> { val i = a.n(); val v = a.n(); if (i in 0..15) palette[i] = ega(v) }
            'W' -> xor = a.n() == 1
            'm' -> { x = a.n(); y = a.n() }
            'T' -> text(a.rest())
            '@' -> { x = a.n(); y = a.n(); text(a.rest()) }
            'Y' -> { font = a.n(); vertical = a.n() == 1; fontSize = a.n().coerceIn(1, 10) }
            'X' -> { setUp(); raster.point(a.n(), a.n()) }
            'L' -> { val x0 = a.n(); val y0 = a.n(); line(x0, y0, a.n(), a.n()) }
            'R' -> {
                val x0 = a.n(); val y0 = a.n(); val x1 = a.n(); val y1 = a.n()
                polyline(listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1, x0 to y0))
            }
            'B' -> {
                val x0 = a.n(); val y0 = a.n(); val x1 = a.n(); val y1 = a.n()
                fill(listOf(x0 to y0, x1 + 1 to y0, x1 + 1 to y1 + 1, x0 to y1 + 1))
            }
            'C' -> { val cx = a.n(); val cy = a.n(); val r = a.n(); setUp(); raster.ellipse(cx, cy, r, aspect(r), 0.0, 360.0) }
            'A' -> { val cx = a.n(); val cy = a.n(); val s = a.n(); val e = a.n(); val r = a.n(); arc(cx, cy, s, e, r, aspect(r)) }
            'O', 'V' -> { val cx = a.n(); val cy = a.n(); val s = a.n(); val e = a.n(); arc(cx, cy, s, e, a.n(), a.n()) }
            'o' -> {
                val cx = a.n(); val cy = a.n(); val rx = a.n(); val ry = a.n()
                fill(raster.ellipsePoints(cx, cy, rx, ry, 0.0, 360.0))
                setUp()
                raster.ellipse(cx, cy, rx, ry, 0.0, 360.0)
            }
            'I' -> { val cx = a.n(); val cy = a.n(); val s = a.n(); val e = a.n(); val r = a.n(); pie(cx, cy, s, e, r, aspect(r)) }
            'i' -> { val cx = a.n(); val cy = a.n(); val s = a.n(); val e = a.n(); pie(cx, cy, s, e, a.n(), a.n()) }
            'Z' -> {
                val p = List(4) { a.n() to a.n() }
                bezier(p, a.n().coerceIn(1, 256))
            }
            'P', 'p', 'l' -> {
                val n = a.n()
                val pts = List(n) { a.n() to a.n() }
                if (pts.size < 2) return
                if (c == 'p') fill(pts)
                polyline(if (c == 'l') pts else pts + pts[0])
            }
            'F' -> { val fx = a.n(); val fy = a.n(); val border = a.n(); setUpFill(); raster.floodFill(fx, fy, argb(border)) }
            '=' -> {
                val style = a.n()
                val user = a.n(4)
                thick = if (a.n() >= 3) 3 else 1
                linePattern = when (style) {
                    1 -> 0xcc
                    2 -> 0xe4
                    3 -> 0xf0
                    4 -> (user shr 8) and 0xff
                    else -> 0xff
                }
            }
            'S' -> { fillPattern = a.n(); fillColor = a.n() and 15 }
            's' -> { userFill = IntArray(8) { a.n() and 0xff }; fillColor = a.n() and 15; fillPattern = 12 }
        }
    }

    /** the level 1 commands: mouse regions, buttons, text blocks, the clipboard, file queries */
    private fun level1(c: Char, a: Args) {
        when (c) {
            'M' -> {
                a.n()
                val x0 = a.n(); val y0 = a.n(); val x1 = a.n(); val y1 = a.n()
                a.n(); a.n(1); a.n(5)
                regions.add(Region(x0, y0, x1, y1, a.rest()))
            }
            'K' -> regions.clear()
            'B' -> {
                a.n(); a.n(); a.n(); a.n(4)
                val bevel = a.n(); val fore = a.n(); a.n()
                val bright = a.n(); val dark = a.n(); val surface = a.n()
                buttonStyle = ButtonStyle(bevel, fore and 15, surface and 15, bright and 15, dark and 15)
            }
            'U' -> {
                val x0 = a.n(); val y0 = a.n(); val x1 = a.n(); val y1 = a.n()
                a.n(); a.n(1); a.n(1)
                button(x0, y0, x1, y1, a.rest())
            }
            'T' -> { val x0 = a.n(); val y0 = a.n(); val x1 = a.n(); block = intArrayOf(x0, y0, x1, a.n(), y0) }
            't' -> block?.let { b ->
                a.n(1)
                x = b[0]
                y = b[4]
                text(a.rest())
                b[4] += cellHeight()
            }
            'E' -> block = null
            'C' -> { val x0 = a.n(); val y0 = a.n(); clipboard = raster.grab(x0, y0, a.n(), a.n()) }
            'P' -> clipboard?.let { g -> val px = a.n(); val py = a.n(); val mode = a.n(); raster.put(g, px, py, xor = mode == 1) }
            'G' -> {
                val x0 = a.n(); val y0 = a.n(); val x1 = a.n(); val y1 = a.n()
                a.n()
                val dest = a.n()
                raster.put(raster.grab(x0, y0, x1, y1), x0, dest)
            }
            // a file query: none of the host's icons are here
            'F' -> t.reply("0\r\n")
        }
    }

    /** RIP's text window: rows [y0] to [y1] (of 43) scroll, the cursor at its top */
    private fun textWindow(x0: Int, y0: Int, x1: Int, y1: Int) {
        if (x0 == 0 && y0 == 0 && x1 == 0 && y1 == 0) return
        val top = y0.coerceIn(0, t.rows - 1)
        val bottom = y1.coerceIn(top, t.rows - 1)
        t.setRegion(top, bottom)
        t.moveCursor(x0.coerceIn(0, t.cols - 1), top)
    }

    /** RIP_RESET_WINDOWS: the picture, the text, the windows, the palette, the regions as at the start */
    private fun resetWindows() {
        layer.reset()
        t.setRegion(0, t.rows - 1)
        t.eraseDisplay(2)
        t.moveCursor(0, 0)
        EGA.copyInto(palette)
        viewport = null
        regions.clear()
        clipboard = null
        color = 15
        x = 0
        y = 0
    }

    /** colour register [c] as drawn: its colour, black transparent (the text shows through) */
    private fun argb(c: Int): Int {
        val v = palette[c and 15]
        return if (v and 0xffffff == 0) 0 else v
    }

    /** the raster set to draw lines: the colour, xor, the line style, the viewport */
    private fun setUp() {
        val r = raster
        r.mode = if (xor) Raster.Mode.COMPLEMENT else Raster.Mode.OVERLAY
        r.color = argb(color)
        r.pattern = linePattern
        r.fillPattern = null
        r.clip = viewport?.let { r.pixelRect(it[0], it[1], it[2], it[3]) }
        if (r.color == 0) r.mode = Raster.Mode.ERASE
        r.background = 0
    }

    /** the raster set to fill: the fill colour and pattern (BGI's twelve, or the user's) */
    private fun setUpFill() {
        setUp()
        val r = raster
        r.color = argb(fillColor)
        r.mode = if (r.color == 0) Raster.Mode.ERASE else Raster.Mode.OVERLAY
        r.fillPattern = when (fillPattern) {
            0 -> IntArray(8)
            1 -> null
            12 -> userFill
            else -> FILLS.getOrNull(fillPattern - 2)
        }
        r.fillBackground = 0
    }

    /** a line in the line style, three pixels thick if it's thick */
    private fun line(x0: Int, y0: Int, x1: Int, y1: Int) {
        setUp()
        raster.line(x0, y0, x1, y1)
        if (thick == 3) {
            raster.line(x0 + 1, y0, x1 + 1, y1)
            raster.line(x0, y0 + 1, x1, y1 + 1)
        }
    }

    /** lines through [pts] */
    private fun polyline(pts: List<Pair<Int, Int>>) {
        for (i in 1 until pts.size) line(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
    }

    /** the polygon through [pts] filled in the fill style */
    private fun fill(pts: List<Pair<Int, Int>>) {
        setUpFill()
        raster.fill(pts)
    }

    /** an elliptical arc from [s] to [e] degrees */
    private fun arc(cx: Int, cy: Int, s: Int, e: Int, rx: Int, ry: Int) {
        setUp()
        raster.ellipse(cx, cy, rx, ry, s.toDouble(), sweep(s, e))
    }

    /** a pie slice from [s] to [e] degrees, filled and outlined */
    private fun pie(cx: Int, cy: Int, s: Int, e: Int, rx: Int, ry: Int) {
        val pts = listOf(cx to cy) + raster.ellipsePoints(cx, cy, rx, ry, s.toDouble(), sweep(s, e)) + (cx to cy)
        fill(pts)
        polyline(pts)
    }

    /** the degrees counterclockwise from [s] to [e] */
    private fun sweep(s: Int, e: Int): Double = (((e - s) % 360 + 360) % 360).let { if (it == 0) 360 else it }.toDouble()

    /** a circle's vertical radius: radius [r] as the EGA's tall pixels have it */
    private fun aspect(r: Int): Int = (r * ASPECT).roundToInt()

    /** a cubic Bézier through control points [p] in [n] lines */
    private fun bezier(p: List<Pair<Int, Int>>, n: Int) {
        val pts = (0..n).map { i ->
            val u = i.toDouble() / n
            val v = 1 - u
            /** one coordinate at u */
            fun c(a: Int, b: Int, cc: Int, d: Int) = (v * v * v * a + 3 * v * v * u * b + 3 * v * u * u * cc + u * u * u * d).roundToInt()
            c(p[0].first, p[1].first, p[2].first, p[3].first) to c(p[0].second, p[1].second, p[2].second, p[3].second)
        }
        polyline(pts)
    }

    /** a character cell's height for the font and size */
    private fun cellHeight(): Int = if (font == 0) 8 * fontSize else 10 * fontSize

    /** [s] drawn from the position in the font (the PC's 8x8 cells, or bigger for the stroke fonts' sizes), the position after it */
    private fun text(s: String) {
        setUp()
        raster.mode = if (xor) Raster.Mode.COMPLEMENT else Raster.Mode.OVERLAY
        val cw = if (font == 0) 8 * fontSize else 6 * fontSize
        val ch = cellHeight()
        for (c in s) {
            raster.text(x, y, c.toString(), LegacyCharsets.VGA, cw, ch)
            if (vertical) y += ch else x += cw
        }
    }

    /** a button (1U): its bevel and surface in the buttons' style, its label, and a region sending its host text ("icon<>label<>host") */
    private fun button(x0: Int, y0: Int, x1: Int, y1: Int, spec: String) {
        val parts = spec.split("<>")
        val label = parts.getOrElse(1) { "" }
        val host = parts.getOrElse(2) { "" }
        val st = buttonStyle
        val saved = fillColor to fillPattern
        fillColor = st.surface
        fillPattern = 1
        fill(listOf(x0 to y0, x1 + 1 to y0, x1 + 1 to y1 + 1, x0 to y1 + 1))
        fillColor = saved.first
        fillPattern = saved.second
        val savedColor = color
        for (b in 0 until st.bevel) {
            color = st.bright
            polyline(listOf(x0 - b - 1 to y1 + b + 1, x0 - b - 1 to y0 - b - 1, x1 + b + 1 to y0 - b - 1))
            color = st.dark
            polyline(listOf(x1 + b + 1 to y0 - b - 1, x1 + b + 1 to y1 + b + 1, x0 - b - 1 to y1 + b + 1))
        }
        color = st.fore
        val savedFont = Triple(font, fontSize, vertical)
        font = 0
        fontSize = 1
        vertical = false
        x = (x0 + x1) / 2 - label.length * 4
        y = (y0 + y1) / 2 - 4
        text(label)
        font = savedFont.first
        fontSize = savedFont.second
        vertical = savedFont.third
        color = savedColor
        if (host.isNotEmpty()) regions.add(Region(x0, y0, x1, y1, host))
    }

    /** a tap on a region or button (the last defined first): its host text, ^ and a letter a control */
    override fun tap(fx: Double, fy: Double): ByteArray? {
        val px = (fx * WIDTH).toInt()
        val py = (fy * HEIGHT).toInt()
        val r = regions.lastOrNull { px in it.x0..it.x1 && py in it.y0..it.y1 } ?: return null
        val out = ArrayList<Byte>()
        var i = 0
        val s = r.send
        while (i < s.length) {
            val c = s[i]
            if (c == '^' && i + 1 < s.length) {
                out.add((s[i + 1].uppercaseChar().code and 0x1f).toByte())
                i += 2
                continue
            }
            out.add(Cp437.encode(c.code).toByte())
            i++
        }
        return out.toByteArray()
    }

    /** the picture put on the screen, after ANSI-BBS's */
    override fun flush() {
        super.flush()
        layer.flush()
    }

    /** RIS: the picture, windows and regions as at the start, RIP on, the 80x43 asked for again */
    override fun reset() {
        super.reset()
        line = null
        bang = false
        escaped = false
        swallowLf = false
        held.setLength(0)
        enabled = true
        lineStart = true
        resetWindows()
        textScreen()
    }

    /** a command's arguments: MegaNums (base 36) of a given width, then the text that's left */
    private class Args(val s: String, var i: Int) {
        /** the next MegaNum, [width] digits (0 for what isn't there) */
        fun n(width: Int = 2): Int {
            var v = 0
            repeat(width) {
                val c = if (i < s.length) s[i] else '0'
                i++
                v = v * 36 + when (c) {
                    in '0'..'9' -> c - '0'
                    in 'A'..'Z' -> c - 'A' + 10
                    in 'a'..'z' -> c - 'a' + 10
                    else -> 0
                }
            }
            return v
        }

        /** the rest of the command, its text */
        fun rest(): String = if (i < s.length) s.substring(i) else ""
    }

    companion object {
        /** RIP's screen: the EGA's 640x350 */
        const val WIDTH = 640
        const val HEIGHT = 350
        /** an EGA pixel's height for its width, as BGI's circles allow for */
        private const val ASPECT = 0.775
        /** a RIP line's characters at most */
        private const val MAX_LINE = 1 shl 16

        /** an EGA colour (0-63: rgbRGB, the lower case the 1/3 bits) as ARGB */
        fun ega(v: Int): Int {
            /** one channel from its 2/3 bit [hi] and 1/3 bit [lo] */
            fun ch(hi: Int, lo: Int) = (if (v shr hi and 1 != 0) 0xaa else 0) + (if (v shr lo and 1 != 0) 0x55 else 0)
            return (0xff shl 24) or (ch(2, 5) shl 16) or (ch(1, 4) shl 8) or ch(0, 3)
        }

        /** the EGA's sixteen at the start */
        private val EGA = intArrayOf(0, 1, 2, 3, 4, 5, 20, 7, 56, 57, 58, 59, 60, 61, 62, 63).map(::ega).toIntArray()

        /** BGI's fill patterns 2-11: lines, light slashes, slashes, backslashes, light backslashes, hatch, cross-hatch, interleave, wide dots, close dots */
        private val FILLS = listOf(
            intArrayOf(0xff, 0xff, 0, 0, 0xff, 0xff, 0, 0),
            intArrayOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80),
            intArrayOf(0xe0, 0xc1, 0x83, 0x07, 0x0e, 0x1c, 0x38, 0x70),
            intArrayOf(0xf0, 0x78, 0x3c, 0x1e, 0x0f, 0x87, 0xc3, 0xe1),
            intArrayOf(0xa5, 0xd2, 0x69, 0xb4, 0x5a, 0x2d, 0x96, 0x4b),
            intArrayOf(0xff, 0x88, 0x88, 0x88, 0xff, 0x88, 0x88, 0x88),
            intArrayOf(0x81, 0x42, 0x24, 0x18, 0x18, 0x24, 0x42, 0x81),
            intArrayOf(0xcc, 0x33, 0xcc, 0x33, 0xcc, 0x33, 0xcc, 0x33),
            intArrayOf(0x80, 0x00, 0x08, 0x00, 0x80, 0x00, 0x08, 0x00),
            intArrayOf(0x88, 0x00, 0x22, 0x00, 0x88, 0x00, 0x22, 0x00),
        )
    }
}
