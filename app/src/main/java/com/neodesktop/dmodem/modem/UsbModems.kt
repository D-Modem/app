// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.modem

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The USB modems that can be the modem's line (D-Modem-RE's usb_line.cpp,
 * on the device Android opens for the app): a Smart Link SmartUSB56
 * (ST7554) and a pico-serial Line stick (a Si3056 DAA on a phone line).
 * The app runs the modem itself: they carry its samples to the line.
 */
object UsbModems {
    enum class Kind(val vendor: Int, val product: Int, val label: String) {
        SMART_USB56(0x0483, 0x7554, "SmartUSB56"),
        PICO_LINE(0x1209, 0x0002, "pico-serial Line"),
        ;

        /** "SmartUSB56 (USB 0483:7554)" */
        val described: String get() = "$label (USB %04x:%04x)".format(vendor, product)
    }

    /** the modems' rate (Hz): both take 9600, the Si3056's default (dmodemd's too) */
    const val RATE = 9600

    /** which of them a USB device with these IDs is, or null */
    fun kindOf(vendor: Int, product: Int): Kind? = Kind.entries.firstOrNull { it.vendor == vendor && it.product == product }

    fun kindOf(d: UsbDevice): Kind? = kindOf(d.vendorId, d.productId)

    /** the ones plugged in, and which each is */
    fun plugged(context: Context): List<Pair<UsbDevice, Kind>> {
        val m = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return m.deviceList.values.mapNotNull { d -> kindOf(d)?.let { d to it } }
    }

    /** what the line setting says of what's plugged in */
    fun describe(context: Context): String = plugged(context).let { p ->
        if (p.isEmpty()) "none" else p.joinToString(", ") { it.second.label }
    }

    /** an opened modem, or why there's none */
    sealed interface Opened {
        class Device(val kind: Kind, val connection: UsbDeviceConnection) : Opened
        class Failed(val why: String) : Opened
    }

    private const val ACTION_PERMISSION = "com.neodesktop.dmodem.USB_MODEM_PERMISSION"

    /**
     * The first one plugged in, opened: Android asked first if the app may
     * use it (the user has [askMs] to say), unless it has said so already.
     */
    suspend fun open(context: Context, askMs: Long = 60_000): Opened {
        val m = context.getSystemService(UsbManager::class.java) ?: return Opened.Failed("this phone has no USB host")
        val (dev, kind) = plugged(context).firstOrNull()
            ?: return Opened.Failed("no USB modem plugged in: a ${Kind.SMART_USB56.described} or a ${Kind.PICO_LINE.described}")
        if (!m.hasPermission(dev)) {
            val allowed = withTimeoutOrNull(askMs) { ask(context, m, dev) } ?: false
            if (!allowed) return Opened.Failed("the ${kind.label} wasn't allowed: allow D-Modem to use it, then start the modem again")
        }
        val conn = m.openDevice(dev) ?: return Opened.Failed("can't open the ${kind.label}")
        return Opened.Device(kind, conn)
    }

    /** Android's question for [dev]: whether it was allowed */
    private suspend fun ask(context: Context, m: UsbManager, dev: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            m.requestPermission(dev, PendingIntent.getBroadcast(context, 2, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags))
        }
}
