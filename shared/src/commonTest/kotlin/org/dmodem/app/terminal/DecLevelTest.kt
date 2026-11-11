// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class DecLevelTest {

    private val replies = StringBuilder()
    private val printed = StringBuilder()
    private val keys = KeyEncoder()

    /** a terminal of [profile] whose replies and printer output are kept */
    private fun term(profile: TerminalProfile, cols: Int = 20, rows: Int = 4): TerminalEmulator {
        replies.setLength(0)
        printed.setLength(0)
        val t = TerminalEmulator(cols, rows, 100, profile) { b -> b.forEach { replies.append((it.toInt() and 0xff).toChar()) } }
        t.listener = object : TerminalEmulator.Listener {
            /** what the printer got */
            override fun onPrint(text: String) { printed.append(text) }
        }
        return t
    }

    /** [s] fed in as Latin-1 octets */
    private fun TerminalEmulator.send(s: String) = append(s.latin1())

    /** the VT52 profile: its cursor moves, direct addressing, its identity, its graphics */
    @Test
    fun vt52Profile() {
        val t = term(TerminalProfiles.VT52)
        t.send("\u001bY\"%X")
        assertEquals(2, t.cursorY)
        assertEquals(6, t.cursorX)
        t.send("\u001bA\u001bD\u001bH")
        assertEquals(0, t.cursorX)
        assertEquals(0, t.cursorY)
        t.send("\u001bZ")
        assertEquals("\u001b/K", replies.toString())
        t.send("\u001bFaf\u001bGa")
        assertEquals("█°a", t.line(0).text().substring(0, 3))
        assertTrue(t.vt52Keys)
        // (its keys: ESC and a letter, PF1 as ESC P)
        assertEquals("\u001bA", keys.key(Key.UP, t))
        assertEquals("\u001bP", keys.key(Key.PF1, t))
    }

    /** a VT100 put in VT52 mode (DECANM reset) speaks VT52 until ESC <, and identifies as a VT100 in it */
    @Test
    fun vt52ModeInAVt100() {
        val t = term(TerminalProfiles.VT100)
        t.send("\u001b[?2l\u001bZ")
        assertEquals("\u001b/Z", replies.toString())
        assertTrue(t.vt52Keys)
        t.send("\u001bY!!")
        assertEquals(1, t.cursorX)
        assertEquals(1, t.cursorY)
        t.send("\u001b<")
        assertFalse(t.vt52Keys)
        replies.setLength(0)
        t.send("\u001b[c")
        assertEquals("\u001b[?1;2c", replies.toString())
    }

    /** the UK set's # is a pound sign */
    @Test
    fun ukSet() {
        val t = term(TerminalProfiles.VT100)
        t.send("\u001b(A#\u001b(B#")
        assertEquals("£#", t.line(0).text())
    }

    /** DECDWL and DECDHL size the line and halve its width; DECSWL undoes it; erasing the line too */
    @Test
    fun lineSizes() {
        val t = term(TerminalProfiles.VT100)
        t.send("\u001b#6")
        assertEquals(LineSize.DOUBLE_WIDTH, t.line(0).size)
        t.send("\u001b[1;30H")
        assertEquals(9, t.cursorX)
        t.send("\u001b#3")
        assertEquals(LineSize.DOUBLE_TOP, t.line(0).size)
        t.send("\u001b#4")
        assertEquals(LineSize.DOUBLE_BOTTOM, t.line(0).size)
        t.send("\u001b#5")
        assertEquals(LineSize.SINGLE, t.line(0).size)
        t.send("\u001b#6\u001b[2J")
        assertEquals(LineSize.SINGLE, t.line(0).size)
    }

    /** DECCOLM asks for 132 or 80 columns and clears; xterm only once mode 40 allows it */
    @Test
    fun columnMode() {
        val t = term(TerminalProfiles.VT220)
        t.send("hello\u001b[?3h")
        assertEquals(132, t.requestedColumns)
        assertEquals("", t.line(0).text().trim())
        t.send("\u001b[?3l")
        assertEquals(80, t.requestedColumns)
        val x = term(TerminalProfiles.XTERM)
        x.send("\u001b[?3h")
        assertEquals(0, x.requestedColumns)
        x.send("\u001b[?40h\u001b[?3h")
        assertEquals(132, x.requestedColumns)
    }

    /** the keypad: its characters in numeric mode, SS3 and a letter in application mode */
    @Test
    fun keypadEncodings() {
        val t = term(TerminalProfiles.VT220)
        assertEquals("7", keys.key(Key.KP_7, t))
        assertEquals("-", keys.key(Key.KP_MINUS, t))
        assertEquals("\u001bOP", keys.key(Key.PF1, t))
        t.send("\u001b=")
        assertEquals("\u001bOw", keys.key(Key.KP_7, t))
        assertEquals("\u001bOm", keys.key(Key.KP_MINUS, t))
        assertEquals("\u001bOl", keys.key(Key.KP_COMMA, t))
        assertEquals("\u001bOn", keys.key(Key.KP_PERIOD, t))
        assertEquals("\u001bOM", keys.key(Key.KP_ENTER, t))
        val v = term(TerminalProfiles.VT52)
        v.send("\u001b=")
        assertEquals("\u001b?p", keys.key(Key.KP_0, v))
    }

    /** the printer port: controller mode's text to the printer and not the screen, auto print, print screen; a VT100 has none */
    @Test
    fun printerPort() {
        val t = term(TerminalProfiles.VT102)
        t.send("\u001b[5ihidden\u001b[4ishown")
        assertEquals("hidden", printed.toString())
        assertEquals("shown", t.line(0).text().trim())
        printed.setLength(0)
        t.send("\r\n\u001b[?5ia\r\n\u001b[?4ib\r\n")
        assertEquals("a\n", printed.toString())
        printed.setLength(0)
        t.send("\u001b[i")
        assertTrue(printed.startsWith("shown"))
        val v = term(TerminalProfiles.VT100)
        v.send("\u001b[5iseen")
        assertEquals("", printed.toString())
        assertEquals("seen", v.line(0).text().trim())
        val v52 = term(TerminalProfiles.VT52)
        v52.send("\u001bWjob\u001bXafter")
        assertEquals("job", printed.toString())
        assertEquals("after", v52.line(0).text().trim())
    }

    /** DECREQTPARM's report, DECLL's LEDs, DECID */
    @Test
    fun reportsAndLeds() {
        val t = term(TerminalProfiles.VT100)
        t.send("\u001b[1x")
        assertEquals("\u001b[3;1;1;128;128;1;0x", replies.toString())
        t.send("\u001b[1;3q")
        assertEquals(0b101, t.leds)
        t.send("\u001b[0q")
        assertEquals(0, t.leds)
        replies.setLength(0)
        t.send("\u001bZ")
        assertEquals("\u001b[?1;2c", replies.toString())
    }

    /** a DEC profile's key bar has the keypad page; the next page's label wraps */
    @Test
    fun keyBars() {
        assertEquals(KeyBars.DEC, TerminalProfiles.VT220.keyBar)
        assertEquals(KeyBars.STANDARD, TerminalProfiles.XTERM.keyBar.take(2))
        assertEquals("KP", KeyBars.nextLabel(KeyBars.DEC, 1))
        assertEquals("ABC", KeyBars.nextLabel(KeyBars.DEC, 2))
    }
}
