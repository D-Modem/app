// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.rlogin

import org.dmodem.app.android.terminal.TerminalLink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** An rlogin login against a server on the loopback: the handshake, its urgent window request, data both ways. */
class RloginLinkTest {
    @Test
    fun logsInAndAnswersTheWindowRequest() {
        val server = ServerSocket(0)
        val got = LinkedBlockingQueue<ByteArray>()
        val t = Thread {
            server.accept().use { c ->
                val inp = c.getInputStream()
                val buf = ByteArray(256)
                // the handshake: 4 NUL-ended strings
                val hs = ByteArrayOutputStream()
                var nuls = 0
                while (nuls < 4) {
                    val b = inp.read()
                    if (b < 0) return@Thread
                    hs.write(b)
                    if (b == 0) nuls++
                }
                got.put(hs.toByteArray())
                c.getOutputStream().write(0)
                c.sendUrgentData(0x80)
                c.getOutputStream().write("login: ".toByteArray())
                // the window's size, then what's typed
                val n = inp.read(buf, 0, 12)
                got.put(buf.copyOf(n))
                val k = inp.read(buf)
                got.put(buf.copyOf(k))
            }
        }.apply { start() }
        val link = RloginLink("127.0.0.1", server.localPort, "alice", term = "vt100", cols = 80, rows = 24)
        val screen = LinkedBlockingQueue<String>()
        link.addListener(TerminalLink.Listener { d, n -> screen.put(String(d, 0, n, Charsets.ISO_8859_1)) })
        link.connect()
        assertEquals("\u0000alice\u0000alice\u0000vt100/38400\u0000", String(got.poll(10, TimeUnit.SECONDS)!!, Charsets.ISO_8859_1))
        assertArrayEquals(
            byteArrayOf(-1, -1, 's'.code.toByte(), 's'.code.toByte(), 0, 24, 0, 80, 2, -128, 1, -128),
            got.poll(10, TimeUnit.SECONDS),
        )
        val shown = StringBuilder()
        while (!shown.contains("login: ")) shown.append(screen.poll(10, TimeUnit.SECONDS)!!)
        assertEquals("login: ", shown.toString())
        link.write("alice\r".toByteArray())
        assertEquals("alice\r", String(got.poll(10, TimeUnit.SECONDS)!!))
        link.close()
        t.join(5000)
        server.close()
    }
}
