// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * Glyphs drawn from bitmaps rather than a font: 8x8 cells, a code point's
 * eight rows packed in a Long (the top row in the high byte, its leftmost
 * pixel the byte's high bit). What a profile whose terminal had its own
 * character ROM (Commodore's, Atari's) draws its cells with.
 */
class BitmapFont(private val codes: IntArray, private val bits: LongArray) {
    /** the glyphs it has */
    val size: Int get() = codes.size

    /** whether it has [cp]'s glyph */
    fun has(cp: Int): Boolean = index(cp) >= 0

    /** [cp]'s glyph (0 if it has none: a blank) */
    fun glyph(cp: Int): Long {
        val i = index(cp)
        return if (i >= 0) bits[i] else 0L
    }

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
        /** whether row [row] (0 top) of glyph [g] has pixel [col] (0 left) set */
        fun pixel(g: Long, row: Int, col: Int): Boolean = (g ushr ((7 - row) * 8 + (7 - col))) and 1L != 0L

        /** row [row] (0 top) of glyph [g], its leftmost pixel the high bit */
        fun row(g: Long, row: Int): Int = ((g ushr ((7 - row) * 8)) and 0xff).toInt()

        /** "CP:ROWS;" entries (hex: the code point, then the 16 digits of its rows), in code point order */
        fun parse(s: String): BitmapFont {
            val entries = s.split(';').filter { it.isNotEmpty() }
            val codes = IntArray(entries.size)
            val bits = LongArray(entries.size)
            for ((i, e) in entries.withIndex()) {
                val colon = e.indexOf(':')
                codes[i] = e.substring(0, colon).toInt(16)
                bits[i] = e.substring(colon + 1).toULong(16).toLong()
            }
            return BitmapFont(codes, bits)
        }
    }
}
