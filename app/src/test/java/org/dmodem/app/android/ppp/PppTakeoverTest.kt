// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ppp

import org.dmodem.app.android.NativeModem
import org.dmodem.app.android.modem.ModemState
import org.dmodem.app.android.settings.IspSettings
import org.dmodem.app.ppp.Ip
import org.dmodem.app.ppp.Ppp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * PPP started on a call the modem has already (dialled and logged in by hand in the terminal, then the
 * terminal's "Start PPP"): no AT commands, PPP straight away on the DTE, an address from the ISP, a
 * packet echoed; disconnected, the call hung up (+++, ATH) and the DTE free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PppTakeoverTest {
    /** the app's end of the modem, and the far end's (the ISP on the call) */
    private val fromApp = PipedInputStream(1 shl 16)
    private val toApp = PipedOutputStream()

    /** the DTE on pipes, the modem running and online */
    @Before
    fun setUp() {
        val appIn = PipedInputStream(toApp, 1 shl 16)
        val appOut = PipedOutputStream(fromApp)
        ModemState.dte.attach(appIn, appOut)
        ModemState.setRun(ModemState.Run.RUNNING)
        ModemState.setStatus(NativeModem.Status(NativeModem.Link.ONLINE, true, 33600, 33600))
    }

    /**
     * disconnected (and its hang-up done, so a link left by a failed test
     * can't reach the next one's DTE), the DTE detached, the modem stopped
     */
    @After
    fun tearDown() {
        Internet.disconnect()
        val end = System.currentTimeMillis() + 15_000
        while (Internet.running && System.currentTimeMillis() < end) Thread.sleep(100)
        ModemState.dte.detach()
        ModemState.setStatus(ModemState.IDLE)
        ModemState.setRun(ModemState.Run.STOPPED)
    }

    /** not online: it says to dial first */
    @Test
    fun needsACall() {
        ModemState.setStatus(ModemState.IDLE)
        assertEquals("no call to start PPP on: dial and log in first", Internet.connect(IspSettings(), dial = false) { null })
    }

    /** PPP on the call: no dial, the link up, a packet each way, the hang-up at the end */
    @Test
    fun pppOnTheCallThereIs() {
        val sent = StringBuilder()
        val isp = TestIsp(Ppp.PROTO_PAP, "alice", "s3cret", echo = true) { f -> synchronized(toApp) { toApp.write(f); toApp.flush() } }
        Thread {
            val buf = ByteArray(4096)
            while (true) {
                val n = try { fromApp.read(buf) } catch (_: Exception) { -1 }
                if (n < 0) break
                synchronized(sent) { sent.append(String(buf, 0, n, Charsets.ISO_8859_1)) }
                isp.input(buf.copyOf(n))
            }
        }.apply { isDaemon = true; start() }

        val tunnels = ArrayBlockingQueue<Ppp.IpConfig>(1)
        val fromLink = ArrayBlockingQueue<ByteArray>(10)
        var closed = false
        // (no number: none is dialled)
        val err = Internet.connect(IspSettings(user = "alice", password = "s3cret"), dial = false) { cfg ->
            tunnels.add(cfg)
            object : Internet.Tunnel {
                /** a packet from the link, kept */
                override fun write(packet: ByteArray) { fromLink.add(packet) }
                /** closed, noted */
                override fun close() { closed = true }
            }
        }
        assertEquals(null, err)
        // (the ISP's side of PPP, once the app's is listening)
        Thread.sleep(300)
        isp.start()
        val cfg = tunnels.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("no link: ${ModemState.log.flow.value.takeLast(30)}")
        assertEquals("192.168.7.42", cfg.address.hostAddress)
        // (PPP's frames from the first octet: no AT commands before them)
        assertEquals('\u007e', synchronized(sent) { sent[0] })
        // (the link's tunnel in place: the address comes to openTunnel before Internet keeps the tunnel
        // it returns, and an answer before that would be dropped)
        val until = System.currentTimeMillis() + 10_000
        while (Internet.state.value?.connected != true && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(Internet.state.value?.connected == true)

        assertTrue(Internet.sendIp(Ip.udp(cfg.address.address, byteArrayOf(192.toByte(), 168.toByte(), 7, 1), 5353, 7, "hi".toByteArray())))
        val back = fromLink.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("no answer")
        assertEquals("pong:hi", String(Ip.udpPayload(back)))

        Internet.disconnect()
        val end = System.currentTimeMillis() + 15_000
        while (Internet.state.value?.running == true && System.currentTimeMillis() < end) Thread.sleep(100)
        assertTrue(closed)
        assertTrue(!ModemState.dte.claimed)
        assertTrue(synchronized(sent) { sent.contains("+++") && sent.contains("ATH") })
    }
}
