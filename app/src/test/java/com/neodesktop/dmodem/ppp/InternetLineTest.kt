// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ppp

import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.IspSettings
import com.neodesktop.dmodem.transfer.TransferIo
import com.neodesktop.dmodem.fax.Class1
import org.junit.After
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Dial-up internet on a modem call: the app's [Internet] on the DTE
 * hub, modemA of native-test's fax_line (two of D-Modem's modems back to
 * back, paced as a line), an ISP on modemB (TestIsp: CHAP, an address,
 * UDP echoed); the phone's side a tunnel here: a packet out, its answer
 * back; then disconnected and hung up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InternetLineTest {
    private var line: Process? = null
    private val log = CopyOnWriteArrayList<String>()
    private lateinit var a: String
    private lateinit var b: String
    private val closers = ArrayList<() -> Unit>()

    private fun faxLine(): File? = listOfNotNull(System.getenv("DMODEM_FAX_LINE"), "../build-host/fax_line", "build-host/fax_line")
        .map { File(it) }.firstOrNull { it.canExecute() }

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

    @After
    fun tearDown() {
        File("build/internet-log.txt").writeText((ModemState.log.flow.value + listOf("----") + log).joinToString("\n"))
        Internet.disconnect()
        closers.forEach { runCatching { it() } }
        ModemState.dte.detach()
        ModemState.setRun(ModemState.Run.STOPPED)
        line?.destroy()
        line?.waitFor(5, TimeUnit.SECONDS)
    }

    private fun waitLog(what: String, secs: Int = 30): Boolean {
        val end = System.currentTimeMillis() + secs * 1000L
        while (System.currentTimeMillis() < end) {
            if (log.any { it.contains(what) }) return true
            Thread.sleep(5)
        }
        return false
    }

    private fun dump() = (ModemState.log.flow.value.takeLast(40) + log.takeLast(40)).joinToString("\n")

    @Test
    fun onlineAndPacketsBothWays() = online(script = false)

    /** an ISP that asks for a text login first (its PPP after "Starting PPP"), and a script for it */
    @Test
    fun aLoginScriptBeforePpp() = online(script = true)

    private fun online(script: Boolean) {
        // the ISP's modem, and its PPP once the call is up
        ProcessBuilder("stty", "-F", b, "raw", "-echo").start().waitFor()
        val input = FileInputStream(b)
        val output = FileOutputStream(b)
        closers.add { input.close(); output.close() }
        val io = TransferIo { bytes -> output.write(bytes); output.flush() }
        var isp: TestIsp? = null
        Thread {
            val buf = ByteArray(4096)
            while (true) {
                val n = try { input.read(buf) } catch (_: Exception) { -1 }
                if (n < 0) break
                isp?.input(buf.copyOf(n)) ?: io.feed(buf, 0, n)
            }
        }.apply { isDaemon = true; start() }
        val c = Class1(io) { log.add("isp: $it") }
        assertEquals("OK", c.command("E0"))

        // the phone's side: the packets that come
        val fromLink = ArrayBlockingQueue<ByteArray>(100)
        val tunnels = ArrayBlockingQueue<Ppp.IpConfig>(1)
        var closed = false
        val isp0 = IspSettings("5551000", "alice", "s3cret", script = if (script) "ABORT incorrect ogin:--ogin: \\U assword: \\P PPP" else "")
        val err = Internet.connect(isp0) { cfg ->
            tunnels.add(cfg)
            object : Internet.Tunnel {
                override fun write(packet: ByteArray) { fromLink.add(packet) }
                override fun close() { closed = true }
            }
        }
        assertEquals(null, err)
        assertTrue(dump(), waitLog("modemA: run cmd: ATD5551000"))
        assertTrue(c.command("A", 60_000).orEmpty().startsWith("CONNECT"))
        if (script) {
            // the host's text login: a banner, login:, Password:, then PPP
            fun say(s: String) { output.write(s.toByteArray()); output.flush() }
            say("\r\nD-Modem test ISP\r\nlogin: ")
            fun typed(): String {
                val sb = StringBuilder()
                while (true) {
                    val ch = io.read(30_000)
                    if (ch < 0 || ch == '\r'.code) return sb.toString()
                    if (ch != '\n'.code) sb.append(ch.toChar())
                }
            }
            assertEquals("alice", typed())
            say("Password: ")
            assertEquals("s3cret", typed())
            say("\r\nStarting PPP...\r\n")
        }
        isp = TestIsp(Ppp.PROTO_CHAP, "alice", "s3cret", echo = true) { f -> output.write(f); output.flush() }
        isp.start()

        val cfg = tunnels.poll(60, TimeUnit.SECONDS) ?: throw AssertionError("no link:\n${dump()}")
        assertEquals("192.168.7.42", cfg.address.hostAddress)
        assertEquals(listOf("192.168.7.1"), cfg.dns.map { it.hostAddress })
        // (connected once the tunnel is there)
        val until = System.currentTimeMillis() + 5000
        while (Internet.state.value?.connected != true && System.currentTimeMillis() < until) Thread.sleep(20)
        val st = Internet.state.value!!
        assertTrue(st.toString(), st.connected && st.connect.startsWith("CONNECT"))

        // a packet from the phone, its echo back from the ISP
        assertTrue(Internet.sendIp(Ip.udp(cfg.address.address, byteArrayOf(192.toByte(), 168.toByte(), 7, 1), 5353, 7, "hello over the modem".toByteArray())))
        val back = fromLink.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("no answer:\n${dump()}")
        assertEquals("pong:hello over the modem", String(Ip.udpPayload(back)))

        // disconnected: the tunnel closed, the modem's DTE free again
        Internet.disconnect()
        val end = System.currentTimeMillis() + 15_000
        while (Internet.state.value?.running == true && System.currentTimeMillis() < end) Thread.sleep(100)
        assertEquals("disconnected", Internet.state.value?.ended)
        assertTrue(closed)
        assertTrue(!ModemState.dte.claimed)
        assertTrue(dump(), log.any { it.contains("modemA: run cmd: ATH") })
    }
}
