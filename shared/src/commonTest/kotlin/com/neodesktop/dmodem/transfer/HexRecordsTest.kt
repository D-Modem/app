// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.transfer

import com.neodesktop.dmodem.io.Bytes
import com.neodesktop.dmodem.io.IOException
import com.neodesktop.dmodem.io.Sink
import com.neodesktop.dmodem.io.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Intel HEX and S-records ([HexRecords]): the published examples, images
 * there and back (across 64 KB, at a base address), their checksums
 * checked, other lines passed over; and sent on a line and received
 * ([HexTransfer], through [Transfer]).
 */
class HexRecordsTest {
    /** [lines] given to a decoder of [format]: it, after them */
    private fun decode(format: HexRecords.Format, lines: List<String>): HexRecords.Decoder =
        HexRecords.Decoder(format).apply { for (l in lines) line(l) }

    /** Intel HEX encodes the spec's example exactly, and decodes back to its bytes at its address */
    @Test
    fun intelHexAsItsSpecHasIt() {
        // (Intel's example: 3 bytes at 0030, then the end)
        val lines = HexRecords.encode(byteArrayOf(0x02, 0x33, 0x7a), HexRecords.Format.INTEL, base = 0x30)
        assertEquals(listOf(":020000040000FA", ":0300300002337A1E", ":00000001FF"), lines)
        val d = decode(HexRecords.Format.INTEL, lines)
        assertTrue(d.ended)
        assertEquals(0x30L, d.start)
        assertContentEquals(byteArrayOf(0x02, 0x33, 0x7a), d.image())
    }

    /** the well-known S-record example decodes to its header, address and bytes */
    @Test
    fun sRecordsAsTheirExampleHasThem() {
        // (the well-known S1 example: 16 bytes at 7AF0)
        val d = decode(HexRecords.Format.SREC, listOf("S00F000068656C6C6F202020202000003C", "S1137AF00A0A0D0000000000000000000000000061", "S9030000FC"))
        assertTrue(d.ended)
        assertEquals("hello", d.header)
        assertEquals(0x7af0L, d.start)
        assertContentEquals(byteArrayOf(0x0a, 0x0a, 0x0d) + ByteArray(13), d.image())
    }

    /** images of either format there and back at any base, across 64 KB, recognised as their own format only; S1, S2 or S3 by the end address */
    @Test
    fun imagesThereAndBack() {
        val data = Random(7).nextBytes(70_000)
        for (format in HexRecords.Format.entries) {
            for (base in listOf(0L, 0x8000L, 0x0123_4560L)) {
                val lines = HexRecords.encode(data, format, base, "rom.bin")
                val d = decode(format, lines)
                assertTrue(d.ended, "$format at $base: ended")
                assertEquals(base, d.start)
                assertContentEquals(data, d.image(), "$format at $base")
                assertTrue(HexRecords.isRecords(lines.joinToString("\r\n"), format))
                assertFalse(HexRecords.isRecords(lines.joinToString("\r\n"), HexRecords.Format.entries.first { it != format }))
            }
        }
        // (S-records' address size by the image's end: S1, S2, S3)
        assertTrue(HexRecords.encode(ByteArray(16), HexRecords.Format.SREC)[1].startsWith("S1"))
        assertTrue(HexRecords.encode(ByteArray(16), HexRecords.Format.SREC, 0x10000)[1].startsWith("S2"))
        assertTrue(HexRecords.encode(ByteArray(16), HexRecords.Format.SREC, 0x1000000)[1].startsWith("S3"))
    }

    /** gaps between records are erased EPROM (0xff), and lines that aren't records are passed over */
    @Test
    fun gapsAreErasedEpromAndOtherLinesPassedOver() {
        val d = decode(
            HexRecords.Format.INTEL,
            listOf("> load", ":0100000041BE", "garbage", ":0100040042B9", ":00000001FF"),
        )
        assertContentEquals(byteArrayOf(0x41, -1, -1, -1, 0x42), d.image())
    }

    /** a wrong checksum or length is an error */
    @Test
    fun aWrongChecksumIsAnError() {
        assertFailsWith<IOException> { decode(HexRecords.Format.INTEL, listOf(":0300300002337A1F")) }
        assertFailsWith<IOException> { decode(HexRecords.Format.SREC, listOf("S1137AF00A0A0D0000000000000000000000000062")) }
        assertFailsWith<IOException> { decode(HexRecords.Format.INTEL, listOf(":0400300002337A1E")) }
    }

    /** files in memory */
    private class Memory : Incoming {
        val files = LinkedHashMap<String, Bytes>()
        /** a new file [name] in memory: its stream and that name */
        override fun create(name: String, size: Long): Pair<Sink, String> = Bytes().let { b -> files[name] = b; b to name }
    }

    /** [data] as a file [name] to send */
    private fun outgoing(name: String, data: ByteArray) = Outgoing(name, data.size.toLong(), 0) {
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

    /** [file] sent by [protocol] on one end of a line, received on the other: what came, and the line's text */
    private fun sent(protocol: Protocol, file: Outgoing): Pair<Memory, String> = runBlocking {
        val mem = Memory()
        val text = StringBuilder()
        lateinit var b: Transfer
        val done = kotlinx.coroutines.CompletableDeferred<TransferState>()
        val sentDone = kotlinx.coroutines.CompletableDeferred<TransferState>()
        val a = Transfer(protocol, true, { bytes -> text.append(bytes.decodeToString()); b.io.feed(bytes) }, {}, {}) { s, _ -> sentDone.complete(s) }
        b = Transfer(protocol, false, { bytes -> a.io.feed(bytes) }, {}, {}) { s, _ -> done.complete(s) }
        b.receive(mem, "image.bin")
        a.send(listOf(file))
        val r = async(Dispatchers.Default) { done.await() }
        assertEquals("", sentDone.await().result)
        val end = r.await()
        assertEquals("", end.result, "received: ${end.result}")
        assertEquals(listOf("image.bin"), end.received)
        // (the sender's thread done with it: its end awaited above)
        mem to text.toString()
    }

    /** Intel HEX and S-records sent on a line arrive as the file, the line's text ending with the end record */
    @Test
    fun sentAndReceivedOnALine() {
        val data = Random(11).nextBytes(5000)
        for (p in listOf(Protocol.INTEL_HEX, Protocol.SREC)) {
            val (mem, text) = sent(p, outgoing("rom.bin", data))
            assertContentEquals(data, mem.files["image.bin"]!!.toByteArray(), p.label)
            // (text records, a line each)
            assertTrue(text.endsWith(if (p == Protocol.INTEL_HEX) ":00000001FF\r\n" else "S9030000FC\r\n"), text.takeLast(40))
        }
    }

    /** a file that is records already is sent as it is, only its line ends made CR LF */
    @Test
    fun aFileOfRecordsAlreadyIsSentAsItIs() {
        val records = ":0300300002337A1E\n:00000001FF\n"
        val (mem, text) = sent(Protocol.INTEL_HEX, outgoing("rom.hex", records.encodeToByteArray()))
        assertEquals(":0300300002337A1E\r\n:00000001FF\r\n", text)
        assertContentEquals(byteArrayOf(0x02, 0x33, 0x7a), mem.files["image.bin"]!!.toByteArray())
    }
}
