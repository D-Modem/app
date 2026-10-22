// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbModemsTest {

    @Test
    fun theTwoModemsByTheirIds() {
        assertEquals(UsbModems.Kind.SMART_USB56, UsbModems.kindOf(0x0483, 0x7554))
        assertEquals(UsbModems.Kind.PICO_LINE, UsbModems.kindOf(0x1209, 0x0002))
        // (the pico-serial board, an STM32's other devices: not modems)
        assertNull(UsbModems.kindOf(0x1209, 0x0001))
        assertNull(UsbModems.kindOf(0x0483, 0x5740))
    }

    @Test
    fun describedWithTheirIds() {
        assertEquals("SmartUSB56 (USB 0483:7554)", UsbModems.Kind.SMART_USB56.described)
        assertEquals("pico-serial Line (USB 1209:0002)", UsbModems.Kind.PICO_LINE.described)
    }
}
