// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.modem

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * The Agere USB modem's ROM patches: in the APK's assets as D-Modem-RE
 * ships them, installed in the app's files byte for byte (their SHA-256
 * sums D-Modem-RE's), with Agere's licence beside them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgereFirmwareTest {
    @Test
    fun installedAsShipped() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = UsbModems.installAgereFirmware(ctx)
        val sums = File(dir, "SHA256SUMS").readLines().filter { it.isNotBlank() }.associate { l ->
            val (sum, name) = l.trim().split(Regex("\\s+"), limit = 2)
            name.removePrefix("*") to sum
        }
        assertEquals(4, sums.size)
        for ((name, sum) in sums) {
            val f = File(dir, name)
            assertTrue(name, f.isFile)
            val got = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(name, sum, got)
        }
        assertTrue(File(dir, "LICENSE").isFile)
        assertTrue(File(dir, "NOTICE.md").isFile)
        // (again: nothing changes)
        assertEquals(dir, UsbModems.installAgereFirmware(ctx))
    }
}
