// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class AtariStTest {

    private val replies = StringBuilder()

    /** a terminal of [profile] at its own size, whose replies are kept */
    private fun term(profile: TerminalProfile = TerminalProfiles.ATARI_ST): TerminalEmulator {
        replies.setLength(0)
        val m = profile as AtariStProfile
        return TerminalEmulator(m.columns, m.rows, 100, profile) { b -> b.forEach { replies.append((it.toInt() and 0xff).toChar()) } }
    }

    /** [s] fed in as Latin-1 octets */
    private fun TerminalEmulator.send(s: String) = append(s.latin1())

    /** row [y]'s text, its trailing blanks gone */
    private fun TerminalEmulator.row(y: Int) = text(y, 0, y, cols - 1).trimEnd()

    /** the VT52's own: ESC Y to a place, ESC K to the line's end, ESC Z answered as a VT52 */
    @Test
    fun vt52() {
        val t = term()
        t.send("\u001bY\"%hello\u001bD\u001bD\u001bK\u001bZ")
        assertEquals("     hel", t.row(2))
        assertEquals("\u001b/K", replies.toString())
    }

    /** ESC b and ESC c: the pens (medium resolution's four: 3 black, 1 red); ESC p and q reverse */
    @Test
    fun colours() {
        val t = term()
        t.send("A\u001bb1B\u001bc2\u001bb\u0003C\u001bpD\u001bqE")
        val s = t.line(0).styles
        assertEquals(3, Style.fg(s[0]))
        assertEquals(1, Style.fg(s[1]))
        assertEquals(3, Style.fg(s[2]))
        assertEquals(2, Style.bg(s[2]))
        assertTrue(Style.attrs(s[3]) and Style.INVERSE != 0)
        assertEquals(0, Style.attrs(s[4]) and Style.INVERSE)
        // (pen 5 on four colours is pen 1)
        t.send("\u001bb5F")
        assertEquals(1, Style.fg(t.line(0).styles[5]))
        // low resolution has sixteen
        val low = term(TerminalProfiles.ATARI_ST_LOW)
        low.send("\u001bb\u000bX")
        assertEquals(11, Style.fg(low.line(0).styles[0]))
        assertEquals(40, low.cols)
    }

    /** ESC E clears, ESC l erases the line, ESC o its start, ESC d the screen above, ESC L and M insert and delete lines */
    @Test
    fun erases() {
        val t = term()
        t.send("one\r\ntwo\r\nthree\u001bo")
        assertEquals("", t.row(2).trim())
        t.send("\u001bA\u001bl")
        assertEquals("", t.row(1))
        assertEquals("one", t.row(0))
        t.send("\u001bH\u001bL")
        assertEquals("", t.row(0))
        assertEquals("one", t.row(1))
        t.send("\u001bM")
        assertEquals("one", t.row(0))
        t.send("\u001bE")
        assertEquals("", t.row(0))
        assertEquals(0, t.cursorX)
    }

    /** ESC j and k save and restore the place; ESC f and e hide and show the cursor; ESC w stops wrapping */
    @Test
    fun cursorAndWrap() {
        val t = term()
        t.send("\u001bY%*\u001bj\u001bH\u001bk")
        assertEquals(10, t.cursorX)
        assertEquals(5, t.cursorY)
        t.send("\u001bf")
        assertFalse(t.cursorVisible)
        t.send("\u001be")
        assertTrue(t.cursorVisible)
        t.send("\u001bH\u001bw" + "x".repeat(85))
        assertEquals("", t.row(1))
    }

    /** the ST's set above 0x7f: ß, Hebrew, Greek */
    @Test
    fun characters() {
        val t = term()
        t.send("\u009eÂà")
        assertEquals("ßאα", t.row(0))
    }

    /** the screen white (opaque white an override like any other colour, not taken for none) */
    @Test
    fun whiteBackground() {
        val t = term()
        assertEquals(0xffffffff.toInt(), t.colorOverride(257))
        assertEquals(0xffffffff.toInt(), t.colorOverride(0))
        assertEquals(0xff000000.toInt(), t.colorOverride(256))
    }
}
