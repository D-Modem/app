// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Fax over SIP as the app has it: the app's engine dialling a dmodemd
 * over loopback SIP (native-test's sip_fax_line, or
 * DMODEM_SIP_FAX_LINE; skipped without it), our T.30 on both modems.
 * With T.38 on both, the call goes over to T.38 once the answerer asks
 * (a second after it answered, in fax mode) and the pages go as IFP
 * packets in UDPTL; with ECM and without; and on audio when T.38 is off.
 */
class SipFaxTest {
    private var line: FaxLineProcess? = null

    @After
    fun tearDown() {
        line?.close()
    }

    private fun call(t38: Boolean, ecm: Boolean) {
        val bin = nativeTool("DMODEM_SIP_FAX_LINE", "sip_fax_line")
        assumeTrue("sip_fax_line isn't built (native-test)", bin != null)
        val l = FaxLineProcess(listOfNotNull(bin!!.path, if (t38) null else "--no-t38"))
        line = l
        val tx = l.modem("modemA", "engine dte")
        val rx = l.modem("modemB", "dmodemd dte")
        assertEquals(l.dump(), "OK", tx.command("E0X3+FCLASS=1"))
        assertEquals(l.dump(), "OK", rx.command("E0+FCLASS=1"))
        val txRates = T30.modemRates(tx)
        val rxRates = T30.modemRates(rx)
        val pages = listOf(FaxTestPage.of(true, rows = 600, seed = 1), FaxTestPage.of(true, rows = 300, seed = 2))
        val got = ArrayList<FaxPage>()
        var rxDone: FaxProgress? = null
        var rxError: Throwable? = null
        tx.send("D5551000")
        assertEquals(l.dump(), "RING", rx.result(20_000))
        val receiver = Thread {
            try {
                assertEquals("CONNECT", rx.command("A", 20_000))
                rxDone = T30.receive(rx, "+1 555 0199", rxRates, ecm = ecm) { pg, _ -> got.add(pg) }
            } catch (e: Throwable) {
                rxError = e
            }
        }
        receiver.start()
        assertEquals(l.dump(), "CONNECT", tx.result(30_000))
        val sent = try {
            T30.send(tx, pages, "+1 555 0100", txRates, ecm = ecm)
        } catch (e: Exception) {
            throw AssertionError("$e\n${l.dump(200)}")
        }
        receiver.join(60_000)
        assertEquals(l.dump(200), null, rxError)
        assertEquals(2, sent.pages)
        assertEquals(ecm, sent.ecm)
        assertEquals("+1 555 0199", sent.remote)
        assertEquals("+1 555 0100", rxDone?.remote)
        assertEquals(2, got.size)
        for (i in pages.indices) {
            assertEquals(pages[i].rows, got[i].rows)
            assertArrayEquals("page ${i + 1}", pages[i].bits, got[i].bits)
        }
        // T.38 taken (the engine's side told: its modem on UDPTL), or not at all
        val onT38 = l.log.any { it.startsWith("engine: ") && it.contains("T.38 on") }
        assertEquals(l.log.filter { it.contains("t38", ignoreCase = true) }.take(30).joinToString("\n"), t38, onT38)
    }

    @Test
    fun overT38() = call(t38 = true, ecm = false)

    @Test
    fun overT38InEcm() = call(t38 = true, ecm = true)

    @Test
    fun onAudioWithoutT38() = call(t38 = false, ecm = true)
}
