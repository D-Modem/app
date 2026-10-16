// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.transfer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.TimeZone

/**
 * Kermit, both ways, stop-and-wait: packets of printable characters (so it
 * goes over lines nothing else does), control characters prefixed, the
 * 8th bit prefixed when the line is 7-bit, runs of a byte counted; long
 * packets, 1-, 2- and 3-character (CRC) checks, file attributes (size and
 * time), as both sides agree in the S/Y exchange. A bad or missing packet
 * is asked for again (NAK), up to [MAX_RETRIES] times; an E packet ends it.
 */
class Kermit(
    private val io: TransferIo,
    private val progress: (Progress) -> Unit = {},
    /** how long to wait for each packet */
    private val timeoutMs: Long = 10_000,
    /** the line passes only 7 bits: the 8th asked to be prefixed */
    private val sevenBit: Boolean = false,
) {
    private var p = Progress()

    private fun report(f: (Progress) -> Progress) {
        p = f(p)
        progress(p)
    }

    // what's agreed: the defaults, until the S/Y exchange
    /** the longest packet the other side takes (its LEN, or its long packets' length) */
    private var sendMax = 80
    private var sendLong = 0
    private var eol = CR
    private var npad = 0
    private var padc = 0
    /** the other side's control prefix: what it sends with */
    private var theirQctl = '#'.code
    private var qbin = 0
    private var rept = 0
    private var check = 1
    private var attributes = false
    /** what the next packet goes out with: an E packet sent on a cancel uses it */
    private var seq = 0

    class Packet(val seq: Int, val type: Char, val data: ByteArray) {
        override fun toString() = "Packet($type $seq, ${data.size})"
    }

    // ------------------------------------------------------------------
    // packets

    private fun checkLen(type: Char) = if (type == 'S' || type == 'I' || type == 'R') 1 else if (forceCheck1) 1 else check

    /** (during the S/Y exchange, both sides on type 1) */
    private var forceCheck1 = true

    private fun sendPacket(seq: Int, type: Char, data: ByteArray) {
        val ct = checkLen(type)
        val out = ByteArrayOutputStream()
        repeat(npad) { out.write(padc) }
        out.write(MARK)
        val body = ByteArrayOutputStream()
        if (data.size + 2 + ct <= 94) {
            body.write(tochar(data.size + 2 + ct))
            body.write(tochar(seq))
            body.write(type.code)
        } else {
            val lx = data.size + ct
            body.write(tochar(0))
            body.write(tochar(seq))
            body.write(type.code)
            body.write(tochar(lx / 95))
            body.write(tochar(lx % 95))
            val h = body.toByteArray()
            body.write(check1(h, 0, h.size))
        }
        body.write(data)
        val b = body.toByteArray()
        out.write(b)
        out.write(checkChars(ct, b, 0, b.size))
        out.write(eol)
        io.write(out.toByteArray())
    }

    private fun checkChars(type: Int, b: ByteArray, off: Int, len: Int): ByteArray {
        var s = 0
        for (i in off until off + len) s += b[i].toInt() and 0xff
        return when (type) {
            1 -> byteArrayOf(tochar((s + ((s and 192) shr 6)) and 63).toByte())
            2 -> byteArrayOf(tochar((s shr 6) and 63).toByte(), tochar(s and 63).toByte())
            else -> {
                val c = crc(b, off, len)
                byteArrayOf(tochar((c shr 12) and 0x0f).toByte(), tochar((c shr 6) and 63).toByte(), tochar(c and 63).toByte())
            }
        }
    }

    private fun check1(b: ByteArray, off: Int, len: Int): Int = checkChars(1, b, off, len)[0].toInt()

    /**
     * the next good packet, or null on a timeout or a bad one (garbage
     * before its mark skipped; a mark in the middle starts over)
     */
    private fun readPacket(wait: Long = timeoutMs): Packet? {
        val deadline = System.currentTimeMillis() + wait
        fun next(): Int {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return -1
            return io.read(left)
        }
        var c = next()
        outer@ while (true) {
            while (c != MARK) {
                if (c < 0) return null
                c = next()
            }
            val buf = ByteArrayOutputStream()
            fun take(): Int {
                val v = next()
                if (v >= 0) buf.write(v)
                return v
            }
            // LEN SEQ TYPE
            val hdr = IntArray(3)
            for (i in 0 until 3) {
                val v = take()
                if (v < 0) return null
                if (v == MARK) { c = v; continue@outer }
                hdr[i] = v
            }
            val len = unchar(hdr[0])
            val pseq = unchar(hdr[1])
            val type = hdr[2].toChar()
            val rest: Int
            if (len == 0) {
                // extended: LENX1 LENX2 HCHECK
                val x = IntArray(3)
                for (i in 0 until 3) {
                    val v = take()
                    if (v < 0) return null
                    if (v == MARK) { c = v; continue@outer }
                    x[i] = v
                }
                val h = buf.toByteArray()
                if (check1(h, 0, 5) != x[2]) return null
                rest = unchar(x[0]) * 95 + unchar(x[1])
            } else {
                if (len < 3) return null
                rest = len - 2
            }
            if (rest > 9100) return null
            val headerLen = buf.size()
            for (i in 0 until rest) {
                val v = take()
                if (v < 0) return null
                if (v == MARK) { c = v; continue@outer }
            }
            val all = buf.toByteArray()
            val ct = if (type == 'S' || type == 'I' || type == 'R' || forceCheck1) 1
                // (an S or I packet's ACK: type 1 too)
                else if (type == 'Y' && pseq == 0 && lastSentType == 'S') 1
                // a NAK or an error: the check its length makes plain
                else check
            if (rest < ct) return null
            val dataEnd = all.size - ct
            val want = checkChars(ct, all, 0, dataEnd)
            for (i in 0 until ct) {
                if (all[dataEnd + i] != want[i]) {
                    // (an E or N packet sent before the check type changed: type 1)
                    if ((type == 'N' || type == 'E') && ct != 1 && rest >= 1) {
                        val w1 = checkChars(1, all, 0, all.size - 1)
                        if (all[all.size - 1] == w1[0]) return Packet(pseq, type, all.copyOfRange(headerLen, all.size - 1))
                    }
                    return null
                }
            }
            return Packet(pseq, type, all.copyOfRange(headerLen, dataEnd))
        }
    }

    private var lastSentType = ' '

    private fun error(why: String): Nothing {
        runCatching { sendPacket(seq, 'E', encodeText(why)) }
        throw IOException(why)
    }

    /** an E packet ending the transfer, for sending when the user cancels */
    fun cancelBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        val tmp = TransferIo { out.write(it) }
        Kermit(tmp).also {
            it.check = check
            it.forceCheck1 = forceCheck1
            it.eol = eol
        }.sendPacket(seq, 'E', "cancelled".toByteArray())
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // the S/Y exchange

    private fun ourParams(): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(tochar(94))                       // MAXL
        b.write(tochar((timeoutMs / 1000).toInt().coerceIn(1, 94))) // TIME
        b.write(tochar(0))                        // NPAD
        b.write(ctl(0))                           // PADC
        b.write(tochar(CR))                       // EOL
        b.write(OUR_QCTL)                         // QCTL
        b.write(if (sevenBit) '&'.code else 'Y'.code) // QBIN
        b.write('3'.code)                         // CHKT
        b.write(REPT)                             // REPT
        b.write(tochar(CAP_LP or CAP_AT))         // CAPAS
        b.write(tochar(1))                        // WINDO
        b.write(tochar(OUR_LONG / 95))            // MAXLX1
        b.write(tochar(OUR_LONG % 95))            // MAXLX2
        return b.toByteArray()
    }

    /** their parameters, agreed with ours ([sender]: we're the one that sent the S) */
    @Suppress("UNUSED_PARAMETER")
    private fun agree(d: ByteArray, sender: Boolean) {
        fun at(i: Int): Int = if (i < d.size) d[i].toInt() and 0xff else -1
        at(0).takeIf { it > 32 }?.let { sendMax = unchar(it).coerceIn(10, 94) }
        npad = at(2).takeIf { it > 32 }?.let { unchar(it) } ?: 0
        padc = at(3).takeIf { it >= 0 }?.let { ctl(it) } ?: 0
        eol = at(4).takeIf { it > 32 }?.let { unchar(it) } ?: CR
        theirQctl = at(5).takeIf { it > 32 } ?: '#'.code
        val ourQbin = if (sevenBit) '&'.code else 'Y'.code
        val theirQbin = at(6)
        qbin = when {
            theirQbin == 'Y'.code && ourQbin != 'Y'.code -> ourQbin
            isPrefix(theirQbin) && (ourQbin == 'Y'.code || ourQbin == theirQbin) -> theirQbin
            else -> 0
        }
        val theirCheck = at(7).takeIf { it in '1'.code..'3'.code }?.let { it - '0'.code } ?: 1
        check = if (theirCheck == 3) 3 else if (theirCheck == 2) 2 else 1
        rept = if (at(8) == REPT) REPT else 0
        // capabilities (more bytes follow while the lowest bit is set)
        var i = 9
        var caps = 0
        if (at(i) > 32) {
            caps = unchar(at(i))
            while (at(i) >= 0 && (unchar(at(i)) and 1) != 0) i++
        }
        i++
        attributes = (caps and CAP_AT) != 0
        sendLong = 0
        if ((caps and CAP_LP) != 0) {
            // WINDO, then MAXLX1 MAXLX2 (none: 500)
            val x1 = at(i + 1)
            val x2 = at(i + 2)
            sendLong = if (x1 > 32 || x2 > 32) unchar(maxOf(x1, 32)) * 95 + unchar(maxOf(x2, 32)) else 500
            if (sendLong < 95) sendLong = 0
            sendLong = minOf(sendLong, OUR_LONG)
        }
        if (qbin != 0 && qbin == rept) rept = 0
    }

    private fun isPrefix(c: Int) = c in 33..62 || c in 96..126

    // ------------------------------------------------------------------
    // the data's encoding

    /** room for data in a packet (an encoded byte is up to 5 characters: kept whole) */
    private fun room(): Int {
        val ct = check
        return if (sendLong > 0) sendLong - 5 - ct - 2 else sendMax - 2 - ct
    }

    private fun encodeByte(out: ByteArrayOutputStream, v: Int) {
        var b = v
        if (qbin != 0 && b and 0x80 != 0) {
            out.write(qbin)
            b = b and 0x7f
        }
        val b7 = b and 0x7f
        when {
            b7 < 32 || b7 == 127 -> {
                out.write(OUR_QCTL)
                out.write(ctl(b))
            }
            b7 == OUR_QCTL || (qbin != 0 && b7 == qbin) || (rept != 0 && b7 == rept) -> {
                out.write(OUR_QCTL)
                out.write(b)
            }
            else -> out.write(b)
        }
    }

    /** [src] from [off], encoded into at most [max] characters: the characters and the bytes they hold */
    private fun encode(src: ByteArray, off: Int, len: Int, max: Int): Pair<ByteArray, Int> {
        val out = ByteArrayOutputStream()
        var i = off
        val end = off + len
        val unit = ByteArrayOutputStream()
        while (i < end) {
            val v = src[i].toInt() and 0xff
            var n = 1
            if (rept != 0) while (i + n < end && n < 94 && src[i + n] == src[i]) n++
            unit.reset()
            if (n >= 3) {
                unit.write(rept)
                unit.write(tochar(n))
                encodeByte(unit, v)
            } else {
                n = 1
                encodeByte(unit, v)
            }
            if (out.size() + unit.size() > max) break
            unit.writeTo(out)
            i += n
        }
        return out.toByteArray() to (i - off)
    }

    private fun encodeText(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return encode(b, 0, b.size, Int.MAX_VALUE).first
    }

    /** a packet's data decoded */
    private fun decode(d: ByteArray, out: OutputStream) {
        var i = 0
        while (i < d.size) {
            var n = 1
            var c = d[i++].toInt() and 0xff
            if (rept != 0 && c == rept && i < d.size) {
                n = unchar(d[i++].toInt() and 0xff)
                if (i >= d.size) break
                c = d[i++].toInt() and 0xff
            }
            var hi = 0
            if (qbin != 0 && c == qbin && i < d.size) {
                hi = 0x80
                c = d[i++].toInt() and 0xff
            }
            if (c == theirQctl && i < d.size) {
                c = d[i++].toInt() and 0xff
                val c7 = c and 0x7f
                if (c7 in 63..95) c = ctl(c)
            }
            c = c or hi
            repeat(n) { out.write(c) }
        }
    }

    private fun decodeText(d: ByteArray): String = ByteArrayOutputStream().also { decode(d, it) }.toString(Charsets.UTF_8.name())

    // ------------------------------------------------------------------
    // sending

    /** a packet sent until its ACK comes (a NAK of the next one counts): the ACK's data */
    private fun exchange(type: Char, data: ByteArray): ByteArray {
        var tries = 0
        lastSentType = type
        while (true) {
            sendPacket(seq, type, data)
            while (true) {
                val r = readPacket() ?: break
                when {
                    r.type == 'E' -> throw IOException("the receiver: " + decodeText(r.data))
                    r.type == 'Y' && r.seq == seq -> {
                        seq = (seq + 1) and 63
                        return r.data
                    }
                    r.type == 'N' && r.seq == ((seq + 1) and 63) -> {
                        seq = (seq + 1) and 63
                        return ByteArray(0)
                    }
                    r.type == 'N' -> break
                    // (an old ACK, repeated: the next one waited for)
                }
            }
            if (++tries > MAX_RETRIES) error("too many retries")
            report { it.copy(errors = it.errors + 1) }
        }
    }

    /** [files] sent: the S/Y exchange, then each file (F, A, D…, Z), then B */
    fun send(files: List<Outgoing>) {
        seq = 0
        forceCheck1 = true
        val their = exchange('S', ourParams())
        agree(their, sender = true)
        forceCheck1 = false
        for (f in files) sendFile(f)
        exchange('B', ByteArray(0))
    }

    private fun sendFile(f: Outgoing) {
        report { it.copy(file = f.name, bytes = 0, size = f.size) }
        val nameAck = exchange('F', encodeText(f.name))
        if (nameAck.isNotEmpty()) decodeText(nameAck)
        if (attributes) {
            val a = ByteArrayOutputStream()
            fun attr(tag: Char, v: String) {
                a.write(tag.code)
                a.write(tochar(v.length))
                a.write(v.toByteArray(Charsets.US_ASCII))
            }
            attr('"', "B8")                          // binary, 8-bit
            if (f.size >= 0) {
                attr('!', ((f.size + 1023) / 1024).toString())
                attr('1', f.size.toString())
            }
            if (f.mtime > 0) attr('#', formatDate(f.mtime))
            val r = exchange('A', a.toByteArray())
            if (r.isNotEmpty() && r[0] == 'N'.code.toByte()) {
                // refused: the file skipped
                exchange('Z', byteArrayOf('D'.code.toByte()))
                return
            }
        }
        val buf = ByteArray(OUR_LONG)
        var have = 0
        var sent = 0L
        f.open().use { input: InputStream ->
            var eof = false
            while (true) {
                while (!eof && have < buf.size) {
                    val n = input.read(buf, have, buf.size - have)
                    if (n < 0) eof = true else have += n
                }
                if (have == 0) break
                val (chars, used) = encode(buf, 0, have, room())
                val ack = exchange('D', chars)
                System.arraycopy(buf, used, buf, 0, have - used)
                have -= used
                sent += used
                report { it.copy(bytes = sent) }
                if (ack.isNotEmpty() && (ack[0] == 'X'.code.toByte() || ack[0] == 'Z'.code.toByte())) {
                    // the receiver wants no more of this file (or of any)
                    exchange('Z', byteArrayOf('D'.code.toByte()))
                    if (ack[0] == 'Z'.code.toByte()) throw TransferCancelled("cancelled by the receiver")
                    return
                }
            }
        }
        exchange('Z', ByteArray(0))
        report { it.copy(files = it.files + 1) }
    }

    // ------------------------------------------------------------------
    // receiving

    private fun ack(seq: Int, data: ByteArray = ByteArray(0)) {
        lastSentType = 'Y'
        sendPacket(seq, 'Y', data)
    }

    private fun nak(seq: Int) {
        lastSentType = 'N'
        sendPacket(seq, 'N', ByteArray(0))
    }

    /** files received into [into] until the sender's B: the names they got */
    fun receive(into: Incoming): List<String> {
        val names = ArrayList<String>()
        seq = 0
        forceCheck1 = true
        var tries = 0
        // waiting for the S (a NAK now and then, for a sender waiting for one)
        var init: Packet
        while (true) {
            val r = readPacket()
            if (r != null && r.type == 'S') { init = r; break }
            if (r != null && r.type == 'E') throw IOException("the sender: " + decodeText(r.data))
            if (++tries > MAX_RETRIES) throw IOException("the sender didn't start")
            nak(0)
        }
        agree(init.data, sender = false)
        val ours = ourParams()
        ack(init.seq, ours)
        var lastAck: Pair<Int, ByteArray> = init.seq to ours
        forceCheck1 = false
        seq = (init.seq + 1) and 63

        var out: OutputStream? = null
        var current: String? = null
        var mtime = 0L
        var got = 0L
        tries = 0
        try {
            while (true) {
                val r = readPacket()
                if (r == null || r.seq != seq) {
                    if (r != null && r.seq == lastAck.first) {
                        // our ACK lost: sent again
                        if (r.type == 'S') forceCheck1 = true
                        ack(lastAck.first, lastAck.second)
                        forceCheck1 = false
                    } else if (r != null && r.type == 'E') {
                        throw IOException("the sender: " + decodeText(r.data))
                    } else {
                        nak(seq)
                    }
                    if (++tries > MAX_RETRIES) error("too many retries")
                    report { it.copy(errors = it.errors + 1) }
                    continue
                }
                tries = 0
                var reply = ByteArray(0)
                when (r.type) {
                    'F' -> {
                        val name = decodeText(r.data).let { n ->
                            // (a name sent "converted", all capitals, as C-Kermit does: back in lower case)
                            if (n.any { it.isLetter() } && n.none { it.isLowerCase() }) n.lowercase() else n
                        }
                        val (o, actual) = into.create(name, -1)
                        out = o.buffered()
                        current = actual
                        mtime = 0
                        got = 0
                        report { it.copy(file = actual, bytes = 0, size = -1) }
                        reply = encodeText(actual)
                    }
                    'A' -> {
                        val a = r.data
                        var i = 0
                        while (i + 1 < a.size) {
                            val tag = a[i].toInt().toChar()
                            val n = unchar(a[i + 1].toInt() and 0xff)
                            if (i + 2 + n > a.size) break
                            val v = String(a, i + 2, n, Charsets.US_ASCII)
                            when (tag) {
                                '1' -> v.trim().toLongOrNull()?.let { s -> report { it.copy(size = s) } }
                                '!' -> if (p.size < 0) v.trim().toLongOrNull()?.let { k -> report { it.copy(size = k * 1024) } }
                                '#' -> mtime = parseDate(v)
                            }
                            i += 2 + n
                        }
                        reply = "Y".toByteArray()
                    }
                    'D' -> {
                        val o = out ?: error("data before a file's name")
                        val counted = CountingStream(o)
                        decode(r.data, counted)
                        got += counted.n
                        report { it.copy(bytes = got) }
                    }
                    'Z' -> {
                        val o = out
                        val name = current
                        out = null
                        current = null
                        if (o != null && name != null) {
                            o.close()
                            if (r.data.isNotEmpty() && r.data[0] == 'D'.code.toByte()) {
                                into.abandon(name)
                            } else {
                                into.done(name, mtime)
                                names.add(name)
                                report { it.copy(files = it.files + 1) }
                            }
                        }
                    }
                    'B' -> {
                        ack(r.seq)
                        return names
                    }
                    'E' -> throw IOException("the sender: " + decodeText(r.data))
                    'X' -> {
                        // text to show (a server's): taken like a file with no name
                        reply = ByteArray(0)
                    }
                    else -> error("unexpected ${r.type} packet")
                }
                ack(r.seq, reply)
                lastAck = r.seq to reply
                seq = (seq + 1) and 63
            }
        } catch (e: IOException) {
            runCatching { out?.close() }
            current?.let { into.abandon(it) }
            throw e
        }
    }

    private class CountingStream(private val o: OutputStream) : OutputStream() {
        var n = 0L
        override fun write(b: Int) { o.write(b); n++ }
    }

    companion object {
        const val MARK = 1
        const val CR = 13
        const val OUR_QCTL = '#'.code
        const val REPT = '~'.code
        const val CAP_LP = 2
        const val CAP_AT = 8
        /** the long packets asked for */
        const val OUR_LONG = 4000
        const val MAX_RETRIES = 10

        fun tochar(x: Int) = x + 32
        fun unchar(x: Int) = x - 32
        fun ctl(x: Int) = x xor 64

        /** CRC-16/KERMIT (CCITT, bits reflected, from 0) */
        fun crc(b: ByteArray, off: Int, len: Int): Int {
            var c = 0
            for (i in off until off + len) {
                c = c xor (b[i].toInt() and 0xff)
                repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 }
            }
            return c
        }

        /** a send's start: a Send-Init packet (sequence 0), as kermit -s sends it, in [b] */
        fun looksLikeStart(b: ByteArray, off: Int = 0, len: Int = b.size - off): Boolean {
            for (i in off until off + len - 3) {
                if (b[i].toInt() == MARK && b[i + 2].toInt() == tochar(0) && b[i + 3] == 'S'.code.toByte()) {
                    val l = (b[i + 1].toInt() and 0xff) - 32
                    if (l in 4..94) return true
                }
            }
            return false
        }

        private fun formatDate(sec: Long): String {
            val c = Calendar.getInstance(TimeZone.getDefault())
            c.timeInMillis = sec * 1000
            return "%04d%02d%02d %02d:%02d:%02d".format(
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
            )
        }

        /** "[yy]yymmdd[ hh:mm[:ss]]", local time: seconds, 0 when it isn't one */
        private fun parseDate(v: String): Long {
            val m = Regex("""^(\d{6}|\d{8})(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?""").find(v.trim()) ?: return 0
            val d = m.groupValues[1]
            val year = if (d.length == 8) d.substring(0, 4).toInt() else 1900 + d.substring(0, 2).toInt().let { if (it < 70) it + 100 else it }
            val rest = d.takeLast(4)
            val c = Calendar.getInstance(TimeZone.getDefault())
            c.clear()
            c.set(year, rest.substring(0, 2).toInt() - 1, rest.substring(2).toInt(),
                m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].toIntOrNull() ?: 0, m.groupValues[4].toIntOrNull() ?: 0)
            return c.timeInMillis / 1000
        }
    }
}
