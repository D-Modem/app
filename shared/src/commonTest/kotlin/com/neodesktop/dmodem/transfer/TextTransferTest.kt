// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.transfer

import com.neodesktop.dmodem.io.Bytes
import com.neodesktop.dmodem.io.Sink
import com.neodesktop.dmodem.io.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plain text ([TextTransfer]): a file's bytes sent as they are (CR, LF,
 * CR LF, tabs, bytes past ASCII: none changed), received as they come
 * until the line goes quiet; through [Transfer], no cancel bytes sent.
 */
class TextTransferTest {
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

    private val text = ("line one\r\nline two\nline three\rtabbed\there\r\n" + "é ü — 東京\n").encodeToByteArray() +
        ByteArray(3000) { (it % 256).toByte() }

    /** a file's bytes go on the line unchanged and are received unchanged until the line goes quiet */
    @Test
    fun sentVerbatimAndReceivedUntilQuiet() = runBlocking {
        // the two ends of a line
        lateinit var b: TransferIo
        val wire = Bytes()
        val a = TransferIo { bytes -> wire.write(bytes, 0, bytes.size); b.feed(bytes) }
        b = TransferIo { bytes -> a.feed(bytes) }
        val mem = Memory()
        val got = async(Dispatchers.Default) { TextTransfer(b, {}, quietMs = 500).receive(mem, "capture.txt") }
        TextTransfer(a, {}).send(outgoing("notes.txt", text))
        assertEquals(listOf("capture.txt"), got.await())
        // on the line, and received: the bytes as they were
        assertContentEquals(text, wire.toByteArray())
        assertContentEquals(text, mem.files["capture.txt"]!!.toByteArray())
    }

    /** through [Transfer] the text is sent as it is, and a cancel sends nothing */
    @Test
    fun throughTransferWithNothingAddedOrSentOnCancel() = runBlocking {
        val wire = Bytes()
        val raw = Bytes()
        val ended = kotlinx.coroutines.CompletableDeferred<TransferState>()
        val t = Transfer(Protocol.TEXT, true, { bytes -> wire.write(bytes, 0, bytes.size) }, { bytes -> raw.write(bytes, 0, bytes.size) }, {}) { s, _ -> ended.complete(s) }
        t.send(listOf(outgoing("notes.txt", text)))
        val s = ended.await()
        assertEquals("", s.result)
        assertEquals(1, s.progress.files)
        assertContentEquals(text, wire.toByteArray())
        // (no protocol to cancel: nothing sent for it)
        t.cancel()
        assertEquals(0, raw.size)
        assertTrue(!Protocol.TEXT.batch)
    }
}
