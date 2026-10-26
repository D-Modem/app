// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.neodesktop.dmodem.modem.UsbModems

/** which USB modems are plugged in ("none", "Agere USB 2.0 modem"), kept up to date as they're plugged in and out while it's shown */
@Composable
fun rememberUsbModemsPlugged(): String {
    val context = LocalContext.current
    var plugged by remember { mutableStateOf(UsbModems.describe(context)) }
    DisposableEffect(context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val gone = if (i.action == UsbManager.ACTION_USB_DEVICE_DETACHED) d?.deviceName else null
                plugged = UsbModems.describe(context, gone)
            }
        }
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // (the system's broadcasts)
        ContextCompat.registerReceiver(context, r, f, ContextCompat.RECEIVER_EXPORTED)
        // (and anything plugged in or out between the first look and now)
        plugged = UsbModems.describe(context)
        onDispose { runCatching { context.unregisterReceiver(r) } }
    }
    return plugged
}
