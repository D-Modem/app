// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class TektronixTest {

    private val replies = StringBuilder()

    /** an 80x24 terminal of [profile] (10x20 cells: an 800x480 picture), whose replies are kept */
    private fun term(profile: TerminalProfile): TerminalEmulator {
        replies.setLength(0)
        return TerminalEmulator(80, 24, 100, profile) { b -> b.forEach { replies.append((it.toInt() and 0xff).toChar()) } }
    }

    /** [s] fed in as Latin-1 octets */
    private fun TerminalEmulator.send(s: String) = append(s.latin1())

    /** a 4010 address (10-bit [x], [y]) as its four bytes: HiY, LoY, HiX, LoX */
    private fun addr(x: Int, y: Int): String = buildString {
        append((0x20 or (y shr 5)).toChar())
        append((0x60 or (y and 31)).toChar())
        append((0x20 or (x shr 5)).toChar())
        append((0x40 or (x and 31)).toChar())
    }

    /** whether cell ([x], [y]) shows a tile of the picture */
    private fun TerminalEmulator.picture(x: Int, y: Int): Boolean = line(y).extra(x)?.image?.image?.picture == true

    /** a vector: GS's first address a dark move, the next drawn to; the cells it crosses show it, the rest don't */
    @Test
    fun vector() {
        val t = term(TerminalProfiles.TEK4014)
        // along the bottom, from the left to the middle
        t.send("\u001d" + addr(0, 10) + addr(511, 10))
        assertTrue(t.picture(0, 23))
        assertTrue(t.picture(39, 23))
        assertFalse(t.picture(60, 23))
        assertFalse(t.picture(0, 0))
        // ESC FF: the screen cleared
        t.send("\u001b\u000c")
        assertFalse(t.picture(0, 23))
    }

    /** a dark move only: nothing drawn */
    @Test
    fun darkMove() {
        val t = term(TerminalProfiles.TEK4014)
        t.send("\u001d" + addr(500, 300))
        for (y in 0 until 24) for (x in 0 until 80) assertFalse(t.picture(x, y))
    }

    /** alpha text drawn at the top left, in the picture */
    @Test
    fun alphaText() {
        val t = term(TerminalProfiles.TEK4014)
        t.send("HI")
        assertTrue(t.picture(0, 0))
        assertFalse(t.picture(0, 23))
    }

    /** ESC ENQ: the status and the beam's place, five bits a byte, then CR */
    @Test
    fun report() {
        val t = term(TerminalProfiles.TEK4014)
        t.send("\u001d" + addr(100, 200) + "\u001b\u0005")
        assertEquals("0#$&(\r", replies.toString())
    }

    /** xterm's Tek mode: DECSET 38 to the tube on the alternate screen, ESC ETX back to the text */
    @Test
    fun xtermTekMode() {
        val t = term(TerminalProfiles.XTERM)
        t.send("text\u001b[?38h")
        assertTrue(t.altScreen)
        t.send("\u001d" + addr(0, 10) + addr(511, 10))
        assertTrue(t.picture(0, 23))
        t.send("\u001b\u0003")
        assertFalse(t.altScreen)
        assertEquals("text", t.text(0, 0, 0, 79).trimEnd())
        // and the VT's sequences read again
        t.send("\u001b[2J\u001b[Hok")
        assertEquals("ok", t.text(0, 0, 0, 79).trimEnd())
    }

    /** a VT100 has no Tek mode */
    @Test
    fun vt100HasNone() {
        val t = term(TerminalProfiles.VT100)
        t.send("\u001b[?38h")
        assertFalse(t.altScreen)
    }
}
