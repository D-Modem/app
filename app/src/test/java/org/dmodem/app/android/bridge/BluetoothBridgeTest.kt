// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.bridge

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.dmodem.app.android.terminal.TerminalLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The modem as a Bluetooth serial port (Robolectric's Bluetooth): a
 * device connecting to the phone's port, the phone connecting to a
 * paired device's; the permission and Bluetooth being off said.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BluetoothBridgeTest {

    private class FakeModem : TerminalLink {
        override val state: StateFlow<TerminalLink.State> = MutableStateFlow(TerminalLink.State.Open)
        val listeners = CopyOnWriteArrayList<TerminalLink.Listener>()
        val got = ByteArrayOutputStream()
        /** [l] told the modem's replies */
        override fun addListener(l: TerminalLink.Listener) { listeners.add(l) }
        /** [l] told no more */
        override fun removeListener(l: TerminalLink.Listener) { listeners.remove(l) }
        /** the bytes kept, and OK replied to a command (one ending in CR) */
        override fun write(data: ByteArray, offset: Int, length: Int): Boolean {
            synchronized(got) { got.write(data, offset, length) }
            if (String(data, offset, length).endsWith("\r")) {
                val ok = "\r\nOK\r\n".toByteArray()
                listeners.forEach { it.onData(ok, ok.size) }
            }
            return true
        }
        /** what was written to the modem, as text */
        fun sent() = synchronized(got) { String(got.toByteArray()) }
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val modem = FakeModem()
    private val signals = MutableStateFlow(LineSignals(ready = true))
    private val hangUps = AtomicInteger()
    private val serial = SerialBridge(modem, signals, { hangUps.incrementAndGet() })
    private var bridge: BluetoothBridge? = null
    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java).adapter

    /** Bluetooth on and allowed */
    @Before
    fun setUp() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(true)
    }

    /** the bridge stopped */
    @After
    fun tearDown() {
        bridge?.stop()
    }

    /** [ok] waited for (5 s at most), failing with [what] if it never holds */
    private fun waitUntil(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(what, ok())
    }

    /** [i] read until [want] has come (5 s at most): what was read */
    private fun readUntil(i: InputStream, want: String): String {
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + 5000
        while (!sb.contains(want) && System.currentTimeMillis() < end) {
            if (i.available() > 0) sb.append(i.read().toChar()) else Thread.sleep(10)
        }
        assertTrue("\"$want\" in \"$sb\"", sb.contains(want))
        return sb.toString()
    }

    /** a device connecting to the phone's port gets the modem; when it goes online, the call's hung up and the port offered again */
    @Test
    fun aDeviceConnectsToThePhone() {
        val b = BluetoothBridge(ctx, serial).also { bridge = it }
        assertTrue(b.start())
        waitUntil("offering the port") { b.state.value is BluetoothBridge.State.Listening && b.serverSocket != null }
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        val s = shadowOf(b.serverSocket!!).deviceConnected(device)
        waitUntil("connected") { b.state.value is BluetoothBridge.State.Connected }
        assertEquals("bluetooth", serial.client.value?.via)
        shadowOf(s).inputStreamFeeder.apply { write("ATZ\r".toByteArray()); flush() }
        readUntil(shadowOf(s).outputStreamSink, "OK")
        assertEquals("ATZ\r", modem.sent())
        // online, then the device gone: hung up, and the port offered again
        signals.value = LineSignals(ready = true, offhook = true, carrier = true)
        shadowOf(s).inputStreamFeeder.close()
        waitUntil("hung up") { hangUps.get() == 1 }
        waitUntil("offered again") { b.state.value is BluetoothBridge.State.Listening }
        assertEquals(null, serial.client.value)
    }

    /** the phone connects to a paired device and the device gets the modem */
    @Test
    fun thePhoneConnectsToAPairedDevice() {
        val device = adapter.getRemoteDevice("66:77:88:99:AA:BB")
        shadowOf(adapter).setBondedDevices(setOf(device))
        assertEquals(listOf("66:77:88:99:AA:BB"), BluetoothBridge.paired(ctx).map { it.second })
        val b = BluetoothBridge(ctx, serial, connectTo = "66:77:88:99:AA:BB").also { bridge = it }
        assertTrue(b.start())
        waitUntil("connected") { b.state.value is BluetoothBridge.State.Connected && b.connection != null }
        val s = b.connection!!
        shadowOf(s).inputStreamFeeder.apply { write("AT&F\r".toByteArray()); flush() }
        readUntil(shadowOf(s).outputStreamSink, "OK")
        assertEquals("AT&F\r", modem.sent())
    }

    /** Bluetooth off, or its permission denied, fails to start and says why */
    @Test
    fun offOrNotAllowedSaysSo() {
        shadowOf(adapter).setEnabled(false)
        val b = BluetoothBridge(ctx, serial)
        assertTrue(!b.start())
        assertEquals(BluetoothBridge.State.Failed("Bluetooth is off"), b.state.value)
        shadowOf(adapter).setEnabled(true)
        shadowOf(ctx as Application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(!b.start())
        assertTrue((b.state.value as BluetoothBridge.State.Failed).why.contains("permission"))
    }
}
