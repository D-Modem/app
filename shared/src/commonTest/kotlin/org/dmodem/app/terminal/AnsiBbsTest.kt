// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class AnsiBbsTest {

    private val keys = KeyEncoder()
    private val played = ArrayList<MusicNote>()

    /** an ANSI-BBS terminal whose music is kept */
    private fun term(): TerminalEmulator {
        val t = TerminalEmulator(80, 25, 100, TerminalProfiles.ANSI_BBS)
        t.listener = object : TerminalEmulator.Listener {
            /** the notes kept */
            override fun onMusic(notes: List<MusicNote>) { played.addAll(notes) }
        }
        return t
    }

    /** [s] fed in as Latin-1 octets */
    private fun TerminalEmulator.send(s: String) = append(s.latin1())

    /** a PC's screen: 80x25, a VGA's 8x16 glyphs for code page 437; given back on a switch */
    @Test
    fun thePcsScreen() {
        val t = term()
        assertEquals(80, t.requestedColumns)
        assertEquals(25, t.requestedRows)
        assertEquals(16, t.glyphs?.height)
        for (b in 0x20..0xff) if (b != 0x7f) assertTrue(LegacyCharsets.VGA.has(Cp437.decode(b)), "0x" + b.toString(16))
        // (the full block: every row full)
        assertEquals(0xff, LegacyCharsets.VGA.row(0x2588, 15))
        t.profile = TerminalProfiles.XTERM
        assertEquals(0, t.requestedColumns)
        assertNull(t.glyphs)
    }

    /** ANSI music: CSI | always, a bare CSI M when a PLAY string follows; DL otherwise */
    @Test
    fun music() {
        val t = term()
        t.send("\u001b[|T120L4O3A\u000e")
        assertEquals(1, played.size)
        assertTrue(abs(played[0].hz - 440.0) < 0.01)
        assertEquals(500, played[0].ms + played[0].restMs)
        played.clear()
        t.send("\u001b[MFCDE\u000eok")
        assertEquals(3, played.size)
        assertEquals("ok", t.line(0).text())
        // (a bare CSI M then other text: a line deleted, the text shown)
        t.send("\u001b[2;1Hsecond\u001b[1;1H\u001b[M\u001b[1;1Hx")
        assertEquals("xecond", t.line(0).text())
        // (CSI = 0 M: only CSI | is music)
        played.clear()
        t.send("\u001b[=0M\u001b[NC\u000e")
        assertTrue(played.isEmpty())
    }

    /** PLAY's commands: octaves, lengths, dots, sharps, pauses, note numbers, staccato */
    @Test
    fun playStrings() {
        val m = AnsiMusic()
        val n = m.parse("O4 C C# P8 L8 D. N1 MS T240 E")
        assertEquals(6, n.size)
        assertTrue(abs(n[0].hz - 523.25) < 0.01)
        assertTrue(abs(n[1].hz - 554.37) < 0.01)
        assertEquals(0.0, n[2].hz)
        assertEquals(250, n[2].restMs)
        assertEquals(375, n[3].ms + n[3].restMs)
        // (PLAY's octave 0, three below middle C's)
        assertTrue(abs(n[4].hz - 32.70) < 0.01)
        assertEquals(94, n[5].ms)
        assertEquals(240, m.tempo)
    }

    /** CSI ? 33 h: iCE colours; CSI * r: the line speed; both gone with the profile */
    @Test
    fun iceColoursAndSpeed() {
        val t = term()
        t.send("\u001b[?33h")
        assertTrue(t.iceColors)
        t.send("\u001b[?33l\u001b[0;4*r")
        assertFalse(t.iceColors)
        assertEquals(2400, t.emulatedBps)
        t.send("\u001b[0;0*r")
        assertEquals(0, t.emulatedBps)
        // (font selection taken, nothing shown)
        t.send("\u001b[0;5 Dx")
        assertEquals("x", t.line(0).text())
    }

    /** doorway mode: the keys as NUL and their scan codes, Alt and a letter its key's */
    @Test
    fun doorway() {
        val t = term()
        assertEquals("\u001b[A", t.keyOctets(keys, Key.UP).decodeToString())
        t.send("\u001b[=255h")
        assertContentEquals(byteArrayOf(0, 0x48), t.keyOctets(keys, Key.UP))
        assertContentEquals(byteArrayOf(0, 0x3b), t.keyOctets(keys, Key.F1))
        assertContentEquals(byteArrayOf(0, 0x10), t.charOctets(keys, 'q'.code, alt = true))
        assertEquals("q", t.charOctets(keys, 'q'.code).decodeToString())
        t.send("\u001b[=255l")
        assertEquals("\u001b[A", t.keyOctets(keys, Key.UP).decodeToString())
    }
}
