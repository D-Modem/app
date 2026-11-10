// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.Test

class TerminalProfileTest {

    private val replies = StringBuilder()
    /** a terminal of [profile] whose replies go to [replies] */
    private fun term(profile: TerminalProfile, cols: Int = 10, rows: Int = 4) =
        TerminalEmulator(cols, rows, 100, profile) { b -> b.forEach { replies.append((it.toInt() and 0xff).toChar()) } }

    /** [s] fed in as Latin-1 octets */
    private fun TerminalEmulator.send(s: String) = append(s.latin1())

    /** what [s] makes [profile] answer */
    private fun answer(profile: TerminalProfile, s: String): String {
        replies.setLength(0)
        term(profile).send(s)
        return replies.toString()
    }

    /** each DEC level answers DA1 as its terminal did; DA2 only from the VT220 on */
    @Test
    fun eachProfileAnswersAsItsTerminal() {
        assertEquals("\u001b[?1;2c", answer(TerminalProfiles.VT100, "\u001b[c"))
        assertEquals("\u001b[?6c", answer(TerminalProfiles.VT102, "\u001b[0c"))
        assertEquals("\u001b[?62;1;2;6;7;8;9c", answer(TerminalProfiles.VT220, "\u001b[c"))
        assertEquals("\u001b[?62;4;22c", answer(TerminalProfiles.XTERM, "\u001b[c"))
        assertEquals("", answer(TerminalProfiles.VT100, "\u001b[>c"))
        assertEquals("\u001b[>1;10;0c", answer(TerminalProfiles.VT220, "\u001b[>c"))
        assertEquals("\u001b[>41;390;0c", answer(TerminalProfiles.ANSI_BBS, "\u001b[>c"))
        // XTVERSION is xterm's
        assertEquals("", answer(TerminalProfiles.VT220, "\u001b[>0q"))
        assertTrue(answer(TerminalProfiles.XTERM, "\u001b[>0q").startsWith("\u001bP>|"))
    }

    /** a VT100 has no colours: SGR's colours leave the cell in the default ones, its attributes still apply */
    @Test
    fun aVt100IgnoresColours() {
        val t = term(TerminalProfiles.VT100)
        t.send("\u001b[1;31;44mA")
        val st = t.line(0).styles[0]
        assertEquals(Style.DEFAULT_FG, Style.fg(st))
        assertEquals(Style.DEFAULT_BG, Style.bg(st))
        assertTrue(Style.attrs(st) and Style.BOLD != 0)
        val x = term(TerminalProfiles.XTERM)
        x.send("\u001b[31mA")
        assertEquals(1, Style.fg(x.line(0).styles[0]))
    }

    /** xterm's extensions (titles, the alternate screen, mouse reports) are only an xterm's */
    @Test
    fun xtermExtensionsOnlyInXterm() {
        var title: String? = null
        val listener = object : TerminalEmulator.Listener {
            /** the title kept */
            override fun onTitle(t: String) { title = t }
        }
        val v = term(TerminalProfiles.VT220)
        v.listener = listener
        v.send("\u001b]2;hello\u0007\u001b[?1049h\u001b[?1000h")
        assertNull(title)
        assertFalse(v.altScreen)
        assertEquals(TerminalEmulator.MouseMode.NONE, v.mouseMode)
        val x = term(TerminalProfiles.XTERM)
        x.listener = listener
        x.send("\u001b]2;hello\u0007\u001b[?1049h\u001b[?1000h")
        assertEquals("hello", title)
        assertTrue(x.altScreen)
        assertEquals(TerminalEmulator.MouseMode.NORMAL, x.mouseMode)
    }

    /** a new profile keeps what is on the screen and starts with its own modes */
    @Test
    fun switchingProfilesKeepsTheScreen() {
        val t = term(TerminalProfiles.XTERM)
        t.send("hi\u001b[?1h\u001b(0")
        assertTrue(t.appCursorKeys)
        t.profile = TerminalProfiles.VT100
        assertSame(TerminalProfiles.VT100, t.profile)
        assertEquals("hi", t.line(0).text())
        assertFalse(t.appCursorKeys)
        // the graphics set gone with the old interpreter: q is a q again
        t.send("q")
        assertEquals("hiq", t.line(0).text())
        assertFalse(t.homeOnClear)
        replies.setLength(0)
        t.send("\u001b[c")
        assertEquals("\u001b[?1;2c", replies.toString())
    }

    /** ESC[2J homes the cursor for the BBS profiles (as ANSI.SYS did), not for a DEC terminal */
    @Test
    fun clearHomesOnlyWhereAnsiSysDid() {
        val bbs = term(TerminalProfiles.ANSI_BBS)
        bbs.send("\u001b[3;4H\u001b[2J")
        assertEquals(0, bbs.cursorX)
        assertEquals(0, bbs.cursorY)
        val vt = term(TerminalProfiles.VT220)
        vt.send("\u001b[3;4H\u001b[2J")
        assertEquals(3, vt.cursorX)
        assertEquals(2, vt.cursorY)
    }

    /** DECSC saves the character sets with the cursor, through the interpreter */
    @Test
    fun theCursorsSaveKeepsTheCharacterSets() {
        val t = term(TerminalProfiles.VT220)
        // (saved in graphics, back to ASCII, then restored: graphics again)
        t.send("\u001b(0\u001b7\u001b(B\u001b8q")
        assertEquals("─", t.line(0).text())
    }

    /** the profiles' names for SSH and Telnet, and their lookup by id */
    @Test
    fun namesAndIds() {
        assertEquals("vt220", TerminalProfiles.VT220.term)
        assertEquals("DEC-VT220", TerminalProfiles.VT220.telnetType)
        assertEquals("ANSI", TerminalProfiles.ANSI_BBS.telnetType)
        assertEquals(TermCharset.CP437, TerminalProfiles.ANSI_BBS.charset)
        assertNull(TerminalProfiles.XTERM.charset)
        for (p in TerminalProfiles.all) assertSame(p, TerminalProfiles.byId(p.id))
        assertNull(TerminalProfiles.byId("nope"))
        assertEquals(TerminalProfiles.all.size, TerminalProfiles.all.map { it.id }.toSet().size)
    }
}
