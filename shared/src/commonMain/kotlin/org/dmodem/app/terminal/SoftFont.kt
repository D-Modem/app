// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * A soft character set a host loaded (DECDLD, a VT220's and later): its
 * characters' bitmaps, sent as sixels, and the designator (Dscs) that
 * puts it in a G set. Its characters are kept as code points of their
 * own in a private use plane ([BASE] + the character, 0x20..0x7f), so a
 * cell holding one is told from text, and drawn from [font].
 */
class SoftFont {
    /** the designator the host gave it (ESC ( and this designates it), or null before any is loaded */
    var designator: String? = null
        private set
    /** a 96-character set (DECDLD's Pcss 1), or 94 */
    var is96 = false
        private set
    /** the characters' cell, in pixels */
    var width = 8
        private set
    var height = 10
        private set
    private val glyphs = HashMap<Int, IntArray>()

    /** the glyphs as a font of [BASE]'s code points, or null when there are none */
    var font: BitmapFont? = null
        private set

    /**
     * a DECDLD: [params] (Pfn; Pcn; Pe; Pcmw; Pss; Pt; Pcmh; Pcss) and
     * [data], the designator and then the glyphs' sixels (each glyph's
     * columns, its bands of six rows parted by /, the glyphs by ;)
     */
    fun load(params: IntArray, data: String) {
        /** parameter [i], 0 when missing */
        fun p(i: Int) = params.getOrElse(i) { 0 }
        // the designator: intermediates, then a final 0x30..0x7e
        var i = 0
        while (i < data.length && data[i].code in 0x20..0x2f) i++
        if (i >= data.length || data[i].code !in 0x30..0x7e) return
        val dscs = data.substring(0, i + 1)
        val w = when (val m = p(3)) {
            0 -> if (p(4) == 2) 9 else 15
            1 -> 15
            2 -> 5
            3 -> 6
            4 -> 7
            else -> m
        }.coerceIn(1, MAX)
        // (a VT320's cell is 15x12 at 80 columns, a VT220's 10 high)
        val h = (if (p(6) == 0) (if (w >= 15) 12 else 10) else p(6)).coerceIn(1, MAX)
        // Pe: 0 and 2 every character erased first, 1 only those loaded replaced
        if (p(2) != 1 || dscs != designator || w != width || h != height) glyphs.clear()
        designator = dscs
        is96 = p(7) == 1
        width = w
        height = h
        // Pcn: the first character's place (a 94-character set's from 1, its 0x21)
        var ch = 0x20 + maxOf(p(1), if (is96) 0 else 1)
        for (g in data.substring(i + 1).split(';')) {
            if (ch > 0x7f || (!is96 && ch > 0x7e)) break
            glyphs[ch] = glyph(g, w, h)
            ch++
        }
        build()
    }

    /** one glyph's sixels as rows of [w] pixels (the leftmost bit w - 1), [h] of them */
    private fun glyph(s: String, w: Int, h: Int): IntArray {
        val rows = IntArray(h)
        var band = 0
        var col = 0
        for (c in s) {
            when (c) {
                '/' -> { band++; col = 0 }
                in '?'..'~' -> {
                    val bits = c.code - 0x3f
                    if (col < w) {
                        for (b in 0 until 6) {
                            val y = band * 6 + b
                            if (bits and (1 shl b) != 0 && y < h) rows[y] = rows[y] or (1 shl (w - 1 - col))
                        }
                    }
                    col++
                }
            }
        }
        return rows
    }

    /** [font] made from the glyphs */
    private fun build() {
        if (glyphs.isEmpty()) {
            font = null
            return
        }
        val chars = glyphs.keys.sorted()
        val rows = IntArray(chars.size * height)
        for ((n, c) in chars.withIndex()) glyphs[c]!!.copyInto(rows, n * height)
        font = BitmapFont(height, IntArray(chars.size) { BASE + chars[it] }, rows, width)
    }

    /** [cp] (0x20..0x7f, as GL has it) as the soft set's code point, or as it is when it hasn't that character */
    fun map(cp: Int): Int = if (glyphs.containsKey(cp)) BASE + cp else cp

    /** whether [designator] (of a 96-character set if [is96]) names this set */
    fun names(designator: String, is96: Boolean): Boolean = this.designator == designator && this.is96 == is96

    /** none loaded (RIS) */
    fun reset() {
        glyphs.clear()
        designator = null
        font = null
    }

    companion object {
        /** the soft characters' code points: plane 15's, this and the character */
        const val BASE = 0xf0000
        /** the largest cell a glyph has, in pixels */
        private const val MAX = 24
    }
}
