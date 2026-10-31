// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ppp

import org.dmodem.app.ppp.Ip
import org.dmodem.app.ppp.Ppp
import org.dmodem.app.fax.Class1
import org.dmodem.app.android.modem.ModemState
import org.dmodem.app.transfer.TransferIo
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Dial-in internet ([DialIn]) on a modem call: the app's modem is modemA
 * of native-test's fax_line (two of D-Modem's modems back to back, paced
 * as a line), the caller's modemB with our PPP client as its dialer's. The
 * caller dials, the app answers; at the CONNECT the app's PPP server gives
 * the caller its address; DNS to the gateway and a ping through it; the
 * caller's PPP over, the app hangs up and has its DTE back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DialInLineTest {
    private var line: Process? = null
    private val log = CopyOnWriteArrayList<String>()
    private lateinit var a: String
    private lateinit var b: String
    private val closers = ArrayList<() -> Unit>()

    /** native-test's fax_line binary: DMODEM_FAX_LINE's or build-host's, or null when not built */
    private fun faxLine(): File? = listOfNotNull(System.getenv("DMODEM_FAX_LINE"), "../build-host/fax_line", "build-host/fax_line")
        .map { File(it) }.firstOrNull { it.canExecute() }

    /** fax_line started, its two modems' ttys read from its log, modemA as the app's DTE and running */
    @Before
    fun setUp() {
        val fl = faxLine()
        assumeTrue("fax_line isn't built (native-test)", fl != null)
        val p = ProcessBuilder(fl!!.path).redirectErrorStream(true).start()
        line = p
        Thread { p.inputStream.bufferedReader().forEachLine { log.add(it) } }.apply { isDaemon = true; start() }
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && log.none { it.startsWith("created modemB: ") }) Thread.sleep(20)
        a = log.first { it.startsWith("created modemA: ") }.substringAfter(": ")
        b = log.first { it.startsWith("created modemB: ") }.substringAfter(": ")
        ProcessBuilder("stty", "-F", a, "raw", "-echo").start().waitFor()
        ModemState.dte.attach(FileInputStream(a), FileOutputStream(a))
        ModemState.setRun(ModemState.Run.RUNNING)
    }

    /** the logs written to build/dial-in-log.txt, the ISP settings reset, the modem detached and stopped, fax_line ended */
    @After
    fun tearDown() {
        File("build/dial-in-log.txt").writeText((ModemState.log.flow.value + listOf("----") + log).joinToString("\n"))
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlinx.coroutines.runBlocking { org.dmodem.app.android.settings.IspSettingsStore(ctx).save(org.dmodem.app.android.settings.IspSettings()) }
        closers.forEach { runCatching { it() } }
        ModemState.dte.detach()
        ModemState.setRun(ModemState.Run.STOPPED)
        line?.destroy()
        line?.waitFor(5, TimeUnit.SECONDS)
    }

    /** the last of the app's log and fax_line's, for a failure's message */
    private fun dump() = (ModemState.log.flow.value.takeLast(40) + log.takeLast(40)).joinToString("\n")

    /** a caller dials in: answered, its PPP given an address, DNS and a ping through the gateway, hung up after with the DTE free */
    @Test
    fun aCallerGetsTheInternet() {
        // the phone's "DNS server" (its answer: the query back with "A")
        val dns = DatagramSocket(0, InetAddress.getLoopbackAddress())
        closers.add { dns.close() }
        Thread {
            val d = DatagramPacket(ByteArray(512), 512)
            dns.receive(d)
            dns.send(DatagramPacket(d.data.copyOf(d.length) + "A".toByteArray(), d.length + 1, d.socketAddress))
        }.apply { isDaemon = true; start() }
        DialIn.dnsServers = { listOf(InetAddress.getLoopbackAddress()) }
        DialIn.dnsPort = dns.localPort
        // on in the settings, as the app has it (its DialIn watching them since the app started)
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlinx.coroutines.runBlocking { org.dmodem.app.android.settings.IspSettingsStore(ctx).save(org.dmodem.app.android.settings.IspSettings(dialIn = true)) }
        val on = System.currentTimeMillis() + 5000
        while (!DialIn.enabled && System.currentTimeMillis() < on) Thread.sleep(20)
        assertTrue(DialIn.enabled)
        DialIn.watch()

        // the caller's modem, then its PPP once the call is up
        ProcessBuilder("stty", "-F", b, "raw", "-echo").start().waitFor()
        val input = FileInputStream(b)
        val output = FileOutputStream(b)
        closers.add { input.close(); output.close() }
        val io = TransferIo { bytes -> output.write(bytes); output.flush() }
        val pppRef = java.util.concurrent.atomic.AtomicReference<Ppp?>(null)
        Thread {
            val buf = ByteArray(4096)
            while (true) {
                val n = try { input.read(buf) } catch (_: Exception) { -1 }
                if (n < 0) break
                pppRef.get()?.input(buf, 0, n) ?: io.feed(buf, 0, n)
            }
        }.apply { isDaemon = true; start() }
        val c = Class1(io) { log.add("caller: $it") }
        assertEquals("OK", c.command("E0"))
        // (the line has no dial tone: the caller dials blind)
        assertEquals("OK", c.command("X3"))

        // the caller dials; the app's modem rings (the line has no ring signal: said so) and answers
        val dialled = ArrayBlockingQueue<String>(1)
        Thread { dialled.add(c.command("D5551000", 60_000) ?: "none") }.apply { isDaemon = true; start() }
        assertTrue(dump(), waitLog("modemB: run cmd: ATD5551000"))
        DialIn.rings()
        assertTrue(ModemState.answer())
        val r = dialled.poll(60, TimeUnit.SECONDS)
        assertTrue("$r\n${dump()}", r.orEmpty().startsWith("CONNECT"))

        // the caller's PPP: an address from the app
        val up = ArrayBlockingQueue<Ppp.IpConfig>(1)
        val packets = LinkedBlockingQueue<ByteArray>()
        val p = Ppp({ f -> output.write(f); output.flush() }, events = object : Ppp.Events {
            /** the link up: its addresses, kept */
            override fun up(config: Ppp.IpConfig) { up.add(config) }
            /** a packet from the app, kept */
            override fun ip(packet: ByteArray) { packets.add(packet) }
            /** the link down: nothing to do */
            override fun down(reason: String) {}
        }, retryMs = 1000)
        pppRef.set(p)
        p.start()
        val cfg = up.poll(60, TimeUnit.SECONDS) ?: throw AssertionError("no link:\n${dump()}")
        assertEquals("10.64.64.2", cfg.address.hostAddress)
        assertEquals(listOf("10.64.64.1"), cfg.dns.map { it.hostAddress })
        val until = System.currentTimeMillis() + 5000
        while (DialIn.state.value?.online != true && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(DialIn.state.value.toString(), DialIn.state.value?.online == true)
        assertTrue(ModemState.dte.claimed)

        // DNS to the gateway: the phone's server's answer; a ping to the gateway
        val me = byteArrayOf(10, 64, 64, 2)
        val gw = byteArrayOf(10, 64, 64, 1)
        p.sendIp(Ip.udp(me, gw, 5353, 53, "query".toByteArray()))
        val answer = packets.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("no DNS answer:\n${dump()}")
        assertEquals("queryA", String(Ip.udpPayload(answer)))
        assertArrayEquals(gw, answer.copyOfRange(12, 16))
        p.sendIp(Ip.icmp(me, gw, 8, 0, byteArrayOf(0, 1, 0, 1) + "ping".toByteArray()))
        val pong = packets.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("no pong:\n${dump()}")
        assertEquals(0, pong[20].toInt())
        assertEquals("ping", String(pong, 28, 4))

        // the caller's PPP over: the app hangs up, its DTE free again
        p.stop("the caller is done")
        val end = System.currentTimeMillis() + 20_000
        while (DialIn.state.value?.running == true && System.currentTimeMillis() < end) Thread.sleep(100)
        assertTrue(DialIn.state.value.toString(), DialIn.state.value?.ended != null)
        assertTrue(!ModemState.dte.claimed)
        assertTrue(dump(), waitLog("modemA: run cmd: ATH"))
    }

    /** whether fax_line's log has [what] within [secs] */
    private fun waitLog(what: String, secs: Int = 30): Boolean {
        val end = System.currentTimeMillis() + secs * 1000L
        while (System.currentTimeMillis() < end) {
            if (log.any { it.contains(what) }) return true
            Thread.sleep(5)
        }
        return false
    }
}
