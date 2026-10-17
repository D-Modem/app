// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Fax on a mobile call: two modems through a speech codec each way
 * (native-test's codec_line, AMR-NB 12.2 kbit/s as a 3G/4G call has it,
 * or DMODEM_CODEC_LINE; skipped without it), our T.30 on both. It
 * doesn't get through: the app turns fax down on that line (Fax).
 */
class CellFaxTest {
    private var line: FaxLineProcess? = null

    @After
    fun tearDown() {
        line?.close()
    }

    private fun call(codec: String, maxBps: Int, ecm: Boolean): Pair<FaxProgress?, List<FaxPage>> {
        val bin = nativeTool("DMODEM_CODEC_LINE", "codec_line")
        assumeTrue("codec_line isn't built (native-test)", bin != null)
        val l = FaxLineProcess(listOf(bin!!.path, codec))
        line = l
        val tx = l.modem("modemA", "a")
        val rx = l.modem("modemB", "b")
        assertEquals("OK", tx.command("E0X3+FCLASS=1"))
        assertEquals("OK", rx.command("E0+FCLASS=1"))
        val txRates = T30.modemRates(tx, maxBps)
        val rxRates = T30.modemRates(rx, maxBps)
        val pages = listOf(FaxTestPage.of(false, rows = 300, seed = 1))
        val got = ArrayList<FaxPage>()
        var rxError: Throwable? = null
        rx.send("A")
        tx.send("D")
        val receiver = Thread {
            try {
                assertEquals("CONNECT", rx.result(20_000))
                T30.receive(rx, "+1 555 0199", rxRates, ecm = ecm) { pg, _ -> got.add(pg) }
            } catch (e: Throwable) {
                rxError = e
            }
        }
        receiver.start()
        assertEquals(l.dump(), "CONNECT", tx.result(30_000))
        val sent = try {
            T30.send(tx, pages, "+1 555 0100", txRates, ecm = ecm)
        } catch (e: Exception) {
            println("$codec $maxBps ecm=$ecm: $e")
            null
        }
        receiver.join(90_000)
        println("$codec $maxBps ecm=$ecm: sent ${sent?.pages} at ${sent?.bps}, got ${got.size}, rx error $rxError")
        if (sent != null && got.isNotEmpty()) {
            println("  page same: ${pages[0].bits.contentEquals(got[0].bits)}")
        }
        println(l.log.filter { it.contains("frame") }.takeLast(40).joinToString("\n"))
        return sent to got
    }

    @Test
    fun throughNoCodecTheFaxGoes() {
        // (the line itself, the codec out of it: the same modems, the same pacing)
        val (sent, got) = call("none", 4800, ecm = true)
        assertEquals(1, sent?.pages)
        assertEquals(1, got.size)
    }

    @Test
    fun throughAmrTheTrainingFails() {
        // AMR-NB 12.2, the best a mobile call has: V.27ter's training at 2400 bit/s
        // with about one bit in sixteen wrong, more than ECM's frames survive.
        // (Why the app turns fax down on the mobile-call line.)
        val (sent, got) = call("amr122", 4800, ecm = true)
        assertEquals(null, sent)
        assertEquals(0, got.size)
    }
}
