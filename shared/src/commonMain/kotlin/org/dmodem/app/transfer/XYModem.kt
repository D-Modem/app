// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.transfer

import org.dmodem.app.io.*


/**
 * XMODEM (128-byte blocks, checksum or CRC; 1K blocks) and YMODEM (1K
 * blocks, CRC, a batch of files each named and sized in block 0), both
 * ways. Errors are repeated (NAK) up to [MAX_ERRORS] times; CAN CAN
 * from the other side ends it.
 *
 * YMODEM-G ([receiveY], [sendY] with g): the receiver asks with G, and
 * the sender sends the blocks with no wait for an ACK (for a line that
 * corrects its errors, a modem's V.42). An error ends it, as no block can
 * be sent again; each file's EOT is still acknowledged. A sender asked
 * with C does plain YMODEM.
 */
class XYModem(
    private val io: TransferIo,
    private val progress: (Progress) -> Unit = {},
    /** how long to wait for each answer (longer on a slow modem line) */
    private val timeoutMs: Long = 10_000,
) {
    private var p = Progress()

    /** the progress changed by [f], and reported */
    private fun report(f: (Progress) -> Progress) {
        p = f(p)
        progress(p)
    }

    // ------------------------------------------------------------------
    // receiving

    /**
     * XMODEM received into [name] in [into] (its size isn't sent: the last
     * block's padding, SUB characters, taken off). Returns the name used.
     */
    fun receiveX(into: Incoming, name: String): String {
        val (out, actual) = into.create(name, -1)
        report { it.copy(file = actual) }
        try {
            out.use { o ->
                val trailing = Bytes()
                receiveFile(o, -1, ymodem = false, first = null, holdLast = trailing)
                // the last block, its padding off
                val last = trailing.toByteArray()
                var end = last.size
                while (end > 0 && last[end - 1] == SUB.toByte()) end--
                o.write(last, 0, end)
            }
            into.done(actual, 0)
            report { it.copy(files = it.files + 1) }
            return actual
        } catch (e: IOException) {
            into.abandon(actual)
            throw e
        }
    }

    /** YMODEM-G: no ACKs, no blocks again */
    private var g = false

    /** a YMODEM batch received (YMODEM-G if [g]): the names the files got */
    fun receiveY(into: Incoming, g: Boolean = false): List<String> {
        this.g = g
        val names = ArrayList<String>()
        while (true) {
            // block 0: the file's name and size, or an empty one (the batch over)
            // (lrzsz's sb in YMODEM-G ends with no empty block 0: the batch over when none comes)
            val b0 = startAndRead(ymodem = true, tries = if (g && names.isNotEmpty()) 2 else 10)
                ?: if (g && names.isNotEmpty()) return names else throw IOException("the sender didn't start")
            if (b0.number != 0) throw IOException("no YMODEM header block")
            val data = b0.data
            if (data[0] == 0.toByte()) {
                io.write(ACK)
                return names
            }
            val nul = data.indexOf(0.toByte())
            val name = data.decodeToString(0, 0 + nul)
            val meta = latin1String(data, nul + 1, data.size - nul - 1).trimEnd('\u0000').split(' ')
            val size = meta.getOrNull(0)?.toLongOrNull() ?: -1
            val mtime = meta.getOrNull(1)?.toLongOrNull(8) ?: 0
            // (YMODEM-G: block 0 not acknowledged, G asks for the data)
            if (!g) io.write(ACK)
            val (out, actual) = into.create(name, size)
            report { it.copy(file = actual, bytes = 0, size = size) }
            try {
                out.use { o ->
                    io.write(if (g) G else C)
                    receiveFile(o, size, ymodem = true, first = null, holdLast = null)
                }
                into.done(actual, mtime)
            } catch (e: IOException) {
                into.abandon(actual)
                throw e
            }
            names.add(actual)
            report { it.copy(files = it.files + 1) }
        }
    }

    private class Block(val number: Int, val data: ByteArray)

    /** the first block, asked for with C (CRC) and, for XMODEM, NAK (a checksum) when C isn't answered */
    private fun startAndRead(ymodem: Boolean, tries: Int = 10): Block? {
        var crc = true
        bad = false
        for (attempt in 0 until tries) {
            if (attempt == 4 && !ymodem) crc = false
            // (a damaged block 0 asked for again with NAK: a C could be taken for its ACK and a
            // request for the data)
            io.write(if (g) G else if (crc && !(ymodem && bad)) C else NAK)
            val b = readBlock(crc, 3000) ?: continue
            this.crc = crc
            return b
        }
        return null
    }

    private var crc = true

    /** the sender's EOT came instead of a block ([readBlock]) */
    private var eot = false

    /** a damaged block came ([readBlock]) */
    private var bad = false

    /**
     * a block within [waitMs], its CRC or checksum checked: (number, data);
     * null on a timeout, a bad block (the line purged) or EOT (which sets
     * [eot]); throws on CAN CAN (the sender cancelling)
     */
    private fun readBlock(useCrc: Boolean, waitMs: Long): Block? {
        eot = false
        bad = false
        // (what isn't a block's start let go by: the sender's own messages, line noise)
        val deadline = monotonicMs() + waitMs
        var size = 0
        while (size == 0) {
            val left = deadline - monotonicMs()
            if (left <= 0) return null
            when (io.read(left)) {
                -1 -> return null
                SOH -> size = 128
                STX -> size = 1024
                EOT -> { eot = true; return null }
                CAN -> if (io.read(1000) == CAN) throw TransferCancelled("cancelled by the sender")
            }
        }
        val n = io.read(1000)
        val nn = io.read(1000)
        if (n < 0 || nn < 0) return null
        val data = ByteArray(size)
        for (i in 0 until size) {
            val c = io.read(1000)
            if (c < 0) return null
            data[i] = c.toByte()
        }
        val ok = if (useCrc) {
            val hi = io.read(1000)
            val lo = io.read(1000)
            hi >= 0 && lo >= 0 && Crc.crc16(data) == ((hi shl 8) or lo)
        } else {
            val sum = io.read(1000)
            sum >= 0 && (data.sumOf { it.toInt() and 0xff } and 0xff) == sum
        }
        if (!ok || (n xor nn) != 0xff) return badBlock()
        return Block(n, data)
    }

    /** the rest of a bad block let go by: null */
    private fun badBlock(): Block? {
        // (what's left of the bad block: let it go by)
        while (io.read(200) >= 0) { /* drop */ }
        bad = true
        return null
    }

    /**
     * a file's blocks into [o] (all but the last one into [holdLast], if
     * given: XMODEM takes its padding off): up to [size] bytes if known
     */
    private fun receiveFile(o: Sink, size: Long, ymodem: Boolean, first: Block?, holdLast: Bytes?) {
        var expect = 1
        var written = 0L
        var errors = 0
        var held: ByteArray? = null
        var block = first ?: if (!ymodem) startAndRead(false) else null
        var eots = 0
        while (true) {
            if (block == null) {
                block = readBlock(crc, timeoutMs)
                if (block == null) {
                    if (eot) {
                        // YMODEM: the first EOT answered NAK, the second ACK (to be sure it was
                        // one); YMODEM-G: ACK
                        eots++
                        if (ymodem && eots == 1 && !g) {
                            io.write(NAK)
                            continue
                        }
                        io.write(ACK)
                        break
                    }
                    if (g) gFail()
                    if (++errors > MAX_ERRORS) throw IOException("too many errors on the line")
                    report { it.copy(errors = it.errors + 1) }
                    io.write(NAK)
                    continue
                }
            }
            val b = block
            block = null
            when (b.number) {
                expect and 0xff -> {
                    var data = b.data
                    if (size >= 0 && written + data.size > size) data = data.copyOf((size - written).toInt().coerceAtLeast(0))
                    if (holdLast != null) {
                        held?.let { o.write(it) }
                        held = data
                    } else {
                        o.write(data)
                    }
                    written += data.size
                    expect++
                    errors = 0
                    if (!g) io.write(ACK)
                    report { it.copy(bytes = written) }
                }
                (expect - 1) and 0xff -> io.write(ACK) // a repeat of one already had (our ACK lost)
                else -> {
                    io.write(CAN, CAN, CAN)
                    throw IOException("blocks out of order")
                }
            }
        }
        held?.let { holdLast?.write(it) }
    }

    /** YMODEM-G's end on an error: no block can come again */
    private fun gFail(): Nothing {
        io.write(CAN, CAN, CAN, CAN, CAN)
        throw IOException("an error on the line (YMODEM-G cannot send a block again)")
    }

    // ------------------------------------------------------------------
    // sending

    /** one file by XMODEM (1K blocks if [oneK]) */
    fun sendX(file: Outgoing, oneK: Boolean) {
        val start = waitForStart(g = false) ?: throw IOException("the receiver didn't start")
        report { it.copy(file = file.name, bytes = 0, size = file.size) }
        file.open().use { sendData(it, if (oneK) 1024 else 128, start == START_CRC, ymodem = false, streaming = false) }
        report { it.copy(files = it.files + 1) }
    }

    /** a YMODEM batch (YMODEM-G if [g] and the receiver asks with G; asked with C, plain YMODEM) */
    fun sendY(files: List<Outgoing>, g: Boolean = false) {
        for (f in files) {
            val start = waitForStart(g)
            if (start == null || start == START_CHECKSUM) throw IOException("the receiver didn't start")
            val streaming = start == START_G
            val head = ByteArray(128)
            val meta = (f.name.substringAfterLast('/').encodeToByteArray() + 0) +
                ("${if (f.size >= 0) f.size else 0} ${(f.mtime.coerceAtLeast(0)).toString(8)} 0").encodeToByteArray()
            val block0 = if (meta.size > 128) ByteArray(1024) else head
            meta.copyInto(block0, 0, 0, minOf(meta.size, block0.size))
            if (streaming) io.write(blockBytes(0, block0, true)) else sendBlock(0, block0, true)
            report { it.copy(file = f.name, bytes = 0, size = f.size) }
            if (waitForStart(g) != start) throw IOException("the receiver didn't ask for the data")
            f.open().use { sendData(it, 1024, useCrc = true, ymodem = true, streaming = streaming) }
            report { it.copy(files = it.files + 1) }
        }
        // the batch over: an empty block 0
        val end0 = waitForStart(g)
        if (end0 == null || end0 == START_CHECKSUM) throw IOException("the receiver didn't ask for the end")
        // (rb exits as it takes it: its ACK can be lost with it, and every file is in)
        val end = blockBytes(0, ByteArray(128), true)
        for (attempt in 0 until 3) {
            io.write(end)
            if (answer(allowC = true, wait = 2_000) == ACK) return
        }
    }

    /** C ([START_CRC]), NAK ([START_CHECKSUM]) or, when [g], G ([START_G]) from the receiver; null if none came */
    private fun waitForStart(g: Boolean): Int? {
        val end = monotonicMs() + 60_000
        while (monotonicMs() < end) {
            when (io.read(timeoutMs)) {
                G -> if (g) return START_G
                C -> return START_CRC
                NAK -> return START_CHECKSUM
                CAN -> if (io.read(1000) == CAN) throw TransferCancelled("cancelled by the receiver")
            }
        }
        return null
    }

    /**
     * the file's data sent in blocks of [blockSize] (a short last one in 128, padded with SUB),
     * then EOT until acknowledged; [streaming]: the blocks with no wait for an ACK (YMODEM-G)
     */
    private fun sendData(input: Source, blockSize: Int, useCrc: Boolean, ymodem: Boolean, streaming: Boolean) {
        var n = 1
        var sent = 0L
        val buf = ByteArray(blockSize)
        while (true) {
            var len = 0
            while (len < blockSize) {
                val r = input.read(buf, len, blockSize - len)
                if (r < 0) break
                len += r
            }
            if (len == 0) break
            // a short last block in 128 when it fits (less padding), padded with SUB
            val size = if (len <= 128) 128 else blockSize
            val data = buf.copyOf(size)
            for (i in len until size) data[i] = SUB.toByte()
            if (streaming) {
                io.write(blockBytes(n, data, true))
                gCheck()
            } else {
                sendBlock(n, data, useCrc)
            }
            sent += len
            n++
            report { it.copy(bytes = sent) }
            if (len < blockSize) break
        }
        // the end: EOT until it's answered ACK (YMODEM answers the first NAK)
        var tries = 0
        while (true) {
            io.write(EOT)
            if (answer(allowC = false) == ACK) return
            if (++tries > MAX_ERRORS) throw IOException("the end not acknowledged")
        }
    }

    /** YMODEM-G: the receiver's CAN CAN, if it sent them (no wait) */
    private fun gCheck() {
        while (io.available()) {
            if (io.read(0) == CAN && io.read(1000) == CAN) throw TransferCancelled("cancelled by the receiver")
        }
    }

    /** a block sent until it's acknowledged */
    private fun sendBlock(n: Int, data: ByteArray, useCrc: Boolean) {
        val bytes = blockBytes(n, data, useCrc)
        var errors = 0
        while (true) {
            io.write(bytes)
            if (answer(allowC = n == 0) == ACK) return
            if (++errors > MAX_ERRORS) throw IOException("too many errors on the line")
            report { it.copy(errors = it.errors + 1) }
        }
    }

    /** block [n]'s bytes: SOH or STX, its number and complement, [data], its CRC-16 or checksum */
    private fun blockBytes(n: Int, data: ByteArray, useCrc: Boolean): ByteArray {
        val out = Bytes()
        out.write(if (data.size == 1024) STX else SOH)
        out.write(n and 0xff)
        out.write(0xff - (n and 0xff))
        out.write(data)
        if (useCrc) {
            val c = Crc.crc16(data)
            out.write(c shr 8)
            out.write(c and 0xff)
        } else {
            out.write(data.sumOf { it.toInt() and 0xff } and 0xff)
        }
        return out.toByteArray()
    }

    /**
     * the receiver's answer to what was sent: ACK, or NAK (also for a
     * timeout); other bytes (its messages, noise) let go by; C taken as
     * an ACK for block 0 when [allowC] (receivers that ask for the data
     * straight away)
     */
    private fun answer(allowC: Boolean, wait: Long = timeoutMs): Int {
        val deadline = monotonicMs() + wait
        while (true) {
            val left = deadline - monotonicMs()
            if (left <= 0) return NAK
            when (io.read(left)) {
                -1 -> return NAK
                ACK -> return ACK
                NAK -> return NAK
                C -> if (allowC) return ACK
                CAN -> if (io.read(1000) == CAN) throw TransferCancelled("cancelled by the receiver")
            }
        }
    }

    companion object {
        const val SOH = 0x01
        const val STX = 0x02
        const val EOT = 0x04
        const val ACK = 0x06
        const val NAK = 0x15
        const val CAN = 0x18
        const val SUB = 0x1a
        const val C = 'C'.code
        const val G = 'G'.code
        const val START_CHECKSUM = 0
        const val START_CRC = 1
        const val START_G = 2
        const val MAX_ERRORS = 10
    }
}
