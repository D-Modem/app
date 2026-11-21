// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.transfer

import org.dmodem.app.io.*

/**
 * Zstandard (RFC 8878), whole frames: [encode] makes one frame of a chunk
 * (single segment, its content size and checksum in it, no dictionary), as
 * ZMODEM with zstd sends a file (D-Modem's docs/zmodem-zstd.md); [decode]
 * takes any frame of up to its limit. In plain Kotlin, so that every
 * target (the JVM, iOS, Linux) has the same; a port of D-Modem's own codec
 * (link/zstd_codec.cpp), for whole frames: a hash chain over the chunk
 * with a lazy match, the repeat offsets, literals raw, RLE or Huffman
 * coded, each sequence table the predefined or a new one.
 */
object Zstd {
    const val MAGIC = 0xFD2FB528.toInt()
    private const val MAX_BLOCK = 128 * 1024
    private const val HUF_MAX = 11

    /** a frame that is not Zstandard, or that breaks one of [decode]'s limits */
    class FormatException(msg: String) : IOException(msg)

    // the index of v's highest set bit (-1 for 0)
    private fun hbit(v: Int): Int = 31 - v.countLeadingZeroBits()

    private val LL_BASE = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18,
        20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)
    private val LL_BITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2,
        3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
    private val ML_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
        21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 37, 39, 41, 43, 47, 51, 59, 67, 83,
        99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539)
    private val ML_BITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
    private val LL_DEF = intArrayOf(4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 3, 2, 1, 1, 1, 1, 1, -1, -1, -1, -1)
    private val ML_DEF = intArrayOf(1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1)
    private val OF_DEF = intArrayOf(1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, -1, -1, -1, -1, -1)

    // b[i] unsigned
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff

    // ------------------------------------------------------------------
    // bits

    /** a forward bit stream (FSE table descriptions), the lowest bits first */
    private class FwdBits(val p: ByteArray, val start: Int, val n: Int) {
        var pos = 0L
        var bad = false
        // the next nb bits, not taken (0 past the end)
        fun peek(nb: Int): Int {
            var v = 0
            for (k in 0 until nb) {
                val q = pos + k
                if (q < 8L * n) v = v or (((p[start + (q shr 3).toInt()].toInt() shr (q and 7).toInt()) and 1) shl k)
            }
            return v
        }
        // the next nb bits, taken; bad set past the end
        fun read(nb: Int): Int {
            val v = peek(nb)
            pos += nb
            if (pos > 8L * n) bad = true
            return v
        }
        // the bytes read so far, a partial last one counted
        fun bytes(): Int = ((pos + 7) / 8).toInt()
    }

    /** a backward bit stream (Huffman and FSE coded data): from under the last byte's marker toward the start */
    private class BackBits(val p: ByteArray, val start: Int, val n: Int) {
        var off = 0L
        // at the last byte's marker bit; false if there is none
        fun init(): Boolean {
            if (n <= 0 || p[start + n - 1].toInt() == 0) return false
            off = 8L * (n - 1) + hbit(u8(p, start + n - 1))
            return true
        }
        // the next nb bits, from the high end down (0 past the start)
        fun read(nb: Int): Long {
            var v = 0L
            off -= nb
            for (k in nb - 1 downTo 0) {
                val q = off + k
                v = (v shl 1) or (if (q >= 0) ((p[start + (q shr 3).toInt()].toInt() shr (q and 7).toInt()) and 1).toLong() else 0L)
            }
            return v
        }
        // whether more bits were read than there are
        fun over() = off < 0
    }

    /** a forward bit writer, the lowest bits first */
    private class BitW {
        val b = Bytes()
        var acc = 0
        var n = 0
        // the low nb bits of v
        fun put(v: Long, nb: Int) {
            for (k in 0 until nb) {
                acc = acc or (((v shr k) and 1L).toInt() shl n)
                if (++n == 8) {
                    b.write(acc)
                    acc = 0
                    n = 0
                }
            }
        }
        // the low nb bits of v (an Int)
        fun put(v: Int, nb: Int) = put(v.toLong() and 0xffffffffL, nb)
        // the end marker bit, and zeros to the byte's end
        fun close() {
            put(1, 1)
            if (n != 0) put(0, 8 - n)
        }
    }

    // ------------------------------------------------------------------
    // tables

    /** an FSE decoding table: each state's symbol, bits and baseline */
    private class Fse {
        var log = 0
        var sym = IntArray(0)
        var nb = IntArray(0)
        var base = IntArray(0)
    }

    /** a Huffman decoding table: by maxBits bits, the symbol and its length */
    private class Huf {
        var maxBits = 0
        var sym = IntArray(0)
        var nb = IntArray(0)
        val empty get() = sym.isEmpty()
    }

    /** an FSE table from the counts (-1: "less than 1") at accuracy log [al] (4.1.1) */
    private fun fseBuild(t: Fse, cnt: IntArray, nsym: Int, al: Int): Boolean {
        val size = 1 shl al
        var high = size - 1
        t.log = al
        t.sym = IntArray(size)
        t.nb = IntArray(size)
        t.base = IntArray(size)
        val next = IntArray(nsym)
        for (s in 0 until nsym) {
            if (cnt[s] == -1) {
                if (high < 0) return false
                t.sym[high--] = s
                next[s] = 1
            } else next[s] = if (cnt[s] > 0) cnt[s] else 0
        }
        val step = (size shr 1) + (size shr 3) + 3
        val mask = size - 1
        var pos = 0
        for (s in 0 until nsym) for (i in 0 until cnt[s]) {
            t.sym[pos] = s
            do pos = (pos + step) and mask while (pos > high)
        }
        if (pos != 0) return false
        for (i in 0 until size) {
            val d = next[t.sym[i]]++
            if (d <= 0) return false
            val nb = al - hbit(d)
            t.nb[i] = nb
            t.base[i] = (d shl nb) - size
        }
        return true
    }

    /** an FSE table description at p[at..at+n): the counts and accuracy log, and its bytes; null if bad */
    private fun fseDesc(p: ByteArray, at: Int, n: Int, maxAl: Int, maxSym: Int): Triple<IntArray, Int, Int>? {
        if (n < 1) return null
        val inb = FwdBits(p, at, n)
        val al = 5 + inb.read(4)
        if (al > maxAl) return null
        var remaining = 1 shl al
        var bits = al + 1
        val cnt = ArrayList<Int>()
        while (remaining > 0 && cnt.size < maxSym) {
            val lower = (1 shl (bits - 1)) - 1
            val thr = (1 shl bits) - 1 - (remaining + 1)
            var v: Int
            if ((inb.peek(bits - 1) and lower) < thr) {
                v = inb.read(bits - 1)
            } else {
                v = inb.read(bits)
                if (v > lower) v -= thr
            }
            val c = v - 1
            remaining -= if (c < 0) -c else c
            cnt.add(c)
            if (c == 0) {
                while (true) {
                    val rep = inb.read(2)
                    var k = 0
                    while (k < rep && cnt.size < maxSym) { cnt.add(0); k++ }
                    if (rep != 3) break
                }
            }
            if (remaining < 0) return null
            bits = hbit(remaining + 1) + 1
            if (inb.bad) return null
        }
        if (remaining != 0 || inb.bad) return null
        return Triple(cnt.toIntArray(), al, inb.bytes())
    }

    /** a Huffman table from the weights of symbols 0..n-1, the last's (n) implied (4.2.1) */
    private fun hufBuild(h: Huf, w: IntArray): Boolean {
        var sum = 0
        for (x in w) {
            if (x > HUF_MAX + 1) return false
            if (x != 0) sum += 1 shl (x - 1)
        }
        if (sum == 0 || w.size > 255) return false
        val mb = hbit(sum) + 1
        val left = (1 shl mb) - sum
        if (mb > HUF_MAX || (left and (left - 1)) != 0) return false
        val bits = IntArray(w.size + 1)
        for (s in w.indices) bits[s] = if (w[s] != 0) mb + 1 - w[s] else 0
        bits[w.size] = mb + 1 - (hbit(left) + 1)
        val rankCnt = IntArray(HUF_MAX + 2)
        val rankIdx = IntArray(HUF_MAX + 2)
        for (b in bits) if (b != 0) rankCnt[b]++
        rankIdx[mb] = 0
        for (i in mb downTo 1) rankIdx[i - 1] = rankIdx[i] + rankCnt[i] * (1 shl (mb - i))
        h.maxBits = mb
        h.sym = IntArray(1 shl mb)
        h.nb = IntArray(1 shl mb)
        for (s in bits.indices) {
            if (bits[s] == 0) continue
            val len = 1 shl (mb - bits[s])
            val at = rankIdx[bits[s]]
            if (at + len > h.sym.size) return false
            for (k in 0 until len) {
                h.sym[at + k] = s
                h.nb[at + k] = bits[s]
            }
            rankIdx[bits[s]] += len
        }
        return true
    }

    /** a Huffman tree description at p[at..at+n): its bytes, or -1 */
    private fun hufDesc(p: ByteArray, at: Int, n: Int, h: Huf): Int {
        if (n < 1) return -1
        val w = ArrayList<Int>()
        val used: Int
        val h0 = u8(p, at)
        if (h0 >= 128) {
            val nw = h0 - 127
            used = 1 + (nw + 1) / 2
            if (used > n) return -1
            for (i in 0 until nw) {
                val b = u8(p, at + 1 + i / 2)
                w.add(if (i and 1 != 0) b and 15 else b shr 4)
            }
        } else {
            val cs = h0
            used = 1 + cs
            if (used > n || cs == 0) return -1
            val (cnt, al, d) = fseDesc(p, at + 1, cs, 6, 255) ?: return -1
            if (d >= cs) return -1
            val t = Fse()
            if (!fseBuild(t, cnt, cnt.size, al)) return -1
            val inb = BackBits(p, at + 1 + d, cs - d)
            if (!inb.init()) return -1
            var s1 = inb.read(al).toInt()
            var s2 = inb.read(al).toInt()
            while (true) {
                if (w.size > 254) return -1
                w.add(t.sym[s1])
                s1 = t.base[s1] + inb.read(t.nb[s1]).toInt()
                if (inb.over()) { w.add(t.sym[s2]); break }
                w.add(t.sym[s2])
                s2 = t.base[s2] + inb.read(t.nb[s2]).toInt()
                if (inb.over()) { w.add(t.sym[s1]); break }
            }
        }
        return if (hufBuild(h, w.toIntArray())) used else -1
    }

    /** a Huffman coded stream into out: [count] symbols; false if bad */
    private fun hufStream(h: Huf, p: ByteArray, at: Int, n: Int, count: Int, out: Bytes): Boolean {
        val inb = BackBits(p, at, n)
        if (!inb.init()) return false
        val mask = (1 shl h.maxBits) - 1
        var st = inb.read(h.maxBits).toInt()
        for (k in 0 until count) {
            out.write(h.sym[st])
            val nb = h.nb[st]
            st = ((st shl nb) and mask) or inb.read(nb).toInt()
        }
        return inb.off + h.maxBits == 0L
    }

    // the code of length v: the last base not over it
    private fun lenCode(v: Int, base: IntArray): Int {
        var c = 0
        while (c + 1 < base.size && base[c + 1] <= v) c++
        return c
    }

    /** the repeat offsets after a sequence's offset value (3.2.2.3): its offset */
    private fun applyOffset(rep: IntArray, ov: Int, ll: Int): Int {
        if (ov > 3) {
            rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = ov - 3
            return rep[0]
        }
        val idx = ov - 1 + (if (ll == 0) 1 else 0)
        if (idx == 0) return rep[0]
        val off = if (idx == 3) rep[0] - 1 else rep[idx]
        if (idx > 1) rep[2] = rep[1]
        rep[1] = rep[0]
        rep[0] = off
        return off
    }

    // ------------------------------------------------------------------
    // the encoder's tables

    /** an FSE encoder: each symbol's states in order */
    private class FseEnc(c: IntArray, val al: Int) {
        val cnt = c.copyOf()
        val states: Array<IntArray>
        init {
            val t = Fse()
            fseBuild(t, c, c.size, al)
            val lists = Array(c.size) { ArrayList<Int>() }
            for (i in 0 until (1 shl al)) lists[t.sym[i]].add(i)
            states = Array(c.size) { lists[it].toIntArray() }
        }
        // a symbol's first state, where the stream starts
        fun start(s: Int) = states[s][0]
        // the state after symbol s from st: its low bits written
        fun step(st: Int, s: Int, w: BitW): Int {
            val c = if (cnt[s] < 0) 1 else cnt[s]
            val x = st + (1 shl al)
            var nb = hbit(x) - hbit(c)
            if ((x shr nb) < c) nb--
            val d = x shr nb
            w.put(x and ((1 shl nb) - 1), nb)
            return states[s][d - c]
        }
    }

    /** Huffman code lengths of the counts, none over HUF_MAX; null with fewer than two symbols */
    private fun hufLengths(freq: IntArray): IntArray? {
        val f = freq.copyOf()
        while (true) {
            val w = ArrayList<Long>()
            val l = ArrayList<Int>()
            val r = ArrayList<Int>()
            val live = ArrayList<Int>()
            for (s in 0 until 256) if (f[s] != 0) {
                w.add(f[s].toLong()); l.add(-1); r.add(s)
                live.add(w.size - 1)
            }
            if (live.size < 2) return null
            // the two lightest (the earlier on a tie, as the heap of the C++ takes them)
            fun lighter(a: Int, b: Int) = if (w[a] != w[b]) w[a] < w[b] else a < b
            while (live.size > 1) {
                var ia = 0
                for (k in 1 until live.size) if (lighter(live[k], live[ia])) ia = k
                val a = live.removeAt(ia)
                var ib = 0
                for (k in 1 until live.size) if (lighter(live[k], live[ib])) ib = k
                val b = live.removeAt(ib)
                w.add(w[a] + w[b]); l.add(a); r.add(b)
                live.add(w.size - 1)
            }
            val bits = IntArray(256)
            var mx = 0
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(live[0] to 0)
            while (stack.isNotEmpty()) {
                val (i, d) = stack.removeLast()
                if (l[i] < 0) {
                    bits[r[i]] = d
                    if (d > mx) mx = d
                } else {
                    stack.addLast(l[i] to d + 1)
                    stack.addLast(r[i] to d + 1)
                }
            }
            if (mx <= HUF_MAX) return bits
            for (s in 0 until 256) if (f[s] != 0) f[s] = (f[s] + 1) / 2
        }
    }

    /** the codes of the lengths, as the decoder's table has them */
    private fun hufCodes(bits: IntArray): IntArray {
        var mb = 0
        for (b in bits) if (b > mb) mb = b
        val rankCnt = IntArray(HUF_MAX + 2)
        val rankIdx = IntArray(HUF_MAX + 2)
        for (b in bits) if (b != 0) rankCnt[b]++
        rankIdx[mb] = 0
        for (i in mb downTo 1) rankIdx[i - 1] = rankIdx[i] + rankCnt[i] * (1 shl (mb - i))
        val code = IntArray(256)
        for (s in 0 until 256) {
            if (bits[s] == 0) continue
            code[s] = rankIdx[bits[s]] shr (mb - bits[s])
            rankIdx[bits[s]] += 1 shl (mb - bits[s])
        }
        return code
    }

    // lit[from..to) Huffman coded, backward, as one stream
    private fun hufEncode(lit: ByteArray, from: Int, to: Int, bits: IntArray, code: IntArray): ByteArray {
        val w = BitW()
        for (k in to - 1 downTo from) {
            val c = lit[k].toInt() and 0xff
            w.put(code[c], bits[c])
        }
        w.close()
        return w.b.toByteArray()
    }

    // a literals section header: its type, sizes and streams
    private fun litHeader(o: Bytes, type: Int, regen: Int, comp: Int, oneStream: Boolean) {
        if (type < 2) {
            when {
                regen < 32 -> o.write(type or (regen shl 3))
                regen < 4096 -> { o.write(type or (1 shl 2) or ((regen and 15) shl 4)); o.write((regen shr 4) and 0xff) }
                else -> {
                    o.write(type or (3 shl 2) or ((regen and 15) shl 4))
                    o.write((regen shr 4) and 0xff); o.write((regen shr 12) and 0xff)
                }
            }
            return
        }
        val (sf, nb, hs) = when {
            oneStream -> Triple(0, 10, 3)
            regen < 1024 && comp < 1024 -> Triple(1, 10, 3)
            regen < 16384 && comp < 16384 -> Triple(2, 14, 4)
            else -> Triple(3, 18, 5)
        }
        val h = type.toLong() or (sf.toLong() shl 2) or (regen.toLong() shl 4) or (comp.toLong() shl (4 + nb))
        for (k in 0 until hs) o.write(((h shr (8 * k)) and 0xff).toInt())
    }

    /** the literals coded (one stream under 1024, else four and the jump table); null if too large */
    private fun hufSection(lit: ByteArray, bits: IntArray): ByteArray? {
        val code = hufCodes(bits)
        val n = lit.size
        if (n < 1024) return hufEncode(lit, 0, n, bits, code)
        val seg = (n + 3) / 4
        val s = Array(4) { k -> hufEncode(lit, minOf(n, k * seg), minOf(n, (k + 1) * seg), bits, code) }
        val o = Bytes()
        for (k in 0 until 3) {
            if (s[k].size > 65535) return null
            o.write(s[k].size and 0xff); o.write(s[k].size shr 8)
        }
        for (x in s) o.write(x)
        return o.toByteArray()
    }

    // counts f made an FSE table's of accuracy log al: summing to 1 shl al, none of a used symbol 0
    private fun fseNormalize(f: IntArray, nsym: Int, al: Int): IntArray {
        var total = 0L
        for (s in 0 until nsym) total += f[s]
        val size = 1 shl al
        var sum = 0
        var big = 0
        val c = IntArray(nsym)
        for (s in 0 until nsym) {
            if (f[s] == 0) continue
            val v = (f[s].toLong() * size + total / 2) / total
            c[s] = maxOf(1L, v).toInt()
            sum += c[s]
            if (c[s] > c[big]) big = s
        }
        c[big] += size - sum
        while (c[big] < 1) {
            var need = 1 - c[big]
            c[big] = 1
            for (s in 0 until nsym) {
                while (need > 0 && s != big && c[s] > 1) { c[s]--; need-- }
                if (need == 0) break
            }
        }
        return c
    }

    // an FSE table description of counts c (4.1.1), into o
    private fun fseDescWrite(c: IntArray, al: Int, o: Bytes) {
        val w = BitW()
        w.put(al - 5, 4)
        var remaining = 1 shl al
        val n = c.size
        var s = 0
        while (s < n && remaining > 0) {
            val bits = hbit(remaining + 1) + 1
            val lower = (1 shl (bits - 1)) - 1
            val thr = (1 shl bits) - 1 - (remaining + 1)
            val v = c[s] + 1
            when {
                v < thr -> w.put(v, bits - 1)
                v <= lower -> w.put(v, bits)
                else -> w.put(v + thr, bits)
            }
            remaining -= if (c[s] < 0) -c[s] else c[s]
            s++
            if (c[s - 1] == 0) {
                var z = 0
                while (s + z < n && c[s + z] == 0) z++
                s += z
                while (z >= 3) { w.put(3, 2); z -= 3 }
                w.put(z, 2)
            }
        }
        if (w.n != 0) w.put(0, 8 - w.n)
        o.write(w.b.toByteArray())
    }

    // the bits sym costs with the table of counts cnt; -1 if one can't be coded
    private fun fseCost(cnt: IntArray, al: Int, sym: IntArray): Double {
        var bits = 0.0
        for (x in sym) {
            if (x >= cnt.size || cnt[x] == 0) return -1.0
            val c = if (cnt[x] < 0) 1 else cnt[x]
            val hb = hbit(c)
            bits += al - (hb + (c - (1 shl hb)).toDouble() / (1 shl hb))
        }
        return bits
    }

    // ------------------------------------------------------------------
    // the encoder

    /** [data] as one Zstandard frame: single segment, its size and checksum in it */
    fun encode(data: ByteArray): ByteArray = Encoder(data).frame()

    private class Encoder(val hist: ByteArray) {
        val n = hist.size
        val window = maxOf(n, 1)
        val rep = intArrayOf(1, 4, 8)
        val head = IntArray(1 shl 14)
        val chain = IntArray(n)
        var ins = 0
        var encBits: IntArray? = null
        val out = Bytes()

        // the hash of the 3 bytes at i (14 bits)
        fun hash3(i: Int): Int =
            (((u8(hist, i) shl 16) or (u8(hist, i + 1) shl 8) or u8(hist, i + 2)) * -1640531535) ushr 18

        // position i put in the hash chain
        fun insert(i: Int) {
            if (i + 2 >= n) return
            val h = hash3(i)
            chain[i] = head[h]
            head[h] = i + 1
        }

        var matchOff = 0

        // the longest match at i before end (matchOff its offset); 0 if under 3
        fun bestMatch(i: Int, end: Int): Int {
            val lim = minOf(end - i, 65536)
            var best = 0
            // the match length at i with offset d
            fun lenAt(d: Int): Int {
                var l = 0
                while (l < lim && hist[i - d + l] == hist[i + l]) l++
                return l
            }
            for (r in 0 until 3) {
                val d = rep[r]
                if (d <= 0 || d > i || d > window) continue
                val l = lenAt(d)
                if (l > best) { best = l; matchOff = d }
            }
            val repBest = best
            if (lim >= 3) {
                var c = head[hash3(i)]
                var depth = 48
                while (c != 0 && depth-- > 0) {
                    val cand = c - 1
                    val d = i - cand
                    if (d > window || d <= 0) break
                    val l = lenAt(d)
                    if (l > best && (l > repBest + 1 || best < 3) && (l > 3 || d <= 1024)) {
                        best = l; matchOff = d
                    }
                    if (best == lim) break
                    c = chain[cand]
                }
            }
            return if (best >= 3) best else 0
        }

        // the whole frame: header, blocks and checksum
        fun frame(): ByteArray {
            // the header: single segment, the checksum, the content size
            val fcsFlag = when {
                n < 256 -> 0
                n < 65536 + 256 -> 1
                else -> 2
            }
            for (b in intArrayOf(0x28, 0xB5, 0x2F, 0xFD)) out.write(b)
            out.write((fcsFlag shl 6) or 0x20 or 0x04)
            when (fcsFlag) {
                0 -> out.write(n)
                1 -> { out.write((n - 256) and 0xff); out.write((n - 256) shr 8) }
                else -> for (k in 0 until 4) out.write((n shr (8 * k)) and 0xff)
            }
            if (n == 0) putBlock(ByteArray(0), 0, 0, true)
            var at = 0
            while (at < n) {
                val len = minOf(MAX_BLOCK, n - at)
                block(at, at + len, at + len == n)
                at += len
            }
            val sum = XxHash64.hash(hist, 0, n)
            for (k in 0 until 4) out.write(((sum shr (8 * k)) and 0xff).toInt())
            return out.toByteArray()
        }

        // a block header and its body
        fun putBlock(body: ByteArray, type: Int, size: Int, last: Boolean) {
            val h = (if (last) 1 else 0) or (type shl 1) or (size shl 3)
            out.write(h and 0xff); out.write((h shr 8) and 0xff); out.write((h shr 16) and 0xff)
            out.write(body)
        }

        // hist[start..end) as one block: compressed, RLE or raw, whichever is smallest
        fun block(start: Int, end: Int, last: Boolean) {
            val nb = end - start
            val repSaved = rep.copyOf()
            val seqLl = ArrayList<Int>()
            val seqOv = ArrayList<Int>()
            val seqMl = ArrayList<Int>()
            val lit = Bytes()
            var i = start
            var litAt = start
            // the hash chain up to upto
            fun catchUp(upto: Int) { while (ins < upto) insert(ins++) }
            if (ins < start) ins = start
            while (i + 3 <= end) {
                catchUp(i)
                var len = bestMatch(i, end)
                val off = matchOff
                if (len != 0 && i + 1 + 3 <= end) {
                    catchUp(i + 1)
                    val len2 = bestMatch(i + 1, end)
                    if (len2 > len + 1) { i++; continue }
                }
                if (len == 0) { i++; continue }
                val ll = i - litAt
                val ov = if (ll != 0) {
                    when (off) { rep[0] -> 1; rep[1] -> 2; rep[2] -> 3; else -> off + 3 }
                } else {
                    when (off) { rep[1] -> 1; rep[2] -> 2; rep[0] - 1 -> 3; else -> off + 3 }
                }
                applyOffset(rep, ov, ll)
                lit.write(hist, litAt, i - litAt)
                seqLl.add(ll); seqOv.add(ov); seqMl.add(len)
                i += len
                litAt = i
            }
            lit.write(hist, litAt, end - litAt)
            catchUp(maxOf(ins, end - 2))
            val litB = lit.toByteArray()

            // the literals: raw, RLE, Huffman with a new table or the last
            val nl = litB.size
            val freq = IntArray(256)
            for (c in litB) freq[c.toInt() and 0xff]++
            var kinds = 0
            var lastSym = 0
            for (s in 0 until 256) if (freq[s] != 0) { kinds++; lastSym = s }
            var bestLit = Bytes().also { litHeader(it, 0, nl, 0, false); it.write(litB) }.toByteArray()
            var newBits: IntArray? = null
            if (kinds == 1 && nl > 1) {
                val r = Bytes().also { litHeader(it, 1, nl, 0, false); it.write(litB[0].toInt() and 0xff) }.toByteArray()
                if (r.size < bestLit.size) bestLit = r
            } else if (kinds > 1 && nl >= 16) {
                val bits = if (lastSym <= 128) hufLengths(freq) else null
                if (bits != null) {
                    var mb = 0
                    for (b in bits) if (b > mb) mb = b
                    val desc = Bytes()
                    desc.write(127 + lastSym)
                    var s = 0
                    while (s < lastSym) {
                        val w0 = if (bits[s] != 0) mb + 1 - bits[s] else 0
                        val w1 = if (s + 1 < lastSym && bits[s + 1] != 0) mb + 1 - bits[s + 1] else 0
                        desc.write((w0 shl 4) or w1)
                        s += 2
                    }
                    val st = hufSection(litB, bits)
                    if (st != null) {
                        val d = desc.toByteArray()
                        val comp = d.size + st.size
                        if (!(nl < 1024 && comp >= 1024)) {
                            val r = Bytes()
                            litHeader(r, 2, nl, comp, nl < 1024)
                            r.write(d); r.write(st)
                            if (r.size < bestLit.size) { bestLit = r.toByteArray(); newBits = bits }
                        }
                    }
                }
                val eb = encBits
                if (eb != null && (0 until 256).all { freq[it] == 0 || eb[it] != 0 }) {
                    val st = hufSection(litB, eb)
                    if (st != null && !(nl < 1024 && st.size >= 1024)) {
                        val r = Bytes()
                        litHeader(r, 3, nl, st.size, nl < 1024)
                        r.write(st)
                        if (r.size < bestLit.size) { bestLit = r.toByteArray(); newBits = null }
                    }
                }
            }
            val body = Bytes()
            body.write(bestLit)

            // the sequences
            val ns = seqLl.size
            when {
                ns < 128 -> body.write(ns)
                ns < 0x7F00 -> { body.write((ns shr 8) + 128); body.write(ns and 0xff) }
                else -> { body.write(255); body.write((ns - 0x7F00) and 0xff); body.write((ns - 0x7F00) shr 8) }
            }
            if (ns != 0) {
                val llc = IntArray(ns) { lenCode(seqLl[it], LL_BASE) }
                val mlc = IntArray(ns) { lenCode(seqMl[it], ML_BASE) }
                val ofc = IntArray(ns) { hbit(seqOv[it]) }
                val codes = arrayOf(llc, ofc, mlc)
                val defs = arrayOf(LL_DEF, OF_DEF, ML_DEF)
                val dal = intArrayOf(6, 5, 6)
                val maxAl = intArrayOf(9, 8, 9)
                val enc = arrayOfNulls<FseEnc>(3)
                var modes = 0
                val descs = Bytes()
                for (t in 0 until 3) {
                    val cp = fseCost(defs[t], dal[t], codes[t])
                    val f = IntArray(53)
                    var nsym = 0
                    var k2 = 0
                    for (x in codes[t]) {
                        if (f[x]++ == 0) k2++
                        if (x + 1 > nsym) nsym = x + 1
                    }
                    val al = minOf(maxAl[t], maxOf(5, hbit(ns) - 1, hbit(k2) + 1))
                    val nc = fseNormalize(f, nsym, al)
                    val d = Bytes().also { fseDescWrite(nc, al, it) }.toByteArray()
                    val cn = 8.0 * d.size + fseCost(nc, al, codes[t])
                    if (cp >= 0 && cp <= cn) {
                        enc[t] = FseEnc(defs[t], dal[t])
                    } else {
                        modes = modes or (2 shl (6 - 2 * t))
                        descs.write(d)
                        enc[t] = FseEnc(nc, al)
                    }
                }
                body.write(modes)
                body.write(descs.toByteArray())
                val fl = enc[0]!!
                val fo = enc[1]!!
                val fm = enc[2]!!
                val w = BitW()
                // sequence k's extra bits: literal length, match length, offset
                fun extras(k: Int) {
                    w.put(seqLl[k] - LL_BASE[llc[k]], LL_BITS[llc[k]])
                    w.put(seqMl[k] - ML_BASE[mlc[k]], ML_BITS[mlc[k]])
                    w.put(seqOv[k] - (1 shl ofc[k]), ofc[k])
                }
                var sl = fl.start(llc[ns - 1])
                var sm = fm.start(mlc[ns - 1])
                var so = fo.start(ofc[ns - 1])
                extras(ns - 1)
                for (k in ns - 2 downTo 0) {
                    so = fo.step(so, ofc[k], w)
                    sm = fm.step(sm, mlc[k], w)
                    sl = fl.step(sl, llc[k], w)
                    extras(k)
                }
                w.put(sm, fm.al)
                w.put(so, fo.al)
                w.put(sl, fl.al)
                w.close()
                body.write(w.b.toByteArray())
            }

            val first = hist[start]
            var same = true
            for (k in start until end) if (hist[k] != first) { same = false; break }
            var coded = false
            when {
                same && nb > 1 -> putBlock(byteArrayOf(first), 1, nb, last)
                body.size < nb -> {
                    putBlock(body.toByteArray(), 2, body.size, last)
                    if (newBits != null) encBits = newBits
                    coded = true
                }
                else -> putBlock(hist.copyOfRange(start, end), 0, nb, last)
            }
            // (the decoder saw no sequences of a block not coded: its offsets as before)
            if (!coded) repSaved.copyInto(rep)
        }
    }

    // ------------------------------------------------------------------
    // the decoder

    /**
     * The content of the frame [frame] (all of it, no more): its checksum
     * looked at, if it has one. Refused (FormatException): not a frame,
     * a dictionary, a window or content over [maxSize], or bad data.
     */
    fun decode(frame: ByteArray, maxSize: Int): ByteArray = Decoder(frame, maxSize).run()

    private class Decoder(val p: ByteArray, val maxSize: Int) {
        val out = Bytes()
        var hist = ByteArray(1024)
        var hn = 0
        val rep = intArrayOf(1, 4, 8)
        val huf = Huf()
        val ll = Fse()
        val of = Fse()
        val ml = Fse()
        val have = BooleanArray(3)
        var window = 0

        // refused, with why
        fun bad(why: String): Nothing = throw FormatException("zstd: $why")

        // one byte of content
        fun emit(c: Byte) {
            if (hn >= maxSize) bad("more than $maxSize bytes")
            if (hn == hist.size) hist = hist.copyOf(minOf(maxSize, hist.size * 2))
            hist[hn++] = c
        }

        // the frame decoded: its content
        fun run(): ByteArray {
            val n = p.size
            if (n < 6) bad("too short")
            val magic = u8(p, 0) or (u8(p, 1) shl 8) or (u8(p, 2) shl 16) or (u8(p, 3) shl 24)
            if (magic != MAGIC) bad("not a frame")
            val fhd = u8(p, 4)
            val single = fhd and 0x20 != 0
            if (fhd and 0x08 != 0) bad("a reserved bit")
            if (fhd and 3 != 0) bad("a dictionary")
            val checksum = fhd and 0x04 != 0
            val fcsFlag = fhd shr 6
            val fcs = if (fcsFlag == 0) (if (single) 1 else 0) else 1 shl fcsFlag
            var at = 5
            var win = 0L
            if (!single) {
                val wd = u8(p, at++)
                val wb = 1L shl (10 + (wd shr 3))
                win = wb + wb / 8 * (wd and 7)
            }
            var size = -1L
            if (fcs != 0) {
                if (at + fcs > n) bad("too short")
                var v = 0L
                for (k in 0 until fcs) v = v or (u8(p, at + k).toLong() shl (8 * k))
                if (fcs == 2) v += 256
                size = v
                if (single) win = v
                at += fcs
            }
            if (win > maxSize || size > maxSize) bad("over $maxSize bytes")
            window = maxOf(win, 1L).toInt()
            while (true) {
                if (at + 3 > n) bad("too short")
                val h = u8(p, at) or (u8(p, at + 1) shl 8) or (u8(p, at + 2) shl 16)
                at += 3
                val last = h and 1 != 0
                val type = (h shr 1) and 3
                val bs = h shr 3
                if (type == 3 || bs > MAX_BLOCK) bad("a bad block")
                when (type) {
                    0 -> { if (at + bs > n) bad("too short"); for (k in 0 until bs) emit(p[at + k]); at += bs }
                    1 -> { if (at + 1 > n) bad("too short"); for (k in 0 until bs) emit(p[at]); at += 1 }
                    else -> {
                        if (at + bs > n) bad("too short")
                        val lit = Bytes()
                        val l = literals(at, bs, lit)
                        sequences(at + l, bs - l, lit.toByteArray())
                        at += bs
                    }
                }
                if (last) break
            }
            if (checksum) {
                if (at + 4 > n) bad("too short")
                val sum = XxHash64.hash(hist, 0, hn)
                for (k in 0 until 4) if (u8(p, at + k) != ((sum shr (8 * k)) and 0xff).toInt()) bad("a bad checksum")
                at += 4
            }
            if (at != n) bad("bytes after the frame")
            if (size >= 0 && size != hn.toLong()) bad("the wrong size")
            return hist.copyOf(hn)
        }

        // the literals section at p[at..at+n) into lit: its bytes
        fun literals(at: Int, n: Int, lit: Bytes): Int {
            if (n < 1) bad("no literals")
            val b0 = u8(p, at)
            val type = b0 and 3
            val sf = (b0 shr 2) and 3
            if (type < 2) {
                val regen: Int
                val hs: Int
                when {
                    sf and 1 == 0 -> { regen = b0 shr 3; hs = 1 }
                    sf == 1 -> { if (n < 2) bad("literals"); regen = (b0 shr 4) or (u8(p, at + 1) shl 4); hs = 2 }
                    else -> {
                        if (n < 3) bad("literals")
                        regen = (b0 shr 4) or (u8(p, at + 1) shl 4) or (u8(p, at + 2) shl 12); hs = 3
                    }
                }
                if (regen > MAX_BLOCK) bad("literals")
                if (type == 0) {
                    if (hs + regen > n) bad("literals")
                    lit.write(p, at + hs, regen)
                    return hs + regen
                }
                if (hs + 1 > n) bad("literals")
                val c = u8(p, at + hs)
                for (k in 0 until regen) lit.write(c)
                return hs + 1
            }
            val streams = if (sf == 0) 1 else 4
            val hs = if (sf < 2) 3 else if (sf == 2) 4 else 5
            val nb = if (sf < 2) 10 else if (sf == 2) 14 else 18
            if (n < hs) bad("literals")
            var h = 0L
            for (k in 0 until hs) h = h or (u8(p, at + k).toLong() shl (8 * k))
            val regen = ((h shr 4) and ((1L shl nb) - 1)).toInt()
            val comp = ((h shr (4 + nb)) and ((1L shl nb) - 1)).toInt()
            if (hs + comp > n || regen > MAX_BLOCK) bad("literals")
            var q = at + hs
            var left = comp
            if (type == 2) {
                val d = hufDesc(p, q, left, huf)
                if (d < 0) bad("a Huffman table")
                q += d
                left -= d
            } else if (huf.empty) bad("no Huffman table")
            if (streams == 1) {
                if (!hufStream(huf, p, q, left, regen, lit)) bad("Huffman data")
            } else {
                if (left < 6) bad("literals")
                val sz = IntArray(4)
                sz[0] = u8(p, q) or (u8(p, q + 1) shl 8)
                sz[1] = u8(p, q + 2) or (u8(p, q + 3) shl 8)
                sz[2] = u8(p, q + 4) or (u8(p, q + 5) shl 8)
                sz[3] = left - 6 - sz[0] - sz[1] - sz[2]
                if (sz[3] < 0) bad("literals")
                val seg = (regen + 3) / 4
                q += 6
                for (k in 0 until 4) {
                    val cnt = if (k < 3) seg else regen - 3 * seg
                    if (cnt < 0 || !hufStream(huf, p, q, sz[k], cnt, lit)) bad("Huffman data")
                    q += sz[k]
                }
            }
            return hs + comp
        }

        // the sequences section at p[start..start+n), with lit, into the content
        fun sequences(start: Int, n: Int, lit: ByteArray) {
            if (n < 1) bad("no sequences")
            var at = start
            val b0 = u8(p, at)
            val ns: Int
            when {
                b0 < 128 -> { ns = b0; at += 1 }
                b0 < 255 -> { if (n < 2) bad("sequences"); ns = ((b0 - 128) shl 8) + u8(p, at + 1); at += 2 }
                else -> { if (n < 3) bad("sequences"); ns = u8(p, at + 1) + (u8(p, at + 2) shl 8) + 0x7F00; at += 3 }
            }
            val end = start + n
            var li = 0
            if (ns != 0) {
                if (at >= end) bad("sequences")
                val modes = u8(p, at++)
                if (modes and 3 != 0) bad("sequences")
                val tabs = arrayOf(ll, of, ml)
                val defs = arrayOf(LL_DEF, OF_DEF, ML_DEF)
                val nsyms = intArrayOf(36, 32, 53)
                val defAl = intArrayOf(6, 5, 6)
                val maxAl = intArrayOf(9, 8, 9)
                for (t in 0 until 3) {
                    when ((modes shr (6 - 2 * t)) and 3) {
                        0 -> fseBuild(tabs[t], defs[t], defs[t].size, defAl[t])
                        1 -> {
                            if (at >= end || u8(p, at) >= nsyms[t]) bad("sequences")
                            tabs[t].log = 0
                            tabs[t].sym = intArrayOf(u8(p, at++))
                            tabs[t].nb = intArrayOf(0)
                            tabs[t].base = intArrayOf(0)
                        }
                        2 -> {
                            val (cnt, al, d) = fseDesc(p, at, end - at, maxAl[t], nsyms[t]) ?: bad("an FSE table")
                            if (!fseBuild(tabs[t], cnt, cnt.size, al)) bad("an FSE table")
                            at += d
                        }
                        3 -> if (!have[t]) bad("no FSE table to repeat")
                    }
                    have[t] = true
                }
                val inb = BackBits(p, at, end - at)
                if (!inb.init()) bad("sequences")
                var sl = inb.read(ll.log).toInt()
                var so = inb.read(of.log).toInt()
                var sm = inb.read(ml.log).toInt()
                for (k in 0 until ns) {
                    val llc = ll.sym[sl]
                    val ofc = of.sym[so]
                    val mlc = ml.sym[sm]
                    if (llc > 35 || mlc > 52 || ofc > 31) bad("sequences")
                    val ov = ((1L shl ofc) + inb.read(ofc)).toInt()
                    val mlen = ML_BASE[mlc] + inb.read(ML_BITS[mlc]).toInt()
                    val llen = LL_BASE[llc] + inb.read(LL_BITS[llc]).toInt()
                    if (k + 1 < ns) {
                        sl = ll.base[sl] + inb.read(ll.nb[sl]).toInt()
                        sm = ml.base[sm] + inb.read(ml.nb[sm]).toInt()
                        so = of.base[so] + inb.read(of.nb[so]).toInt()
                    }
                    if (inb.over() || li + llen > lit.size) bad("sequences")
                    for (j in 0 until llen) emit(lit[li++])
                    val off = applyOffset(rep, ov, llen)
                    if (off <= 0 || off > hn || off > window) bad("an offset")
                    val from = hn - off
                    for (j in 0 until mlen) emit(hist[from + j])
                }
            }
            while (li < lit.size) emit(lit[li++])
        }
    }
}

/** XXH64 (seed 0), for Zstandard's content checksum */
object XxHash64 {
    private const val P1 = -7046029288634856825L // 0x9E3779B185EBCA87
    private const val P2 = -4417276706812531889L // 0xC2B2AE3D27D4EB4F
    private const val P3 = 1609587929392839161L  // 0x165667B19E3779F9
    private const val P4 = -8796714831421723037L // 0x85EBCA77C2B2AE63
    private const val P5 = 2870177450012600261L  // 0x27D4EB2F165667C5

    // 8 bytes at i, little-endian
    private fun le64(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k))
        return v
    }

    // 4 bytes at i, little-endian
    private fun le32(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k))
        return v
    }

    // one XXH64 round
    private fun round(acc: Long, input: Long): Long = (acc + input * P2).rotateLeft(31) * P1

    // an accumulator merged into the hash
    private fun merge(acc: Long, v: Long): Long = (acc xor round(0, v)) * P1 + P4

    /** XXH64 of b[off..off+len) */
    fun hash(b: ByteArray, off: Int, len: Int): Long {
        var i = off
        val end = off + len
        var h: Long
        if (len >= 32) {
            var v1 = P1 + P2
            var v2 = P2
            var v3 = 0L
            var v4 = -P1
            while (i + 32 <= end) {
                v1 = round(v1, le64(b, i)); v2 = round(v2, le64(b, i + 8))
                v3 = round(v3, le64(b, i + 16)); v4 = round(v4, le64(b, i + 24))
                i += 32
            }
            h = v1.rotateLeft(1) + v2.rotateLeft(7) + v3.rotateLeft(12) + v4.rotateLeft(18)
            h = merge(h, v1); h = merge(h, v2); h = merge(h, v3); h = merge(h, v4)
        } else {
            h = P5
        }
        h += len.toLong()
        while (i + 8 <= end) {
            h = (h xor round(0, le64(b, i))).rotateLeft(27) * P1 + P4
            i += 8
        }
        if (i + 4 <= end) {
            h = (h xor (le32(b, i) * P1)).rotateLeft(23) * P2 + P3
            i += 4
        }
        while (i < end) {
            h = (h xor ((b[i].toLong() and 0xff) * P5)).rotateLeft(11) * P1
            i++
        }
        h = (h xor (h ushr 33)) * P2
        h = (h xor (h ushr 29)) * P3
        return h xor (h ushr 32)
    }
}
