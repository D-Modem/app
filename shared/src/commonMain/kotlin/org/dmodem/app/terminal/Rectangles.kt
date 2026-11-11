// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * A VT420's rectangular area operations on [t]'s screen: copy (DECCRA),
 * erase (DECERA, DECSERA), fill (DECFRA), attributes changed or reversed
 * (DECCARA, DECRARA, as a rectangle or as the stream of characters
 * between two corners: DECSACE) and the checksum (DECRQCRA). An area is
 * its top, left, bottom and right, from 1, relative to the margins in
 * origin mode, as the sequences give it.
 */
class Rectangles(private val t: TerminalEmulator) {
    /** DECSACE: DECCARA and DECRARA change the stream from one corner to the other (false) or the rectangle (true) */
    var rectangle = false

    /** an area as the screen's rows and columns (from 0, inclusive), or null when it is empty */
    class Area(val top: Int, val left: Int, val bottom: Int, val right: Int)

    /**
     * the area [p] gives from index [at] (Pt; Pl; Pb; Pr, each defaulting to
     * the page's edge), from the margins in origin mode, cut to the screen;
     * as a [stream] its left can be right of its right on lines after the first
     */
    fun area(p: (Int, Int) -> Int, at: Int = 0, stream: Boolean = false): Area? {
        val oy = if (t.originMode) t.top else 0
        val ox = if (t.originMode) t.left else 0
        val maxY = if (t.originMode) t.bottom else t.rows - 1
        val maxX = if (t.originMode) t.right else t.cols - 1
        val top = (p(at, 1) - 1 + oy).coerceIn(0, maxY)
        val left = (p(at + 1, 1) - 1 + ox).coerceIn(0, maxX)
        val bottom = (p(at + 2, maxY - oy + 1) - 1 + oy).coerceIn(0, maxY)
        val right = (p(at + 3, maxX - ox + 1) - 1 + ox).coerceIn(0, maxX)
        return if (top > bottom || left > right && !(stream && top < bottom)) null else Area(top, left, bottom, right)
    }

    /** DECCRA: [a]'s cells copied with its top left at row [toY], column [toX] (from 0), cut to the screen */
    fun copy(a: Area, toY: Int, toX: Int) {
        val h = minOf(a.bottom - a.top + 1, t.rows - toY)
        val w = minOf(a.right - a.left + 1, t.cols - toX)
        if (h <= 0 || w <= 0) return
        // (taken first, so an area copied over itself is right)
        val chars = Array(h) { y -> IntArray(w) { x -> t.line(a.top + y).chars[a.left + x] } }
        val styles = Array(h) { y -> LongArray(w) { x -> t.line(a.top + y).styles[a.left + x] } }
        for (y in 0 until h) {
            val l = t.line(toY + y)
            for (x in 0 until w) l.set(toX + x, chars[y][x], styles[y][x])
        }
    }

    /** DECERA (DECSERA if [selective]: the cells DECSCA protects kept): [a]'s cells blanked */
    fun erase(a: Area, selective: Boolean) {
        val e = Style.erased(t.style)
        for (y in a.top..a.bottom) {
            val l = t.line(y)
            for (x in a.left..a.right) {
                if (selective && Style.attrs(l.styles[x]) and Style.PROTECTED != 0) continue
                l.set(x, 0, e)
            }
        }
    }

    /** DECFRA: [a] filled with [cp] in the current rendition */
    fun fill(a: Area, cp: Int) {
        for (y in a.top..a.bottom) {
            val l = t.line(y)
            for (x in a.left..a.right) l.set(x, cp, t.style)
        }
    }

    /**
     * DECCARA ([reverse] false: the SGR attributes [ps] set or cleared; 0
     * all of them off) or DECRARA (true: those [ps] names reversed) over
     * [a], as a rectangle or a stream as [rectangle] says
     */
    fun attributes(a: Area, ps: List<Int>, reverse: Boolean) {
        var on = 0
        var off = 0
        for (n in ps.ifEmpty { listOf(0) }) {
            when (n) {
                0 -> off = off or CHANGEABLE
                1 -> on = on or Style.BOLD
                4 -> on = on or Style.UNDERLINE
                5 -> on = on or Style.BLINK
                7 -> on = on or Style.INVERSE
                8 -> on = on or Style.INVISIBLE
                22 -> off = off or Style.BOLD
                24 -> off = off or Style.UNDERLINE
                25 -> off = off or Style.BLINK
                27 -> off = off or Style.INVERSE
                28 -> off = off or Style.INVISIBLE
            }
        }
        each(a) { l, x ->
            val s = l.styles[x]
            var at = Style.attrs(s)
            at = if (reverse) at xor ((on or off) and CHANGEABLE) else (at and off.inv()) or on
            l.styles[x] = Style.withAttrs(s, at)
        }
    }

    /** DECRQCRA's checksum of [a]: the characters (a blank as a space) and their attributes summed, negated, 16 bits */
    fun checksum(a: Area): Int {
        var sum = 0
        for (y in a.top..a.bottom) {
            val l = t.line(y)
            for (x in a.left..a.right) {
                val cp = l.chars[x].let { if (it <= 0) ' '.code else it }
                val at = Style.attrs(l.styles[x])
                sum += cp
                if (at and Style.PROTECTED != 0) sum += 0x04
                if (at and Style.INVISIBLE != 0) sum += 0x08
                if (at and Style.ANY_UNDERLINE != 0) sum += 0x10
                if (at and Style.INVERSE != 0) sum += 0x20
                if (at and Style.BLINK != 0) sum += 0x40
                if (at and Style.BOLD != 0) sum += 0x80
            }
        }
        return (-sum) and 0xffff
    }

    /** DECIC: [n] blank columns in at column [at], within the margins and the scrolling region (the right margin's pushed off) */
    fun insertColumns(at: Int, n: Int) {
        val e = Style.erased(t.style)
        for (y in t.top..t.bottom) t.line(y).insertCells(at, n, e, t.right + 1)
    }

    /** DECDC: [n] columns at column [at] deleted, within the margins and the region (blank ones in at the right margin) */
    fun deleteColumns(at: Int, n: Int) {
        val e = Style.erased(t.style)
        for (y in t.top..t.bottom) t.line(y).deleteCells(at, n, e, t.right + 1)
    }

    /** [f] on each of [a]'s cells: its rectangle's, or (as a stream) from its first corner along the lines to its second */
    private fun each(a: Area, f: (Line, Int) -> Unit) {
        for (y in a.top..a.bottom) {
            val l = t.line(y)
            val from = if (rectangle || y == a.top) a.left else 0
            val to = if (rectangle || y == a.bottom) a.right else t.cols - 1
            for (x in from..to) f(l, x)
        }
    }

    companion object {
        /** the attributes DECCARA and DECRARA change */
        private const val CHANGEABLE = Style.BOLD or Style.UNDERLINE or Style.BLINK or Style.INVERSE or Style.INVISIBLE
    }
}
