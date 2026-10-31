// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.transfer

import org.dmodem.app.io.IOException

/**
 * Intel HEX and Motorola S-records: a binary image as lines of text, each
 * a record of an address, its data and a checksum, for EPROM programmers,
 * monitor ROMs and boot loaders, which take them on a plain serial line.
 *
 * Intel HEX: ":" count, address, type, data, checksum (the two's
 * complement of the bytes' sum); types 00 data, 01 the end, 02 an extended
 * segment address (×16), 04 an extended linear address (the upper 16
 * bits), 03 and 05 a start address (taken, unused).
 *
 * S-records: "S" type, count (address, data and checksum's bytes),
 * address, data, checksum (the one's complement of the bytes' sum); S0 a
 * header, S1/S2/S3 data at 16/24/32-bit addresses, S5/S6 the count of
 * data records, S7/S8/S9 the end (with a start address).
 */
object HexRecords {
    enum class Format { INTEL, SREC }

    /** the data records' size */
    const val RECORD_BYTES = 16

    /** [data] at [base] as [format]'s records, the end record last (S-records: a header of [name] first) */
    fun encode(data: ByteArray, format: Format, base: Long = 0, name: String = ""): List<String> =
        when (format) {
            Format.INTEL -> intel(data, base)
            Format.SREC -> srec(data, base, name)
        }

    /** [data] at [base] as Intel HEX: an extended linear address at each 64 KB, data records not crossing one, the end record */
    private fun intel(data: ByteArray, base: Long): List<String> {
        val out = ArrayList<String>()
        var upper = -1L
        var i = 0
        while (i < data.size) {
            val addr = base + i
            // (a record doesn't cross a 64 KB boundary: its extended address first when it changes)
            if (addr ushr 16 != upper) {
                upper = addr ushr 16
                out.add(intelRecord(0, 4, byteArrayOf((upper ushr 8).toByte(), upper.toByte())))
            }
            val room = (0x10000 - (addr and 0xffff)).toInt()
            val n = minOf(RECORD_BYTES, data.size - i, room)
            out.add(intelRecord((addr and 0xffff).toInt(), 0, data.copyOfRange(i, i + n)))
            i += n
        }
        out.add(":00000001FF")
        return out
    }

    /** an Intel HEX record of [type] at [addr], its checksum after */
    private fun intelRecord(addr: Int, type: Int, data: ByteArray): String {
        val bytes = byteArrayOf(data.size.toByte(), (addr ushr 8).toByte(), addr.toByte(), type.toByte()) + data
        val sum = bytes.sumOf { it.toInt() and 0xff }
        return ":" + hex(bytes) + hex(byteArrayOf((-sum).toByte()))
    }

    /** [data] at [base] as S-records: the header, data records (S1, S2 or S3, the smallest that holds it), the count, the end */
    private fun srec(data: ByteArray, base: Long, name: String): List<String> {
        val last = base + maxOf(data.size, 1) - 1
        // (the smallest addresses that hold the image: S1 16 bits, S2 24, S3 32)
        val (dataType, addrBytes, endType) = when {
            last <= 0xffff -> Triple(1, 2, 9)
            last <= 0xffffff -> Triple(2, 3, 8)
            else -> Triple(3, 4, 7)
        }
        val out = ArrayList<String>()
        out.add(srecRecord(0, 2, 0, name.encodeToByteArray().take(60).toByteArray()))
        var i = 0
        var count = 0
        while (i < data.size) {
            val n = minOf(RECORD_BYTES, data.size - i)
            out.add(srecRecord(dataType, addrBytes, base + i, data.copyOfRange(i, i + n)))
            i += n
            count++
        }
        if (count <= 0xffff) out.add(srecRecord(5, 2, count.toLong(), ByteArray(0)))
        out.add(srecRecord(endType, addrBytes, base, ByteArray(0)))
        return out
    }

    /** an S-record of [type] with an address of [addrBytes] bytes, its count first and checksum after */
    private fun srecRecord(type: Int, addrBytes: Int, addr: Long, data: ByteArray): String {
        val a = ByteArray(addrBytes) { k -> (addr ushr (8 * (addrBytes - 1 - k))).toByte() }
        val body = byteArrayOf((addrBytes + data.size + 1).toByte()) + a + data
        val sum = body.sumOf { it.toInt() and 0xff }
        return "S$type" + hex(body) + hex(byteArrayOf((sum.inv()).toByte()))
    }

    private val DIGITS = "0123456789ABCDEF"

    /** [b] in upper-case hex */
    private fun hex(b: ByteArray): String = buildString(b.size * 2) {
        for (x in b) {
            append(DIGITS[(x.toInt() ushr 4) and 15])
            append(DIGITS[x.toInt() and 15])
        }
    }

    /** whether [text] is [format]'s records already (every line one, the end record among them): sent as it is */
    fun isRecords(text: String, format: Format): Boolean {
        val d = Decoder(format)
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            val ok = try { d.line(l) } catch (_: IOException) { false }
            if (!ok && !d.ended) return false
        }
        return d.ended
    }

    /**
     * Records read a line at a time ([line]): the data at their addresses,
     * until the end record ([ended]). Lines that aren't records (a prompt,
     * an echo) are passed over; a record whose checksum or length is wrong
     * is an error.
     */
    class Decoder(private val format: Format) {
        /** the data, by address */
        private val chunks = ArrayList<Pair<Long, ByteArray>>()
        var ended = false
            private set
        /** data records taken */
        var records = 0
            private set
        /** the data's bytes so far */
        var bytes = 0L
            private set
        /** the S0 header's text, if any */
        var header = ""
            private set
        private var upper = 0L

        /** a line: true if it was a record of the format (an error if it's a bad one) */
        fun line(raw: String): Boolean {
            val l = raw.trim()
            if (ended || l.isEmpty()) return false
            return when (format) {
                Format.INTEL -> intel(l)
                Format.SREC -> srec(l)
            }
        }

        /** the bytes the hex [digits] spell (an error, naming [what], if they aren't hex) */
        private fun bytesOf(digits: String, what: String): ByteArray {
            if (digits.length % 2 != 0 || digits.any { it !in '0'..'9' && it !in 'A'..'F' && it !in 'a'..'f' }) {
                throw IOException("$what: not hex digits")
            }
            return ByteArray(digits.length / 2) { k -> digits.substring(2 * k, 2 * k + 2).toInt(16).toByte() }
        }

        /** an Intel HEX line: false if it isn't a record; its data, the end or an extended address taken */
        private fun intel(l: String): Boolean {
            if (!l.startsWith(":")) return false
            val b = bytesOf(l.substring(1), "Intel HEX record ${records + 1}")
            if (b.size < 5 || b.size != (b[0].toInt() and 0xff) + 5) throw IOException("Intel HEX: a record's length is wrong: $l")
            if ((b.sumOf { it.toInt() and 0xff } and 0xff) != 0) throw IOException("Intel HEX: a checksum is wrong: $l")
            val n = b[0].toInt() and 0xff
            val addr = ((b[1].toInt() and 0xff) shl 8) or (b[2].toInt() and 0xff)
            val data = b.copyOfRange(4, 4 + n)
            when (b[3].toInt() and 0xff) {
                0 -> put(upper + addr, data)
                1 -> ended = true
                2 -> upper = (((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)).toLong() shl 4
                4 -> upper = (((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)).toLong() shl 16
                3, 5 -> {}
                else -> throw IOException("Intel HEX: an unknown record type: $l")
            }
            return true
        }

        /** an S-record line: false if it isn't a record; its header, data or the end taken */
        private fun srec(l: String): Boolean {
            if (l.length < 2 || (l[0] != 'S' && l[0] != 's') || !l[1].isDigit()) return false
            val type = l[1] - '0'
            val b = bytesOf(l.substring(2), "S-record ${records + 1}")
            if (b.isEmpty() || b.size != (b[0].toInt() and 0xff) + 1) throw IOException("S-record: a record's length is wrong: $l")
            if ((b.sumOf { it.toInt() and 0xff } and 0xff) != 0xff) throw IOException("S-record: a checksum is wrong: $l")
            val addrBytes = when (type) {
                0, 1, 5, 9 -> 2
                2, 6, 8 -> 3
                3, 7 -> 4
                else -> throw IOException("S-record: an unknown type: $l")
            }
            if (b.size < 1 + addrBytes + 1) throw IOException("S-record: too short: $l")
            var addr = 0L
            for (k in 0 until addrBytes) addr = (addr shl 8) or (b[1 + k].toLong() and 0xff)
            val data = b.copyOfRange(1 + addrBytes, b.size - 1)
            when (type) {
                0 -> header = data.decodeToString().trim { it <= ' ' }
                1, 2, 3 -> put(addr, data)
                5, 6 -> {}
                else -> ended = true
            }
            return true
        }

        /** [data] at [addr] kept, and counted */
        private fun put(addr: Long, data: ByteArray) {
            chunks.add(addr to data)
            records++
            bytes += data.size
        }

        /** the lowest address with data (0 for none) */
        val start: Long get() = chunks.minOfOrNull { it.first } ?: 0

        /**
         * the image from the lowest address to the end of the highest
         * record, gaps as an erased EPROM has them (0xFF)
         */
        fun image(): ByteArray {
            if (chunks.isEmpty()) return ByteArray(0)
            val first = chunks.minOf { it.first }
            val end = chunks.maxOf { it.first + it.second.size }
            if (end - first > MAX_IMAGE) throw IOException("the records span ${end - first} bytes: too many for an image")
            val out = ByteArray((end - first).toInt()) { 0xff.toByte() }
            // (in the order they came: a record written again wins)
            for ((a, d) in chunks) d.copyInto(out, (a - first).toInt())
            return out
        }
    }

    /** the largest image put together (64 MB) */
    const val MAX_IMAGE = 64L shl 20
}

/**
 * A hex records transfer on a line: no protocol but the records' own
 * checksums. Sending: the file as records, a line each (CR LF), or as it
 * is if it's the format's records already. Receiving: the records that
 * come, until the end record (or a pause once some have come), as the
 * binary image they hold.
 */
class HexTransfer(
    private val io: TransferIo,
    private val format: HexRecords.Format,
    private val progress: (Progress) -> Unit,
) {
    /** [file] sent */
    fun send(file: Outgoing) {
        val data = readAll(file)
        val text = data.decodeToString()
        val lines = if (HexRecords.isRecords(text, format)) {
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        } else {
            HexRecords.encode(data, format, 0, file.name)
        }
        val total = lines.sumOf { it.length + 2L }
        var sent = 0L
        for (l in lines) {
            if (io.isCancelled) throw TransferCancelled("cancelled")
            io.write((l + "\r\n").encodeToByteArray())
            sent += l.length + 2
            progress(Progress(file.name, sent, total))
        }
        progress(Progress(file.name, sent, total, files = 1))
    }

    /** the records received, their image into [into] as [name]: its name there */
    fun receive(into: Incoming, name: String): List<String> {
        val d = HexRecords.Decoder(format)
        val line = StringBuilder()
        while (!d.ended) {
            val c = io.read(if (d.records == 0) FIRST_MS else PAUSE_MS)
            if (c < 0) {
                if (d.records == 0) throw IOException("no ${label()} records came")
                // (a sender that sends no end record: done once it pauses)
                break
            }
            if (c == '\r'.code || c == '\n'.code) {
                if (line.isNotEmpty()) {
                    d.line(line.toString())
                    line.setLength(0)
                    progress(Progress(name, d.bytes))
                }
            } else if (line.length < MAX_LINE) {
                line.append(c.toChar())
            }
        }
        val image = d.image()
        val (sink, got) = into.create(name, image.size.toLong())
        try {
            sink.write(image)
            sink.close()
        } catch (e: IOException) {
            into.abandon(got)
            throw e
        }
        into.done(got, 0)
        progress(Progress(got, image.size.toLong(), image.size.toLong(), files = 1))
        return listOf(got)
    }

    /** the format's name, for messages */
    private fun label() = if (format == HexRecords.Format.INTEL) "Intel HEX" else "S-record"

    /** [file]'s bytes, all of them (an error past [HexRecords.MAX_IMAGE]) */
    private fun readAll(file: Outgoing): ByteArray {
        val out = org.dmodem.app.io.Bytes()
        file.open().use { src ->
            val buf = ByteArray(8192)
            while (true) {
                val n = src.read(buf, 0, buf.size)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size > HexRecords.MAX_IMAGE) throw IOException("${file.name}: too big for ${label()}")
            }
        }
        return out.toByteArray()
    }

    companion object {
        /** how long the first record may take to come (the other side started by hand), and a pause after */
        const val FIRST_MS = 60_000L
        const val PAUSE_MS = 10_000L
        /** a line longer than any record: the rest of it dropped */
        const val MAX_LINE = 600
    }
}
