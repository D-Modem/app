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

    private const val COLOR_MASK = 0x1ffffffL

    val NORMAL: Long = of(DEFAULT_FG, DEFAULT_BG, 0)

    fun of(fg: Int, bg: Int, attrs: Int): Long =
        (fg.toLong() and COLOR_MASK) or
            ((bg.toLong() and COLOR_MASK) shl 25) or
            (attrs.toLong() shl 50)

    fun fg(s: Long): Int = (s and COLOR_MASK).toInt()
    fun bg(s: Long): Int = ((s ushr 25) and COLOR_MASK).toInt()
    fun attrs(s: Long): Int = (s ushr 50).toInt()

    fun withFg(s: Long, fg: Int) = of(fg, bg(s), attrs(s))
    fun withBg(s: Long, bg: Int) = of(fg(s), bg, attrs(s))
    fun withAttrs(s: Long, a: Int) = of(fg(s), bg(s), a)

    /** the look of erased cells: only the background stays (xterm's BCE) */
    fun erased(s: Long) = of(DEFAULT_FG, bg(s), 0)
}

/** a line of cells: code points (0: never written, shown as a space) and their looks */
class Line(cols: Int, style: Long = Style.NORMAL) {
    var chars = IntArray(cols)
    var styles = LongArray(cols) { style }
    /** it goes on in the next line (the text wrapped at the right margin) */
    var wrapped = false

    val cols: Int get() = chars.size

    fun clear(from: Int, to: Int, style: Long) {
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(cols)) {
            chars[i] = 0
            styles[i] = style
        }
    }

    fun resize(n: Int) {
        if (n == cols) return
        val c = IntArray(n)
        val s = LongArray(n) { Style.NORMAL }
        System.arraycopy(chars, 0, c, 0, minOf(n, cols))
        System.arraycopy(styles, 0, s, 0, minOf(n, cols))
        chars = c
        styles = s
    }

    fun copyFrom(o: Line) {
        System.arraycopy(o.chars, 0, chars, 0, minOf(cols, o.cols))
        System.arraycopy(o.styles, 0, styles, 0, minOf(cols, o.cols))
        wrapped = o.wrapped
    }

    /** the text, without the blank cells at the end */
    fun text(from: Int = 0, to: Int = cols): String {
        var end = to.coerceAtMost(cols)
        while (end > from && chars[end - 1] == 0) end--
        val sb = StringBuilder()
        for (i in from until end) sb.appendCodePoint(if (chars[i] == 0) ' '.code else chars[i])
        return sb.toString()
    }
}
