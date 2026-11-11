// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class AmigaAnsiTest {

    /** the Amiga: Latin-1, 8x8 glyphs for all of it, its colours over the theme's, the theme's back for the next profile */
    @Test
    fun amiga() {
        val p = TerminalProfiles.AMIGA as AmigaAnsiProfile
        assertEquals(TermCharset.LATIN1, p.charset)
        for (cp in 0xa1..0xff) assertTrue(LegacyCharsets.GLYPHS.has(cp), "U+" + cp.toString(16))
        val t = TerminalEmulator(80, 25, 100, p) {}
        assertEquals(0xffcc2222.toInt(), t.colorOverride(1))
        assertEquals(0xffffffff.toInt(), t.colorOverride(15))
        t.profile = TerminalProfiles.ANSI_BBS
        assertEquals(TerminalEmulator.NO_COLOR, t.colorOverride(1))
    }
}
