// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.Test

class RomFontsTest {
    /** [cp]'s glyph's rows in [f] */
    private fun rows(f: BitmapFont?, cp: Int): List<Int> = (0 until f!!.height).map { f.row(cp, it) }

    /** hex bytes as rows */
    private fun hex(s: String): List<Int> = s.chunked(2).map { it.toInt(16) }

    /** each machine's own glyph for a character it had */
    @Test
    fun machines() {
        // (the VGA's A, the C64's A and a, the Atari's heart, Topaz's A halved back to 8x8)
        assertEquals(hex("00001038" + "6cc6c6fe" + "c6c6c6c6" + "00000000"), rows(TerminalProfiles.ANSI_BBS.glyphs, 'A'.code))
        assertEquals(hex("183c667e66666600"), rows(TerminalProfiles.C64.glyphs, 'A'.code))
        assertEquals(hex("00003c063e663e00"), rows(TerminalProfiles.C64.glyphs, 'a'.code))
        assertEquals(hex("00367f7f3e1c0800"), rows(TerminalProfiles.ATARI.glyphs, LegacyCharsets.ATARI_GRAPHICS[0]))
        assertEquals(hex("3c66667e66666600"), rows(TerminalProfiles.AMIGA.glyphs, 'A'.code))
        // (code page 437's high half, the C64's graphics, Latin-1's letters, the ST's Hebrew, Prestel's ½: all there)
        for (b in 0x80..0xff) assertTrue(TerminalProfiles.ANSI_BBS.glyphs!!.has(Cp437.decode(b)))
        for (b in 0xa0..0xff) assertTrue(TerminalProfiles.C64.glyphs!!.has(LegacyCharsets.C64_UPPER[b]))
        for (b in 0xa0..0xff) assertTrue(TerminalProfiles.AMIGA.glyphs!!.has(b))
        assertTrue(TerminalProfiles.ATARI_ST.glyphs!!.has('א'.code))
        assertEquals(16, TerminalProfiles.VIEWDATA.glyphs!!.height)
        assertTrue(TerminalProfiles.VIEWDATA.glyphs!!.has('½'.code))
        assertEquals(8, TerminalProfiles.RIP.glyphs!!.height)
        // (a character another model had, from Unscii's: the Atari's international set's)
        assertTrue(TerminalProfiles.ATARI_INTL.glyphs!!.has(LegacyCharsets.ATARI_INTERNATIONAL[1]))
    }

    /** a ROM font asked for the other size: 8 rows doubled, 16 halved (both rows' pixels kept) */
    @Test
    fun sizes() {
        val f = RomFont(h8 = "81".repeat(8 * 256))
        assertEquals(List(16) { 0x81 }, f.glyph(0, 16).toList())
        val g = RomFont(h16 = ("01" + "80").repeat(8 * 256))
        assertEquals(List(8) { 0x81 }, g.glyph(5, 8).toList())
    }

    /** CSI 0 ; n SP D: SyncTERM's font n read as the session's octets, at the profile's height; its own number, RIS or a new profile back to the profile's */
    @Test
    fun selection() {
        val t = TerminalEmulator(80, 25, 100, TerminalProfiles.ANSI_BBS) {}
        t.decoder.charset = TermCharset.CP437
        val vga = t.glyphs
        t.appendText("\u001b[0;32 D")
        // (the C64's A, doubled to the VGA's 16 rows)
        assertEquals(hex("183c667e66666600").flatMap { listOf(it, it) }, rows(t.glyphs, 'A'.code))
        t.appendText("\u001b[0;0 D")
        assertSame(vga, t.glyphs)
        t.appendText("\u001b[0;5 D")
        assertSame(vga, t.glyphs)
        t.appendText("\u001b[0;42 D")
        assertEquals(16, t.glyphs!!.height)
        t.appendText("\u001bc")
        assertSame(vga, t.glyphs)
        t.appendText("\u001b[0;36 D")
        t.profile = TerminalProfiles.AMIGA
        assertSame(TerminalProfiles.AMIGA.glyphs, t.glyphs)
        assertNull(RomFonts.syncterm[1])
    }
}
