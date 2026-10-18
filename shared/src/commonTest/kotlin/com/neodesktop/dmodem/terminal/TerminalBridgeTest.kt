// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The terminal as Swift has it: octets as Latin-1 strings each way, rows as runs, keys, replies. */
class TerminalBridgeTest {
    @Test
    fun octetsInScreenOutRepliesBack() {
        val t = TerminalBridge(20, 4)
        val r0 = t.revision
        t.feed("hello\r\nworld")
        assertTrue(t.revision != r0)
        assertEquals("hello", t.runs(0).joinToString("") { it.text }.trimEnd())
        assertEquals(1, t.cursorY)
        assertEquals(5, t.cursorX)
        // a cursor position report asked for: the answer to send back
        t.feed("\u001b[6n")
        assertEquals("\u001b[2;6R", t.takeReplies())
        assertEquals("", t.takeReplies())
        // the title
        t.feed("\u001b]2;BBS\u0007")
        assertEquals("BBS", t.title)
    }

    @Test
    fun typedAndKeys() {
        val t = TerminalBridge(20, 4)
        assertEquals("a\r", t.typed("a\n"))
        assertEquals("\u0003", t.typed("c", ctrl = true))
        // UTF-8's octets, one character each
        assertEquals("Ã©", t.typed("é"))
        assertEquals("\u001b[A", t.key("UP"))
        assertEquals("\u007f", t.key("BACKSPACE"))
        assertEquals("", t.key("NOPE"))
        // application cursor keys, as the remote asks
        t.feed("\u001b[?1h")
        assertEquals("\u001bOA", t.key("UP"))
    }

    @Test
    fun cp437ForTheBbs() {
        val t = TerminalBridge(10, 2)
        t.setLook("vga", boldBright = true, iceColors = false, charset = "CP437")
        // 0xdb: CP437's full block
        t.feed("Û")
        assertEquals("█", t.runs(0)[0].text.take(1))
        assertEquals("Û", t.typed("█"))
    }
}
