// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.cell

import com.neodesktop.dmodem.settings.ModemSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** On mobile calls the modem starts at the speed chosen (+MS), with the init commands on the same line. */
class CellSettingsTest {
    /** mobile calls start with the +MS speed chosen and the init commands on the same line; other lines with the init commands alone */
    @Test
    fun startCommands() {
        val cell = ModemSettings(line = ModemSettings.LINE_CELL)
        // (V.cvm by default, S7 long enough for its start-up)
        assertEquals("AT+MS=V_CVM;S7=90;X3", cell.startCommands())
        assertEquals("AT+MS=V21;X3", cell.copy(cellSpeed = "V21").startCommands())
        assertEquals("AT+MS=V22B;X3M2", cell.copy(cellSpeed = "V22B", initCommands = "atX3M2").startCommands())
        assertEquals("AT+MS=V21", cell.copy(cellSpeed = "V21", initCommands = " ").startCommands())
        // (another line: the init commands as they are)
        assertEquals("ATX3", ModemSettings().startCommands())
        assertEquals("ATX3", ModemSettings(line = ModemSettings.LINE_WIFI).startCommands())
    }

    /** a CONNECT's modulation and protocols as labelled, from the last one; none for a plain speed */
    @Test
    fun whatTheConnectSaid() {
        val label = com.neodesktop.dmodem.modem.ConnectInfo::label
        assertEquals("V.cvm · LAPM", label("ATD5550002\r\nCONNECT 1200/CVM/LAPM\r\n"))
        assertEquals("V.92 · LAPM · V.44", label("CONNECT 48000/V92/LAPM/V44\r\nhello"))
        assertEquals("", label("CONNECT 300\r\n"))
        assertEquals("", label("OK\r\n"))
        // (the last one)
        assertEquals("V.cvm", label("CONNECT 9600/V32\r\nNO CARRIER\r\nCONNECT 1200/CVM\r\n"))
    }
}
