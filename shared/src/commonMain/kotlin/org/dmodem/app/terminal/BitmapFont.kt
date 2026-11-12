// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * Glyphs drawn from bitmaps rather than a font: [width] pixels wide (8,
 * or up to 24 for a soft font a host loaded) and [height] high (8 for the
 * home computers' character ROMs, 16 for a VGA's), a row an Int, its
 * leftmost pixel bit [width] - 1. What a profile whose terminal had its
 * own character ROM (Commodore's, Atari's, a PC's) draws its cells with,
 * and a DEC terminal's soft characters (DECDLD).
 */
class BitmapFont(val height: Int, private val codes: IntArray, private val rows: IntArray, val width: Int = 8) {
    /** the glyphs it has */
    val size: Int get() = codes.size

    /** whether it has [cp]'s glyph */
    fun has(cp: Int): Boolean = index(cp) >= 0

    /** row [row] (0 top) of [cp]'s glyph, its leftmost pixel bit [width] - 1 (0 if it has none: a blank) */
    fun row(cp: Int, row: Int): Int {
        val i = index(cp)
        return if (i < 0 || row !in 0 until height) 0 else rows[i * height + row]
    }

    /** its glyphs by code point, each [height] rows */
    fun glyphs(): Map<Int, IntArray> = codes.withIndex().associate { (i, cp) -> cp to rows.copyOfRange(i * height, (i + 1) * height) }

    /** whether [cp]'s glyph has pixel [col] (0 left) of row [row] (0 top) set */
    fun pixel(cp: Int, row: Int, col: Int): Boolean = (row(cp, row) shr (width - 1 - col)) and 1 != 0

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
        /**
         * a font of [height]-row, 8-wide glyphs from [layers] (each its
         * glyphs by code point: an earlier layer's glyph the one drawn)
         */
        fun of(height: Int, vararg layers: Map<Int, IntArray>): BitmapFont {
            val all = HashMap<Int, IntArray>()
            for (layer in layers.reversed()) all.putAll(layer)
            val codes = all.keys.sorted().toIntArray()
            val rows = IntArray(codes.size * height)
            for ((i, cp) in codes.withIndex()) all.getValue(cp).copyInto(rows, i * height, 0, height)
            return BitmapFont(height, codes, rows)
        }

        /** "CP:ROWS;" entries (hex: the code point, then two digits a row, [height] rows), in code point order */
        fun parse(height: Int, s: String): BitmapFont {
            val entries = s.split(';').filter { it.isNotEmpty() }
            val codes = IntArray(entries.size)
            val rows = IntArray(entries.size * height)
            for ((i, e) in entries.withIndex()) {
                val colon = e.indexOf(':')
                codes[i] = e.substring(0, colon).toInt(16)
                for (r in 0 until height) {
                    rows[i * height + r] = e.substring(colon + 1 + 2 * r, colon + 3 + 2 * r).toInt(16)
                }
            }
            return BitmapFont(height, codes, rows)
        }
    }
}

/**
 * A machine's character ROM: its 256 codes' glyphs, 8 wide, in the
 * machine's code order, kept as hex (two digits a row) [h8] rows high,
 * [h16], or both. Asked for a size it hasn't got, it doubles its 8 rows
 * or halves its 16.
 */
class RomFont(private val h8: String? = null, private val h16: String? = null) {
    private val rows8: IntArray? by lazy { h8?.let(::decode) }
    private val rows16: IntArray? by lazy { h16?.let(::decode) }

    /** code [code]'s glyph, [height] (8 or 16) rows */
    fun glyph(code: Int, height: Int): IntArray {
        val own = if (height == 16) rows16 else rows8
        if (own != null) return own.copyOfRange(code * height, (code + 1) * height)
        return if (height == 16) {
            val r = rows8!!
            IntArray(16) { r[code * 8 + it / 2] }
        } else {
            // (both rows' pixels, so a thin stroke stays)
            val r = rows16!!
            IntArray(8) { r[code * 16 + 2 * it] or r[code * 16 + 2 * it + 1] }
        }
    }

    /**
     * its glyphs by code point, [height] rows: those of [codes] that
     * [codePoint] gives one (0: none), the first code that has one the
     * glyph drawn for it
     */
    fun byCodePoint(height: Int, codes: IntRange = 0..0xff, codePoint: (Int) -> Int): Map<Int, IntArray> {
        val out = HashMap<Int, IntArray>()
        for (c in codes) {
            val cp = codePoint(c)
            if (cp != 0 && cp !in out) out[cp] = glyph(c, height)
        }
        return out
    }

    /** as a font read in [codePoint]'s set ([height] rows), what it hasn't got [fallback]'s */
    fun font(height: Int, fallback: BitmapFont? = null, codes: IntRange = 0x20..0xff, codePoint: (Int) -> Int): BitmapFont =
        BitmapFont.of(height, byCodePoint(height, codes, codePoint), fallback?.takeIf { it.height == height }?.glyphs() ?: emptyMap())

    private companion object {
        /** hex, two digits a row, as the rows */
        fun decode(hex: String): IntArray = IntArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16) }
    }
}
