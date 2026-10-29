// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.fax

import com.neodesktop.dmodem.TestLogs
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.FaxSettingsStore
import com.neodesktop.dmodem.transfer.TransferIo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The app's fax ([Fax]) on the modem's DTE hub as the service has it,
 * the hub on one of fax_line's modems (native-test), our T.30 on the
 * other: a text document sent, a fax answered and saved as a PDF in
 * Downloads (the app's folder, before Android 10).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class FaxAppTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var line: Process? = null
    private val log = CopyOnWriteArrayList<String>()
    private lateinit var a: String
    private lateinit var b: String
    private val closers = ArrayList<() -> Unit>()

    /** native-test's fax_line, if built, or null */
    private fun faxLine(): File? = listOfNotNull(System.getenv("DMODEM_FAX_LINE"), "../build-host/fax_line", "build-host/fax_line")
        .map { File(it) }.firstOrNull { it.canExecute() }

    /** fax_line started, modemA's pty on the app's DTE hub as the service has it, the app's fax set up */
    @Before
    fun setUp() {
        val fl = faxLine()
        assumeTrue("fax_line isn't built (native-test)", fl != null)
        val p = ProcessBuilder(fl!!.path).redirectErrorStream(true).apply { environment()["LINE_SPEED"] = LINE_SPEED }.start()
        line = p
        TestLogs.echo("fax_line", p.inputStream) { log.add(it) }
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && log.none { it.startsWith("created modemB: ") }) Thread.sleep(20)
        a = log.first { it.startsWith("created modemA: ") }.substringAfter(": ")
        b = log.first { it.startsWith("created modemB: ") }.substringAfter(": ")
        // the app's modem: modemA's pty on the hub, as the service attaches the native modem
        ProcessBuilder("stty", "-F", a, "raw", "-echo").start().waitFor()
        ModemState.dte.attach(FileInputStream(a), FileOutputStream(a))
        ModemState.setRun(ModemState.Run.RUNNING)
        Fax.init(ctx)
        runBlocking { FaxSettingsStore(ctx).update { it.copy(ident = "+1 555 0100", fine = true, maxBps = 14400) } }
    }

    /** the app's modem log into the test's, the fax cancelled, the hub detached, fax_line stopped */
    @After
    fun tearDown() {
        // (the app's modem log, into the test's)
        ModemState.log.flow.value.forEach { TestLogs.note("app", it) }
        Fax.cancel()
        closers.forEach { runCatching { it() } }
        ModemState.dte.detach()
        ModemState.setRun(ModemState.Run.STOPPED)
        line?.destroy()
        line?.waitFor(5, TimeUnit.SECONDS)
    }

    /** the other fax machine, on modemB */
    private fun other(): Class1 {
        ProcessBuilder("stty", "-F", b, "raw", "-echo").start().waitFor()
        val input = FileInputStream(b)
        val output = FileOutputStream(b)
        closers.add { input.close(); output.close() }
        val io = TransferIo { bytes -> output.write(bytes); output.flush() }
        Thread {
            val buf = ByteArray(4096)
            while (true) {
                val n = try { input.read(buf) } catch (_: Exception) { -1 }
                if (n < 0) break
                io.feed(buf, 0, n)
            }
        }.apply { isDaemon = true; start() }
        return Class1(io) { log.add("B: $it"); TestLogs.note("B", it) }
    }

    /** whether fax_line logged [what] within [secs] s */
    private fun waitLog(what: String, secs: Int = 30): Boolean {
        val end = System.currentTimeMillis() + secs * 1000L
        while (System.currentTimeMillis() < end) {
            if (log.any { it.contains(what) }) return true
            Thread.sleep(5)
        }
        return false
    }

    /** the fax's state once it's done, within [secs] s; fails if it's still going */
    private fun waitDone(secs: Int): Fax.State {
        val end = System.currentTimeMillis() + secs * 1000L
        while (System.currentTimeMillis() < end) {
            val s = Fax.state.value
            if (s != null && !s.running) return s
            Thread.sleep(100)
        }
        throw AssertionError("still going: ${Fax.state.value}\n" + (ModemState.log.flow.value.takeLast(40) + log.takeLast(40)).joinToString("\n"))
    }

    /** a 90-line text document sent: two fine A4 pages with text on them, the remote's ID both ways, in the list of faxes, the hub free again */
    @Test
    fun aTextDocumentSent() {
        val doc = File(ctx.cacheDir, "letter.txt").apply {
            writeText((1..90).joinToString("\n") { "Line $it of a letter faxed from D-Modem, the phone's own modem." })
        }
        val c = other()
        assertEquals("OK", c.command("E0"))
        assertEquals("OK", c.command("+FCLASS=1"))
        val rates = T30.modemRates(c)
        val err = Fax.send(ctx, "5551234", listOf(Uri.fromFile(doc)))
        assertEquals(null, err)
        // the app dials; the other machine answers
        assertTrue(waitLog("modemA: run cmd: ATD5551234"))
        assertEquals("CONNECT", c.command("A", 30_000))
        val got = ArrayList<FaxPage>()
        val rx = T30.receive(c, "+1 555 0199", rates) { pg, _ -> got.add(pg) }
        val st = waitDone(60)
        assertEquals(st.toString(), "", st.result)
        assertEquals("+1 555 0100", rx.remote)
        assertEquals("+1 555 0199", st.progress.remote)
        // 90 lines of text: two A4 pages at fine resolution, with text on them
        assertEquals(2, got.size)
        for (pg in got) {
            assertEquals(FaxPage.a4Rows(true), pg.rows)
            val black = pg.bits.sumOf { Integer.bitCount(it.toInt() and 0xff) }
            assertTrue("text on the page: $black black pels", black > 20_000)
        }
        // in the list of faxes
        val h = Fax.history(ctx).items.value.last()
        assertTrue(h.sending)
        assertEquals("5551234", h.number)
        assertEquals(2, h.pages)
        assertEquals("", h.result)
        // the modem back to data mode, the hub the terminal's again
        assertTrue(!ModemState.dte.claimed)
    }

    /** a fax answered: its pages saved as a PDF in the app's Downloads, every pel as sent, in the list of faxes */
    @Test
    fun aFaxAnsweredAndSaved() {
        val c = other()
        assertEquals("OK", c.command("E0"))
        assertEquals("OK", c.command("X3+FCLASS=1"))
        val rates = T30.modemRates(c)
        val pages = listOf(FaxTestPage.of(true, rows = 800, seed = 7), FaxTestPage.of(true, rows = 400, seed = 8))
        // the other machine dials, the app answers
        c.send("D5550100")
        assertEquals(null, Fax.answer(ctx))
        assertEquals("CONNECT", c.result(60_000))
        val sent = T30.send(c, pages, "+1 555 0199", rates)
        assertEquals(2, sent.pages)
        val st = waitDone(30)
        assertEquals(st.toString(), "", st.result)
        assertEquals(2, st.progress.pages)
        assertEquals("+1 555 0199", st.progress.remote)
        // the PDF in Downloads (the app's own, before Android 10)
        val f = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), st.file)
        assertTrue("$f", f.isFile && f.length() > 1000)
        assertTrue(String(f.readBytes().copyOf(5)).startsWith("%PDF"))
        // its pages the ones sent, every pel (poppler reading it, where it's installed)
        if (File("/usr/bin/pdfimages").canExecute()) {
            val dir = File(ctx.cacheDir, "pdf-images").apply { mkdirs() }
            val back = FaxPdfTest.images(f, dir)
            assertEquals(2, back.size)
            for (i in pages.indices) org.junit.Assert.assertArrayEquals("page ${i + 1}", pages[i].bits, back[i].bits)
        }
        val h = Fax.history(ctx).items.value.last()
        assertTrue(!h.sending)
        assertEquals(st.file, h.file)
    }

    /** text from the app's modem (its DTE hub), as the terminal sees it */
    private fun hubText(): () -> String {
        val sb = StringBuffer()
        val l = com.neodesktop.dmodem.terminal.TerminalLink.Listener { d, n -> sb.append(String(d, 0, n, Charsets.ISO_8859_1)) }
        ModemState.dte.addListener(l)
        closers.add { ModemState.dte.removeListener(l) }
        return { sb.toString() }
    }

    /** waits until [cond], up to [secs] s; fails with the log's end */
    private fun waitFor(what: String, secs: Int = 30, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + secs * 1000L
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("no $what\n" + log.takeLast(40).joinToString("\n"))
            Thread.sleep(20)
        }
    }

    /** with adaptive answer a fax machine's call is answered as FAX and received by the app on its own */
    @Test
    fun aFaxMachinesCallAnsweredAdaptively() {
        // the service's start: adaptive answer on (in data mode, as the terminal has it)
        val seen = hubText()
        ModemState.adaptiveAnswer = true
        ModemState.dte.write("ATE0X3+FAA=1\r")
        waitFor("OK to +FAA=1") { seen().contains("OK") }
        val c = other()
        assertEquals("OK", c.command("E0"))
        assertEquals("OK", c.command("X3+FCLASS=1"))
        val rates = T30.modemRates(c)
        val pages = listOf(FaxTestPage.of(true, rows = 600, seed = 3))
        // a fax machine dials (its modem's CNG as it waits); the app's plain answer, as the popup's
        c.send("D5550100")
        // (fax_line's line doesn't ring: answered at once, as aFaxAnsweredAndSaved)
        assertTrue(ModemState.answer())
        // FAX: the fax receiver takes it by itself
        assertEquals("CONNECT", c.result(60_000))
        val sent = T30.send(c, pages, "+1 555 0199", rates)
        assertEquals(1, sent.pages)
        val st = waitDone(30)
        assertEquals(st.toString(), "", st.result)
        assertTrue(!st.sending)
        assertEquals(1, st.progress.pages)
        assertEquals("+1 555 0199", st.progress.remote)
        assertTrue(seen().contains("FAX"))
        assertTrue(File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), st.file).isFile)
        assertTrue(!ModemState.dte.claimed)
    }

    /** with adaptive answer a modem's call is answered as DATA, a CONNECT for the terminal, no fax */
    @Test
    fun aDataCallAnsweredAdaptively() {
        val seen = hubText()
        ModemState.adaptiveAnswer = true
        ModemState.dte.write("ATE0X3+FAA=1\r")
        waitFor("OK to +FAA=1") { seen().contains("OK") }
        val c = other()
        assertEquals("OK", c.command("E0X3"))
        // a modem dials: DATA, its CONNECT, the terminal's call; no fax
        c.send("D5550100")
        // (fax_line's line doesn't ring: answered at once, as aFaxAnsweredAndSaved)
        assertTrue(ModemState.answer())
        waitFor("DATA and CONNECT", 60) { seen().contains("DATA") && seen().contains("CONNECT") }
        assertTrue(c.result(30_000)!!.startsWith("CONNECT"))
        assertEquals(null, Fax.state.value?.takeIf { it.running })
        assertTrue(!ModemState.dte.claimed)
    }
}
