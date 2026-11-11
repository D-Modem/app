// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * Teletext's and videotex's block graphics: a cell split into 2x3 blocks,
 * six bits saying which are lit, contiguous (the blocks touching) or
 * separated (each with a gap round it). Drawn from bitmaps the app makes
 * (not a font's sextants, which have no separated set), kept as code
 * points of their own in plane 15.
 */
object Mosaic {
    /** the contiguous mosaics' code points: this and the six bits; the separated ones' from [BASE] + 0x40 */
    const val BASE = 0xf1000

    /** the glyphs' size: each block 3 pixels wide and 3 high */
    private const val W = 6
    private const val H = 9

    /** every mosaic but the blank one, contiguous then separated */
    val font: BitmapFont by lazy {
        val codes = ArrayList<Int>()
        val rows = ArrayList<Int>()
        for (sep in listOf(false, true)) {
            for (mask in 1 until 64) {
                codes.add(code(mask, sep))
                for (y in 0 until H) rows.add(row(mask, sep, y))
            }
        }
        BitmapFont(H, codes.toIntArray(), rows.toIntArray(), W)
    }

    /** the code point of the mosaic with blocks [mask] lit (bit 0 top left, 1 top right, 2 and 3 the middle, 4 and 5 the bottom), space for none */
    fun code(mask: Int, separated: Boolean): Int =
        if (mask and 0x3f == 0) ' '.code else BASE + (mask and 0x3f) + (if (separated) 0x40 else 0)

    /**
     * a teletext mosaic character's six bits (0x20-0x3f, 0x60-0x7f): its
     * low five bits, and bit 6 for the bottom right block
     */
    fun bits(c: Int): Int = (c and 0x1f) or ((c and 0x40) shr 1)

    /** pixel row [y] of mosaic [mask], the leftmost pixel bit 5 */
    private fun row(mask: Int, sep: Boolean, y: Int): Int {
        val band = y / 3
        // (separated: each block's last row and column dark)
        if (sep && y % 3 == 2) return 0
        var r = 0
        if (mask and (1 shl (band * 2)) != 0) r = r or (if (sep) 0b110000 else 0b111000)
        if (mask and (1 shl (band * 2 + 1)) != 0) r = r or (if (sep) 0b000110 else 0b000111)
        return r
    }
}
