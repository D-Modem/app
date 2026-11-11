// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class PetsciiTest {

    private val keys = KeyEncoder()

    /** a terminal of [profile], 40x25 as the view would fit it */
    private fun term(profile: TerminalProfile = TerminalProfiles.C64, cols: Int = 40, rows: Int = 25) =
        TerminalEmulator(cols, rows, 100, profile)

    /** octets fed in as they are */
    private fun TerminalEmulator.send(vararg b: Int) = append(ByteArray(b.size) { b[it].toByte() })

    /** [s]'s characters fed in as octets */
    private fun TerminalEmulator.send(s: String) = append(s.latin1())

    /** the octets [b] as ints */
    private fun ints(b: ByteArray) = b.map { it.toInt() and 0xff }

    /** the C64 asks for its screen and palette, and a VT profile after it gets the view's back */
    @Test
    fun theMachinesScreen() {
        val t = term(TerminalProfiles.XTERM)
        t.profile = TerminalProfiles.C64
        assertEquals(40, t.requestedColumns)
        assertEquals(25, t.requestedRows)
        assertTrue(t.readsOctets)
        assertEquals(CommodorePalettes.PEPTO[2], t.colorOverride(2))
        assertEquals(CommodorePalettes.PEPTO[0], t.colorOverride(257))
        assertTrue(t.glyphs != null)
        t.profile = TerminalProfiles.XTERM
        assertEquals(0, t.requestedColumns)
        assertEquals(0, t.requestedRows)
        assertEquals(-1, t.colorOverride(2))
        assertNull(t.glyphs)
        assertEquals(22, term(TerminalProfiles.VIC20).requestedColumns)
        assertEquals(80, term(TerminalProfiles.C128_80).requestedColumns)
    }

    /** octets aren't decoded in the user's set: 0xC1 is the graphics set's spade, the other set's capital A */
    @Test
    fun theTwoSets() {
        val t = term()
        t.decoder.charset = TermCharset.UTF8
        t.send(0x41, 0xc1, 0x5c)
        assertEquals("A♠£", t.line(0).text())
        t.send(0x0e, 0x41, 0xc1, 0x8e, 0x41)
        assertEquals("A♠£aAA", t.line(0).text())
    }

    /** the colour codes, reverse on and off, RETURN ending reverse */
    @Test
    fun coloursAndReverse() {
        val t = term()
        t.send(0x1c, 'A'.code, 0x12, 'B'.code, 0x9a, 0x0d, 'C'.code)
        val l = t.line(0)
        assertEquals(2, Style.fg(l.styles[0]))
        assertEquals(0, Style.attrs(l.styles[0]) and Style.INVERSE)
        assertTrue(Style.attrs(l.styles[1]) and Style.INVERSE != 0)
        val c = t.line(1).styles[0]
        assertEquals(14, Style.fg(c))
        assertEquals(0, Style.attrs(c) and Style.INVERSE)
        // (a VIC-20 has no C= colours, a PET none at all)
        val v = term(TerminalProfiles.VIC20)
        v.send(0x81, 'A'.code)
        assertEquals(1, Style.fg(v.line(0).styles[0]))
        val p = term(TerminalProfiles.PET)
        p.send(0x1c, 'A'.code)
        assertEquals(1, Style.fg(p.line(0).styles[0]))
    }

    /** the cursor keys wrap round the line ends; HOME and CLR; the last column wraps at once */
    @Test
    fun cursorAndWrap() {
        val t = term(cols = 40, rows = 3)
        t.send(0x9d)
        assertEquals(0, t.cursorX)
        t.send(0x11, 0x9d)
        assertEquals(39, t.cursorX)
        assertEquals(0, t.cursorY)
        t.send(0x1d)
        assertEquals(0, t.cursorX)
        assertEquals(1, t.cursorY)
        t.send(0x91, 0x91)
        assertEquals(0, t.cursorY)
        t.send(0x13)
        t.send("x".repeat(40))
        assertEquals(0, t.cursorX)
        assertEquals(1, t.cursorY)
        // (down at the bottom scrolls)
        t.send(0x11, 0x11)
        assertEquals(2, t.cursorY)
        assertEquals("", t.line(0).text().trim())
        t.send(0x93)
        assertEquals(0, t.cursorX)
        assertEquals(0, t.cursorY)
    }

    /** DEL takes the character before the cursor, INST opens a space */
    @Test
    fun deleteAndInsert() {
        val t = term()
        t.send("ABC")
        t.send(0x9d, 0x14)
        assertEquals("AC", t.line(0).text())
        assertEquals(1, t.cursorX)
        t.send(0x94)
        assertEquals("A C", t.line(0).text())
    }

    /** the C128's ESC sequences, its line feed and the VDC's underline */
    @Test
    fun c128() {
        val t = term(TerminalProfiles.C128)
        t.send("HELLO")
        t.send(0x9d, 0x9d, 0x1b, 'Q'.code)
        assertEquals("HEL", t.line(0).text())
        t.send(0x0a)
        assertEquals(1, t.cursorY)
        assertEquals(3, t.cursorX)
        val c64 = term()
        c64.send(0x0a)
        assertEquals(0, c64.cursorY)
        val v = term(TerminalProfiles.C128_80, cols = 80)
        v.send(0x02, 'U'.code, 0x82, 'N'.code)
        assertTrue(Style.attrs(v.line(0).styles[0]) and Style.UNDERLINE != 0)
        assertEquals(0, Style.attrs(v.line(0).styles[1]) and Style.UNDERLINE)
    }

    /** the keys a C64 sends: the cursor keys, the function keys, RETURN, DEL */
    @Test
    fun keys() {
        val t = term()
        assertEquals(listOf(0x91), ints(t.keyOctets(keys, Key.UP)))
        assertEquals(listOf(0x1d), ints(t.keyOctets(keys, Key.RIGHT)))
        assertEquals(listOf(0x89), ints(t.keyOctets(keys, Key.F2)))
        assertEquals(listOf(0x0d), ints(t.keyOctets(keys, Key.ENTER)))
        assertEquals(listOf(0x14), ints(t.keyOctets(keys, Key.BACKSPACE)))
        assertEquals(listOf(0x93), ints(t.keyOctets(keys, Key.HOME, shift = true)))
        assertTrue(t.keyOctets(keys, Key.PAGE_UP).isEmpty())
    }

    /** typing: letters by the set, CTRL and C= with digits the colours, C= with a letter its graphic, a pasted graphic its code */
    @Test
    fun typing() {
        val t = term()
        assertEquals(listOf(0x41), ints(t.charOctets(keys, 'a'.code)))
        assertEquals(listOf(0x41), ints(t.charOctets(keys, 'A'.code)))
        assertEquals(listOf(0x1c), ints(t.charOctets(keys, '3'.code, ctrl = true)))
        assertEquals(listOf(0x81), ints(t.charOctets(keys, '1'.code, alt = true)))
        assertEquals(listOf(0xb0), ints(t.charOctets(keys, 'a'.code, alt = true)))
        assertEquals(listOf(0x03), ints(t.charOctets(keys, 'c'.code, ctrl = true)))
        assertEquals(listOf(0xc1), ints(t.charOctets(keys, '♠'.code)))
        assertEquals(listOf(0x5c), ints(t.charOctets(keys, '£'.code)))
        assertEquals(listOf(0x3f), ints(t.charOctets(keys, '{'.code)))
        t.send(0x0e)
        assertEquals(listOf(0x41), ints(t.charOctets(keys, 'a'.code)))
        assertEquals(listOf(0xc1), ints(t.charOctets(keys, 'A'.code)))
        // (an xterm's are as they were)
        val x = term(TerminalProfiles.XTERM)
        assertEquals("\u001b[A", x.keyOctets(keys, Key.UP).decodeToString())
        assertEquals("a", x.charOctets(keys, 'a'.code).decodeToString())
        assertFalse(x.readsOctets)
    }

    /** every character of every Commodore set has its glyph, and the glyphs read as stored */
    @Test
    fun glyphs() {
        val g = LegacyCharsets.GLYPHS
        for (set in listOf(LegacyCharsets.C64_UPPER, LegacyCharsets.C64_LOWER, LegacyCharsets.VIC_UPPER,
            LegacyCharsets.VIC_LOWER, LegacyCharsets.PET_UPPER, LegacyCharsets.PET_LOWER)) {
            for (cp in set) if (cp != 0) assertTrue(g.has(cp), "U+" + cp.toString(16))
        }
        // (the left half block: its left four pixels; a space: none)
        assertEquals(8, g.height)
        assertEquals(0xf0, g.row(0x258c, 0))
        assertTrue(g.pixel(0x258c, 7, 0))
        assertFalse(g.pixel(0x258c, 7, 7))
        assertEquals(0, (0 until 8).sumOf { g.row(' '.code, it) })
        assertFalse(g.has(0x4e00))
    }

    /** a Commodore's key bar: its keys, the colour page's swatches in its palette */
    @Test
    fun keyBar() {
        val bar = TerminalProfiles.C64.keyBar
        assertEquals(3, bar.size)
        val colours = bar[2].rows[0].filterIsInstance<BarKey.Octets>()
        assertEquals(8, colours.size)
        assertEquals("\u001c", colours[2].octets)
        assertEquals(CommodorePalettes.PEPTO[2], colours[2].color)
        assertEquals(2, TerminalProfiles.PET.keyBar.size)
    }
}
