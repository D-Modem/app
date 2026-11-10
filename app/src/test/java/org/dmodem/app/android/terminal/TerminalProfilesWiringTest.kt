// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.terminal

import org.dmodem.app.android.settings.TerminalSettings
import org.dmodem.app.terminal.TermCharset
import org.dmodem.app.terminal.TerminalProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TerminalProfilesWiringTest {

    /** Telnet's TERMINAL-TYPE: the profile's, and xterm in CP437 still ANSI (a BBS's) as before profiles */
    @Test
    fun telnetTypeFollowsTheProfile() {
        assertEquals("ANSI", Terminal.telnetType(TerminalProfiles.XTERM, TermCharset.CP437))
        assertEquals("XTERM-256COLOR", Terminal.telnetType(TerminalProfiles.XTERM, TermCharset.UTF8))
        assertEquals("DEC-VT220", Terminal.telnetType(TerminalProfiles.VT220, TermCharset.CP437))
        assertEquals("ANSI", Terminal.telnetType(TerminalProfiles.ANSI_BBS, TermCharset.UTF8))
    }

    /** an entry keeps its terminal type through directory.json; none stays none */
    @Test
    fun anEntrysTerminalTypeIsKept() {
        val e = DialEntry(name = "BBS", kind = EntryKind.TELNET, host = "bbs.example", terminal = "vt220")
        assertEquals("vt220", DialEntry.fromJson(e.toJson()).terminal)
        assertEquals(null, DialEntry.fromJson(DialEntry(name = "x").toJson()).terminal)
    }

    /** the settings' terminal type as a profile: the default for one not known */
    @Test
    fun theSettingsProfile() {
        assertSame(TerminalProfiles.XTERM, TerminalSettings().profile)
        assertSame(TerminalProfiles.VT100, TerminalSettings(terminalType = "vt100").profile)
        assertSame(TerminalProfiles.DEFAULT, TerminalSettings(terminalType = "gone").profile)
    }
}
