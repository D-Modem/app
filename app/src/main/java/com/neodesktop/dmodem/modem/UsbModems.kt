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
    enum class Kind(val vendor: Int, val products: List<Int>, val label: String, val rate: Int) {
        SMART_USB56(0x0483, listOf(0x7554), "SmartUSB56", 9600),
        PICO_LINE(0x1209, listOf(0x0002), "pico-serial Line", 9600),
        /** on its ROM's own isochronous stream (D-Modem-RE's usb_agere.cpp); a USB 2.0 port */
        AGERE(0x047E, listOf(0x2892, 0x2828), "Agere USB 2.0 modem", 8000),
        ;

        /** "SmartUSB56 (USB 0483:7554)" */
        val described: String
            get() = "$label (USB " + products.joinToString("/") { "%04x:%04x".format(vendor, it) } + ")"
    }

    /** the modems' rate (Hz) but the Agere's: both take 9600, the Si3056's default (dmodemd's too); the Agere 8000 */
    const val RATE = 9600

    /** which of them a USB device with these IDs is, or null */
    fun kindOf(vendor: Int, product: Int): Kind? = Kind.entries.firstOrNull { it.vendor == vendor && product in it.products }

    /** which of them USB device [d] is, or null */
    fun kindOf(d: UsbDevice): Kind? = kindOf(d.vendorId, d.productId)

    /** the ones plugged in, and which each is; not [gone] (the device name of one just unplugged, which Android may still list) */
    fun plugged(context: Context, gone: String? = null): List<Pair<UsbDevice, Kind>> {
        val m = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return m.deviceList.values.filter { it.deviceName != gone }.mapNotNull { d -> kindOf(d)?.let { d to it } }
    }

    /** what the line setting says of what's plugged in (see [plugged]) */
    fun describe(context: Context, gone: String? = null): String = plugged(context, gone).let { p ->
        if (p.isEmpty()) "none" else p.joinToString(", ") { it.second.label }
    }

    /** "the Agere USB 2.0 modem (USB 047e:2892) at /dev/bus/usb/001/002" */
    private fun said(d: UsbDevice, kind: Kind): String =
        "the ${kind.label} (USB %04x:%04x) at ${d.deviceName}".format(d.vendorId, d.productId)

    /**
     * The ones plugged in said to [log] now, and each as it's plugged in or goes (unplugged, or a reset of
     * its own: Android says so as it goes from the bus and comes back), for as long as the app runs.
     */
    fun watch(context: Context, log: (String) -> Unit) {
        for ((d, kind) in plugged(context)) log("USB: ${said(d, kind)}: plugged in")
        val r = object : BroadcastReceiver() {
            /** a modem plugged in or gone, said to [log] (other devices: nothing) */
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                val kind = kindOf(d) ?: return
                log(
                    "USB: ${said(d, kind)}: " + if (i.action == UsbManager.ACTION_USB_DEVICE_DETACHED) "gone from the bus (unplugged, or it reset)"
                    else "plugged in",
                )
            }
        }
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // (the system's broadcasts)
        ContextCompat.registerReceiver(context, r, f, ContextCompat.RECEIVER_EXPORTED)
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
            ?: return Opened.Failed(
                "no USB modem plugged in: a ${Kind.SMART_USB56.described}, a ${Kind.PICO_LINE.described} or an ${Kind.AGERE.described}",
            )
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
                /** Android's answer: the question's receiver let go, [cont] resumed with it */
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
