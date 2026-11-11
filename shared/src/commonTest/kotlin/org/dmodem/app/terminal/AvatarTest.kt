// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class AvatarTest {

    /** an 80x25 Avatar terminal in CP437 (the profile's, as the app sets it) */
    private fun term() = TerminalEmulator(80, 25, 100, TerminalProfiles.AVATAR) {}.also { it.decoder.charset = TermCharset.CP437 }

    /** [s] fed in as Latin-1 octets */
    private fun TerminalEmulator.send(s: String) = append(s.latin1())

    /** row [y]'s text, its trailing blanks gone */
    private fun TerminalEmulator.row(y: Int) = text(y, 0, y, cols - 1).trimEnd()

    /** ^Y repeats a character; ^V ^H moves (row, column, from 1); ^V ^G clears to the line's end */
    @Test
    fun repeatAndMove() {
        val t = term()
        t.send("\u0019-\u0005\u0016\u0008\u0002\u0003ab\u0016\u0005\u0016\u0007")
        assertEquals("-----", t.row(0))
        assertEquals("  a", t.row(1))
    }

    /** ^V ^A: a PC attribute (bright yellow on blue: 0x1e), DLE-quoted; ^L clears to cyan (3) */
    @Test
    fun attributes() {
        val t = term()
        t.send("\u0016\u0001\u001eX\u0016\u0001\u0010\u0094Y")
        val s = t.line(0).styles
        assertEquals(11, Style.fg(s[0]))
        assertEquals(4, Style.bg(s[0]))
        // (0x94: blink, red on blue)
        assertEquals(1, Style.fg(s[1]))
        assertTrue(Style.attrs(s[1]) and Style.BLINK != 0)
        t.send("\u000cZ")
        assertEquals("Z", t.row(0))
        assertEquals(6, Style.fg(t.line(0).styles[0]))
    }

    /** AVT/0+: ^V ^J scrolls an area up, ^V ^Y repeats a pattern, ^V ^N deletes a character, ^V ^I inserts */
    @Test
    fun avt0Plus() {
        val t = term()
        t.send("aaaa\r\nbbbb\r\ncccc")
        // the area rows 1-3, columns 2-3, up one
        t.send("\u0016\u000a\u0001\u0001\u0002\u0003\u0003")
        assertEquals("abba", t.row(0))
        assertEquals("bccb", t.row(1))
        assertEquals("c  c", t.row(2))
        t.send("\u0016\u0008\u0005\u0001\u0016\u0019\u0002xy\u0003")
        assertEquals("xyxyxy", t.row(4))
        t.send("\u0016\u0008\u0005\u0001\u0016\u000e")
        assertEquals("yxyxy", t.row(4))
        t.send("\u0016\u0009Q")
        assertEquals("Qyxyxy", t.row(4))
    }

    /** ANSI still works underneath */
    @Test
    fun ansiToo() {
        val t = term()
        t.send("\u001b[3;5Hhi")
        assertEquals("    hi", t.row(2))
    }
}
