// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A graphics terminal's picture (ReGIS's, a Tektronix's): its own
 * coordinates ([logicalWidth] x [logicalHeight], 0,0 top left) drawn into
 * [width] x [height] pixels, the screen's, so it fills the text it lies
 * over. Lines (with a pattern), points, circles and arcs, filled
 * polygons and text from a [BitmapFont], each in a colour and a writing
 * mode; what's never drawn stays transparent (0).
 */
class Raster(val width: Int, val height: Int, private val logicalWidth: Int, private val logicalHeight: Int) {
    val pixels = IntArray(width * height)

    /** how a pixel is written: over what's there, replacing it, complementing it, or erasing it */
    enum class Mode { OVERLAY, REPLACE, COMPLEMENT, ERASE }

    var mode = Mode.OVERLAY
    /** the colour drawn in (ARGB) */
    var color = 0xffffffff.toInt()
    /** the background (ERASE writes it; REPLACE writes it where the pattern is off) */
    var background = 0
    /** the line pattern: 8 bits, the first pixel the high one (0xff solid) */
    var pattern = 0xff
    private var patternStep = 0
    /** changed since [take] */
    var dirty = false
        private set

    /** a logical x as a pixel column */
    fun px(x: Int): Int = (x.toLong() * width / logicalWidth).toInt()

    /** a logical y as a pixel row */
    fun py(y: Int): Int = (y.toLong() * height / logicalHeight).toInt()

    /** all of it [c] (0: transparent) */
    fun clear(c: Int = 0) {
        pixels.fill(c)
        dirty = true
    }

    /** pixel ([x], [y]) written as the mode says ([on]: the pattern's bit there) */
    private fun put(x: Int, y: Int, on: Boolean = true) {
        if (x !in 0 until width || y !in 0 until height) return
        val i = y * width + x
        when (mode) {
            Mode.OVERLAY -> if (on) pixels[i] = color
            Mode.REPLACE -> pixels[i] = if (on) color else background
            Mode.COMPLEMENT -> if (on) pixels[i] = pixels[i] xor 0x00ffffff or (0xff shl 24)
            Mode.ERASE -> pixels[i] = background
        }
        dirty = true
    }

    /** a point at logical ([x], [y]) */
    fun point(x: Int, y: Int) {
        put(px(x), py(y))
    }

    /** a line from logical ([x0], [y0]) to ([x1], [y1]), in the pattern */
    fun line(x0: Int, y0: Int, x1: Int, y1: Int) {
        pixelLine(px(x0), py(y0), px(x1), py(y1))
    }

    /** Bresenham's line between two pixels, the pattern stepping along it */
    private fun pixelLine(ax: Int, ay: Int, bx: Int, by: Int) {
        var x = ax
        var y = ay
        val dx = abs(bx - ax)
        val dy = -abs(by - ay)
        val sx = if (ax < bx) 1 else -1
        val sy = if (ay < by) 1 else -1
        var err = dx + dy
        while (true) {
            put(x, y, pattern and (0x80 ushr (patternStep and 7)) != 0)
            patternStep++
            if (x == bx && y == by) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** a circle round logical ([cx], [cy]) of radius [r] (in x's units) */
    fun circle(cx: Int, cy: Int, r: Int) {
        arc(cx, cy, r, 0.0, 360.0)
    }

    /** an arc round logical ([cx], [cy]), radius [r], from [start] degrees (0 east, counterclockwise) through [sweep] */
    fun arc(cx: Int, cy: Int, r: Int, start: Double, sweep: Double) {
        val steps = maxOf(16, (abs(sweep) / 360 * maxOf(8, px(abs(r)) * 2) * PI).toInt())
        var lx = 0
        var ly = 0
        for (i in 0..steps) {
            val a = (start + sweep * i / steps) * PI / 180
            val x = px(cx) + (px(r) * cos(a)).roundToInt()
            // (the same radius in pixels both ways: round on any screen)
            val y = py(cy) - (px(r) * sin(a)).roundToInt()
            if (i > 0) pixelLine(lx, ly, x, y)
            lx = x
            ly = y
        }
    }

    /** the polygon through logical [points] (x, y pairs) filled, by scan lines (even-odd) */
    fun fill(points: List<Pair<Int, Int>>) {
        if (points.size < 3) return
        val p = points.map { px(it.first) to py(it.second) }
        val top = p.minOf { it.second }.coerceAtLeast(0)
        val bottom = p.maxOf { it.second }.coerceAtMost(height - 1)
        val xs = ArrayList<Int>()
        for (y in top..bottom) {
            xs.clear()
            for (i in p.indices) {
                val (x0, y0) = p[i]
                val (x1, y1) = p[(i + 1) % p.size]
                if (y0 == y1) continue
                if (y >= minOf(y0, y1) && y < maxOf(y0, y1)) xs.add(x0 + (y - y0) * (x1 - x0) / (y1 - y0))
            }
            xs.sort()
            for (k in 0 until xs.size - 1 step 2) for (x in xs[k]..xs[k + 1]) put(x, y)
        }
    }

    /**
     * [text] with its top left at logical ([x], [y]), each character
     * [cw] x [ch] logical units drawn from [font] (its glyph scaled to the
     * cell); the logical x after it
     */
    fun text(x: Int, y: Int, text: String, font: BitmapFont, cw: Int, ch: Int): Int {
        var cx = x
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAtIndex(i)
            i += charCountOf(cp)
            glyph(cp, cx, y, font, cw, ch)
            cx += cw
        }
        return cx
    }

    /** [cp]'s glyph from [font] in the cell at logical ([x], [y]), [cw] x [ch] */
    private fun glyph(cp: Int, x: Int, y: Int, font: BitmapFont, cw: Int, ch: Int) {
        if (!font.has(cp)) return
        val left = px(x)
        val top = py(y)
        val w = maxOf(1, px(x + cw) - left)
        val h = maxOf(1, py(y + ch) - top)
        for (yy in 0 until h) {
            val row = font.row(cp, yy * font.height / h)
            if (row == 0) continue
            for (xx in 0 until w) {
                val col = xx * font.width / w
                if ((row shr (font.width - 1 - col)) and 1 != 0) put(left + xx, top + yy)
            }
        }
    }

    /** the picture as an image whose tiles are [cellWidth] x [cellHeight] (the screen's cells), not dirty after */
    fun take(cellWidth: Int, cellHeight: Int): TermImage {
        dirty = false
        return TermImage(width, height, pixels.copyOf(), cellWidth, cellHeight, picture = true)
    }
}

/**
 * A graphics terminal's picture over the screen's text (a Tektronix's,
 * ReGIS's): a [Raster] of [logicalWidth] x [logicalHeight] kept the
 * screen's size in pixels, [setUp] on each new one, and put on the screen
 * when it's been drawn on.
 */
class PictureLayer(
    private val t: TerminalEmulator,
    private val logicalWidth: Int,
    private val logicalHeight: Int,
    private val setUp: (Raster) -> Unit = {},
) {
    private var current: Raster = make()

    /** the raster to draw in: a new one first if the screen's size changed (what's drawn can't be redrawn: the picture starts again) */
    val raster: Raster
        get() {
            if (pixelWidth() != current.width || pixelHeight() != current.height) reset()
            return current
        }

    /** the screen's width in pixels */
    private fun pixelWidth() = maxOf(1, t.cols * t.cellPixelWidth)

    /** the screen's height in pixels */
    private fun pixelHeight() = maxOf(1, t.rows * t.cellPixelHeight)

    /** a raster the screen's size, blank */
    private fun make() = Raster(pixelWidth(), pixelHeight(), logicalWidth, logicalHeight).also(setUp)

    /** the picture started again, blank (what it showed taken off the screen at the next [flush]) */
    fun reset() {
        current = make().also { it.clear() }
    }

    /** what was drawn put on the screen */
    fun flush() {
        val r = raster
        if (r.dirty) t.showPicture(r.take(t.cellPixelWidth, t.cellPixelHeight))
    }
}
