// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {

    private val replies = StringBuilder()
    private fun term(cols: Int = 10, rows: Int = 4, history: Int = 100) =
        TerminalEmulator(cols, rows, history) { replies.append(String(it, Charsets.ISO_8859_1)) }

    private fun TerminalEmulator.row(r: Int) = line(r).text()
    private fun TerminalEmulator.send(s: String) = append(s.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun textWrapsAndScrollsIntoTheHistory() {
        val t = term(5, 2)
        t.send("hello world!")
        // "hello" wraps, " worl" wraps, "d!" on the last line: "hello" in the history
        assertEquals("hello", t.row(-1))
        assertEquals(" worl", t.row(0))
        assertEquals("d!", t.row(1))
        assertTrue(t.line(0).wrapped)
        assertEquals(1, t.historySize)
        assertEquals("hello worl", t.text(-1, 0, 0, 4))
    }

    @Test
    fun theLastColumnWaitsBeforeWrapping() {
        val t = term(5, 3)
        t.send("abcde")
        assertEquals(4, t.cursorX)
        assertEquals(0, t.cursorY)
        t.send("\r\n")
        assertEquals(1, t.cursorY)
        assertEquals("", t.row(1))
    }

    @Test
    fun cursorMovesAndErases() {
        val t = term(10, 4)
        t.send("0123456789\u001b[2;3HXY\u001b[1;5H\u001b[K")
        assertEquals("0123", t.row(0))
        assertEquals("  XY", t.row(1))
        t.send("\u001b[2;1H\u001b[1P")
        assertEquals(" XY", t.row(1))
        t.send("\u001b[2;1H\u001b[2@")
        assertEquals("   XY", t.row(1))
        t.send("\u001b[2;4H\u001b[1X")
        assertEquals("    Y", t.row(1))
        t.send("\u001b[2J")
        assertEquals("", t.row(0))
        assertEquals(0, t.cursorX)
        assertEquals(0, t.cursorY)
    }

    @Test
    fun sgrColoursAndAttributes() {
        val t = term()
        t.send("\u001b[1;31;44mA\u001b[0m\u001b[38;5;200;48;2;1;2;3mB\u001b[38:2::10:20:30mC\u001b[7;4mD\u001b[22;27mE")
        val l = t.line(0)
        assertEquals(1, Style.fg(l.styles[0]))
        assertEquals(4, Style.bg(l.styles[0]))
        assertEquals(Style.BOLD, Style.attrs(l.styles[0]))
        assertEquals(200, Style.fg(l.styles[1]))
        assertEquals(Style.RGB or 0x010203, Style.bg(l.styles[1]))
        assertEquals(Style.RGB or 0x0a141e, Style.fg(l.styles[2]))
        assertEquals(Style.INVERSE or Style.UNDERLINE, Style.attrs(l.styles[3]) and (Style.INVERSE or Style.UNDERLINE))
        assertEquals(Style.UNDERLINE, Style.attrs(l.styles[4]))
        t.send("\u001b[mF")
        assertEquals(Style.NORMAL, l.styles[5])
        t.send("\u001b[95;102mG")
        assertEquals(13, Style.fg(l.styles[6]))
        assertEquals(10, Style.bg(l.styles[6]))
    }

    @Test
    fun scrollingRegionKeepsTheRestAndTheHistory() {
        val t = term(5, 4)
        t.send("a\r\nb\r\nc\r\nd")
        t.send("\u001b[2;3r")       // rows 2..3, the cursor home
        t.send("\u001b[3;1H\nX")     // a line feed at the region's bottom
        assertEquals("a", t.row(0))
        assertEquals("c", t.row(1))
        assertEquals("X", t.row(2))
        assertEquals("d", t.row(3))
        assertEquals(0, t.historySize)
        t.send("\u001b[2;1H\u001b[L")
        assertEquals("", t.row(1))
        assertEquals("c", t.row(2))
        t.send("\u001b[M")
        assertEquals("c", t.row(1))
    }

    @Test
    fun theAlternateScreenComesAndGoes() {
        val t = term(5, 3)
        t.send("main")
        t.send("\u001b[?1049h")
        assertTrue(t.altScreen)
        assertEquals("", t.row(0))
        t.send("alt")
        t.send("\u001b[?1049l")
        assertFalse(t.altScreen)
        assertEquals("main", t.row(0))
        assertEquals(4, t.cursorX)
    }

    @Test
    fun answersForTheRemote() {
        val t = term(10, 5)
        t.send("\u001b[3;4H\u001b[6n\u001b[c\u001b[5n")
        assertEquals("\u001b[3;4R\u001b[?62;4;22c\u001b[0n", replies.toString())
        replies.setLength(0)
        t.answerback = "DMODEM"
        t.send("\u0005")
        assertEquals("DMODEM", replies.toString())
    }

    @Test
    fun ansiSysSaveAndRestore() {
        val t = term()
        t.send("ab\u001b[s\u001b[3;5Hx\u001b[uc")
        assertEquals("abc", t.row(0))
    }

    @Test
    fun decLineDrawing() {
        val t = term()
        t.send("\u001b(0lqk\u001b(Bx")
        assertEquals("┌─┐x", t.row(0))
        t.send("\r\n\u001b)0\u000eq\u000fq")
        assertEquals("─q", t.row(1))
    }

    @Test
    fun cp437AndUtf8() {
        val t = term(20, 2)
        t.decoder.charset = TermCharset.CP437
        t.append(byteArrayOf(0xc9.toByte(), 0xcd.toByte(), 0xbb.toByte(), 0xb0.toByte(), 0xdb.toByte(), 0x81.toByte()))
        assertEquals("╔═╗░█ü", t.row(0))
        t.decoder.charset = TermCharset.UTF8
        t.send("\r\n")
        val euro = "€é".toByteArray(Charsets.UTF_8)
        // split in the middle of the euro sign: two reads
        t.append(euro.copyOfRange(0, 2))
        t.append(euro.copyOfRange(2, euro.size))
        assertEquals("€é", t.row(1))
    }

    @Test
    fun cp437TableIsWhole() {
        for (b in 0x80..0xff) assertEquals(b, Cp437.encode(Cp437.decode(b)))
        assertEquals("╔".let { byteArrayOf(0xc9.toByte()).toList() }, TermCharset.CP437.encode("╔").toList())
    }

    @Test
    fun oscSetsTheTitleAndControlStringsAreSkipped() {
        val t = term()
        var title: String? = null
        t.listener = object : TerminalEmulator.Listener {
            override fun onTitle(title2: String) { title = title2 }
        }
        t.send("\u001b]0;BBS Main\u0007\u001bPq#0;1;2\u001b\\ok")
        assertEquals("BBS Main", title)
        assertEquals("ok", t.row(0))
    }

    @Test
    fun resizeKeepsTheCursorsLine() {
        val t = term(10, 4)
        t.send("1\r\n2\r\n3\r\n4")
        t.resize(10, 2)
        assertEquals("3", t.row(0))
        assertEquals("4", t.row(1))
        assertEquals(1, t.cursorY)
        assertEquals("2", t.row(-1))
        t.resize(10, 4)
        assertEquals("1", t.row(0))
        assertEquals("4", t.row(3))
        assertEquals(3, t.cursorY)
        t.resize(4, 4)
        t.send("\r56789")
        // (4 columns: the 9 wraps to a new line)
        assertEquals("5678", t.row(2))
        assertEquals("9", t.row(3))
    }

    @Test
    fun tabsAndBackspace() {
        val t = term(20, 2)
        // a at 0, tab to 8, b there, BS back onto it, c over it; back tab from 9 to the stop at 8
        t.send("a\tb\u0008c\u001b[Zd")
        assertEquals("a       d", t.row(0))
    }

    @Test
    fun repeatAndInsertMode() {
        val t = term()
        t.send("x\u001b[3b")
        assertEquals("xxxx", t.row(0))
        t.send("\r\u001b[4hY\u001b[4l")
        assertEquals("Yxxxx", t.row(0))
    }

    @Test
    fun keys() {
        val k = KeyEncoder()
        assertEquals("\u001b[A", k.key(Key.UP, appCursor = false))
        assertEquals("\u001bOA", k.key(Key.UP, appCursor = true))
        assertEquals("\u001b[1;5C", k.key(Key.RIGHT, appCursor = true, ctrl = true))
        assertEquals("\u001b[5~", k.key(Key.PAGE_UP, false))
        assertEquals("\u001bOP", k.key(Key.F1, false))
        assertEquals("\u001b[24~", k.key(Key.F12, false))
        assertEquals("\u001b[Z", k.key(Key.TAB, false, shift = true))
        assertEquals("\u007f", k.key(Key.BACKSPACE, false))
        k.backspace = BackspaceMode.BS
        assertEquals("\u0008", k.key(Key.BACKSPACE, false))
        k.enter = EnterMode.CRLF
        assertEquals("\r\n", k.key(Key.ENTER, false))
        assertEquals("\u0003", k.char('c'.code, ctrl = true))
        assertEquals("\u0003", k.char('C'.code, ctrl = true))
        assertEquals("\u0000", k.char(' '.code, ctrl = true))
        assertEquals("\u001bx", k.char('x'.code, alt = true))
        assertEquals("\u001b\u0018", k.char('x'.code, alt = true, ctrl = true))
    }

    @Test
    fun links() {
        val s = "see https://example.com/a_(b). or www.bbs.org, ok"
        assertEquals("https://example.com/a_(b)", UrlFinder.at(s, 6))
        assertEquals("http://www.bbs.org", UrlFinder.at(s, 34))
        assertNull(UrlFinder.at(s, 1))
        assertEquals(listOf("https://example.com/a_(b)", "www.bbs.org"), UrlFinder.all(s))
    }
}
