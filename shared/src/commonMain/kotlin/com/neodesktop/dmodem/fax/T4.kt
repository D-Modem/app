// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import com.neodesktop.dmodem.io.*


/**
 * A fax page: [rows] rows of [width] pels, 1 = black, packed eight to a
 * byte with the first pel in the top bit; [fine]: 196 lines per inch
 * (else 98), always 204 pels per inch across.
 */
class FaxPage(val width: Int, val rows: Int, val fine: Boolean, val bits: ByteArray = ByteArray(width / 8 * rows)) {
    val stride: Int get() = width / 8

    fun black(x: Int, y: Int): Boolean = (bits[y * stride + x / 8].toInt() shr (7 - x % 8)) and 1 == 1

    fun set(x: Int, y: Int, on: Boolean) {
        val i = y * stride + x / 8
        val m = 0x80 shr (x % 8)
        bits[i] = (if (on) bits[i].toInt() or m else bits[i].toInt() and m.inv()).toByte()
    }

    companion object {
        const val WIDTH = 1728
        /** A4's rows (297 mm) */
        fun a4Rows(fine: Boolean) = if (fine) 2287 else 1143
    }
}

/**
 * T.4's one-dimensional coding (Modified Huffman), as fax sends a page:
 * an EOL before each row, each row's runs (white first) in the code
 * tables, fill before the EOLs for a receiver's minimum scan line time,
 * RTC (six EOLs) at the end. The bits as the line carries them: the
 * first one in the bottom bit of a byte (as a class 1 modem takes them).
 */
object T4 {
    private class Code(val run: Int, val bits: String)

    private val WHITE = codes(
        "0:00110101 1:000111 2:0111 3:1000 4:1011 5:1100 6:1110 7:1111 8:10011 9:10100 10:00111 11:01000 " +
            "12:001000 13:000011 14:110100 15:110101 16:101010 17:101011 18:0100111 19:0001100 20:0001000 " +
            "21:0010111 22:0000011 23:0000100 24:0101000 25:0101011 26:0010011 27:0100100 28:0011000 " +
            "29:00000010 30:00000011 31:00011010 32:00011011 33:00010010 34:00010011 35:00010100 36:00010101 " +
            "37:00010110 38:00010111 39:00101000 40:00101001 41:00101010 42:00101011 43:00101100 44:00101101 " +
            "45:00000100 46:00000101 47:00001010 48:00001011 49:01010010 50:01010011 51:01010100 52:01010101 " +
            "53:00100100 54:00100101 55:01011000 56:01011001 57:01011010 58:01011011 59:01001010 60:01001011 " +
            "61:00110010 62:00110011 63:00110100 " +
            "64:11011 128:10010 192:010111 256:0110111 320:00110110 384:00110111 448:01100100 512:01100101 " +
            "576:01101000 640:01100111 704:011001100 768:011001101 832:011010010 896:011010011 960:011010100 " +
            "1024:011010101 1088:011010110 1152:011010111 1216:011011000 1280:011011001 1344:011011010 " +
            "1408:011011011 1472:010011000 1536:010011001 1600:010011010 1664:011000 1728:010011011",
    )
    private val BLACK = codes(
        "0:0000110111 1:010 2:11 3:10 4:011 5:0011 6:0010 7:00011 8:000101 9:000100 10:0000100 11:0000101 " +
            "12:0000111 13:00000100 14:00000111 15:000011000 16:0000010111 17:0000011000 18:0000001000 " +
            "19:00001100111 20:00001101000 21:00001101100 22:00000110111 23:00000101000 24:00000010111 " +
            "25:00000011000 26:000011001010 27:000011001011 28:000011001100 29:000011001101 30:000001101000 " +
            "31:000001101001 32:000001101010 33:000001101011 34:000011010010 35:000011010011 36:000011010100 " +
            "37:000011010101 38:000011010110 39:000011010111 40:000001101100 41:000001101101 42:000011011010 " +
            "43:000011011011 44:000001010100 45:000001010101 46:000001010110 47:000001010111 48:000001100100 " +
            "49:000001100101 50:000001010010 51:000001010011 52:000000100100 53:000000110111 54:000000111000 " +
            "55:000000100111 56:000000101000 57:000001011000 58:000001011001 59:000000101011 60:000000101100 " +
            "61:000001011010 62:000001100110 63:000001100111 " +
            "64:0000001111 128:000011001000 192:000011001001 256:000001011011 320:000000110011 384:000000110100 " +
            "448:000000110101 512:0000001101100 576:0000001101101 640:0000001001010 704:0000001001011 " +
            "768:0000001001100 832:0000001001101 896:0000001110010 960:0000001110011 1024:0000001110100 " +
            "1088:0000001110101 1152:0000001110110 1216:0000001110111 1280:0000001010010 1344:0000001010011 " +
            "1408:0000001010100 1472:0000001010101 1536:0000001011010 1600:0000001011011 1664:0000001100100 " +
            "1728:0000001100101",
    )
    /** the make-up codes past 1728, the same for both colours */
    private val EXTENDED = codes(
        "1792:00000001000 1856:00000001100 1920:00000001101 1984:000000010010 2048:000000010011 " +
            "2112:000000010100 2176:000000010101 2240:000000010110 2304:000000010111 2368:000000011100 " +
            "2432:000000011101 2496:000000011110 2560:000000011111",
    )
    private const val EOL = "000000000001"

    private fun codes(s: String) = s.trim().split(Regex("\\s+")).map { it.split(':').let { (r, b) -> Code(r.toInt(), b) } }

    private val whiteByRun = (WHITE + EXTENDED).associateBy { it.run }
    private val blackByRun = (BLACK + EXTENDED).associateBy { it.run }

    /** the codes as a binary tree to decode with: [next] pairs, a leaf's run (or -1) */
    private class Tree(table: List<Code>) {
        var zero = IntArray(256)
        var one = IntArray(256)
        var leaf = IntArray(256) { -1 }
        var n = 1

        init {
            zero.fill(-1)
            one.fill(-1)
            for (c in table) {
                var at = 0
                for (ch in c.bits) {
                    val next = if (ch == '0') zero else one
                    if (next[at] < 0) {
                        if (n == zero.size) grow()
                        next[at] = n++
                    }
                    at = (if (ch == '0') zero else one)[at]
                }
                leaf[at] = c.run
            }
        }

        private fun grow() {
            val s = zero.size * 2
            zero = zero.copyOf(s).also { it.fill(-1, s / 2, s) }
            one = one.copyOf(s).also { it.fill(-1, s / 2, s) }
            leaf = leaf.copyOf(s).also { it.fill(-1, s / 2, s) }
        }
    }

    private val whiteTree = Tree(WHITE + EXTENDED)
    private val blackTree = Tree(BLACK + EXTENDED)

    /** bits out, the first in the bottom bit of each byte */
    private class BitWriter {
        val out = Bytes()
        private var cur = 0
        private var n = 0
        var count = 0L
            private set

        fun bit(b: Int) {
            if (b != 0) cur = cur or (1 shl n)
            n++
            count++
            if (n == 8) { out.write(cur); cur = 0; n = 0 }
        }

        fun bits(s: String) { for (c in s) bit(if (c == '1') 1 else 0) }

        fun bytes(): ByteArray {
            if (n > 0) { out.write(cur); cur = 0; n = 0 }
            return out.toByteArray()
        }
    }

    /**
     * [page] in MH: each row with its EOL at least [minBits] long (fill
     * zeros before the EOL: the receiver's minimum scan time at the
     * rate), RTC after the last
     */
    fun encode(page: FaxPage, minBits: Int = 0): ByteArray {
        val w = BitWriter()
        for (y in 0 until page.rows) {
            val start = w.count
            w.bits(EOL)
            var x = 0
            var black = false
            while (x < page.width) {
                var run = 0
                while (x + run < page.width && page.black(x + run, y) == black) run++
                runCode(w, run, black)
                x += run
                black = !black
            }
            // (a row's bits with its EOL, at least minBits: the fill before the next EOL)
            val used = (w.count - start).toInt()
            repeat((minBits - used).coerceAtLeast(0)) { w.bit(0) }
        }
        repeat(6) { w.bits(EOL) }
        return w.bytes()
    }

    private fun runCode(w: BitWriter, run: Int, black: Boolean) {
        val table = if (black) blackByRun else whiteByRun
        var left = run
        while (left >= 2560) { w.bits(table.getValue(2560).bits); left -= 2560 }
        if (left >= 64) { w.bits(table.getValue(left / 64 * 64).bits); left %= 64 }
        w.bits(table.getValue(left).bits)
    }

    /** what [decode] found: the page, and how many rows were damaged (copied from the one before) */
    class Decoded(val page: FaxPage, val badRows: Int)

    /**
     * MH page data (from the line, as [encode] has it) decoded: rows of
     * [width] until RTC or the data's end; a row with an error is the one
     * before it again (as fax machines show it)
     */
    fun decode(data: ByteArray, width: Int = FaxPage.WIDTH, fine: Boolean): Decoded {
        val rows = ArrayList<ByteArray>()
        var bad = 0
        val total = data.size * 8L
        var pos = 0L
        fun bit(): Int {
            if (pos >= total) return -1
            val b = (data[(pos / 8).toInt()].toInt() shr (pos % 8).toInt()) and 1
            pos++
            return b
        }
        /** to just after the next EOL (eleven zeros or more, then a one); false at the end */
        fun syncEol(): Boolean {
            var zeros = 0
            while (true) {
                val b = bit()
                if (b < 0) return false
                if (b == 0) zeros++ else if (zeros >= 11) return true else zeros = 0
            }
        }
        if (!syncEol()) return Decoded(FaxPage(width, 0, fine), 0)
        var emptyEols = 0
        val stride = width / 8
        while (true) {
            val rowStart = pos
            val row = ByteArray(stride)
            var x = 0
            var black = false
            var ok = true
            var eolNow = false
            // runs until the row is full
            while (x < width) {
                val run = readRun(if (black) blackTree else whiteTree, ::bit)
                if (run == EOL_SEEN) { eolNow = true; ok = x == 0; break }
                if (run < 0) { ok = false; break }
                if (x + run > width) { ok = false; break }
                if (black) for (i in x until x + run) row[i / 8] = (row[i / 8].toInt() or (0x80 shr (i % 8))).toByte()
                x += run
                if (run < 64) black = !black
            }
            if (eolNow && x == 0) {
                // an EOL with no row before it: RTC's (or an empty one)
                emptyEols++
                if (emptyEols >= 2) break
                continue
            }
            if (ok && x == width) {
                emptyEols = 0
                rows.add(row)
                if (!syncEol()) break
            } else {
                emptyEols = 0
                bad++
                rows.add(rows.lastOrNull()?.copyOf() ?: ByteArray(stride))
                pos = rowStart
                if (!syncEol()) break
            }
            if (rows.size > 20_000) break
        }
        val page = FaxPage(width, rows.size, fine)
        for ((y, r) in rows.withIndex()) r.copyInto(page.bits, y * stride, 0, stride)
        return Decoded(page, bad)
    }

    private const val EOL_SEEN = -2

    /**
     * a run's length (a make-up code's, or a terminating one's), EOL_SEEN
     * at an EOL, -1 for no code. No code starts with more than seven zeros
     * and an EOL with eleven: eight zeros, and it's an EOL if eleven or
     * more come before its one
     */
    private fun readRun(t: Tree, bit: () -> Int): Int {
        var at = 0
        var leading = 0
        var allZeros = true
        while (true) {
            val b = bit()
            if (b < 0) return -1
            if (b == 0 && allZeros) leading++ else allZeros = false
            if (allZeros && leading >= 8) {
                while (true) {
                    val c = bit()
                    if (c < 0) return -1
                    if (c == 1) return if (leading >= 11) EOL_SEEN else -1
                    leading++
                }
            }
            val next = if (b == 0) t.zero[at] else t.one[at]
            if (next < 0) return -1
            at = next
            if (t.leaf[at] >= 0) return t.leaf[at]
        }
    }
}
