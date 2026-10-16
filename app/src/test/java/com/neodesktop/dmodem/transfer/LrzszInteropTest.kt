// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.transfer

import com.neodesktop.dmodem.local.LocalLink
import com.neodesktop.dmodem.local.LocalShellTest
import com.neodesktop.dmodem.local.Pty
import com.neodesktop.dmodem.terminal.TerminalLink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
 * XMODEM, YMODEM and ZMODEM against lrzsz (sz, rz, sx, rx, sb, rb) on a
 * pseudo-terminal, as on a remote's shell: files each way, binary and
 * empty ones, several at once; and with bytes damaged on the line, both
 * ways (the protocols' repeats and ZRPOS putting it right).
 */
class LrzszInteropTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val links = ArrayList<LocalLink>()

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue("lrzsz isn't installed", File("/usr/bin/sz").canExecute() && File("/usr/bin/rz").canExecute())
            Pty.load(LocalShellTest.hostPty())
        }
    }

    @After
    fun tearDown() = links.forEach { it.close() }

    /** [cmd] on a pty in [dir], and a TransferIo on it; [damageIn] / [damageOut] spoil a byte every so many */
    private fun line(dir: File, cmd: List<String>, damageIn: Int = 0, damageOut: Int = 0): Pair<LocalLink, TransferIo> {
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
            if (damageIn > 0) {
                for (i in 0 until n) if (inCount.incrementAndGet() % damageIn == 0) b[i] = (b[i].toInt() xor 0x55).toByte()
            }
            io.feed(b)
        }
        // (the bytes each way, for a failing run: build/transfer-trace.txt)
        val trace = File("build/transfer-trace.txt").apply { delete() }
        io.trace = { inbound, b ->
            synchronized(trace) {
                trace.appendText((if (inbound) "<< " else ">> ") + b.joinToString("") { c ->
                    val v = c.toInt() and 0xff
                    if (v in 0x20..0x7e) v.toChar().toString() else "\\x%02x".format(v)
                } + "\n")
            }
        }
        assertTrue(l.start())
        return l to io
    }

    private fun waitExit(l: LocalLink): Int? {
        val end = System.currentTimeMillis() + 30_000
        while (l.state.value !is TerminalLink.State.Closed && System.currentTimeMillis() < end) Thread.sleep(20)
        return l.exitStatus
    }

    private fun files(dir: File): List<File> {
        dir.mkdirs()
        val big = File(dir, "binary.bin").apply { writeBytes(Random(7).nextBytes(300_000)) }
        // every byte value, the ZDLE-escaped ones many times over
        val every = File(dir, "every byte.dat").apply { writeBytes(ByteArray(4096) { it.toByte() } + ByteArray(2048) { 0x18 } + ByteArray(2048) { 0x11 }) }
        val text = File(dir, "notes.txt").apply { writeText("hello from the other side\n".repeat(100)) }
        val empty = File(dir, "empty").apply { writeBytes(ByteArray(0)) }
        return listOf(big, every, text, empty)
    }

    // ZMODEM ------------------------------------------------------------

    @Test
    fun zmodemReceivedFromSz() = receivedFromSz(damage = 0)

    @Test
    fun zmodemReceivedFromSzOnANoisyLine() = receivedFromSz(damage = 20_011)

    private fun receivedFromSz(damage: Int) {
        val src = files(tmp.newFolder("src"))
        val (l, io) = line(src[0].parentFile!!, listOf("/usr/bin/sz", "-b") + src.map { it.path }, damageIn = damage)
        val into = FolderIncoming(tmp.newFolder("into"))
        var last = Progress()
        val names = ZModem(io, { last = it }).receive(into)
        assertEquals(src.map { it.name }, names)
        for (f in src) assertArrayEquals(f.name, f.readBytes(), File(into.received.first { it.name == f.name }.path).readBytes())
        assertEquals(0, waitExit(l))
        if (damage > 0) assertTrue("errors corrected: ${last.errors}", last.errors > 0)
        assertEquals(4, last.files)
    }

    @Test
    fun zmodemSentToRz() = sentToRz(damage = 0)

    @Test
    fun zmodemSentToRzOnANoisyLine() = sentToRz(damage = 30_011)

    private fun sentToRz(damage: Int) {
        val src = files(tmp.newFolder("src"))
        val dest = tmp.newFolder("dest")
        val (l, io) = line(dest, listOf("/usr/bin/rz", "-b", "-y"), damageOut = damage)
        var last = Progress()
        ZModem(io, { last = it }).send(src.map { Outgoing.of(it) })
        assertEquals(0, waitExit(l))
        for (f in src) assertArrayEquals(f.name, f.readBytes(), File(dest, f.name).readBytes())
        // the times kept (to the second)
        assertEquals(src[0].lastModified() / 1000, File(dest, src[0].name).lastModified() / 1000)
        if (damage > 0) assertTrue("errors corrected: ${last.errors}", last.errors > 0)
    }

    @Test
    fun aCancelFromTheOtherSideStopsIt() {
        val src = File(tmp.newFolder("src"), "big").apply { writeBytes(Random(1).nextBytes(2_000_000)) }
        val (l, io) = line(src.parentFile!!, listOf("/usr/bin/sz", "-b", src.path))
        val into = FolderIncoming(tmp.newFolder("into"))
        val t = Thread { Thread.sleep(300); l.write(ZModem.CANCEL) }
        t.start()
        val e = runCatching { ZModem(io).receive(object : Incoming by into {
            // (slowly: the cancel comes in the middle)
            override fun create(name: String, size: Long) = into.create(name, size).let { (o, n) ->
                object : java.io.FilterOutputStream(o) {
                    override fun write(b: ByteArray, off: Int, len: Int) { Thread.sleep(5); out.write(b, off, len) }
                } to n
            }
        }) }.exceptionOrNull()
        // the sender's own cancel echoed back, or the line going quiet
        assertTrue("$e", e != null)
        assertTrue("half a file not kept", into.received.isEmpty())
    }

    // YMODEM and XMODEM ---------------------------------------------------

    @Test
    fun ymodemReceivedFromSb() {
        val src = files(tmp.newFolder("src")).filter { it.length() > 0 }
        val (l, io) = line(src[0].parentFile!!, listOf("/usr/bin/sb") + src.map { it.path })
        val into = FolderIncoming(tmp.newFolder("into"))
        val names = XYModem(io).receiveY(into)
        assertEquals(src.map { it.name }, names)
        for (f in src) assertArrayEquals(f.name, f.readBytes(), into.received.first { it.name == f.name }.readBytes())
        assertEquals(0, waitExit(l))
    }

    @Test
    fun ymodemSentToRb() {
        val src = files(tmp.newFolder("src")).filter { it.length() > 0 }
        val dest = tmp.newFolder("dest")
        val (l, io) = line(dest, listOf("/usr/bin/rb", "-y"))
        XYModem(io).sendY(src.map { Outgoing.of(it) })
        assertEquals(0, waitExit(l))
        for (f in src) assertArrayEquals(f.name, f.readBytes(), File(dest, f.name).readBytes())
    }

    @Test
    fun xmodemBothWaysWithOneKBlocksAndNoise() {
        val data = Random(3).nextBytes(100_000)
        // from sx (1K blocks, CRC), the line damaged: our NAKs make it repeat
        val src = File(tmp.newFolder("src"), "x.bin").apply { writeBytes(data) }
        val (l, io) = line(src.parentFile!!, listOf("/usr/bin/sx", "-k", src.path), damageIn = 15_013)
        val into = FolderIncoming(tmp.newFolder("into"))
        var last = Progress()
        val name = XYModem(io, { last = it }).receiveX(into, "x.bin")
        assertEquals(0, waitExit(l))
        // (XMODEM pads the last block: the padding taken off, the file as it was)
        assertArrayEquals(data, into.received.first { it.name == name }.readBytes())
        assertTrue("errors corrected", last.errors > 0)

        // to rx (128-byte blocks)
        val dest = tmp.newFolder("dest")
        val (l2, io2) = line(dest, listOf("/usr/bin/rx", "-c", "got.bin"))
        XYModem(io2).sendX(Outgoing.of(src), oneK = false)
        assertEquals(0, waitExit(l2))
        // (rx keeps the padding: XMODEM has no size)
        val got = File(dest, "got.bin").readBytes()
        assertArrayEquals(data, got.copyOf(data.size))
        assertTrue(got.drop(data.size).all { it == 0x1a.toByte() })
    }
}
