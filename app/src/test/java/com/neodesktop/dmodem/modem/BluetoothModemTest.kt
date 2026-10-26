// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.modem

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.neodesktop.dmodem.terminal.TerminalLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

/** a paired Bluetooth modem connected (Robolectric's Bluetooth) and its AT commands through the modem's DTE hub */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BluetoothModemTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java).adapter

    private fun waitUntil(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(what, ok())
    }

    @Test
    fun theTerminalTalksToIt() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(true)
        val bta = adapter.getRemoteDevice("08:00:46:12:34:56")
        shadowOf(adapter).setBondedDevices(setOf(bta))
        val log = mutableListOf<String>()
        val s = BluetoothModem.connect(ctx, "08:00:46:12:34:56") { log += it }
        assertTrue(log.toString(), log.any { it.contains("(Dial-Up Networking)") })

        val hub = DteHub()
        val heard = StringBuffer()
        hub.addListener(TerminalLink.Listener { d, n -> heard.append(String(d, 0, n, Charsets.ISO_8859_1)) })
        hub.attach(s.inputStream, s.outputStream)
        assertTrue(hub.write("ATZ\r".toByteArray(), 0, 4))
        val sink = shadowOf(s).outputStreamSink
        val got = ByteArray(4)
        var n = 0
        while (n < 4) n += sink.read(got, n, 4 - n)
        assertEquals("ATZ\r", String(got))
        shadowOf(s).inputStreamFeeder.apply { write("\r\nOK\r\n".toByteArray()); flush() }
        waitUntil("its OK heard") { heard.contains("OK") }
        hub.detach()
    }

    @Test
    fun offSaysSo() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(false)
        try {
            BluetoothModem.connect(ctx, "08:00:46:12:34:56") {}
            fail("connected with Bluetooth off")
        } catch (e: IOException) {
            assertEquals("Bluetooth is off", e.message)
        }
    }
}
