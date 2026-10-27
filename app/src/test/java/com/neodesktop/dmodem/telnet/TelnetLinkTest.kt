// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.telnet

import com.neodesktop.dmodem.telnet.TelnetLink.Companion.DO
import com.neodesktop.dmodem.telnet.TelnetLink.Companion.DONT
import com.neodesktop.dmodem.telnet.TelnetLink.Companion.ECHO
import com.neodesktop.dmodem.telnet.TelnetLink.Companion.IAC
import com.neodesktop.dmodem.telnet.TelnetLink.Companion.NAWS
import com.neodesktop.dmodem.telnet.TelnetLink.Companion.SB
import com.neodesktop.dmodem.telnet.TelnetLink.Companion.SE
import com.neodesktop.dmodem.telnet.TelnetLink.Companion.SGA
import com.neodesktop.dmodem.telnet.TelnetLink.Companion.TTYPE
import com.neodesktop.dmodem.telnet.TelnetLink.Companion.WILL
import com.neodesktop.dmodem.telnet.TelnetLink.Companion.WONT
import com.neodesktop.dmodem.terminal.TerminalLink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

/** The Telnet client against a scripted server on a loopback socket. */
class TelnetLinkTest {

    /** [v] as bytes */
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** what the server reads, until it has n octets (or 5 s) */
    private fun readN(s: Socket, n: Int): ByteArray {
        val out = ByteArrayOutputStream()
        s.soTimeout = 5000
        val inp = s.getInputStream()
        while (out.size() < n) {
            val b = inp.read()
            if (b < 0) break
            out.write(b)
        }
        return out.toByteArray()
    }

    /** waited until [cond], or 5 seconds */
    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    /** options answered (TTYPE, NAWS with the size, ECHO, SGA, an unknown one refused), TTYPE's IS, IAC in data both ways, CR NUL, a resize, the echo dropped, closed */
    @Test
    fun negotiationDataAndTheWindowsSize() {
        ServerSocket(0).use { ss ->
            val link = TelnetLink("127.0.0.1", ss.localPort, termType = "ANSI", cols = 80, rows = 25)
            val got = StringBuffer()
            link.addListener { d, n -> got.append(String(d, 0, n, Charsets.ISO_8859_1)) }
            link.connect()
            val s = ss.accept()
            waitFor { link.state.value == TerminalLink.State.Open }
            val o = s.getOutputStream()

            // the server: DO TTYPE, DO NAWS, WILL ECHO, WILL SGA, DO 99 (unknown)
            o.write(bytes(IAC, DO, TTYPE, IAC, DO, NAWS, IAC, WILL, ECHO, IAC, WILL, SGA, IAC, DO, 99))
            o.flush()
            val answers = readN(s, 3 + 3 + 9 + 3 + 3 + 3)
            assertArrayEquals(
                bytes(
                    IAC, WILL, TTYPE,
                    IAC, WILL, NAWS, IAC, SB, NAWS, 0, 80, 0, 25, IAC, SE,
                    IAC, DO, ECHO,
                    IAC, DO, SGA,
                    IAC, WONT, 99,
                ),
                answers,
            )

            // TERMINAL-TYPE SEND: IS ANSI
            o.write(bytes(IAC, SB, TTYPE, 1, IAC, SE)); o.flush()
            assertArrayEquals(bytes(IAC, SB, TTYPE, 0, 'A'.code, 'N'.code, 'S'.code, 'I'.code, IAC, SE), readN(s, 10))

            // data with an escaped 0xff and a command (NOP) in the middle
            o.write(bytes('h'.code, 'i'.code, IAC, IAC, IAC, 241, '!'.code)); o.flush()
            waitFor { got.length >= 4 }
            assertEquals("hiÿ!", got.toString())

            // ours: 0xff doubled, a lone CR as CR NUL, CR LF as it is
            link.write(bytes(0xff, 'x'.code, '\r'.code, 'y'.code, '\r'.code, '\n'.code))
            assertArrayEquals(bytes(IAC, IAC, 'x'.code, '\r'.code, 0, 'y'.code, '\r'.code, '\n'.code), readN(s, 8))

            // a new size: NAWS
            link.resize(132, 50)
            assertArrayEquals(bytes(IAC, SB, NAWS, 0, 132, 0, 50, IAC, SE), readN(s, 9))

            // the server takes its echo back
            o.write(bytes(IAC, WONT, ECHO)); o.flush()
            assertArrayEquals(bytes(IAC, DONT, ECHO), readN(s, 3))

            s.close()
            waitFor { link.state.value is TerminalLink.State.Closed }
            assertTrue((link.state.value as TerminalLink.State.Closed).reason!!.contains("closed"))
        }
    }

    /** nobody listening: closed */
    @Test
    fun noServerThere() {
        val port = ServerSocket(0).use { it.localPort }
        val link = TelnetLink("127.0.0.1", port)
        link.connect()
        waitFor { link.state.value is TerminalLink.State.Closed }
        assertTrue(link.state.value is TerminalLink.State.Closed)
    }
}
