// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.content.Intent
import android.hardware.usb.UsbDevice
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
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** "Plugged in" follows a USB modem plugged in and out while it's on screen */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsbPluggedTest {
    @get:Rule
    val compose = createComposeRule()

    /** an Agere USB 2.0 modem's device (047e:2892) at [name] */
    private fun agere(name: String): UsbDevice {
        val s = String::class.java
        val i = Int::class.javaPrimitiveType
        val b = ReflectionHelpers.callConstructor(
            Class.forName("android.hardware.usb.UsbDevice\$Builder"),
            ClassParameter(s, name), ClassParameter(i, 0x047E), ClassParameter(i, 0x2892),
            ClassParameter(i, 0xFF), ClassParameter(i, 0), ClassParameter(i, 0),
            ClassParameter(s, "Agere"), ClassParameter(s, "USB modem"), ClassParameter(s, "1.0"),
            ClassParameter(Array<android.hardware.usb.UsbConfiguration>::class.java, emptyArray<android.hardware.usb.UsbConfiguration>()),
            ClassParameter(s, "1"),
            ClassParameter(Boolean::class.javaPrimitiveType, false), ClassParameter(Boolean::class.javaPrimitiveType, false),
            ClassParameter(Boolean::class.javaPrimitiveType, false), ClassParameter(Boolean::class.javaPrimitiveType, false),
            ClassParameter(Boolean::class.javaPrimitiveType, false),
        )
        // (its serial number's reader: an interface of Android's own, answered with nothing)
        val reader = Class.forName("android.hardware.usb.IUsbSerialReader")
        val none = java.lang.reflect.Proxy.newProxyInstance(reader.classLoader, arrayOf(reader)) { _, _, _ -> null }
        return ReflectionHelpers.callInstanceMethod(b, "build", ClassParameter(reader, none))
    }

    @Test
    fun followsPlugAndUnplug() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val usb = shadowOf(context.getSystemService(UsbManager::class.java))
        compose.setContent { Text("Plugged in: " + rememberUsbModemsPlugged()) }
        compose.onNodeWithText("Plugged in: none").assertExists()

        val d = agere("/dev/bus/usb/001/002")
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
