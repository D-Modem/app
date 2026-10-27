// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.terminal

/**
 * A cell's look, packed in a Long: the foreground colour (bits 0-24), the
 * background colour (25-49) and the attributes (50-63). A colour is an
 * index of the 256-colour palette, [DEFAULT_FG] or [DEFAULT_BG], or
 * [RGB] or'ed with a 24-bit colour.
 */
object Style {
    const val DEFAULT_FG = 256
    const val DEFAULT_BG = 257
    const val RGB = 0x1000000

    const val BOLD = 1
    const val FAINT = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val BLINK = 16
    const val INVERSE = 32
    const val INVISIBLE = 64
    const val STRIKE = 128
    /** SGR 21 and 4:2 */
    const val DOUBLE_UNDERLINE = 256
    /** SGR 4:3 (and the dotted and dashed ones, 4:4 and 4:5) */
    const val CURLY_UNDERLINE = 512
    const val OVERLINE = 1024

    /** any of the underlines */
    const val ANY_UNDERLINE = UNDERLINE or DOUBLE_UNDERLINE or CURLY_UNDERLINE

    private const val COLOR_MASK = 0x1ffffffL

    val NORMAL: Long = of(DEFAULT_FG, DEFAULT_BG, 0)

    /** the look of colours [fg] and [bg] with attributes [attrs] */
    fun of(fg: Int, bg: Int, attrs: Int): Long =
        (fg.toLong() and COLOR_MASK) or
            ((bg.toLong() and COLOR_MASK) shl 25) or
            (attrs.toLong() shl 50)

    /** a look's foreground colour */
    fun fg(s: Long): Int = (s and COLOR_MASK).toInt()
    /** a look's background colour */
    fun bg(s: Long): Int = ((s ushr 25) and COLOR_MASK).toInt()
    /** a look's attributes */
    fun attrs(s: Long): Int = (s ushr 50).toInt()

    /** [s] with foreground [fg] */
    fun withFg(s: Long, fg: Int) = of(fg, bg(s), attrs(s))
    /** [s] with background [bg] */
    fun withBg(s: Long, bg: Int) = of(fg(s), bg, attrs(s))
    /** [s] with attributes [a] */
    fun withAttrs(s: Long, a: Int) = of(fg(s), bg(s), a)

    /** the look of erased cells: only the background stays (xterm's BCE) */
    fun erased(s: Long) = of(DEFAULT_FG, bg(s), 0)
}

/** a picture (a sixel image): its pixels, and the cell size it was placed at (its tiles are cells of that size) */
class TermImage(val width: Int, val height: Int, val pixels: IntArray, val cellWidth: Int, val cellHeight: Int) {
    val id = nextId++

    private companion object {
        var nextId = 1L
    }
}

/** the part of an image a cell shows: its tile (column, row of tiles) */
class ImageTile(val image: TermImage, val tx: Int, val ty: Int)

/** what few cells carry besides their character: combining marks, a picture's tile, a hyperlink */
class CellExtra(
    var combining: IntArray? = null,
    var image: ImageTile? = null,
    var link: String? = null,
) {
    /** a copy of its own (the combining marks copied, the tile and link shared) */
    fun copy() = CellExtra(combining?.copyOf(), image, link)
    val isEmpty get() = combining == null && image == null && link == null
}

/**
 * a line of cells: code points (0: never written, shown as a space;
 * [WIDE_TAIL]: the right half of the wide character before it), their
 * looks, and the few cells' extras
 */
class Line(cols: Int, style: Long = Style.NORMAL) {
    var chars = IntArray(cols)
    var styles = LongArray(cols) { style }
    /** combining marks, image tiles, links: allocated when a cell first needs one */
    var extras: Array<CellExtra?>? = null
        private set
    /** it goes on in the next line (the text wrapped at the right margin) */
    var wrapped = false

    val cols: Int get() = chars.size

    /** cell [i]'s extras, or null if it has none */
    fun extra(i: Int): CellExtra? = extras?.getOrNull(i)

    /** the cell's extras, made if it has none */
    fun extraFor(i: Int): CellExtra {
        val e = extras ?: arrayOfNulls<CellExtra>(cols).also { extras = it }
        return e[i] ?: CellExtra().also { e[i] = it }
    }

    /** a cell set: its extras gone (a new character over it) */
    fun set(i: Int, cp: Int, style: Long) {
        chars[i] = cp
        styles[i] = style
        extras?.let { it[i] = null }
    }

    /** cells [from] until [to] blanked in [style], their extras gone */
    fun clear(from: Int, to: Int, style: Long) {
        val f = from.coerceAtLeast(0)
        val t = to.coerceAtMost(cols)
        for (i in f until t) {
            chars[i] = 0
            styles[i] = style
        }
        extras?.let { e -> for (i in f until t) e[i] = null }
    }

    /** [n] blank cells at [at], those at the end pushed off */
    fun insertCells(at: Int, n: Int, style: Long) {
        if (at >= cols || n <= 0) return
        val k = minOf(n, cols - at)
        chars.copyInto(chars, at + k, at, cols - k)
        styles.copyInto(styles, at + k, at, cols - k)
        extras?.let { it.copyInto(it, at + k, at, cols - k) }
        clear(at, at + k, style)
    }

    /** [n] cells at [at] removed, blank ones in from the end */
    fun deleteCells(at: Int, n: Int, style: Long) {
        if (at >= cols || n <= 0) return
        val k = minOf(n, cols - at)
        chars.copyInto(chars, at, at + k, cols)
        styles.copyInto(styles, at, at + k, cols)
        extras?.let { it.copyInto(it, at, at + k, cols) }
        clear(cols - k, cols, style)
    }

    /** [n] columns wide: cut or padded with blank cells (a wide character cut at the edge gone) */
    fun resize(n: Int) {
        if (n == cols) return
        val c = IntArray(n)
        val s = LongArray(n) { Style.NORMAL }
        chars.copyInto(c, 0, 0, minOf(n, cols))
        styles.copyInto(s, 0, 0, minOf(n, cols))
        extras = extras?.let { e -> arrayOfNulls<CellExtra>(n).also { e.copyInto(it, 0, 0, minOf(n, cols)) } }
        chars = c
        styles = s
        // (a wide character cut in half at the new edge: gone)
        if (n > 0 && chars[n - 1] > 0 && Wcwidth.width(chars[n - 1]) == 2) chars[n - 1] = 0
    }

    /** [o]'s cells (as many as fit), extras and wrap copied into this one */
    fun copyFrom(o: Line) {
        val n = minOf(cols, o.cols)
        o.chars.copyInto(chars, 0, 0, n)
        o.styles.copyInto(styles, 0, 0, n)
        val oe = o.extras
        if (oe == null) {
            extras = null
        } else {
            val e = arrayOfNulls<CellExtra>(cols)
            for (i in 0 until n) e[i] = oe[i]?.copy()
            extras = e
        }
        wrapped = o.wrapped
    }

    /** the text, without the blank cells at the end (combining marks with their characters) */
    fun text(from: Int = 0, to: Int = cols): String {
        var end = to.coerceAtMost(cols)
        while (end > from && chars[end - 1] == 0) end--
        val sb = StringBuilder()
        for (i in from until end) {
            val cp = chars[i]
            if (cp == WIDE_TAIL) continue
            sb.appendCp(if (cp == 0) ' '.code else cp)
            extra(i)?.combining?.forEach { sb.appendCp(it) }
        }
        return sb.toString()
    }

    companion object {
        /** the right half of a wide character */
        const val WIDE_TAIL = -1
    }
}
