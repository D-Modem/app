// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.Test

class VideotexTest {

    private val replies = StringBuilder()
    private val keys = KeyEncoder()

    /** a terminal of [profile] at its own size, whose replies are kept */
    private fun term(profile: TerminalProfile, cols: Int = 40, rows: Int = 25): TerminalEmulator {
        replies.setLength(0)
        return TerminalEmulator(cols, rows, 100, profile) { b -> b.forEach { replies.append((it.toInt() and 0xff).toChar()) } }
    }

    /** [s] fed in as Latin-1 octets */
    private fun TerminalEmulator.send(s: String) = append(s.latin1())

    /** the mosaics: six bits each, contiguous and separated, drawn 6x9 */
    @Test
    fun mosaics() {
        assertEquals(' '.code, Mosaic.code(0, false))
        assertEquals(0x3f, Mosaic.bits(0x7f))
        assertEquals(0x01, Mosaic.bits(0x21))
        val all = Mosaic.code(0x3f, false)
        assertEquals(0b111111, Mosaic.font.row(all, 0))
        val sep = Mosaic.code(0x3f, true)
        assertEquals(0b110110, Mosaic.font.row(sep, 0))
        assertEquals(0, Mosaic.font.row(sep, 2))
    }

    /** Viewdata: 40x24, attributes taking a cell and changing the rest of the row, written in again when one changes */
    @Test
    fun viewdataAttributes() {
        val t = term(TerminalProfiles.VIEWDATA, rows = 24)
        assertEquals(40, t.requestedColumns)
        assertEquals(24, t.requestedRows)
        // red text, then yellow mosaics: a full block (0x7f) and a top left one (0x21)
        t.send("\u001bAHi\u001bS\u007f!")
        val l = t.line(0)
        assertEquals(' '.code, l.chars[0])
        assertEquals("Hi", l.text(1, 3))
        assertEquals(1, Style.fg(l.styles[1]))
        assertEquals(Mosaic.code(0x3f, false), l.chars[4])
        assertEquals(Mosaic.code(0x01, false), l.chars[5])
        assertEquals(3, Style.fg(l.styles[4]))
        assertNotNull(t.glyphFor(l.chars[4]))
        // the attribute at column 0 changed to green: the whole row follows
        t.send("\u001e\u001bB")
        assertEquals(2, Style.fg(t.line(0).styles[1]))
        // new background (0x1d) takes the colour; teletext's # and £
        t.send("\r\n\u001bD\u001b]#_")
        val m = t.line(1)
        assertEquals(4, Style.bg(m.styles[2]))
        assertEquals("£#", m.text(2, 4))
        // separated mosaics, and the cursor wrapping round from the last row to the first
        t.send("\u000c\u001bQ\u001bZ\u007f")
        assertEquals(Mosaic.code(0x3f, true), t.line(0).chars[2])
        t.send("\u000b")
        assertEquals(23, t.cursorY)
        // Enter and # send Prestel's #
        assertContentEquals(byteArrayOf(0x5f), t.keyOctets(keys, Key.ENTER))
        assertContentEquals(byteArrayOf(0x5f), t.charOctets(keys, '#'.code))
    }

    /** the Minitel: row 0 its status row, US addressing, colours, the zone's background at a delimiter, SS2's accents, mosaics */
    @Test
    fun minitelVideotex() {
        val t = term(TerminalProfiles.MINITEL)
        assertEquals(25, t.requestedRows)
        assertEquals(1, t.cursorY)
        // row 5 column 3, red on a blue zone: the background from the space on
        t.send("\u001fEC\u001bA\u001bTa b")
        val l = t.line(5)
        assertEquals("a b", l.text(2, 5))
        assertEquals(1, Style.fg(l.styles[2]))
        assertEquals(0, Style.bg(l.styles[2]))
        assertEquals(4, Style.bg(l.styles[3]))
        // SS2 accents, a G2 symbol, REP
        t.send("\u001fFA\u0019Be\u0019Aa\u0019Kc\u0019#x\u0012B")
        assertEquals("éàç£xxx", t.line(6).text(0, 7))
        // mosaics by SO, separated by ESC Z from the next one on
        t.send("\u001fGA\u000e\u007f\u001bZ\u007f\u000fA")
        val g = t.line(7)
        assertEquals(Mosaic.code(0x3f, false), g.chars[0])
        assertEquals(Mosaic.code(0x3f, true), g.chars[1])
        assertEquals('A'.code, g.chars[2])
        // FF clears and goes to row 1; the cursor wraps from the last row to row 1
        t.send("\u000c")
        assertEquals(1, t.cursorY)
        t.send("\u000b")
        assertEquals(24, t.cursorY)
        t.send("\n")
        assertEquals(1, t.cursorY)
    }

    /** the Minitel's protocol: who it is, where the cursor is, CSI, its keys, and the Téléinformatique mode (a VT100) */
    @Test
    fun minitelProtocol() {
        val t = term(TerminalProfiles.MINITEL)
        t.send("\u001b9{")
        assertEquals("\u0001Cv;\u0004", replies.toString())
        replies.setLength(0)
        t.send("\u001b[3;5H\u001ba")
        assertEquals("\u001fCE", replies.toString())
        assertContentEquals(byteArrayOf(0x13, 0x41), t.keyOctets(keys, Key.ENTER))
        assertContentEquals(byteArrayOf(0x19, 0x42, 'e'.code.toByte()), t.charOctets(keys, 'é'.code))
        // Téléinformatique: 80x24, a VT100's sequences, until ESC [ ? {
        t.send("\u001b:1}")
        assertEquals(80, t.requestedColumns)
        t.send("\u001b[2;3HZ")
        assertEquals('Z'.code, t.line(1).chars[2])
        t.send("\u001b[?{")
        assertEquals(40, t.requestedColumns)
        assertEquals(1, t.cursorY)
    }
}
