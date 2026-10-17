// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.cell

import com.neodesktop.dmodem.settings.ModemSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** On mobile calls the modem starts at the speed chosen (+MS), with the init commands on the same line. */
class CellSettingsTest {
    @Test
    fun startCommands() {
        val cell = ModemSettings(line = ModemSettings.LINE_CELL)
        assertEquals("AT+MS=V21;X3", cell.startCommands())
        assertEquals("AT+MS=V22B;X3M2", cell.copy(cellSpeed = "V22B", initCommands = "atX3M2").startCommands())
        assertEquals("AT+MS=V21", cell.copy(initCommands = " ").startCommands())
        // (another line: the init commands as they are)
        assertEquals("ATX3", ModemSettings().startCommands())
        assertEquals("ATX3", ModemSettings(line = ModemSettings.LINE_WIFI).startCommands())
    }
}
