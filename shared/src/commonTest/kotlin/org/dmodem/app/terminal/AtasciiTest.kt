// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class AtasciiTest {

    private val keys = KeyEncoder()

    /** an Atari's screen, 40x24 */
    private fun term(profile: TerminalProfile = TerminalProfiles.ATARI) = TerminalEmulator(40, 24, 100, profile)

    /** octets fed in as they are */
    private fun TerminalEmulator.send(vararg b: Int) = append(ByteArray(b.size) { b[it].toByte() })

    /** the octets [b] as ints */
    private fun ints(b: ByteArray) = b.map { it.toInt() and 0xff }

    /** its screen, its characters (0x80 up reversed), ESC showing a control's character */
    @Test
    fun charactersAndInverse() {
        val t = term()
        assertEquals(40, t.requestedColumns)
        assertEquals(24, t.requestedRows)
        t.send(0x41, 0x00, 0x60, 0x7b, 0xc1, 0x1b, 0x1c, 0x1b, 0x7d)
        assertEquals("A♥♦♠A↑\uD83E\uDCB0", t.line(0).text())
        val l = t.line(0)
        assertEquals(0, Style.attrs(l.styles[0]) and Style.INVERSE)
        assertTrue(Style.attrs(l.styles[4]) and Style.INVERSE != 0)
        assertEquals(0, Style.attrs(l.styles[5]) and Style.INVERSE)
        val i = term(TerminalProfiles.ATARI_INTL)
        i.send(0x7b)
        assertEquals("Ä", i.line(0).text())
    }

    /** the cursor keys wrap round the screen; EOL, CLEAR, BACK S, delete and insert */
    @Test
    fun editing() {
        val t = term()
        t.send(0x1c)
        assertEquals(23, t.cursorY)
        t.send(0x1d, 0x1e)
        assertEquals(0, t.cursorY)
        assertEquals(39, t.cursorX)
        t.send(0x1f)
        assertEquals(0, t.cursorX)
        t.send(0x41, 0x42, 0x43, 0x7e)
        assertEquals("AB", t.line(0).text())
        assertEquals(2, t.cursorX)
        t.send(0x1e, 0x1e, 0xfe)
        assertEquals("B", t.line(0).text())
        t.send(0xff)
        assertEquals(" B", t.line(0).text())
        t.send(0x9b)
        assertEquals(1, t.cursorY)
        assertEquals(0, t.cursorX)
        t.send(0x7d)
        assertEquals("", t.line(0).text())
        assertEquals(0, t.cursorY)
    }

    /** its keys and typing: EOL for Enter, CTRL with a letter its graphic, the inverse key reversing */
    @Test
    fun keysAndTyping() {
        val t = term()
        assertEquals(listOf(0x9b), ints(t.keyOctets(keys, Key.ENTER)))
        assertEquals(listOf(0x1c), ints(t.keyOctets(keys, Key.UP)))
        assertEquals(listOf(0x7e), ints(t.keyOctets(keys, Key.BACKSPACE)))
        assertEquals(listOf(0x01), ints(t.charOctets(keys, 'a'.code, ctrl = true)))
        assertEquals(listOf(0x60), ints(t.charOctets(keys, '.'.code, ctrl = true)))
        assertEquals(listOf(0xe1), ints(t.charOctets(keys, 'a'.code, alt = true)))
        assertEquals(listOf(0x7b), ints(t.charOctets(keys, '♠'.code)))
        assertEquals(listOf(0x3f), ints(t.charOctets(keys, '~'.code)))
    }

    /** every character of both sets has its glyph */
    @Test
    fun glyphs() {
        for (set in listOf(LegacyCharsets.ATARI_GRAPHICS, LegacyCharsets.ATARI_INTERNATIONAL)) {
            for (cp in set) if (cp != 0) assertTrue(LegacyCharsets.GLYPHS.has(cp), "U+" + cp.toString(16))
        }
    }
}
