// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class RipTest {

    private val replies = StringBuilder()

    /** a RIP terminal at its 80x43 (10x20 cells: an 800x860 picture), whose replies are kept */
    private fun term(): TerminalEmulator {
        replies.setLength(0)
        return TerminalEmulator(80, 43, 100, TerminalProfiles.RIP) { b -> b.forEach { replies.append((it.toInt() and 0xff).toChar()) } }
            .also { it.decoder.charset = TermCharset.CP437 }
    }

    /** [s] fed in as Latin-1 octets */
    private fun TerminalEmulator.send(s: String) = append(s.latin1())

    /** [v] as a MegaNum of [w] digits */
    private fun m(v: Int, w: Int = 2): String {
        val d = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var n = v
        val b = CharArray(w)
        for (i in w - 1 downTo 0) { b[i] = d[n % 36]; n /= 36 }
        return b.concatToString()
    }

    /** whether RIP's pixel ([x], [y]) (of 640x350) is drawn: the picture's pixel there, through its tile */
    private fun TerminalEmulator.drawn(x: Int, y: Int): Int {
        val px = x * 800 / 640
        val py = y * 860 / 350
        val tile = line(py / 20).extra(px / 10)?.image?.takeIf { it.image.picture } ?: return 0
        return tile.image.pixels[py * tile.image.width + px]
    }

    /** row [y]'s text, its trailing blanks gone */
    private fun TerminalEmulator.row(y: Int) = text(y, 0, y, cols - 1).trimEnd()

    /** ESC [ ! asks for RIP: its version; other CSIs still ANSI's */
    @Test
    fun query() {
        val t = term()
        t.send("\u001b[!")
        assertEquals("RIPSCRIP015400", replies.toString())
        t.send("\u001b[1mB\u001b[0m")
        assertTrue(Style.attrs(t.line(0).styles[0]) and Style.BOLD != 0)
        assertEquals("B", t.row(0))
    }

    /** a line in a colour; a ! that doesn't start a RIP line is text */
    @Test
    fun lineAndText() {
        val t = term()
        t.send("!|c0E|L${m(10)}${m(100)}${m(300)}${m(100)}\r\nHello!\r\n!not rip")
        assertEquals(0xffffff55.toInt(), t.drawn(150, 100))
        assertEquals(0, t.drawn(150, 200))
        assertEquals("Hello!", t.row(0))
        assertEquals("!not rip", t.row(1))
    }

    /** B: a bar in the fill colour; R and F: an outline flood-filled inside */
    @Test
    fun fills() {
        val t = term()
        t.send("!|S0104|B${m(10)}${m(10)}${m(50)}${m(50)}\r\n")
        assertEquals(RipInterpreter.ega(4), t.drawn(30, 30))
        t.send("!|c0F|R${m(100)}${m(100)}${m(200)}${m(200)}|S0102|F${m(150)}${m(150)}0F\r\n")
        assertEquals(RipInterpreter.ega(2), t.drawn(150, 150))
        assertEquals(0, t.drawn(250, 150))
    }

    /** a button: drawn, and a tap on it sends its host text (^M a CR, \| a bar), a tap elsewhere nothing */
    @Test
    fun buttons() {
        val t = term()
        t.send("!|1U${m(64)}${m(35)}${m(192)}${m(70)}0000<>Go<>G\\|o^M\r\n")
        assertTrue(t.drawn(100, 50) != 0)
        assertContentEquals("G|o\r".latin1(), t.tap(0.2, 0.15))
        assertNull(t.tap(0.9, 0.9))
        // 1K: the regions gone
        t.send("!|1K\r\n")
        assertNull(t.tap(0.2, 0.15))
    }

    /** a line continued with a backslash at its end; * resets the windows (the picture gone) */
    @Test
    fun continuationAndReset() {
        val t = term()
        t.send("!|c0F|L${m(10)}${m(10)}\\\r\n${m(300)}${m(10)}\r\n")
        assertTrue(t.drawn(150, 10) != 0)
        t.send("!|*\r\n")
        assertEquals(0, t.drawn(150, 10))
    }

    /** ESC [ 1 ! turns RIP off: !| is text */
    @Test
    fun disabled() {
        val t = term()
        t.send("\u001b[1!!|c0F\r\n")
        assertEquals("!|c0F", t.row(0))
    }
}
