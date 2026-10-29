// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.terminal

import android.graphics.Canvas
import android.graphics.Paint

/**
 * The box drawing characters (U+2500..) and block elements (U+2580..)
 * drawn as shapes that fill their cell, as a PC's text mode had them: a
 * font's glyphs leave gaps between the lines and blocks of ANSI art.
 */
object BoxDrawing {

    // each line character: its arms (up, right, down, left), 0 none, 1 light, 2 double, 3 heavy
    private val arms = HashMap<Int, Int>()

    /** each of [chars] given these arms, packed two bits each in [arms] */
    private fun put(chars: String, up: Int, right: Int, down: Int, left: Int) {
        for (c in chars) arms[c.code] = (up shl 6) or (right shl 4) or (down shl 2) or left
    }

    init {
        put("─", 0, 1, 0, 1); put("━", 0, 3, 0, 3)
        put("│", 1, 0, 1, 0); put("┃", 3, 0, 3, 0)
        put("┌", 0, 1, 1, 0); put("┐", 0, 0, 1, 1); put("└", 1, 1, 0, 0); put("┘", 1, 0, 0, 1)
        put("┏", 0, 3, 3, 0); put("┓", 0, 0, 3, 3); put("┗", 3, 3, 0, 0); put("┛", 3, 0, 0, 3)
        put("├", 1, 1, 1, 0); put("┤", 1, 0, 1, 1); put("┬", 0, 1, 1, 1); put("┴", 1, 1, 0, 1); put("┼", 1, 1, 1, 1)
        put("┣", 3, 3, 3, 0); put("┫", 3, 0, 3, 3); put("┳", 0, 3, 3, 3); put("┻", 3, 3, 0, 3); put("╋", 3, 3, 3, 3)
        put("═", 0, 2, 0, 2); put("║", 2, 0, 2, 0)
        put("╔", 0, 2, 2, 0); put("╗", 0, 0, 2, 2); put("╚", 2, 2, 0, 0); put("╝", 2, 0, 0, 2)
        put("╠", 2, 2, 2, 0); put("╣", 2, 0, 2, 2); put("╦", 0, 2, 2, 2); put("╩", 2, 2, 0, 2); put("╬", 2, 2, 2, 2)
        put("╒", 0, 2, 1, 0); put("╓", 0, 1, 2, 0); put("╕", 0, 0, 1, 2); put("╖", 0, 0, 2, 1)
        put("╘", 1, 2, 0, 0); put("╙", 2, 1, 0, 0); put("╛", 1, 0, 0, 2); put("╜", 2, 0, 0, 1)
        put("╞", 1, 2, 1, 0); put("╟", 2, 1, 2, 0); put("╡", 1, 0, 1, 2); put("╢", 2, 0, 2, 1)
        put("╤", 0, 2, 1, 2); put("╥", 0, 1, 2, 1); put("╧", 1, 2, 0, 2); put("╨", 2, 1, 0, 1)
        put("╪", 1, 2, 1, 2); put("╫", 2, 1, 2, 1)
    }

    /** whether [cp] is one this draws: a block element or a line character */
    fun handles(cp: Int): Boolean = cp in 0x2580..0x259f || arms.containsKey(cp)

    /** cp in the cell (x, y, w, h) in paint's colour; false if it isn't one of ours */
    fun draw(c: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float, paint: Paint): Boolean {
        if (cp in 0x2580..0x259f) return block(c, cp, x, y, w, h, paint)
        val a = arms[cp] ?: return false
        // on whole pixels: the lines of one cell meet those of the next
        val light = maxOf(1f, kotlin.math.round(w / 8f))
        val heavy = light * 2
        val gap = maxOf(kotlin.math.round(light * 1.5f), 2f)
        val cx = kotlin.math.floor(x + w / 2)
        val cy = kotlin.math.floor(y + h / 2)
        val up = (a shr 6) and 3
        val right = (a shr 4) and 3
        val down = (a shr 2) and 3
        val left = a and 3
        // a horizontal line of thickness t centred on yc from x0 to x1; a vertical one on xc
        fun h(x0: Float, x1: Float, yc: Float, t: Float) {
            val top = yc - kotlin.math.floor(t / 2)
            c.drawRect(x0, top, x1, top + t, paint)
        }
        // a vertical line of thickness t centred on xc from y0 to y1
        fun v(y0: Float, y1: Float, xc: Float, t: Float) {
            val left = xc - kotlin.math.floor(t / 2)
            c.drawRect(left, y0, left + t, y1, paint)
        }
        // a line of thickness t centred on c covers lo(c, t) until hi(c, t)
        fun lo(c0: Float, t: Float) = c0 - kotlin.math.floor(t / 2)
        // where a line of thickness t centred on c0 ends
        fun hi(c0: Float, t: Float) = lo(c0, t) + t
        // where a line of a double arm meets the arm across its side: none, the
        // outer corner (-gap); single or heavy, the centre line; double, the inner corner (+gap)
        fun near(across: Int, opposite: Int): Float = when (across) {
            0 -> if (opposite == 2) -gap else 0f
            2 -> gap
            else -> 0f
        }
        val vDouble = up == 2 || down == 2
        val hDouble = left == 2 || right == 2
        // the thickness of the line across (for a single arm to meet its far side)
        val vT = if (up == 3 || down == 3) heavy else light
        val hT = if (left == 3 || right == 3) heavy else light
        val thick = { k: Int -> if (k == 3) heavy else light }

        if (right != 0) {
            if (right == 2) {
                h(lo(cx + near(up, down), vT), x + w, cy - gap, light)
                h(lo(cx + near(down, up), vT), x + w, cy + gap, light)
            } else {
                h(lo(if (vDouble) cx - gap else cx, vT), x + w, cy, thick(right))
            }
        }
        if (left != 0) {
            if (left == 2) {
                h(x, hi(cx - near(up, down), vT), cy - gap, light)
                h(x, hi(cx - near(down, up), vT), cy + gap, light)
            } else {
                h(x, hi(if (vDouble) cx + gap else cx, vT), cy, thick(left))
            }
        }
        if (down != 0) {
            if (down == 2) {
                v(lo(cy + near(left, right), hT), y + h, cx - gap, light)
                v(lo(cy + near(right, left), hT), y + h, cx + gap, light)
            } else {
                v(lo(if (hDouble) cy - gap else cy, hT), y + h, cx, thick(down))
            }
        }
        if (up != 0) {
            if (up == 2) {
                v(y, hi(cy - near(left, right), hT), cx - gap, light)
                v(y, hi(cy - near(right, left), hT), cx + gap, light)
            } else {
                v(y, hi(if (hDouble) cy + gap else cy, hT), cx, thick(up))
            }
        }
        return true
    }

    /** a block element (U+2580..259F) filling its part of the cell, the shades in paint's alpha; false if not one */
    private fun block(c: Canvas, cp: Int, x: Float, y: Float, w: Float, h: Float, paint: Paint): Boolean {
        val alpha = paint.alpha
        when (cp) {
            0x2580 -> c.drawRect(x, y, x + w, y + h / 2, paint)                     // ▀
            in 0x2581..0x2588 -> {                                                   // ▁..█: eighths from the bottom
                val n = cp - 0x2580
                c.drawRect(x, y + h - h * n / 8, x + w, y + h, paint)
            }
            in 0x2589..0x258f -> {                                                   // ▉..▏: eighths from the left
                val n = 8 - (cp - 0x2588)
                c.drawRect(x, y, x + w * n / 8, y + h, paint)
            }
            0x2590 -> c.drawRect(x + w / 2, y, x + w, y + h, paint)                 // ▐
            0x2591, 0x2592, 0x2593 -> {                                              // ░ ▒ ▓
                paint.alpha = alpha * (cp - 0x2590) / 4
                c.drawRect(x, y, x + w, y + h, paint)
                paint.alpha = alpha
            }
            0x2594 -> c.drawRect(x, y, x + w, y + h / 8, paint)                     // ▔
            0x2595 -> c.drawRect(x + w * 7 / 8, y, x + w, y + h, paint)             // ▕
            else -> {                                                                // ▖..▟: quadrants
                val q = when (cp) {
                    0x2596 -> 0b0010; 0x2597 -> 0b0001; 0x2598 -> 0b1000; 0x2599 -> 0b1011
                    0x259a -> 0b1001; 0x259b -> 0b1110; 0x259c -> 0b1101; 0x259d -> 0b0100
                    0x259e -> 0b0110; 0x259f -> 0b0111; else -> return false
                }
                val hw = w / 2
                val hh = h / 2
                if (q and 0b1000 != 0) c.drawRect(x, y, x + hw, y + hh, paint)
                if (q and 0b0100 != 0) c.drawRect(x + hw, y, x + w, y + hh, paint)
                if (q and 0b0010 != 0) c.drawRect(x, y + hh, x + hw, y + h, paint)
                if (q and 0b0001 != 0) c.drawRect(x + hw, y + hh, x + w, y + h, paint)
            }
        }
        return true
    }
}
