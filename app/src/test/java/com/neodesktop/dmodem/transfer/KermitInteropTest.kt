// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.transfer

import com.neodesktop.dmodem.TestLogs
import com.neodesktop.dmodem.local.LocalLink
import com.neodesktop.dmodem.local.LocalShellTest
import com.neodesktop.dmodem.local.Pty
import com.neodesktop.dmodem.terminal.TerminalLink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Kermit against G-Kermit and C-Kermit on a pseudo-terminal, as on a
 * remote's shell: files each way (binary, every byte value, runs for the
 * repeat counts), on a 7-bit line (the 8th bit prefixed), and with bytes
 * damaged on the line (packets asked for again).
 */
class KermitInteropTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val links = ArrayList<LocalLink>()

    companion object {
        /** skipped without gkermit; the host's pty library loaded */
        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue("gkermit isn't installed", File("/usr/bin/gkermit").canExecute())
            Pty.load(LocalShellTest.hostPty())
        }
    }

    /** the links closed */
    @After
    fun tearDown() = links.forEach { it.close() }

    /** [cmd] started in [dir] on a pty, and a line to it: every [damageIn]th or [damageOut]th byte damaged, the 8th bit stripped when [strip8] */
    private fun line(dir: File, cmd: List<String>, damageIn: Int = 0, damageOut: Int = 0, strip8: Boolean = false): Pair<LocalLink, TransferIo> {
        val l = LocalLink(cmd, mapOf("PATH" to "/usr/bin:/bin", "HOME" to dir.path), dir.path, 80, 24)
        links.add(l)
        val outCount = AtomicInteger()
        val io = TransferIo { b ->
            if (damageOut > 0) {
                for (i in b.indices) if (outCount.incrementAndGet() % damageOut == 0) b[i] = (b[i].toInt() xor 0x55).toByte()
            }
            l.write(b)
        }
        val inCount = AtomicInteger()
        l.addListener { data, n ->
            val b = data.copyOf(n)
            for (i in 0 until n) {
                if (strip8) b[i] = (b[i].toInt() and 0x7f).toByte()
                if (damageIn > 0 && inCount.incrementAndGet() % damageIn == 0) b[i] = (b[i].toInt() xor 0x55).toByte()
            }
            io.feed(b)
        }
        // (KERMIT_TRACE=1: the line both ways in the test's log, for a failing run)
        if (System.getenv("KERMIT_TRACE") != null) traceTo(io)
        assertTrue(l.start())
        return l to io
    }

    /** the line both ways, in the test's log */
    private fun traceTo(io: TransferIo) {
        io.trace = { inbound, b ->
            TestLogs.note(if (inbound) "<<" else ">>", b.joinToString("") { c ->
                val v = c.toInt() and 0xff
                if (v in 0x20..0x7e) v.toChar().toString() else "\\x%02x".format(v)
            })
        }
    }

    /** the program's exit status, once it's ended (or 30 seconds) */
    private fun waitExit(l: LocalLink): Int? {
        val end = System.currentTimeMillis() + 30_000
        while (l.state.value !is TerminalLink.State.Closed && System.currentTimeMillis() < end) Thread.sleep(20)
        return l.exitStatus
    }

    /** three files to send, made in [dir]: random binary, every byte value with runs, text */
    private fun files(dir: File): List<File> {
        dir.mkdirs()
        val big = File(dir, "binary.bin").apply { writeBytes(Random(7).nextBytes(64_000)) }
        val every = File(dir, "every-byte.dat").apply {
            writeBytes(ByteArray(4096) { it.toByte() } + ByteArray(3000) { '~'.code.toByte() } + ByteArray(2048) { '#'.code.toByte() } + ByteArray(500) { 0x80.toByte() })
        }
        val text = File(dir, "notes.txt").apply { writeText("hello from the other side\n".repeat(100)) }
        return listOf(big, every, text)
    }

    /** [names] are [src]'s, and each came into [into] the same */
    private fun received(src: List<File>, into: FolderIncoming, names: List<String>) {
        assertEquals(src.map { it.name }, names)
        for (f in src) assertArrayEquals(f.name, f.readBytes(), into.received.first { it.name == f.name }.readBytes())
    }

    /** files from gkermit -s received, the same, its progress counting three */
    @Test
    fun receivedFromGkermit() {
        val src = files(tmp.newFolder("src"))
        val (l, io) = line(src[0].parentFile!!, listOf("/usr/bin/gkermit", "-q", "-i", "-P", "-s") + src.map { it.name })
        val into = FolderIncoming(tmp.newFolder("into"))
        var last = Progress()
        val names = Kermit(io, { last = it }).receive(into)
        received(src, into, names)
        assertEquals(0, waitExit(l))
        assertEquals(3, last.files)
    }

    /** files sent to gkermit -r, the same there */
    @Test
    fun sentToGkermit() {
        val src = files(tmp.newFolder("src"))
        val dest = tmp.newFolder("dest")
        val (l, io) = line(dest, listOf("/usr/bin/gkermit", "-q", "-i", "-P", "-r"))
        Kermit(io).send(src.map { Outgoing.of(it) })
        assertEquals(0, waitExit(l))
        for (f in src) assertArrayEquals(f.name, f.readBytes(), File(dest, f.name).readBytes())
    }

    /** received and sent with gkermit with bytes damaged on the line: the same, errors corrected */
    @Test
    fun bothWaysOnANoisyLine() {
        val src = files(tmp.newFolder("src"))
        val (l, io) = line(src[0].parentFile!!, listOf("/usr/bin/gkermit", "-q", "-i", "-P", "-b", "2", "-s") + src.map { it.name }, damageIn = 7_001)
        val into = FolderIncoming(tmp.newFolder("into"))
        var last = Progress()
        received(src, into, Kermit(io, { last = it }, timeoutMs = 2_000).receive(into))
        assertEquals(0, waitExit(l))
        assertTrue("errors corrected: ${last.errors}", last.errors > 0)

        val dest = tmp.newFolder("dest")
        val (l2, io2) = line(dest, listOf("/usr/bin/gkermit", "-q", "-i", "-P", "-b", "2", "-r"), damageOut = 7_001)
        var last2 = Progress()
        Kermit(io2, { last2 = it }, timeoutMs = 2_000).send(src.map { Outgoing.of(it) })
        assertEquals(0, waitExit(l2))
        for (f in src) assertArrayEquals(f.name, f.readBytes(), File(dest, f.name).readBytes())
        assertTrue("errors corrected: ${last2.errors}", last2.errors > 0)
    }

    /** from gkermit on a line that strips the 8th bit: prefixed, the files the same */
    @Test
    fun aSevenBitLine() {
        val src = files(tmp.newFolder("src"))
        // gkermit -p s: space parity, so it prefixes the 8th bit
        val (l, io) = line(src[0].parentFile!!, listOf("/usr/bin/gkermit", "-q", "-i", "-P", "-p", "s", "-s") + src.map { it.name }, strip8 = true)
        val into = FolderIncoming(tmp.newFolder("into"))
        received(src, into, Kermit(io, sevenBit = true).receive(into))
        assertEquals(0, waitExit(l))
    }

    /** from and to C-Kermit: the files the same, the time kept by the attributes */
    @Test
    fun ckermitBothWays() {
        assumeTrue("C-Kermit isn't installed", File("/usr/bin/kermit").canExecute())
        val src = files(tmp.newFolder("src"))
        // (-Y: no init file; -i binary)
        val (l, io) = line(src[0].parentFile!!, listOf("/usr/bin/kermit", "-Y", "-q", "-i", "-s") + src.map { it.name })
        val into = FolderIncoming(tmp.newFolder("into"))
        received(src, into, Kermit(io).receive(into))
        assertEquals(0, waitExit(l))

        val dest = tmp.newFolder("dest")
        val (l2, io2) = line(dest, listOf("/usr/bin/kermit", "-Y", "-q", "-i", "-r"))
        Kermit(io2).send(src.map { Outgoing.of(it) })
        assertEquals(0, waitExit(l2))
        for (f in src) {
            assertArrayEquals(f.name, f.readBytes(), File(dest, f.name).readBytes())
        }
        // the attributes kept the time (to the second)
        assertEquals(src[0].lastModified() / 1000, File(dest, src[0].name).lastModified() / 1000)
    }

    /** kermit -s's first packet recognised, plain text not; CRC-16/KERMIT's check value */
    @Test
    fun theStartIsRecognised() {
        // what kermit -s sends first
        val s = "\u0001/ S~* @-#Y3~^>J)0___F\"U1@\r".toByteArray()
        assertTrue(Kermit.looksLikeStart(s))
        assertFalse(Kermit.looksLikeStart("plain text\r\n".toByteArray()))
        // CRC-16/KERMIT's check value
        assertEquals(0x2189, Kermit.crc("123456789".toByteArray(), 0, 9))
    }
}
