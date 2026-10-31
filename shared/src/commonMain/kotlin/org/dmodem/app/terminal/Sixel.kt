// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.math.roundToInt

/**
 * A sixel image's data (DCS P1;P2;P3 q … ST), read as it comes: colour
 * registers (#Pc;Pu;Px;Py;Pz: HLS or RGB, in percent), repeats (!Pn),
 * graphics carriage return ($) and new line (-), raster attributes
 * ("Pan;Pad;Ph;Pv), and the sixels themselves ('?'..'~': six pixels in a
 * column). The background is register 0's colour (as the image leaves
 * it), or transparent when P2 is 1. At most [maxWidth] x [maxHeight] pixels: what falls outside is
 * dropped.
 */
class Sixel(
    /** P2: 1 when the pixels not drawn stay transparent */
    private val transparent: Boolean,
    /** the terminal's palette, shared by the images that don't set their own (xterm's default); null: each its own */
    private val sharedPalette: IntArray? = null,
    private val maxWidth: Int = 4096,
    private val maxHeight: Int = 4096,
) {
    private val palette: IntArray = sharedPalette ?: defaultPalette()
    private var color = 0
    private var x = 0
    private var y = 0 // the top of the current sixel band
    private var repeat = 1

    private var buf = IntArray(0)
    private var bufW = 0
    private var bufH = 0
    /** the image's size so far: the furthest pixel drawn, or the raster attributes' */
    private var width = 0
    private var height = 0

    // the parameters of the command being read
    private var command = 0
    private val params = IntArray(5)
    private var nParams = 0

    /** one code point of the image's data: a command's parameters gathered, else a sixel, a command begun, a carriage return or new line */
    fun feed(cp: Int) {
        if (command != 0) {
            when (cp) {
                in '0'.code..'9'.code -> {
                    if (nParams == 0) nParams = 1
                    if (nParams <= params.size) params[nParams - 1] = (params[nParams - 1] * 10 + (cp - '0'.code)).coerceAtMost(99999)
                    return
                }
                ';'.code -> {
                    if (nParams == 0) nParams = 1
                    if (nParams < params.size) { params[nParams] = 0; nParams++ }
                    return
                }
                else -> endCommand()
            }
        }
        when (cp) {
            in 0x3f..0x7e -> sixel(cp - 0x3f)
            '#'.code, '!'.code, '"'.code -> startCommand(cp)
            '$'.code -> x = 0
            '-'.code -> { x = 0; y += 6 }
            else -> {} // spaces, line breaks: nothing
        }
    }

    /** command [c] (#, ! or ") begun, its parameters cleared */
    private fun startCommand(c: Int) {
        command = c
        nParams = 0
        params.fill(0)
    }

    /** the command read applied: a repeat count, a colour register set or chosen, or the raster attributes' size */
    private fun endCommand() {
        when (command.toChar()) {
            '!' -> repeat = params[0].coerceAtLeast(1)
            '#' -> {
                val n = params[0] and 0xff
                if (nParams >= 5) {
                    palette[n] = if (params[1] == 1) hls(params[2], params[3], params[4])
                    else rgb(pct(params[2]), pct(params[3]), pct(params[4]))
                }
                color = n
            }
            '"' -> {
                // the image's size, ahead of its pixels (the background filled to it)
                if (nParams >= 4 && params[2] > 0 && params[3] > 0) {
                    val w = params[2].coerceAtMost(maxWidth)
                    val h = params[3].coerceAtMost(maxHeight)
                    grow(w, h)
                    width = maxOf(width, w)
                    height = maxOf(height, h)
                }
            }
        }
        command = 0
    }

    /** a sixel's six pixels drawn in the colour at x (repeated as !Pn said), x past them */
    private fun sixel(bits: Int) {
        val n = repeat
        repeat = 1
        if (bits != 0 && y < maxHeight) {
            val right = (x + n).coerceAtMost(maxWidth)
            if (right > x) {
                grow(right, y + 6)
                val c = palette[color]
                for (b in 0 until 6) {
                    if (bits and (1 shl b) == 0) continue
                    val row = y + b
                    if (row >= maxHeight) break
                    val base = row * bufW
                    for (px in x until right) buf[base + px] = c
                    if (row + 1 > height) height = row + 1
                }
                if (right > width) width = right
            }
        }
        x += n
    }

    /** the buffer made at least [w] x [h] (doubling, so that growing is cheap) */
    private fun grow(w: Int, h: Int) {
        if (w <= bufW && h <= bufH) return
        val nw = maxOf(bufW, minOf(maxWidth, maxOf(w, bufW * 2, 64)))
        val nh = maxOf(bufH, minOf(maxHeight + 6, maxOf(h, bufH * 2, 64)))
        // (0: not drawn; the background's colour put in when the image is finished)
        val nb = IntArray(nw * nh)
        for (r in 0 until bufH) buf.copyInto(nb, r * nw, r * bufW, r * bufW + bufW)
        buf = nb
        bufW = nw
        bufH = nh
    }

    /** the image (null when nothing was drawn) */
    fun finish(cellWidth: Int, cellHeight: Int): TermImage? {
        if (command != 0) endCommand()
        val h = height.coerceAtMost(maxHeight)
        if (width <= 0 || h <= 0) return null
        val px = IntArray(width * h)
        for (r in 0 until h) buf.copyInto(px, r * width, r * bufW, r * bufW + width)
        if (!transparent) {
            // the background: register 0 as the image left it
            val bg = palette[0]
            for (i in px.indices) if (px[i] == 0) px[i] = bg
        }
        return TermImage(width, h, px, cellWidth, cellHeight)
    }

    companion object {
        /** a percentage as 0-255 */
        private fun pct(v: Int) = (v.coerceIn(0, 100) * 255 + 50) / 100

        /** opaque ARGB of [r], [g], [b] */
        private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

        /** DEC's HLS: hue in degrees with blue at 0 (red at 120), lightness and saturation in percent */
        fun hls(hDeg: Int, lPct: Int, sPct: Int): Int {
            val l = lPct.coerceIn(0, 100) / 100.0
            val s = sPct.coerceIn(0, 100) / 100.0
            // DEC's hue is the usual one turned by 240 degrees (its 0 is blue)
            val h = (((hDeg + 240) % 360) + 360) % 360 / 360.0
            if (s == 0.0) {
                val v = (l * 255).roundToInt()
                return rgb(v, v, v)
            }
            val q = if (l < 0.5) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            /** one channel (0-255) from the hue fraction [t0], HSL's way */
            fun hue(t0: Double): Int {
                var t = t0
                if (t < 0) t += 1.0
                if (t > 1) t -= 1.0
                val v = when {
                    t < 1.0 / 6 -> p + (q - p) * 6 * t
                    t < 1.0 / 2 -> q
                    t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                    else -> p
                }
                return (v * 255).roundToInt().coerceIn(0, 255)
            }
            return rgb(hue(h + 1.0 / 3), hue(h), hue(h - 1.0 / 3))
        }

        /** the VT340's 16 colours, then xterm's 256-colour cube and greys */
        fun defaultPalette(): IntArray {
            val p = IntArray(256)
            val vt340 = intArrayOf(
                0, 0, 0, 20, 20, 80, 80, 13, 13, 20, 80, 20, 80, 20, 80, 20, 80, 80, 80, 80, 20, 53, 53, 53,
                26, 26, 26, 33, 33, 60, 60, 26, 26, 33, 60, 33, 60, 33, 60, 33, 60, 60, 60, 60, 33, 80, 80, 80,
            )
            for (i in 0 until 16) p[i] = rgb(pct(vt340[i * 3]), pct(vt340[i * 3 + 1]), pct(vt340[i * 3 + 2]))
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            for (i in 16 until 232) {
                val n = i - 16
                p[i] = rgb(steps[n / 36], steps[(n / 6) % 6], steps[n % 6])
            }
            for (i in 232 until 256) {
                val v = 8 + (i - 232) * 10
                p[i] = rgb(v, v, v)
            }
            return p
        }
    }
}
