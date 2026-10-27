// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.bridge

import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.neodesktop.dmodem.TestUsbDevices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** adapters under their makers' own IDs found, with their chip's driver; the chip makers' still are */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsbSerialAdaptersTest {
    /** the driver class found for a device with these IDs, or null */
    private fun driver(vendor: Int, product: Int) =
        UsbSerialAdapters.table().findDriver(TestUsbDevices.device("/dev/bus/usb/001/002", vendor, product))

    /** adapters under their makers' own IDs get their chip's driver */
    @Test
    fun theirMakersIds() {
        // an ATEN UC-232A (a PL2303), a Silicon Labs CP210x under another ID, a CH340 variant, an FTDI clone that FTDI's driver bricked
        assertEquals(ProlificSerialDriver::class.java, driver(0x0557, 0x2008))
        assertEquals(Cp21xxSerialDriver::class.java, driver(0x10c4, 0x8a2a))
        assertEquals(Ch34xSerialDriver::class.java, driver(0x1a86, 0x7522))
        assertEquals(FtdiSerialDriver::class.java, driver(0x0403, 0x0000))
    }

    /** Exar's UARTs get our own driver */
    @Test
    fun exarsTheirOwn() {
        assertEquals(XrSerialDriver::class.java, driver(0x04e2, 0x1410))
        assertEquals(XrSerialDriver::class.java, driver(0x04e2, 0x1422))
    }

    /** the chip makers' own IDs still get theirs */
    @Test
    fun theChipMakersStill() {
        assertEquals(FtdiSerialDriver::class.java, driver(0x0403, 0x6001))
        assertEquals(Cp21xxSerialDriver::class.java, driver(0x10c4, 0xea60))
        assertEquals(ProlificSerialDriver::class.java, driver(0x067b, 0x2303))
        assertEquals(Ch34xSerialDriver::class.java, driver(0x1a86, 0x7523))
    }

    /** a USB modem and an unknown device get none */
    @Test
    fun notOthers() {
        // the Agere USB modem (UsbModems'), and nothing in particular
        assertNull(driver(0x047e, 0x2892))
        assertNull(driver(0x1234, 0x5678))
        assertTrue(UsbSerialAdapters.known > 1000)
    }
}
