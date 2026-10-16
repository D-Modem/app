// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.transfer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * ZMODEM (Forsberg's, as lrzsz's sz and rz speak it), both ways: hex and
 * binary headers (CRC-16, and CRC-32 when both sides can), ZDLE escaping,
 * data in subpackets streamed (ZCRCG) with the receiver's ZRPOS putting
 * the sender back where the line went wrong, files offered by name, size
 * and time, and ended with ZFIN and "OO".
 */
class ZModem(
    private val io: TransferIo,
    private val progress: (Progress) -> Unit = {},
    private val timeoutMs: Long = 10_000,
) {
    private var p = Progress()

    private fun report(f: (Progress) -> Progress) {
        p = f(p)
        progress(p)
    }

    // ------------------------------------------------------------------
    // the frames

    class Header(val type: Int, val p: ByteArray = ByteArray(4)) {
        /** ZP0..ZP3 as a number (positions) */
        val pos: Long get() = (p[0].toLong() and 0xff) or ((p[1].toLong() and 0xff) shl 8) or
            ((p[2].toLong() and 0xff) shl 16) or ((p[3].toLong() and 0xff) shl 24)

        /** ZF0 (ZF0 is p[3], as the spec numbers the flags) */
        val f0: Int get() = p[3].toInt() and 0xff

        override fun toString() = "Header(${NAMES.getOrElse(type) { "$type" }}, $pos)"

        companion object {
            fun pos(type: Int, pos: Long) = Header(type, ByteArray(4) { ((pos shr (8 * it)) and 0xff).toByte() })
            fun flags(type: Int, f0: Int, f1: Int = 0, f2: Int = 0, f3: Int = 0) =
                Header(type, byteArrayOf(f3.toByte(), f2.toByte(), f1.toByte(), f0.toByte()))
        }
    }

    /** whether the receiver can take CRC-32 (we use it then) */
    private var crc32 = false

    /** a header in hex (the first ones, and the receiver's) */
    private fun sendHex(h: Header) {
        val b = ByteArrayOutputStream()
        b.write(ZPAD); b.write(ZPAD); b.write(ZDLE); b.write('B'.code)
        val raw = byteArrayOf(h.type.toByte()) + h.p
        for (x in raw) b.write(hex2(x.toInt() and 0xff))
        val crc = Crc.crc16(raw)
        b.write(hex2(crc shr 8)); b.write(hex2(crc and 0xff))
        b.write(0x0d); b.write(0x8a)
        // (an XON after all but ZACK and ZFIN, as sz does: a line stopped by flow control started again)
        if (h.type != ZACK && h.type != ZFIN) b.write(XON)
        io.write(b.toByteArray())
    }

    private fun hex2(v: Int): ByteArray = "%02x".format(v).toByteArray()

    /** a header in binary (the sender's): CRC-32 when the receiver can */
    private fun sendBin(h: Header) {
        val b = ByteArrayOutputStream()
        b.write(ZPAD); b.write(ZDLE); b.write(if (crc32) 'C'.code else 'A'.code)
        val raw = byteArrayOf(h.type.toByte()) + h.p
        for (x in raw) escape(b, x.toInt() and 0xff)
        if (crc32) {
            val c = Crc.crc32(raw).inv()
            for (i in 0 until 4) escape(b, (c ushr (8 * i)) and 0xff)
        } else {
            val c = Crc.crc16(raw)
            escape(b, c shr 8); escape(b, c and 0xff)
        }
        io.write(b.toByteArray())
    }

    /** a data subpacket ended by [end] (ZCRCE, ZCRCG, ZCRCQ, ZCRCW) */
    private fun sendData(data: ByteArray, len: Int, end: Int) {
        val b = ByteArrayOutputStream(len + len / 8 + 16)
        for (i in 0 until len) escape(b, data[i].toInt() and 0xff)
        b.write(ZDLE); b.write(end)
        if (crc32) {
            val c = Crc.crc32(Crc.crc32(data, 0, len), end).inv()
            for (i in 0 until 4) escape(b, (c ushr (8 * i)) and 0xff)
        } else {
            val c = Crc.crc16(Crc.crc16(data, 0, len), end)
            escape(b, c shr 8); escape(b, c and 0xff)
        }
        if (end == ZCRCW) b.write(XON)
        io.write(b.toByteArray())
    }

    private var lastSent = 0

    /** ZDLE escaping: ZDLE, XON/XOFF, DLE (and their high-bit forms), and CR after @ (a Telnet escape) */
    private fun escape(b: ByteArrayOutputStream, c: Int) {
        val esc = when (c) {
            ZDLE, 0x10, 0x90, 0x11, 0x91, 0x13, 0x93 -> true
            0x0d, 0x8d -> (lastSent and 0x7f) == '@'.code
            0x7f -> false
            0xff -> false
            else -> false
        }
        if (esc) {
            b.write(ZDLE)
            b.write(c xor 0x40)
        } else {
            b.write(c)
        }
        lastSent = c
    }

    /** a byte with ZDLE's escapes undone; GOT_* (over 0xff) for a frame end, and CANs counted */
    private fun readEscaped(): Int {
        while (true) {
            val c = readByte()
            if (c == XON || c == XOFF || c == XON or 0x80 || c == XOFF or 0x80) continue
            if (c != ZDLE) return c
            var cans = 1
            while (true) {
                val d = readByte()
                when {
                    d == XON || d == XOFF || d == XON or 0x80 || d == XOFF or 0x80 -> continue
                    d == CAN -> {
                        if (++cans >= 5) throw TransferCancelled("cancelled by the other side")
                        continue
                    }
                    d == ZCRCE || d == ZCRCG || d == ZCRCQ || d == ZCRCW -> return d or GOT_END
                    d == 'l'.code -> return 0x7f
                    d == 'm'.code -> return 0xff
                    (d and 0x60) == 0x40 -> return d xor 0x40
                    else -> return BAD
                }
            }
        }
    }

    private fun readByte(): Int {
        val c = io.read(timeoutMs)
        if (c < 0) throw IOException("the other side went quiet")
        return c
    }

    /**
     * the next header: skipping what isn't one ("**" or ZPAD, ZDLE, then
     * A, B or C); null when one came damaged (its check failed)
     */
    private fun readHeader(): Header? {
        var garbage = 0
        var cans = 0
        while (true) {
            val c = readByte()
            if (c == CAN) {
                if (++cans >= 5) throw TransferCancelled("cancelled by the other side")
            } else {
                cans = 0
            }
            if (c != ZDLE && c != ZDLE or 0x80) {
                if (++garbage > 64 * 1024) throw IOException("no ZMODEM header")
                continue
            }
            val kind = readByte() and 0x7f
            // (the ZDLE before a header is also a CAN: one isn't a cancel)
            if (kind == CAN) { cans = 2; continue }
            return when (kind) {
                'A'.code -> readBinHeader(false)
                'C'.code -> readBinHeader(true)
                'B'.code -> readHexHeader()
                else -> continue
            }
        }
    }

    private fun readBinHeader(c32: Boolean): Header? {
        val raw = ByteArray(5)
        for (i in 0 until 5) {
            val c = readEscaped()
            if (c > 0xff) return null
            raw[i] = c.toByte()
        }
        if (c32) {
            var crc = 0L
            for (i in 0 until 4) {
                val c = readEscaped()
                if (c > 0xff) return null
                crc = crc or (c.toLong() shl (8 * i))
            }
            if (Crc.crc32(raw).inv().toLong() and 0xffffffffL != crc) return null
        } else {
            val hi = readEscaped()
            val lo = readEscaped()
            if (hi > 0xff || lo > 0xff || Crc.crc16(raw) != ((hi shl 8) or lo)) return null
        }
        rxCrc32 = c32
        return Header(raw[0].toInt() and 0xff, raw.copyOfRange(1, 5))
    }

    /** how the sender's data comes: CRC-32 when its headers did */
    private var rxCrc32 = false

    private fun readHexHeader(): Header? {
        val raw = ByteArray(7)
        for (i in 0 until 7) {
            val hi = hexDigit(readByte() and 0x7f)
            val lo = hexDigit(readByte() and 0x7f)
            if (hi < 0 || lo < 0) return null
            raw[i] = ((hi shl 4) or lo).toByte()
        }
        // CR LF (or CR with its high bit) after it; then maybe an XON
        val crc = ((raw[5].toInt() and 0xff) shl 8) or (raw[6].toInt() and 0xff)
        if (Crc.crc16(raw, 0, 5) != crc) return null
        // CR LF after every hex header (an XON after most: let go by with the next header's garbage)
        readByte(); readByte()
        return Header(raw[0].toInt() and 0xff, raw.copyOfRange(1, 5))
    }

    private fun hexDigit(c: Int): Int = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }

    /** a data subpacket: its bytes and how it ended (ZCRCx); null if its check failed or it ran over [max] */
    private fun readData(max: Int): Pair<ByteArray, Int>? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = readEscaped()
            if (c == BAD) return null
            if (c > 0xff) {
                val end = c and 0xff
                val data = b.toByteArray()
                if (rxCrc32) {
                    var crc = 0L
                    for (i in 0 until 4) {
                        val x = readEscaped()
                        if (x > 0xff) return null
                        crc = crc or (x.toLong() shl (8 * i))
                    }
                    if (Crc.crc32(Crc.crc32(data), end).inv().toLong() and 0xffffffffL != crc) return null
                } else {
                    val hi = readEscaped()
                    val lo = readEscaped()
                    if (hi > 0xff || lo > 0xff || Crc.crc16(Crc.crc16(data), end) != ((hi shl 8) or lo)) return null
                }
                return data to end
            }
            b.write(c)
            if (b.size() > max) return null
        }
    }

    // ------------------------------------------------------------------
    // receiving (the other side runs sz)

    /** files received into [into] until the sender ends (ZFIN): their names */
    fun receive(into: Incoming): List<String> {
        val names = ArrayList<String>()
        var errors = 0
        fun zrinit() = sendHex(Header.flags(ZRINIT, CANFDX or CANOVIO or CANFC32))
        zrinit()
        var lastHeaderAt = System.currentTimeMillis()
        while (true) {
            val h = try {
                readHeader()
            } catch (e: IOException) {
                if (e is TransferCancelled) throw e
                // quiet: ask again (the sender may not have heard us)
                if (System.currentTimeMillis() - lastHeaderAt > 60_000 || ++errors > MAX_ERRORS) throw e
                zrinit()
                continue
            }
            if (h == null) {
                if (++errors > MAX_ERRORS) throw IOException("too many errors on the line")
                zrinit()
                continue
            }
            lastHeaderAt = System.currentTimeMillis()
            when (h.type) {
                ZRQINIT -> zrinit()
                ZSINIT -> {
                    readData(1024)
                    sendHex(Header.pos(ZACK, 0))
                }
                ZFILE -> {
                    val d = readData(4096)
                    if (d == null) {
                        sendHex(Header(ZNAK))
                        continue
                    }
                    val name = receiveFile(d.first, into) ?: continue
                    names.add(name)
                    report { it.copy(files = it.files + 1) }
                    zrinit()
                }
                ZFIN -> {
                    sendHex(Header(ZFIN))
                    // "OO" (over and out) follows: read past it if it comes
                    repeat(2) { if (io.read(500) < 0) return names }
                    return names
                }
                ZCAN, ZABORT -> throw TransferCancelled("cancelled by the sender")
                ZCOMMAND -> {
                    // commands are never run here
                    readData(4096)
                    sendHex(Header.pos(ZCOMPL, 0))
                }
                ZFREECNT -> sendHex(Header.pos(ZACK, 0))
                else -> {}
            }
        }
    }

    /** a file's data received after its ZFILE: its name, or null when it was skipped */
    private fun receiveFile(info: ByteArray, into: Incoming): String? {
        val nul = info.indexOf(0.toByte()).takeIf { it >= 0 } ?: info.size
        val name = String(info, 0, nul, Charsets.UTF_8)
        val meta = if (nul + 1 < info.size) String(info, nul + 1, info.size - nul - 1, Charsets.ISO_8859_1).trimEnd('\u0000').trim().split(' ') else emptyList()
        val size = meta.getOrNull(0)?.toLongOrNull() ?: -1
        val mtime = meta.getOrNull(1)?.toLongOrNull(8) ?: 0
        val (out, actual) = into.create(name, size)
        report { it.copy(file = actual, bytes = 0, size = size) }
        var pos = 0L
        var errors = 0
        var ok = false
        try {
            out.use { o ->
                sendHex(Header.pos(ZRPOS, pos))
                loop@ while (true) {
                    val h = readHeader()
                    if (h == null) {
                        if (++errors > MAX_ERRORS) throw IOException("too many errors on the line")
                        sendHex(Header.pos(ZRPOS, pos))
                        continue
                    }
                    when (h.type) {
                        ZDATA -> {
                            if (h.pos != pos) {
                                // not where we are: back to it (the data in between let go by)
                                sendHex(Header.pos(ZRPOS, pos))
                                continue@loop
                            }
                            while (true) {
                                val d = readData(8192)
                                if (d == null) {
                                    if (++errors > MAX_ERRORS) throw IOException("too many errors on the line")
                                    report { it.copy(errors = it.errors + 1) }
                                    io.purge()
                                    sendHex(Header.pos(ZRPOS, pos))
                                    continue@loop
                                }
                                o.write(d.first)
                                pos += d.first.size
                                errors = 0
                                report { it.copy(bytes = pos) }
                                when (d.second) {
                                    ZCRCW -> { sendHex(Header.pos(ZACK, pos)); continue@loop }
                                    ZCRCQ -> sendHex(Header.pos(ZACK, pos))
                                    ZCRCE -> continue@loop
                                    // ZCRCG: more follows
                                }
                            }
                        }
                        ZEOF -> {
                            if (h.pos != pos) continue@loop
                            ok = true
                            break@loop
                        }
                        ZFILE -> {
                            // our ZRPOS lost: the same file offered again
                            readData(4096)
                            sendHex(Header.pos(ZRPOS, pos))
                        }
                        ZSKIP -> break@loop
                        ZCAN, ZABORT -> throw TransferCancelled("cancelled by the sender")
                        ZFIN -> throw IOException("the sender ended in the middle of a file")
                        else -> {}
                    }
                }
            }
        } catch (e: IOException) {
            into.abandon(actual)
            throw e
        }
        if (!ok) {
            into.abandon(actual)
            return null
        }
        into.done(actual, mtime)
        return actual
    }

    // ------------------------------------------------------------------
    // sending (the other side runs rz)

    /**
     * [files] sent: "rz\r" first when [startRz] (as sz does, for a shell
     * at the other end), then ZRQINIT until the receiver's ZRINIT; each
     * file until the receiver's next ZRINIT says it has it
     */
    fun send(files: List<Outgoing>, startRz: Boolean = false) {
        if (startRz) io.write("rz\r".toByteArray())
        val r = waitForZrinit()
        crc32 = r.f0 and CANFC32 != 0
        var left = files.sumOf { it.size.coerceAtLeast(0) }
        for ((i, f) in files.withIndex()) {
            report { it.copy(file = f.name, bytes = 0, size = f.size) }
            val skipped = sendFile(f, files.size - i, left)
            left -= f.size.coerceAtLeast(0)
            if (!skipped) report { it.copy(files = it.files + 1) }
        }
        // the end: ZFIN, its answer, "OO"
        for (attempt in 0 until MAX_ERRORS) {
            sendHex(Header(ZFIN))
            if (readHeaderOrNull()?.type == ZFIN) break
        }
        io.write("OO".toByteArray())
    }

    private fun waitForZrinit(): Header {
        var tries = 0
        sendHex(Header(ZRQINIT))
        while (true) {
            val h = try {
                readHeader()
            } catch (e: TransferCancelled) {
                throw e
            } catch (e: IOException) {
                if (++tries > MAX_ERRORS) throw IOException("the receiver didn't answer")
                sendHex(Header(ZRQINIT))
                continue
            }
            when (h?.type) {
                ZRINIT -> return h
                ZCHALLENGE -> sendHex(Header(ZACK, h.p))
                ZCAN, ZABORT -> throw TransferCancelled("cancelled by the receiver")
                ZRQINIT -> throw IOException("the other side is sending, not receiving")
                else -> if (++tries > MAX_ERRORS * 3) throw IOException("the receiver didn't answer")
            }
        }
    }

    private fun readHeaderOrNull(): Header? =
        try { readHeader() } catch (e: TransferCancelled) { throw e } catch (_: IOException) { null }

    /** a file sent, until the receiver's ZRINIT: true if it skipped it */
    private fun sendFile(f: Outgoing, filesLeft: Int, bytesLeft: Long): Boolean {
        val info = (f.name.substringAfterLast('/').toByteArray(Charsets.UTF_8) + 0) +
            ("${f.size.coerceAtLeast(0)} ${java.lang.Long.toOctalString(f.mtime.coerceAtLeast(0))} 0 0 $filesLeft $bytesLeft").toByteArray() + 0
        var start = -1L
        var tries = 0
        send@ while (start < 0) {
            sendBin(Header.flags(ZFILE, ZCBIN))
            sendData(info, info.size, ZCRCW)
            while (true) {
                val h = readHeaderOrNull()
                when (h?.type) {
                    ZRPOS -> { start = h.pos; break@send }
                    ZSKIP -> return true
                    ZCRC -> sendHex(Header.pos(ZCRC, 0))
                    ZCAN, ZABORT -> throw TransferCancelled("cancelled by the receiver")
                    ZFERR -> throw IOException("the receiver couldn't write the file")
                    // (a ZRINIT repeated, from before the ZFILE: not an answer to it; sending it
                    // again would get two ZRPOS, and each one a restart)
                    ZRINIT -> {}
                    else -> {
                        if (++tries > MAX_ERRORS) throw IOException("the receiver didn't answer the file")
                        continue@send
                    }
                }
            }
        }
        val reader = Reader(f)
        try {
            stream(reader, start)
        } finally {
            reader.close()
        }
        return false
    }

    /** a file read from where the receiver wants it (reopened to go back) */
    private class Reader(private val f: Outgoing) {
        private var input: InputStream = f.open()
        var pos = 0L
            private set

        fun seek(to: Long) {
            if (to < pos) {
                input.close()
                input = f.open()
                pos = 0
            }
            while (pos < to) {
                val n = input.skip(to - pos)
                if (n > 0) {
                    pos += n
                } else {
                    if (input.read() < 0) throw IOException("can't go to $to in ${f.name}")
                    pos++
                }
            }
        }

        fun read(buf: ByteArray): Int {
            var len = 0
            while (len < buf.size) {
                val n = input.read(buf, len, buf.size - len)
                if (n < 0) break
                len += n
            }
            pos += len
            return len
        }

        fun close() = input.close()
    }

    /**
     * the file's data from [start] in ZCRCG subpackets, a ZCRCQ every
     * [WINDOW] bytes to hear how it goes, ZCRCE and ZEOF at the end; a
     * ZRPOS from the receiver (in the stream or after ZEOF) puts it back
     * there; done at the receiver's ZRINIT
     */
    private fun stream(r: Reader, start: Long) {
        val buf = ByteArray(BLOCK)
        var errors = 0
        var at = start
        // (errors counted while no progress is made: a long noisy file still gets there)
        var lastBack = -1L
        fun back(to: Long) {
            if (to > lastBack) errors = 0
            lastBack = to
            if (++errors > MAX_ERRORS) throw IOException("too many errors on the line")
            report { it.copy(errors = it.errors + 1) }
            at = to
            // (what the receiver said before it saw this restart: its ZRPOSes for the same
            // error, which would each be another restart)
            Thread.sleep(20)
            io.purge()
        }
        resend@ while (true) {
            r.seek(at)
            sendBin(Header.pos(ZDATA, r.pos))
            var sinceAck = 0
            while (true) {
                // the receiver's word in between, if any: a ZRPOS (an error: back to it).
                // (what isn't the start of a header let go first: the XON, CR LF a hex header ends
                // with, else it would be waited on as a header that never comes)
                while (true) {
                    val c = io.peek()
                    if (c < 0 || c == ZPAD || c == ZDLE) break
                    io.read(0)
                }
                if (io.available()) {
                    val h = readHeaderOrNull()
                    when (h?.type) {
                        ZRPOS -> {
                            back(h.pos)
                            continue@resend
                        }
                        ZCAN, ZABORT -> throw TransferCancelled("cancelled by the receiver")
                        ZSKIP -> return
                        else -> {}
                    }
                }
                val len = r.read(buf)
                sinceAck += len
                val end = when {
                    len < BLOCK -> ZCRCE
                    sinceAck >= WINDOW -> ZCRCQ
                    else -> ZCRCG
                }
                sendData(buf, len, end)
                report { it.copy(bytes = r.pos) }
                if (end == ZCRCE) break
                if (end == ZCRCQ) {
                    sinceAck = 0
                    val to = waitForAck(r.pos)
                    if (to != null) {
                        back(to)
                        continue@resend
                    }
                }
            }
            // ZEOF until the receiver's ZRINIT (it has the file), or a ZRPOS (it wants some again)
            while (true) {
                sendBin(Header.pos(ZEOF, r.pos))
                val h = readHeaderOrNull()
                when (h?.type) {
                    ZRINIT -> return
                    ZRPOS -> {
                        back(h.pos)
                        continue@resend
                    }
                    ZSKIP -> return
                    ZCAN, ZABORT -> throw TransferCancelled("cancelled by the receiver")
                    ZACK -> {}
                    else -> if (++errors > MAX_ERRORS) throw IOException("the end of the file wasn't answered")
                }
            }
        }
    }

    /** a ZCRCQ's answer: null for its ACK, else the position a ZRPOS asks for */
    private fun waitForAck(pos: Long): Long? {
        repeat(MAX_ERRORS) {
            val h = readHeaderOrNull() ?: return@repeat
            when (h.type) {
                ZACK -> return null
                ZRPOS -> return h.pos
                ZCAN, ZABORT -> throw TransferCancelled("cancelled by the receiver")
            }
        }
        throw IOException("no answer at $pos")
    }

    companion object {
        const val ZPAD = '*'.code
        const val ZDLE = 0x18
        const val CAN = 0x18
        const val XON = 0x11
        const val XOFF = 0x13

        const val ZRQINIT = 0
        const val ZRINIT = 1
        const val ZSINIT = 2
        const val ZACK = 3
        const val ZFILE = 4
        const val ZSKIP = 5
        const val ZNAK = 6
        const val ZABORT = 7
        const val ZFIN = 8
        const val ZRPOS = 9
        const val ZDATA = 10
        const val ZEOF = 11
        const val ZFERR = 12
        const val ZCRC = 13
        const val ZCHALLENGE = 14
        const val ZCOMPL = 15
        const val ZCAN = 16
        const val ZFREECNT = 17
        const val ZCOMMAND = 18
        val NAMES = listOf(
            "ZRQINIT", "ZRINIT", "ZSINIT", "ZACK", "ZFILE", "ZSKIP", "ZNAK", "ZABORT", "ZFIN", "ZRPOS",
            "ZDATA", "ZEOF", "ZFERR", "ZCRC", "ZCHALLENGE", "ZCOMPL", "ZCAN", "ZFREECNT", "ZCOMMAND",
        )

        const val ZCRCE = 'h'.code
        const val ZCRCG = 'i'.code
        const val ZCRCQ = 'j'.code
        const val ZCRCW = 'k'.code
        private const val GOT_END = 0x100
        private const val BAD = 0x200

        const val CANFDX = 0x01
        const val CANOVIO = 0x02
        const val CANFC32 = 0x20
        const val ZCBIN = 1

        const val BLOCK = 1024
        /** bytes between the sender's ZCRCQs (its window) */
        const val WINDOW = 16 * 1024
        const val MAX_ERRORS = 10

        /** a remote sz starting (ZRQINIT) */
        val SZ_START = byteArrayOf(ZPAD.toByte(), ZPAD.toByte(), ZDLE.toByte(), 'B'.code.toByte(), '0'.code.toByte(), '0'.code.toByte())
        /** a remote rz waiting (ZRINIT) */
        val RZ_START = byteArrayOf(ZPAD.toByte(), ZPAD.toByte(), ZDLE.toByte(), 'B'.code.toByte(), '0'.code.toByte(), '1'.code.toByte())

        /** what stops a transfer at the other end: CANs, then backspaces over them */
        val CANCEL = ByteArray(10) { CAN.toByte() } + ByteArray(10) { 8 }
    }
}
