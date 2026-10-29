// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.ui

import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Plugged in" follows a USB modem plugged in and out while it's on screen */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsbPluggedTest {
    @get:Rule
    val compose = createComposeRule()

    /** "Plugged in" names the modem once it's attached and says none again once it's detached */
    @Test
    fun followsPlugAndUnplug() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val usb = shadowOf(context.getSystemService(UsbManager::class.java))
        compose.setContent { Text("Plugged in: " + rememberUsbModemsPlugged()) }
        compose.onNodeWithText("Plugged in: none").assertExists()

        val d = com.neodesktop.dmodem.TestUsbDevices.device("/dev/bus/usb/001/002", 0x047E, 0x2892)
        usb.addOrUpdateUsbDevice(d, true)
        context.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, d))
        compose.waitForIdle()
        compose.onNodeWithText("Plugged in: Agere USB 2.0 modem").assertExists()

        // (Android may still list it as it says it's gone)
        context.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, d))
        compose.waitForIdle()
        compose.onNodeWithText("Plugged in: none").assertExists()
    }
}
