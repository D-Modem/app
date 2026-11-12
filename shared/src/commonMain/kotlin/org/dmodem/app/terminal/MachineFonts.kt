// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * The terminal types' fonts: each machine's own character ROM
 * ([RomFonts]) read in its own set, so a cell's code point is drawn with
 * the glyph its machine drew for it. What a ROM hasn't got (a character of
 * another model's set) comes from Unscii's ([LegacyCharsets]).
 */
internal object MachineFonts {
    /** a PC's VGA: code page 437 in 8x16 */
    val VGA: BitmapFont by lazy { pc(RomFonts.VGA, 16) }

    /** RIPterm's 8x8 code page 437, for RIPscrip's 80x43 */
    val RIPTERM: BitmapFont by lazy { pc(RomFonts.RIPTERM, 8) }

    /** a Commodore 64's (the VIC-20's, Plus/4's and PET's too: nearly the same ROM), both its sets */
    val C64: BitmapFont by lazy { commodore(RomFonts.C64_UPPER, RomFonts.C64_LOWER) }

    /** a Commodore 128's, both its sets */
    val C128: BitmapFont by lazy { commodore(RomFonts.C128_UPPER, RomFonts.C128_LOWER) }

    /** an Atari 8-bit's (ATASCII's graphics set: the international set's own letters Unscii's) */
    val ATARI: BitmapFont by lazy {
        RomFonts.ATARI.font(8, LegacyCharsets.GLYPHS, 0..0x7f) { LegacyCharsets.ATARI_GRAPHICS[it] }
    }

    /** an Amiga's Topaz, in Latin-1 */
    val TOPAZ: BitmapFont by lazy { latin1(RomFonts.TOPAZ, 8) }

    /** an Atari ST's colour modes' 8x8, in the ST's set */
    val ATARI_ST: BitmapFont by lazy { RomFonts.ATARI_ST.font(8, LegacyCharsets.GLYPHS) { AtariStInterpreter.stChar(it) } }

    /** Prestel's teletext characters, 8x16, in teletext's English set */
    val PRESTEL: BitmapFont by lazy {
        RomFonts.PRESTEL.font(16, LegacyCharsets.VGA, 0x20..0x7f) { ViewdataInterpreter.G0[it - 0x20] }
    }

    /**
     * SyncTERM's font [n] (CSI SP D's) as a font [height] rows high, its
     * codes read as octets in [charset] (as SyncTERM does: the font's code
     * for each octet); null if it isn't here
     */
    fun syncterm(n: Int, height: Int, charset: TermCharset): BitmapFont? {
        val rom = RomFonts.syncterm[n] ?: return null
        val fallback = if (height == 16) LegacyCharsets.VGA else LegacyCharsets.GLYPHS
        return rom.font(height, fallback) { octet(charset, it) }
    }

    /** [rom] in code page 437, [height] rows */
    private fun pc(rom: RomFont, height: Int): BitmapFont =
        rom.font(height, if (height == 16) LegacyCharsets.VGA else LegacyCharsets.GLYPHS) { octet(TermCharset.CP437, it) }

    /** [rom] in Latin-1, [height] rows */
    private fun latin1(rom: RomFont, height: Int): BitmapFont =
        rom.font(height, LegacyCharsets.GLYPHS) { octet(TermCharset.LATIN1, it) }

    /** a Commodore's two ROM sets, upper case and graphics first */
    private fun commodore(upper: RomFont, lower: RomFont): BitmapFont = BitmapFont.of(
        8,
        upper.byCodePoint(8) { LegacyCharsets.C64_UPPER[it] },
        lower.byCodePoint(8) { LegacyCharsets.C64_LOWER[it] },
        LegacyCharsets.GLYPHS.glyphs(),
    )

    /** octet [b]'s code point in [charset] (0 for a control) */
    private fun octet(charset: TermCharset, b: Int): Int = when {
        b < 0x20 || b == 0x7f -> 0
        charset == TermCharset.CP437 -> Cp437.decode(b)
        b in 0x80..0x9f -> 0
        charset == TermCharset.LATIN1 -> b
        else -> if (b < 0x80) b else 0
    }
}
