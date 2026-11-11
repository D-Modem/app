// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.Test

class Vt420Test {

    private val replies = StringBuilder()
    private val keys = KeyEncoder()

    /** a terminal of [profile] whose replies are kept */
    private fun term(profile: TerminalProfile = TerminalProfiles.VT420, cols: Int = 10, rows: Int = 4): TerminalEmulator {
        replies.setLength(0)
        return TerminalEmulator(cols, rows, 100, profile) { b -> b.forEach { replies.append((it.toInt() and 0xff).toChar()) } }
    }

    /** [s] fed in as Latin-1 octets */
    private fun TerminalEmulator.send(s: String) = append(s.latin1())

    /** the replies so far, then forgotten */
    private fun taken(): String = replies.toString().also { replies.setLength(0) }

    /** row [y]'s text, [n] cells of it (blanks as spaces) */
    private fun TerminalEmulator.row(y: Int, n: Int = cols): String = line(y).text().padEnd(n).substring(0, n)

    /** DECLRMM and DECSLRM: text wraps at the right margin to the left one, and scrolls only between them */
    @Test
    fun margins() {
        val t = term()
        t.send("0123456789\r\n0123456789\r\n0123456789\r\n0123456789")
        t.send("\u001b[?69h\u001b[3;6s")
        assertEquals(0, t.cursorX)
        // (CUP from home: column 3 is within the margins)
        t.send("\u001b[4;3HABCDEF")
        assertEquals("0123456789", t.row(0))
        assertEquals("01ABCD6789", t.row(2))
        assertEquals("01EF  6789", t.row(3))
        // CSI s is DECSLRM now; DECRQSS tells the margins
        t.send("\u001bP\$qs\u001b\\")
        assertEquals("\u001bP1\$r3;6s\u001b\\", taken())
        // reset: CSI s saves the cursor again
        t.send("\u001b[?69l\u001b[?69\$p")
        assertEquals("\u001b[?69;2\$y", taken())
    }

    /** DECCRA, DECERA, DECFRA, DECSERA and the checksum */
    @Test
    fun rectangles() {
        val t = term()
        t.send("abcdefghij\r\nklmnopqrst")
        // fill rows 3-4, columns 2-3 with X
        t.send("\u001b[88;3;2;4;3\$x")
        assertEquals(" XX       ", t.row(2))
        assertEquals(" XX       ", t.row(3))
        // copy rows 1-2, columns 1-3 to row 3, column 6
        t.send("\u001b[1;1;2;3;1;3;6;1\$v")
        assertEquals(" XX  abc  ", t.row(2))
        assertEquals(" XX  klm  ", t.row(3))
        // erase rows 1-2, columns 2-9
        t.send("\u001b[1;2;2;9\$z")
        assertEquals("a        j", t.row(0))
        // a protected cell kept by DECSERA, not by DECERA
        t.send("\u001b[1;1H\u001b[1\"qP\u001b[0\"q\u001b[1;1;1;10\${")
        assertEquals("P", t.row(0, 1))
        // the checksum of row 1, columns 1-2: P and a blank, protected P
        t.send("\u001b[7;1;1;1;1;2*y")
        val sum = (-('P'.code + 0x04 + ' '.code)) and 0xffff
        assertEquals("\u001bP7!~${sum.toString(16).uppercase().padStart(4, '0')}\u001b\\", taken())
    }

    /** DECCARA and DECRARA, in a stream or (DECSACE 2) a rectangle */
    @Test
    fun attributes() {
        val t = term()
        t.send("abcdefghij\r\nklmnopqrst")
        // bold from row 1 column 9 to row 2 column 2: a stream
        t.send("\u001b[1;9;2;2;1\$r")
        assertEquals(Style.BOLD, Style.attrs(t.line(0).styles[9]))
        assertEquals(Style.BOLD, Style.attrs(t.line(1).styles[0]))
        assertEquals(0, Style.attrs(t.line(1).styles[5]))
        // as a rectangle, inverse reversed over columns 1-2
        t.send("\u001b[2*x\u001b[1;1;2;2;7\$t")
        assertEquals(Style.INVERSE, Style.attrs(t.line(0).styles[0]))
        assertEquals(Style.BOLD or Style.INVERSE, Style.attrs(t.line(1).styles[0]))
        assertEquals(0, Style.attrs(t.line(0).styles[5]))
        t.send("\u001bP\$q*x\u001b\\")
        assertEquals("\u001bP1\$r2*x\u001b\\", taken())
    }

    /** DECIC and DECDC, DECBI and DECFI */
    @Test
    fun columns() {
        val t = term()
        t.send("0123456789\r\n0123456789")
        t.send("\u001b[1;3H\u001b[2'}")
        assertEquals("01  234567", t.row(0))
        assertEquals("01  234567", t.row(1))
        t.send("\u001b[3'~")
        assertEquals("0134567   ", t.row(0))
        // DECBI at the left edge: everything right a column; DECFI at the right edge: left a column
        t.send("\u001b[1;1H\u001b6")
        assertEquals(" 0134567  ", t.row(0))
        t.send("\u001b[1;10H\u001b9")
        assertEquals("0134567   ", t.row(0))
    }

    /** NP, PP, PPA, PPR and PPB: the pages kept apart, DECXCPR telling the page */
    @Test
    fun pages() {
        val t = term()
        t.send("one\u001b[U")
        assertEquals(1, t.page)
        assertEquals(0, t.cursorX)
        t.send("two\u001b[?6n")
        assertEquals("\u001b[?1;4;2R", taken())
        t.send("\u001b[1 P")
        assertEquals("one", t.row(0, 3))
        t.send("\u001b[1 Q")
        assertEquals("two", t.row(0, 3))
        t.send("\u001b[9 Q")
        assertEquals(Vt420Interpreter.PAGES - 1, t.page)
        t.send("\u001bc")
        assertEquals(0, t.page)
    }

    /** the lines and columns asked for, the keypad and backarrow modes, and the identities */
    @Test
    fun modesAndIdentity() {
        val t = term()
        t.send("\u001b[36*|")
        assertEquals(36, t.requestedRows)
        t.send("\u001b[132\$|")
        assertEquals(132, t.requestedColumns)
        t.send("\u001b[?66h")
        assertEquals(true, t.appKeypad)
        t.send("\u001b[?67h")
        assertContentEquals(byteArrayOf(8), t.keyOctets(keys, Key.BACKSPACE))
        t.send("\u001b[c\u001b[>c")
        assertEquals("\u001b[?64;1;2;6;7;8;9;15;18;21;28c\u001b[>41;10;0c", taken())
        val v = term(TerminalProfiles.VT520)
        v.send("\u001b[\"v\u001b[>c")
        assertEquals("\u001b[4;10;1;1;1\"w\u001b[>64;10;0c", taken())
        // a VT320 hasn't the VT420's
        val o = term(TerminalProfiles.VT320)
        o.send("\u001b[36*|")
        assertEquals(0, o.requestedRows)
    }

    /** a VT525's colours: DECAC, and the colour table reported (DECRQTSR) and restored (DECRSTS) */
    @Test
    fun colorTable() {
        val t = term(TerminalProfiles.VT525)
        t.send("\u001bP2\$p1;2;100;0;0/2;1;240;50;100\u001b\\")
        assertEquals(0xffff0000.toInt(), t.colorOverride(1))
        // (DEC's hue 240 is green)
        assertEquals(0xff00ff00.toInt(), t.colorOverride(2))
        t.send("\u001b[2\$u")
        val table = taken()
        assertEquals(true, table.startsWith("\u001bP2\$s0;2;"))
        assertEquals(true, table.contains("/1;2;100;0;0/2;2;0;100;0/"))
        t.send("\u001b[1;1;2,|")
        assertEquals(0xffff0000.toInt(), t.colorOverride(256))
        assertEquals(0xff00ff00.toInt(), t.colorOverride(257))
    }
}
