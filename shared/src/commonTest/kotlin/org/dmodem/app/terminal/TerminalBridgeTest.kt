// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The terminal as Swift has it: octets as Latin-1 strings each way, rows as runs, keys, replies. */
class TerminalBridgeTest {
    /** octets in show on the screen as runs with the cursor after them, a position report's answer is taken once, and the title is set */
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

    /** typed text and keys as the octets to send: CR for a newline, control characters, UTF-8, and application cursor keys when asked */
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

    /** CP437 shows 0xdb as a full block, and typing one sends 0xdb */
    @Test
    fun cp437ForTheBbs() {
        val t = TerminalBridge(10, 2)
        t.setLook("vga", boldBright = true, iceColors = false, charset = "CP437")
        // 0xdb: CP437's full block
        t.feed("Û")
        assertEquals("█", t.runs(0)[0].text.take(1))
        assertEquals("Û", t.typed("█"))
    }

    /** a picture's cells as runs of one image's tiles, its pixels as Latin-1 RGBA (premultiplied) */
    @Test
    fun imageRuns() {
        val b = TerminalBridge(80, 24)
        b.profile = "vt340"
        // a red line across the top row's first ten cells
        b.feed("\u001bPpS(M2(H120L50S100))W(I2)P[0,5]V[99,5]\u001b\\")
        val runs = b.imageRuns(0)
        assertEquals(1, runs.size)
        val r = runs[0]
        assertEquals(0, r.col)
        assertEquals(10, r.cells)
        assertEquals(0, r.tx)
        assertEquals(0, r.ty)
        assertEquals(800, r.width)
        val px = b.imagePixels(r.imageId)
        assertEquals(800 * 480 * 4, px.length)
        val i = (5 * 800 + 50) * 4
        assertEquals(listOf(255, 0, 0, 255), (0 until 4).map { px[i + it].code })
        assertEquals(0, px[3].code)
        assertTrue(b.imageRuns(1).isEmpty())
    }
}
