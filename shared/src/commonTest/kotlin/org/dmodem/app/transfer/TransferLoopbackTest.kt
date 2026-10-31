// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.transfer

import org.dmodem.app.io.Bytes
import org.dmodem.app.io.Sink
import org.dmodem.app.io.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The transfers both ways in memory, the sender's line the receiver's
 * (common Kotlin: the JVM, and natively as iOS has it): XMODEM, YMODEM,
 * ZMODEM and Kermit, each file as it was sent; Kermit's dates.
 */
class TransferLoopbackTest {
    /** files in memory, and what came */
    private class Memory : Incoming {
        val files = LinkedHashMap<String, Bytes>()
        val times = HashMap<String, Long>()
        /** a new file [name] in memory: its stream and that name */
        override fun create(name: String, size: Long): Pair<Sink, String> = Bytes().let { b -> files[name] = b; b to name }
        /** file [name]'s time noted */
        override fun done(name: String, mtime: Long) { times[name] = mtime }
        /** file [name] forgotten */
        override fun abandon(name: String) { files.remove(name) }
    }

    /** [data] as a file [name] of time [mtime] to send */
    private fun outgoing(name: String, data: ByteArray, mtime: Long = 1_700_000_000) =
        Outgoing(name, data.size.toLong(), mtime) {
            object : Source {
                var at = 0
                /** the next of [data], into [b]: how much, -1 at its end */
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (at >= data.size) return -1
                    val n = minOf(len, data.size - at)
                    data.copyInto(b, off, at, at + n)
                    at += n
                    return n
                }
            }
        }

    /** two ends of a line: what one writes, the other reads */
    private fun line(): Pair<TransferIo, TransferIo> {
        lateinit var b: TransferIo
        val a = TransferIo { bytes -> b.feed(bytes) }
        b = TransferIo { bytes -> a.feed(bytes) }
        return a to b
    }

    /** [send] on one end, [receive] on the other, at once */
    private fun <T> both(send: (TransferIo) -> Unit, receive: (TransferIo) -> T): T = runBlocking {
        val (a, b) = line()
        val r = async(Dispatchers.Default) { receive(b) }
        val s = async(Dispatchers.Default) { send(a) }
        s.await()
        r.await()
    }

    private val data = Random(7).nextBytes(40_000)
    private val small = "hello, modem\n".encodeToByteArray()

    /** XMODEM-1K: what came starts with the file (the last block padded) */
    @Test
    fun xmodem() {
        val m = Memory()
        both({ XYModem(it).sendX(outgoing("x.bin", data), oneK = true) }, { XYModem(it).receiveX(m, "x.bin") })
        // (XMODEM pads the last block: what came starts with the file)
        assertContentEquals(data, m.files.getValue("x.bin").toByteArray().copyOf(data.size))
    }

    /** YMODEM: a batch of two, each file whole, with its time */
    @Test
    fun ymodem() {
        val m = Memory()
        val names = both({ XYModem(it).sendY(listOf(outgoing("a.bin", data), outgoing("b.txt", small))) }, { XYModem(it).receiveY(m) })
        assertEquals(listOf("a.bin", "b.txt"), names)
        assertContentEquals(data, m.files.getValue("a.bin").toByteArray())
        assertContentEquals(small, m.files.getValue("b.txt").toByteArray())
        assertEquals(1_700_000_000, m.times["a.bin"])
    }

    /** ZMODEM: a batch of two, each file whole */
    @Test
    fun zmodem() {
        val m = Memory()
        val names = both({ ZModem(it).send(listOf(outgoing("z.bin", data), outgoing("small.txt", small))) }, { ZModem(it).receive(m) })
        assertEquals(listOf("z.bin", "small.txt"), names)
        assertContentEquals(data, m.files.getValue("z.bin").toByteArray())
        assertContentEquals(small, m.files.getValue("small.txt").toByteArray())
    }

    /** Kermit: a batch of two, each file whole, with its time there and back */
    @Test
    fun kermit() {
        val m = Memory()
        both({ Kermit(it).send(listOf(outgoing("k.bin", data), outgoing("note.txt", small))) }, { Kermit(it).receive(m) })
        assertContentEquals(data, m.files.getValue("k.bin").toByteArray())
        assertContentEquals(small, m.files.getValue("note.txt").toByteArray())
        // (its date: local time there and back)
        assertEquals(1_700_000_000, m.times["k.bin"])
    }

    /** Kermit's dates: days to the calendar and back */
    @Test
    fun kermitsCalendar() {
        assertEquals(Triple(1970, 1, 1), Kermit.civil(0))
        assertEquals(Triple(2000, 2, 29), Kermit.civil(11016))
        assertEquals(Triple(2026, 10, 17), Kermit.civil(20743))
        for (d in listOf(-1L, 0, 59, 11016, 20743, 50000)) {
            val (y, m, day) = Kermit.civil(d)
            assertEquals(d, Kermit.days(y, m, day))
        }
    }
}
