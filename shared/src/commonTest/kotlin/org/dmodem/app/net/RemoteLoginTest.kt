// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.net

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteLoginTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun s(t: String) = t.encodeToByteArray()
    private fun ints(a: ByteArray) = a.map { it.toInt() and 0xff }

    @Test
    fun telnetNegotiates() {
        val t = TelnetCodec("ANSI", 80, 24)
        t.connected()
        assertEquals(listOf(255, 251, 24, 255, 251, 31, 255, 253, 3), ints(t.output()))
        // DO TTYPE, DO NAWS (our size), WILL ECHO, WILL SGA (asked: not answered), DO LINEMODE (refused), text
        t.feed(b(255, 253, 24, 255, 253, 31, 255, 251, 1, 255, 251, 3, 255, 253, 34) + s("Hi") + b(255, 255) + s("!"))
        assertEquals(listOf(255, 250, 31, 0, 80, 0, 24, 255, 240, 255, 253, 1, 255, 252, 34), ints(t.output()))
        assertEquals(listOf('H'.code, 'i'.code, 255, '!'.code), ints(t.screen()))
        // TERMINAL-TYPE SEND, split
        t.feed(b(255, 250, 24))
        t.feed(b(1, 255, 240))
        assertEquals(listOf(255, 250, 24, 0) + ints(s("ANSI")) + listOf(255, 240), ints(t.output()))
        // CR NUL is a CR
        t.feed(s("a\r") + b(0) + s("b"))
        assertEquals("a\rb", t.screen().decodeToString())
        // typed: a lone CR as CR NUL, IAC doubled; CR LF as it is
        t.type(s("ls\r") + b(255))
        assertEquals(ints(s("ls\r")) + listOf(0, 255, 255), ints(t.output()))
        t.type(s("\r\n"))
        assertEquals("\r\n", t.output().decodeToString())
        // resized: NAWS with 0xff doubled, once
        t.resize(255, 50)
        assertEquals(listOf(255, 250, 31, 0, 255, 255, 0, 50, 255, 240), ints(t.output()))
        t.resize(255, 50)
        assertEquals(0, t.output().size)
    }

    @Test
    fun rloginHandshakeAndWindow() {
        val r = RloginCodec("alice", "xterm", 80, 24)
        r.connected()
        assertEquals("\u0000alice\u0000alice\u0000xterm/38400\u0000", r.output().decodeToString())
        r.feed(b(0) + s("Password:"))
        assertEquals("Password:", r.screen().decodeToString())
        r.resize(100, 30)
        assertEquals(0, r.output().size)
        r.urgent(0x80)
        assertEquals(listOf(255, 255, 's'.code, 's'.code, 0, 30, 0, 100, 3, 32, 1, 224), ints(r.output()))
        r.type(s("\r"))
        assertEquals("\r", r.output().decodeToString())
    }

    @Test
    fun rloginInlineUrgent() {
        val r = RloginCodec("bob", "ansi", 80, 24, inlineUrgent = true)
        r.connected()
        r.output()
        r.feed(b(0, 0x80) + s("$ "))
        assertEquals("$ ", r.screen().decodeToString())
        assertEquals(listOf(255, 255, 's'.code, 's'.code, 0, 24, 0, 80, 2, 128, 1, 128), ints(r.output()))
        // later 0x80s are data
        r.feed(b(0x80))
        assertEquals(listOf(0x80), ints(r.screen()))
    }

    @Test
    fun bridgeIsLatin1() {
        val l = LoginBridge("telnet", "", "ANSI", 80, 24, false)
        l.feed("ÿÿx")
        assertEquals("ÿx", l.screen())
        assertEquals(513, LoginBridge.defaultPort("rlogin"))
    }
}
