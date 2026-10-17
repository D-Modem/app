// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Our fax (its T.30 on a class 1 modem) against another machine's:
 * D-Modem-RE's own T.30 (dsp/t30.cpp), on the far end of a phone line
 * from the modem (native-test's t30_line: cmake -S native-test -B
 * build-host && cmake --build build-host, or DMODEM_T30_LINE; skipped
 * without it). With error correction mode both ways, on a clean line
 * and a noisy one (ECM's frames spoilt there sent again), and without.
 */
class T30InteropTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var line: FaxLineProcess? = null

    @After
    fun tearDown() {
        line?.close()
    }

    /** t30_line started: our class 1 modem's DTE */
    private fun start(vararg args: String): Class1 {
        val bin = nativeTool("DMODEM_T30_LINE", "t30_line")
        assumeTrue("t30_line isn't built (native-test)", bin != null)
        val l = FaxLineProcess(listOf(bin!!.path) + args.toList())
        line = l
        val c = l.modem("modemA", "dte")
        assertEquals("OK", c.command("E0"))
        return c
    }

    private fun dump() = line!!.dump()

    /** the machine's RESULT line's fields: ok, pages, bps, ecm */
    private fun machineResult(): Map<String, String> {
        val end = System.currentTimeMillis() + 30_000
        var r: String? = null
        while (r == null && System.currentTimeMillis() < end) {
            r = line!!.log.firstOrNull { it.startsWith("RESULT ") }
            if (r == null) Thread.sleep(50)
        }
        if (r == null) throw AssertionError("no RESULT\n${dump()}")
        val f = r.split(' ')
        return mapOf("ok" to f[1], "pages" to f[3], "bps" to f[5], "ecm" to f[7], "why" to f.drop(8).joinToString(" "))
    }

    private fun theyReceive(ecm: Boolean, snr: Double = 0.0) {
        val dir = tmp.newFolder()
        val args = mutableListOf("receive", dir.path)
        if (snr > 0) args += listOf("--snr", snr.toString())
        val c = start(*args.toTypedArray())
        assertEquals("OK", c.command("X3+FCLASS=1"))
        val rates = T30.modemRates(c)
        val pages = listOf(FaxTestPage.of(true, rows = 600, seed = 1), FaxTestPage.of(true, rows = 300, seed = 2))
        assertEquals(dump(), "CONNECT", c.command("D", 30_000))
        val sent = try {
            T30.send(c, pages, "+1 555 0100", rates, ecm = ecm)
        } catch (e: Exception) {
            throw AssertionError("$e\n${dump()}")
        }
        val m = machineResult()
        assertEquals(m.toString(), "1", m["ok"])
        assertEquals(2, sent.pages)
        assertEquals(ecm, sent.ecm)
        assertEquals(if (ecm) "1" else "0", m["ecm"])
        assertEquals("+1 555 0399", sent.remote)
        for (i in pages.indices) {
            val got = Pbm.read(File(dir, "got${i + 1}.pbm"), true)
            assertEquals(pages[i].rows, got.rows)
            assertArrayEquals("page ${i + 1}", pages[i].bits, got.bits)
        }
    }

    private fun theySend(ecm: Boolean, snr: Double = 0.0, maxBps: Int = 14400): List<FaxPage> {
        val dir = tmp.newFolder()
        val args = mutableListOf("send", dir.path, "--bps", maxBps.toString())
        if (snr > 0) args += listOf("--snr", snr.toString())
        val c = start(*args.toTypedArray())
        assertEquals("OK", c.command("+FCLASS=1"))
        val rates = T30.modemRates(c)
        val got = ArrayList<FaxPage>()
        // (it calls once the modem is off hook: answered at once)
        assertEquals(dump(), "CONNECT", c.command("A", 30_000))
        val rx = try {
            T30.receive(c, "+1 555 0199", rates, ecm = ecm) { pg, _ -> got.add(pg) }
        } catch (e: Exception) {
            throw AssertionError("$e\n${dump()}")
        }
        val m = machineResult()
        assertEquals(m.toString() + "\n" + dump(), "1", m["ok"])
        assertEquals(ecm, rx.ecm)
        assertEquals("+1 555 0300", rx.remote)
        assertEquals(2, got.size)
        for (i in got.indices) {
            val sent = Pbm.read(File(dir, "sent${i + 1}.pbm"), true)
            assertEquals(sent.rows, got[i].rows)
            assertArrayEquals("page ${i + 1}", sent.bits, got[i].bits)
        }
        return got
    }

    @Test
    fun weSendInEcm() = theyReceive(ecm = true)

    @Test
    fun weSendWithoutEcm() = theyReceive(ecm = false)

    @Test
    fun weReceiveInEcm() {
        theySend(ecm = true)
    }

    // (at 12000: at V.17's 14400 its first page's row 90, 1-pel runs across
    // the page, comes with its second half spoilt, the same place each time,
    // on a clean line: from D-Modem-RE's T.30 sender to its class 1 receiver,
    // before our T.30 sees it; ECM's frames sent again hide it)
    @Test
    fun weReceiveWithoutEcm() {
        theySend(ecm = false, maxBps = 12000)
    }

    @Test
    fun weSendInEcmOnANoisyLine() {
        theyReceive(ecm = true, snr = 21.0)
        println("PPRs to us: " + line!!.log.count { it.contains("<- frame ff 13 bc") })
    }

    @Test
    fun weReceiveInEcmOnANoisyLine() {
        theySend(ecm = true, snr = 21.0)
        println("PPRs from us: " + line!!.log.count { it.contains("-> frame ff 13 bc") })
    }
}
