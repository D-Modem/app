// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** USB devices for Robolectric tests (Android's own constructor is hidden) */
object TestUsbDevices {
    /** a device [vendor]:[product] at [name], with no configurations */
    fun device(name: String, vendor: Int, product: Int): UsbDevice {
        val s = String::class.java
        val i = Int::class.javaPrimitiveType
        val z = Boolean::class.javaPrimitiveType
        val b = ReflectionHelpers.callConstructor(
            Class.forName("android.hardware.usb.UsbDevice\$Builder"),
            ClassParameter(s, name), ClassParameter(i, vendor), ClassParameter(i, product),
            ClassParameter(i, 0xFF), ClassParameter(i, 0), ClassParameter(i, 0),
            ClassParameter(s, "maker"), ClassParameter(s, "product"), ClassParameter(s, "1.0"),
            ClassParameter(Array<UsbConfiguration>::class.java, emptyArray<UsbConfiguration>()),
            ClassParameter(s, "1"),
            ClassParameter(z, false), ClassParameter(z, false), ClassParameter(z, false), ClassParameter(z, false), ClassParameter(z, false),
        )
        // (its serial number's reader: an interface of Android's own, answered with nothing)
        val reader = Class.forName("android.hardware.usb.IUsbSerialReader")
        val none = java.lang.reflect.Proxy.newProxyInstance(reader.classLoader, arrayOf(reader)) { _, _, _ -> null }
        return ReflectionHelpers.callInstanceMethod(b, "build", ClassParameter(reader, none))
    }
}
