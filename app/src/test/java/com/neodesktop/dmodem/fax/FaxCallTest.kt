// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import com.neodesktop.dmodem.TestLogs
import com.neodesktop.dmodem.transfer.TransferIo
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Fax calls on D-Modem's own modems: modem_test's two back to back (an
 * audio pipe between them, a pty for each one's DTE), class 1 on each.
 * Our sender to our receiver, and efax's both ways (its own T.30), the
 * pages as they were sent.
 *
 * The line is native-test's fax_line (modem_test paced as a phone line:
 * cmake -S native-test -B build-host && cmake --build build-host), or
 * DMODEM_FAX_LINE's; skipped without it.
 */
class FaxCallTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var modems: Process? = null
    private val log = CopyOnWriteArrayList<String>()
    private lateinit var a: String
    private lateinit var b: String
    private val ends = ArrayList<PtyEnd>()

    private fun modemTest(): File? = listOfNotNull(
        System.getenv("DMODEM_FAX_LINE"),
        "../build-host/fax_line",
        "build-host/fax_line",
    ).map { File(it) }.firstOrNull { it.canExecute() }

    @Before
    fun setUp() {
        val mt = modemTest()
        assumeTrue("fax_line isn't built (native-test)", mt != null)
        val p = ProcessBuilder(mt!!.path).redirectErrorStream(true).apply { environment()["LINE_SPEED"] = LINE_SPEED }.start()
        modems = p
        Thread {
            p.inputStream.bufferedReader().forEachLine { l -> log.add(l); TestLogs.note("fax_line", l) }
        }.apply { isDaemon = true; start() }
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && log.none { it.startsWith("created modemB: ") }) Thread.sleep(20)
        a = log.first { it.startsWith("created modemA: ") }.substringAfter(": ")
        b = log.first { it.startsWith("created modemB: ") }.substringAfter(": ")
    }

    @After
    fun tearDown() {
        ends.forEach { it.close() }
        modems?.destroy()
        modems?.waitFor(5, TimeUnit.SECONDS)
    }

    /** a modem's pty from here: raw, its octets into a TransferIo */
    private inner class PtyEnd(path: String) {
        val input: FileInputStream
        val output: FileOutputStream
        val io: TransferIo
        val c1: Class1

        init {
            ProcessBuilder("stty", "-F", path, "raw", "-echo").start().waitFor()
            input = FileInputStream(path)
            output = FileOutputStream(path)
            io = TransferIo { bytes -> output.write(bytes); output.flush() }
            c1 = Class1(io) { line ->
                log.add("${path.substringAfterLast('/')}: $line")
                TestLogs.note(path.substringAfterLast('/'), line)
            }
            Thread {
                val buf = ByteArray(4096)
                while (true) {
                    val n = try { input.read(buf) } catch (_: Exception) { -1 }
                    if (n < 0) break
                    io.feed(buf, 0, n)
                }
            }.apply { isDaemon = true; start() }
            ends.add(this)
        }

        fun close() {
            runCatching { input.close() }
            runCatching { output.close() }
        }

        var rates: List<T30.Rate> = emptyList()

        fun setUp(caller: Boolean, maxBps: Int = 14400) {
            assertEquals("OK", c1.command("E0"))
            assertEquals("OK", c1.command(if (caller) "X3+FCLASS=1" else "+FCLASS=1"))
            rates = T30.modemRates(c1, maxBps)
        }
    }

    private fun waitLog(what: String, secs: Int = 30): Boolean {
        val end = System.currentTimeMillis() + secs * 1000L
        while (System.currentTimeMillis() < end) {
            if (log.any { it.contains(what) }) return true
            Thread.sleep(5)
        }
        return false
    }

    private fun dump() = log.takeLast(80).joinToString("\n")

    @Test
    fun ourSenderToOurReceiver() = ourCall(ecm = true)

    @Test
    fun ourSenderToOurReceiverWithoutEcm() = ourCall(ecm = false)

    private fun ourCall(ecm: Boolean) {
        val pages = listOf(FaxTestPage.of(true, rows = 600, seed = 1), FaxTestPage.of(true, rows = 300, seed = 2))
        val tx = PtyEnd(a).apply { setUp(caller = true) }
        val rx = PtyEnd(b).apply { setUp(caller = false) }
        val got = ArrayList<FaxPage>()
        var rxDone: FaxProgress? = null
        var rxError: Throwable? = null
        // (on this line the answer and the dial go together)
        rx.io.write("ATA\r".toByteArray())
        tx.io.write("ATD\r".toByteArray())
        val receiver = Thread {
            try {
                assertEquals("CONNECT", rx.c1.result(10_000))
                rxDone = T30.receive(rx.c1, "+1 555 0199", rx.rates, ecm = ecm) { pg, _ -> got.add(pg) }
            } catch (e: Throwable) { rxError = e }
        }
        receiver.start()
        assertEquals(dump(), "CONNECT", tx.c1.result(10_000))
        val sent = try {
            T30.send(tx.c1, pages, "+1 555 0100", tx.rates, ecm = ecm)
        } catch (e: Exception) {
            throw AssertionError("$e\n${dump()}")
        }
        receiver.join(60_000)
        assertEquals(null, rxError)
        assertEquals(2, sent.pages)
        assertEquals(ecm, sent.ecm)
        assertEquals(ecm, rxDone?.ecm)
        assertEquals("+1 555 0199", sent.remote)
        assertEquals("+1 555 0100", rxDone?.remote)
        assertTrue("a fast rate: ${sent.bps}", sent.bps >= 9600)
        assertEquals(2, got.size)
        for (i in pages.indices) {
            assertEquals(pages[i].rows, got[i].rows)
            assertArrayEquals("page ${i + 1}", pages[i].bits, got[i].bits)
        }
    }

    // efax ---------------------------------------------------------------

    private fun efaxThere() = File("/usr/bin/efax").canExecute() && File("/usr/bin/efix").canExecute()

    private fun run(vararg cmd: String, dir: File): Int =
        ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "${File(cmd[0]).name}.out")).start().waitFor()

    @Test
    fun efaxSendsToUs() {
        assumeTrue("efax isn't installed", efaxThere())
        val dir = tmp.newFolder("efax-tx")
        val page = FaxTestPage.of(true, rows = 500, seed = 3)
        Pbm.write(page, File(dir, "page.pbm"))
        // (a TIFF, as efax reads its documents)
        assertEquals(0, run("efix", "-i", "pbm", "-R", "204x196", "-o", "tiffg3", "-n", "page.tif", "page.pbm", dir = dir))
        val rx = PtyEnd(b).apply { setUp(caller = false, maxBps = 9600) }
        val got = ArrayList<FaxPage>()
        var rxError: Throwable? = null
        val efax = ProcessBuilder("efax", "-v", "ewin", "-o1", "-h", "", "-l", "+1 555 0100", "-d", a, "-iX3", "-t", "", "page.tif")
            .directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "efax.log")).start()
        // efax's dial, and our answer with it
        assertTrue(dump(), waitLog("modemA: run cmd: ATD"))
        rx.io.write("ATA\r".toByteArray())
        val receiver = Thread {
            try {
                assertEquals("CONNECT", rx.c1.result(10_000))
                T30.receive(rx.c1, "+1 555 0199", rx.rates) { pg, _ -> got.add(pg) }
            } catch (e: Throwable) { rxError = e }
        }
        receiver.start()
        assertTrue(efax.waitFor(180, TimeUnit.SECONDS))
        receiver.join(30_000)
        val efaxLog = File(dir, "efax.log").readText()
        assertEquals("$efaxLog\n${dump()}", 0, efax.exitValue())
        assertEquals("$rxError\n$efaxLog", null, rxError)
        assertEquals(1, got.size)
        // the page as it was, below the band efax puts at the top of each page (blank: -h '')
        val g = got[0]
        val band = g.rows - page.rows
        assertTrue("$band rows of efax's header ($efaxLog)", band in 0..64)
        assertTrue("its header blank", g.bits.copyOf(band * g.stride).all { it.toInt() == 0 })
        assertArrayEquals(page.bits, g.bits.copyOfRange(band * g.stride, g.bits.size))
    }

    @Test
    fun weSendToEfax() {
        assumeTrue("efax isn't installed", efaxThere())
        val dir = tmp.newFolder("efax-rx")
        val pages = listOf(FaxTestPage.of(false, rows = 300, seed = 4), FaxTestPage.of(false, rows = 200, seed = 5))
        val tx = PtyEnd(a).apply { setUp(caller = true) }
        // efax waiting for the modem's CONNECT (-w): its answer given here with our dial, as this line needs
        val efax = ProcessBuilder("efax", "-v", "ewin", "-o1", "-l", "+1 555 0199", "-d", b, "-w", "-r", "got")
            .directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "efax.log")).start()
        val end = System.currentTimeMillis() + 20_000
        while (!File(dir, "efax.log").readText().contains("waiting for activity") && System.currentTimeMillis() < end) Thread.sleep(50)
        FileOutputStream(b).use { o ->
            o.write("ATA\r".toByteArray())
            tx.io.write("ATD\r".toByteArray())
        }
        assertEquals(dump(), "CONNECT", tx.c1.result(10_000))
        val sent = try {
            T30.send(tx.c1, pages, "+1 555 0100", tx.rates)
        } catch (e: Exception) {
            throw AssertionError("$e\n${File(dir, "efax.log").readText()}\n${dump()}")
        }
        assertTrue(efax.waitFor(60, TimeUnit.SECONDS))
        val efaxLog = File(dir, "efax.log").readText()
        assertEquals(efaxLog, 0, efax.exitValue())
        assertEquals(2, sent.pages)
        // (efax has no ECM: without it, though we'd have it)
        assertTrue(!sent.ecm)
        assertEquals("+1 555 0199", sent.remote)
        for (i in pages.indices) {
            val f = File(dir, "got.%03d".format(i + 1))
            assertTrue("$f\n$efaxLog", f.exists())
            assertEquals(0, run("efix", "-i", "fax", "-o", "pbm", "-n", "got-${i + 1}.pbm", f.name, dir = dir))
            val back = Pbm.read(File(dir, "got-${i + 1}.pbm"), false)
            assertEquals("page ${i + 1}'s rows", pages[i].rows, back.rows)
            assertArrayEquals("page ${i + 1}", pages[i].bits, back.bits)
        }
    }
}
