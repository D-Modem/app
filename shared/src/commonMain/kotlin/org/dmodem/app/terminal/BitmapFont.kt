// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * Glyphs drawn from bitmaps rather than a font: 8 pixels wide and
 * [height] high (8 for the home computers' character ROMs, 16 for a VGA's),
 * a row a byte, its leftmost pixel the high bit. What a profile whose
 * terminal had its own character ROM (Commodore's, Atari's, a PC's) draws
 * its cells with.
 */
class BitmapFont(val height: Int, private val codes: IntArray, private val rows: ByteArray) {
    /** the glyphs' width in pixels */
    val width: Int get() = 8

    /** the glyphs it has */
    val size: Int get() = codes.size

    /** whether it has [cp]'s glyph */
    fun has(cp: Int): Boolean = index(cp) >= 0

    /** row [row] (0 top) of [cp]'s glyph, its leftmost pixel bit 7 (0 if it has none: a blank) */
    fun row(cp: Int, row: Int): Int {
        val i = index(cp)
        return if (i < 0 || row !in 0 until height) 0 else rows[i * height + row].toInt() and 0xff
    }

    /** whether [cp]'s glyph has pixel [col] (0 left) of row [row] (0 top) set */
    fun pixel(cp: Int, row: Int, col: Int): Boolean = (row(cp, row) shr (7 - col)) and 1 != 0

    /** [cp]'s place in [codes] (they're in order), -1 if it isn't there */
    private fun index(cp: Int): Int {
        var lo = 0
        var hi = codes.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = codes[mid]
            when {
                c < cp -> lo = mid + 1
                c > cp -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    companion object {
        /** "CP:ROWS;" entries (hex: the code point, then two digits a row, [height] rows), in code point order */
        fun parse(height: Int, s: String): BitmapFont {
            val entries = s.split(';').filter { it.isNotEmpty() }
            val codes = IntArray(entries.size)
            val rows = ByteArray(entries.size * height)
            for ((i, e) in entries.withIndex()) {
                val colon = e.indexOf(':')
                codes[i] = e.substring(0, colon).toInt(16)
                for (r in 0 until height) {
                    rows[i * height + r] = e.substring(colon + 1 + 2 * r, colon + 3 + 2 * r).toInt(16).toByte()
                }
            }
            return BitmapFont(height, codes, rows)
        }
    }
}
