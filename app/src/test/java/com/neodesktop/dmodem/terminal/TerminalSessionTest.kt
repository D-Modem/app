// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.terminal

import com.neodesktop.dmodem.modem.DteHub
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class TerminalSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Modem {
        val written = ByteArrayOutputStream()
        val toHub = PipedOutputStream()
        val hubIn = PipedInputStream(toHub, 65536)
        /** what the modem was sent since last asked, as text */
        fun sent() = written.toString(Charsets.ISO_8859_1).also { written.reset() }
    }

    /** a hub with a fake modem on it, and a session on the hub capturing to the temporary folder */
    private fun setup(): Triple<DteHub, TerminalSession, Modem> {
        val hub = DteHub()
        val m = Modem()
        hub.attach(m.hubIn, m.written)
        return Triple(hub, TerminalSession(hub) { tmp.root }, m)
    }

    /** waited until [cond], or 5 seconds */
    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    /** a latched Ctrl or Alt goes with the next key, then is let go */
    @Test
    fun latchedModifiersGoWithTheNextKeyOnly() {
        val (hub, s, m) = setup()
        s.ctrl.value = true
        s.sendChar('c'.code)
        assertEquals("\u0003", m.sent())
        assertFalse(s.ctrl.value)
        s.sendChar('c'.code)
        assertEquals("c", m.sent())
        s.alt.value = true
        s.sendKey(Key.UP)
        assertEquals("\u001b[1;3A", m.sent())
        hub.detach()
    }

    /** what's sent, in the session's charset (CP437's box lines) */
    @Test
    fun theSessionsCharset() {
        val (hub, s, m) = setup()
        s.charset = TermCharset.CP437
        s.send("╔═╗")
        assertArrayEquals(byteArrayOf(0xc9.toByte(), 0xcd.toByte(), 0xbb.toByte()), m.written.toByteArray())
        hub.detach()
    }

    /** a paste's newlines sent as returns, and bracketed once the remote asks for it */
    @Test
    fun pasteAndBracketedPaste() {
        val (hub, s, m) = setup()
        s.paste("one\ntwo\r\n")
        assertEquals("one\rtwo\r", m.sent())
        synchronized(s.emulator) { s.emulator.appendText("\u001b[?2004h") }
        s.paste("x")
        assertEquals("\u001b[200~x\u001b[201~", m.sent())
        hub.detach()
    }

    /** local echo on the screen, and the modem's output after it */
    @Test
    fun localEchoAndTheModemsOutputOnTheScreen() {
        val (hub, s, m) = setup()
        s.localEcho = true
        s.send("hi\r")
        assertEquals("hi", synchronized(s.emulator) { s.emulator.line(0).text() })
        m.toHub.write("\r\nCONNECT 52000\r\n".toByteArray()); m.toHub.flush()
        waitFor { synchronized(s.emulator) { s.emulator.line(2).text() } == "CONNECT 52000" }
        assertEquals("CONNECT 52000", synchronized(s.emulator) { s.emulator.line(2).text() })
        hub.detach()
    }

    /** the remote's cursor position report asked for, answered */
    @Test
    fun theRemotesQuestionsAreAnswered() {
        val (hub, s, m) = setup()
        m.toHub.write("\u001b[6n".toByteArray()); m.toHub.flush()
        waitFor { m.written.size() > 0 }
        assertEquals("\u001b[1;1R", m.sent())
        hub.detach()
    }

    /** a capture file with what the modem sent, stopped, named dmodem-... */
    @Test
    fun captureKeepsWhatTheModemSent() {
        val (hub, s, m) = setup()
        val f = s.startCapture()
        assertNotNull(f)
        m.toHub.write("RING\r\n".toByteArray()); m.toHub.flush()
        waitFor { f!!.length() >= 6 }
        assertEquals(f, s.stopCapture())
        assertNull(s.capture.value)
        assertEquals("RING\r\n", f!!.readText())
        assertTrue(f.name.startsWith("dmodem-"))
        hub.detach()
    }

    /** a snippet's escapes: \r, ^C, \e, \t, \\, \x01; ^ before a lower-case letter as it is */
    @Test
    fun snippetEscapes() {
        assertEquals("ATH\r", Snippet.unescape("ATH\\r"))
        assertEquals("\u0003", Snippet.unescape("^C"))
        assertEquals("\u001b[2J\t\\x", Snippet.unescape("\\e[2J\\t\\\\x"))
        assertEquals("A\u0001", Snippet.unescape("A\\x01"))
        assertEquals("^a", Snippet.unescape("^a"))
    }
}
