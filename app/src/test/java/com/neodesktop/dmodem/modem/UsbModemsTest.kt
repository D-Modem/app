// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbModemsTest {

    /** the USB modems known by vendor and product ID, other devices not */
    @Test
    fun theTwoModemsByTheirIds() {
        assertEquals(UsbModems.Kind.SMART_USB56, UsbModems.kindOf(0x0483, 0x7554))
        assertEquals(UsbModems.Kind.PICO_LINE, UsbModems.kindOf(0x1209, 0x0002))
        assertEquals(UsbModems.Kind.AGERE, UsbModems.kindOf(0x047E, 0x2892))
        assertEquals(UsbModems.Kind.AGERE, UsbModems.kindOf(0x047E, 0x2828))
        assertNull(UsbModems.kindOf(0x047E, 0x1234))
        // (the pico-serial board, an STM32's other devices: not modems)
        assertNull(UsbModems.kindOf(0x1209, 0x0001))
        assertNull(UsbModems.kindOf(0x0483, 0x5740))
    }

    /** each USB modem's description with its IDs, and its sample rate */
    @Test
    fun describedWithTheirIds() {
        assertEquals("SmartUSB56 (USB 0483:7554)", UsbModems.Kind.SMART_USB56.described)
        assertEquals("pico-serial Line (USB 1209:0002)", UsbModems.Kind.PICO_LINE.described)
        assertEquals("Agere USB 2.0 modem (USB 047e:2892/047e:2828)", UsbModems.Kind.AGERE.described)
        // (the Agere runs at 8000 Hz, the others at 9600)
        assertEquals(8000, UsbModems.Kind.AGERE.rate)
        assertEquals(9600, UsbModems.Kind.PICO_LINE.rate)
    }
}
