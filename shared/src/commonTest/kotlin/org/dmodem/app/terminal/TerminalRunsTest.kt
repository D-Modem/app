// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A screen's rows as styled runs (what the iOS app draws): the text, its cells, its colours resolved. */
class TerminalRunsTest {
    private val vga = TermTheme.VGA
    /** [rgb] opaque */
    private fun argb(rgb: Int) = 0xff000000.toInt() or rgb

    /** a row's runs split where the style changes: colours, bold as bright (or heavy), inverse swapping the defaults, all cells counted */
    @Test
    fun plainColouredBoldInverse() {
        val t = TerminalEmulator(20, 3)
        // red, then bold red (bright, as BBSes have it), inverse, default
        t.appendText("\u001b[31mab\u001b[1mcd\u001b[0;7mef\u001b[0mgh")
        val r = t.runs(0)
        assertEquals(listOf("ab", "cd", "ef", "gh" + " ".repeat(12)), r.map { it.text })
        assertEquals(listOf(0, 2, 4, 6), r.map { it.col })
        assertEquals(argb(0xaa0000), r[0].fg)
        assertEquals(argb(0xff5555), r[1].fg)
        assertFalse(r[1].bold)
        // inverse: the default colours swapped
        assertEquals(vga.bgArgb, r[2].fg)
        assertEquals(vga.fgArgb, r[2].bg)
        assertEquals(20, r.sumOf { it.cells })
        // bold shown heavy when it isn't bright instead
        val heavy = t.runs(0, RunLook(boldBright = false))
        assertTrue(heavy[1].bold)
        assertEquals(argb(0xaa0000), heavy[1].fg)
    }

    /** a wide character is one run's text over two cells, true colour resolved, and an unwritten row is spaces in the theme's colours */
    @Test
    fun wideCharactersAndTrueColour() {
        val t = TerminalEmulator(10, 2)
        t.appendText("\u001b[38;2;1;2;3m漢x")
        val r = t.runs(0)
        // the wide character once, over two cells
        assertEquals("漢x", r[0].text)
        assertEquals(3, r[0].cells)
        assertEquals(argb(0x010203), r[0].fg)
        // a row never written: spaces, the theme's colours
        val blank = t.runs(1).single()
        assertEquals(" ".repeat(10), blank.text)
        assertEquals(vga.bgArgb, blank.bg)
    }

    /** a colour the remote sets (OSC 4) is the one runs use */
    @Test
    fun theRemotesPalette() {
        val t = TerminalEmulator(10, 1)
        // OSC 4: colour 1 is now green
        t.appendText("\u001b]4;1;rgb:00/ff/00\u001b\\\u001b[31mx")
        assertEquals(argb(0x00ff00), t.runs(0)[0].fg)
    }
}
