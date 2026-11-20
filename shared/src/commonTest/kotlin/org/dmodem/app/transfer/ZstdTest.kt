// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.transfer

import org.dmodem.app.io.Bytes
import org.dmodem.app.io.Sink
import org.dmodem.app.io.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * zstd: the codec there and back, the specification's test vector, and
 * ZMODEM with zstd (docs/zmodem-zstd.md of D-Modem) in memory: smaller on
 * the line, whole after errors, resumed, plain when one side doesn't take it.
 */
class ZstdTest {
    /** n bytes of text that compresses, as a BBS's text files do */
    private fun text(n: Int, seed: Int): ByteArray {
        val words = listOf("modem", "carrier", "the", "BBS", "file", "area", "sysop", "message", "ZMODEM",
            "of", "and", "a", "upload", "download", "baud", "line", "\r\n")
        val r = Random(seed)
        val b = Bytes()
        while (b.size < n) {
            b.write(words[r.nextInt(words.size)].encodeToByteArray())
            b.write(' '.code)
        }
        return b.toByteArray().copyOf(n)
    }

    // XXH64's known values
    @Test
    fun xxHash64() {
        assertEquals(0xEF46DB3751D8E999uL.toLong(), XxHash64.hash(ByteArray(0), 0, 0))
        assertEquals(0xD24EC4F1A98C6E5BuL.toLong(), XxHash64.hash("a".encodeToByteArray(), 0, 1))
        val abc = "Nobody inspects the spammish repetition".encodeToByteArray()
        assertEquals(0xFBCEA83C8A378BF1uL.toLong(), XxHash64.hash(abc, 0, abc.size))
    }

    /** the specification's frame, from the reference zstd 1.5.5 */
    @Test
    fun specVector() {
        val hex = "28b52ffd2466550100240244 2d4d6f64656d205a4d4f44454d207a73746420746573742076656374" +
            "6f722e0d0a01008a82aa273221699d"
        val frame = hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(55, frame.size)
        val want = "D-Modem ZMODEM zstd test vector.\r\n".repeat(3).encodeToByteArray()
        assertContentEquals(want, Zstd.decode(frame, 1024))
        // a byte of it wrong: the checksum (or the data) says so
        val bad = frame.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertFailsWith<Zstd.FormatException> { Zstd.decode(bad, 1024) }
        // over the limit
        assertFailsWith<Zstd.FormatException> { Zstd.decode(frame, 100) }
    }

    // there and back: empty, short, random, text, around the header's size limits
    @Test
    fun roundTrip() {
        val r = Random(3)
        val cases = listOf(
            ByteArray(0), byteArrayOf(42), ByteArray(1000) { 7 }, r.nextBytes(255), r.nextBytes(256),
            r.nextBytes(70_000), text(255, 1), text(256, 2), text(65_791, 3), text(65_792, 4),
            text(64 * 1024, 5), text(200_000, 6), ByteArray(150_000) { (it / 1000).toByte() },
        )
        for (c in cases) {
            val f = Zstd.encode(c)
            assertContentEquals(c, Zstd.decode(f, 256 * 1024), "${c.size} bytes")
        }
        val t = text(64 * 1024, 9)
        val f = Zstd.encode(t)
        assertTrue(f.size * 3 < t.size, "${t.size} bytes of text in ${f.size}")
    }

    // ------------------------------------------------------------------
    // ZMODEM with zstd

    private class Memory : Incoming {
        val files = LinkedHashMap<String, Bytes>()
        var had: ByteArray? = null
        // a new file, in memory
        override fun create(name: String, size: Long): Pair<Sink, String> = Bytes().let { b -> files[name] = b; b to name }
        // a file dropped
        override fun abandon(name: String) { files.remove(name) }
        // the part in had, if any, to continue
        override fun resume(name: String, size: Long): Resumed? {
            val h = had ?: return null
            val b = Bytes().also { it.write(h) }
            files[name] = b
            return Resumed(b, name, h.size.toLong())
        }
    }

    // data, offered as the file name
    private fun outgoing(name: String, data: ByteArray) = Outgoing(name, data.size.toLong(), 1_700_000_000) {
        object : Source {
            var at = 0
            // up to len bytes of data
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= data.size) return -1
                val n = minOf(len, data.size - at)
                data.copyInto(b, off, at, at + n)
                at += n
                return n
            }
        }
    }

    /** bytes the sender put on the line */
    @Volatile private var sent = 0L

    /** [send] on one end, [receive] on the other; every [every]th byte from the sender damaged (0: none) */
    private fun <T> both(every: Int, send: (TransferIo) -> Unit, receive: (TransferIo) -> T): T = runBlocking {
        lateinit var b: TransferIo
        var n = 0L
        val a = TransferIo { bytes ->
            if (every > 0) for (i in bytes.indices) if (++n % every == 0L) bytes[i] = (bytes[i].toInt() xor 0x55).toByte()
            sent += bytes.size
            b.feed(bytes)
        }
        b = TransferIo { bytes -> a.feed(bytes) }
        sent = 0
        val r = async(Dispatchers.Default) { receive(b) }
        val s = async(Dispatchers.Default) { send(a) }
        s.await()
        r.await()
    }

    // files of text, compressed: whole, and less than half on the line
    @Test
    fun zmodemZstd() {
        val m = Memory()
        val files = listOf(text(0, 1), text(1, 2), text(64 * 1024, 3), text(64 * 1024 + 1, 4), text(300_001, 5))
        val total = files.sumOf { it.size }
        both(0, { ZModem(it).send(files.mapIndexed { i, d -> outgoing("f$i.txt", d) }) }, { ZModem(it).receive(m) })
        for ((i, d) in files.withIndex()) assertContentEquals(d, m.files.getValue("f$i.txt").toByteArray(), "f$i.txt")
        assertTrue(sent * 2 < total, "$sent bytes on the line for $total")
    }

    // plain where the receiver doesn't take zstd, or the file is a .zip
    @Test
    fun zmodemZstdOff() {
        val d = text(100_000, 6)
        for ((name, rxZstd) in listOf("plain.txt" to false, "archive.zip" to true)) {
            val m = Memory()
            both(0, { ZModem(it).send(listOf(outgoing(name, d))) }, { ZModem(it, zstd = rxZstd).receive(m) })
            assertContentEquals(d, m.files.getValue(name).toByteArray(), name)
            assertTrue(sent >= d.size, "$name: $sent bytes on the line: compressed")
        }
    }

    // whole on a line that damages bytes
    @Test
    fun zmodemZstdNoisy() {
        val m = Memory()
        val d = text(150_000, 7)
        both(9_000, { ZModem(it).send(listOf(outgoing("noisy.txt", d))) }, { ZModem(it).receive(m) })
        assertContentEquals(d, m.files.getValue("noisy.txt").toByteArray())
    }

    // a part continued, with and without zstd
    @Test
    fun zmodemResume() {
        for (name in listOf("resume.txt", "resume.zip")) {
            val d = text(200_000, 8)
            val m = Memory().also { it.had = d.copyOf(123_457) }
            both(0, { ZModem(it).send(listOf(outgoing(name, d))) }, { ZModem(it).receive(m) })
            assertContentEquals(d, m.files.getValue(name).toByteArray(), name)
            if (name.endsWith(".zip")) assertTrue(sent < d.size - 123_457 + 2_000, "$name: $sent bytes: not resumed")
        }
    }
}
