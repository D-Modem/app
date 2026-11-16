// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.bridge

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.dmodem.app.android.NativeModem
import java.io.File
import java.io.IOException

/**
 * The firmware file of the MultiTech modems with a TUSB3410 (MT9234MU,
 * MT9234ZBA-USB), as the user imported it. The app has no copy of its own:
 * MultiTech gave no licence that lets others distribute it.
 */
object TiFirmwareStore {
    private const val PATH = "firmware/mts_mt9234mu.fw"

    /** where the imported file is kept */
    fun file(context: Context): File = File(context.filesDir, PATH)

    /** the imported file, or null if there is none */
    fun read(context: Context): ByteArray? = file(context).takeIf { it.isFile }?.readBytes()

    /** [bytes] kept as the firmware: null, or why they can't be */
    fun import(context: Context, bytes: ByteArray): String? {
        TiFirmware.problem(bytes)?.let { return it }
        val f = file(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".new")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            tmp.delete()
            return "the file could not be saved"
        }
        return null
    }

    /** the imported file removed */
    fun remove(context: Context) {
        file(context).delete()
    }

    /** what the imported file is, for the user, or null if there is none */
    fun describe(context: Context): String? = read(context)?.let { TiFirmware.describe(it) }
}

/**
 * A MultiTech modem in its TUSB3410's boot ROM given its firmware: the
 * download, the reset, and the wait until it comes back with the
 * firmware's configurations.
 */
object TiFirmwareLoader {
    /** how long the modem may take to come back */
    private const val BACK_MS = 10_000L

    /**
     * [file] downloaded into [dev] through [conn]: its image in 64-octet
     * packets to the boot ROM's one bulk OUT pipe; an IOException if a
     * packet does not go
     */
    fun download(conn: UsbDeviceConnection, dev: UsbDevice, file: ByteArray) {
        val itf = dev.getInterface(0)
        val ep = (0 until itf.endpointCount).map { itf.getEndpoint(it) }
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
            ?: throw IOException("no bulk OUT pipe")
        if (!conn.claimInterface(itf, true)) throw IOException("could not claim interface ${itf.id}")
        try {
            for (p in TiFirmware.packets(TiFirmware.image(file))) {
                val n = conn.bulkTransfer(ep, p, p.size, 1000)
                if (n != p.size) throw IOException("a packet of the firmware did not go ($n)")
            }
        } finally {
            runCatching { conn.releaseInterface(itf) }
        }
    }

    /**
     * The modem [dev] (in its boot ROM, the app permitted) given its
     * firmware, as [who]: null once it is back with its UART, or why not.
     */
    suspend fun load(context: Context, m: UsbManager, dev: UsbDevice, who: String, label: String): String? {
        val file = TiFirmwareStore.read(context)
            ?: return "$label needs its firmware (mts_mt9234mu.fw): import the file in Settings, Line, then try again"
        if (!SerialAdapters.take(dev.deviceName, who)) return "$label is in use (${SerialAdapters.holder(dev.deviceName)})"
        try {
            val conn = m.openDevice(dev) ?: return "can't open $label"
            try {
                download(conn, dev, file)
                delay(200)
                // (the TUSB3410 runs the firmware after a reset; the
                // modem comes back as a new device)
                if (m.deviceList.containsKey(dev.deviceName)) NativeModem.usbReset(conn.fileDescriptor)
            } catch (e: IOException) {
                return "$label: the firmware download failed (${e.message})"
            } finally {
                conn.close()
            }
        } finally {
            SerialAdapters.release(dev.deviceName)
        }
        val back = withTimeoutOrNull(BACK_MS) {
            var d: UsbDevice? = null
            while (d == null) {
                delay(250)
                d = m.deviceList.values.firstOrNull {
                    it.vendorId == dev.vendorId && it.productId == dev.productId && !TiFirmware.needsFirmware(it)
                }
            }
            d
        }
        return if (back == null) "$label did not come back after its firmware download: disconnect it, connect it again, and try again" else null
    }
}
