// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.cell

import com.neodesktop.dmodem.peer.TestModem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Two phones' modems calling each other over mobile calls: the app's
 * engine (peer_host) and its [PeerLine] on [CellTransport], the calls a
 * [FakeNetwork]'s: the sound on the phones' audio clock, and through
 * AMR-NB as a mobile network has it (native-test's codec_line; both
 * skipped without them).
 */
class CellLineTest {
    private val closers = ArrayList<() -> Unit>()

    /** the modems, transports and processes closed, last first */
    @After
    fun tearDown() = closers.asReversed().forEach { runCatching { it() } }

    private inner class CellModem(name: String, val phone: FakeNetwork.Phone, listen: Boolean) :
        TestModem(name, CellTransport(phone, phone.audio, audioMs = 2000), listen, 60, closers) {
        /** the number dialled: its digits, or null for none */
        override fun target(dial: String) = dial.filter { it.isDigit() }.ifEmpty { null }
    }

    /** the speed a CONNECT line gives */
    private fun rate(connect: String) = Regex("CONNECT (\\d+)").find(connect)!!.groupValues[1].toInt()

    /** two modems connect over a clean mobile call at 9600 or more, carry data both ways, and hang up */
    @Test
    fun aMobileCall() {
        val net = FakeNetwork(closers = closers)
        val a = CellModem("cellA", net.phone("5550001"), listen = false)
        val b = CellModem("cellB", net.phone("5550002"), listen = true)
        a.command("ATE0")
        b.command("ATE0S0=1")
        a.send("ATDT5550002\r")
        b.expect("RING", 30)
        val ca = a.connectLine()
        val cb = b.connectLine()
        // (a clean call, no codec: as fast as two modems go)
        assertTrue(ca, rate(ca) >= 9600)
        assertTrue(cb, rate(cb) >= 9600)
        assertEquals(1, b.phone.answered)
        a.send("hello over the mobile network\r\n")
        b.expect("hello over the mobile network")
        b.send("and back\r\n")
        a.expect("and back")
        // A hangs up: the call ends, B's NO CARRIER
        Thread.sleep(1200)
        a.send("+++")
        a.expect("OK", 10)
        a.command("ATH")
        b.expect("NO CARRIER", 20)
        assertEquals(CellPhone.State.IDLE, a.phone.state.value)
        assertEquals(CellPhone.State.IDLE, b.phone.state.value)
    }

    /** through AMR 12.2 two V.21 modems connect at 300 and the data's start gets through */
    @Test
    fun throughAmr() {
        assumeTrue("codec_line isn't built (native-test)", FakeNetwork.codecLine() != null)
        val net = FakeNetwork("amr122", closers)
        val a = CellModem("amrA", net.phone("5550001"), listen = false)
        val b = CellModem("amrB", net.phone("5550002"), listen = true)
        // (what a speech codec lets through: V.21's 300 bit/s for sure; V.22 and V.22bis connect, but lose
        // data half the time even at AMR's best, 12.2 kbit/s)
        a.command("ATE0+MS=V21")
        b.command("ATE0S0=1+MS=V21")
        a.send("ATD5550002\r")
        val ca = a.connectLine()
        b.connectLine()
        assertEquals(ca, 300, rate(ca))
        // (no repeats: its end found once, where it ends)
        val r = kotlin.random.Random(3)
        val data = (1..150).joinToString("") { ('a' + r.nextInt(26)).toString() }
        a.send(data)
        // (V.21 has no error correction: a speech codec's distortion can garble a character now and then, so
        // what's checked is the data getting through, its start intact)
        assertTrue(b.expect(data.take(40), 30).endsWith(data.take(40)))
    }

    /** V.cvm through AMR 12.2: connected at 600 or more, LAPM data whole each way, +CVM? its figures */
    @Test
    fun throughAmrInVcvm() {
        assumeTrue("codec_line isn't built (native-test)", FakeNetwork.codecLine() != null)
        val net = FakeNetwork("amr122", closers)
        val a = CellModem("cvmA", net.phone("5550001"), listen = false)
        val b = CellModem("cvmB", net.phone("5550002"), listen = true)
        // V.cvm (D-Modem-RE's, for speech codecs): V.8, then 15 to 21 s probing the codec and
        // loading a mode for it; S7 long enough for that
        a.command("ATE0\\V1+MS=V_CVM;S7=90")
        b.command("ATE0\\V1S0=1+MS=V_CVM;S7=90")
        a.send("ATD5550002\r")
        val ca = a.connectLine()
        val cb = b.connectLine()
        println("V.cvm through AMR 12.2: A ${ca.trim()}, B ${cb.trim()}")
        assertTrue(ca, ca.contains("/CVM"))
        assertTrue(cb, cb.contains("/CVM"))
        // through AMR 12.2 about 1200 bit/s, coded (V.21's four times)
        assertTrue(ca, rate(ca) >= 600)
        // LAPM over it: the data whole, each way
        val r = kotlin.random.Random(4)
        val data = (1..600).joinToString("") { ('a' + r.nextInt(26)).toString() }
        a.send(data + "\r\n")
        assertTrue(b.expect(data.takeLast(40), 60).contains(data))
        b.send("and back over V.cvm\r\n")
        a.expect("and back over V.cvm", 30)
        // the call's figures (+CVM?: the modes, then raw and coded rates each way, ...)
        Thread.sleep(1200)
        a.send("+++")
        a.expect("OK", 10)
        a.send("AT+CVM?\r")
        val fig = a.expect("OK", 10)
        println("A's figures: " + fig.lines().filter { it.startsWith("+CVM") })
        assertTrue(fig, Regex("\\+CVM: \\d+,\\d+,\\d+,\\d+,").containsMatchIn(fig))
    }

    /** a number nobody has ends the call; a modem not taking calls leaves its phone ringing, no RING */
    @Test
    fun nobodyAndNotForTheModem() {
        val net = FakeNetwork(closers = closers)
        val a = CellModem("noA", net.phone("5550001"), listen = false)
        // a number nobody has: the network ends the call
        a.command("ATE0")
        a.send("ATD5559999\r")
        a.expect("NO ", 40)
        // B's modem doesn't take calls: its phone rings as a phone, no RING, not answered
        val b = CellModem("noB", net.phone("5550002"), listen = false)
        b.command("ATE0S0=1")
        a.send("ATD5550002\r")
        val end = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < end && b.phone.state.value != CellPhone.State.RINGING) Thread.sleep(50)
        assertEquals(CellPhone.State.RINGING, b.phone.state.value)
        Thread.sleep(3000)
        assertEquals(0, b.phone.answered)
        assertTrue(b.events.none { it == "RING" })
    }
}
