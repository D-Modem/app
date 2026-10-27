// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.bridge

import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * The USB serial adapters the app drives: usb-serial-for-android's own table (the chip makers' IDs,
 * any CDC-ACM device, a few modems), the many adapters on the same chips under their makers' own
 * IDs, as Linux's drivers know them (UsbSerialIds), and Exar's XR21V141x/XR21B14xx/XR2280x (XrSerialDriver).
 */
object UsbSerialAdapters {
    /** finds them among what's plugged in */
    val prober: UsbSerialProber by lazy { UsbSerialProber(table()) }

    /** usb-serial-for-android's table, with the adapters' IDs Linux knows and Exar's chips added */
    internal fun table(): ProbeTable = UsbSerialProber.getDefaultProbeTable().apply {
        add(UsbSerialIds.FTDI, FtdiSerialDriver::class.java)
        add(UsbSerialIds.CP210X, Cp21xxSerialDriver::class.java)
        add(UsbSerialIds.PL2303, ProlificSerialDriver::class.java)
        add(UsbSerialIds.CH34X, Ch34xSerialDriver::class.java)
        // (Exar's: before the CDC-ACM driver, which would take them by their class but can't set their line)
        for (pid in XrChip.products) addProduct(XrChip.VENDOR, pid, XrSerialDriver::class.java)
    }

    /** [driver] for each of [ids] (vendor << 16 | product) */
    private fun ProbeTable.add(ids: IntArray, driver: Class<out UsbSerialDriver>) {
        for (id in ids) addProduct(id ushr 16, id and 0xffff, driver)
    }

    /** how many it knows by their IDs (not counting the CDC-ACM ones, known by their class) */
    val known: Int get() = UsbSerialIds.FTDI.size + UsbSerialIds.CP210X.size + UsbSerialIds.PL2303.size + UsbSerialIds.CH34X.size + XrChip.products.size
}
