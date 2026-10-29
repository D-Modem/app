// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.UUID

/**
 * The modem as a Bluetooth serial port (SPP, RFCOMM): either the phone
 * offers it and a computer (or a Bluetooth serial adapter set to connect)
 * connects to it, or the phone connects to a paired device's serial port
 * (an HC-05 on a vintage computer's serial port, say), again whenever
 * the connection drops while it's on.
 */
@SuppressLint("MissingPermission") // (checked: hasPermission())
class BluetoothBridge(
    private val context: Context,
    private val bridge: SerialBridge,
    /** null: the phone offers the port; else the paired device's address it connects to */
    val connectTo: String? = null,
) {
    sealed interface State {
        data object Stopped : State
        /** offering the port, under [name] */
        data class Listening(val name: String) : State
        data class Connecting(val device: String) : State
        data class Connected(val device: String) : State
        data class Failed(val why: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var running = false
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var thread: Thread? = null

    /** (for the tests: the port offered, the connection there is) */
    internal val serverSocket: BluetoothServerSocket? get() = server
    internal val connection: BluetoothSocket? get() = socket

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** the bridge's thread started (true), or why not in [state] (false) */
    fun start(): Boolean {
        if (running) return true
        val a = adapter
        when {
            a == null -> return fail("this phone has no Bluetooth")
            !hasPermission(context) -> return fail("Bluetooth isn't allowed (the app's permission)")
            !a.isEnabled -> return fail("Bluetooth is off")
        }
        running = true
        thread = Thread({
            try {
                if (connectTo == null) serve(a!!) else connect(a!!, connectTo)
            } catch (e: Exception) {
                // (a SecurityException: the permission taken back; anything else the stack throws)
                running = false
                fail(e.message ?: e.toString())
            }
        }, "bt-bridge").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** the bridge stopped: its sockets closed, its thread joined */
    fun stop() {
        running = false
        runCatching { server?.close() }
        runCatching { socket?.close() }
        thread?.join(2000)
        thread = null
        _state.value = State.Stopped
    }

    /** [why] in [state]; false, for [start] */
    private fun fail(why: String): Boolean {
        _state.value = State.Failed(why)
        return false
    }

    /** the port offered: each client served in turn */
    private fun serve(a: BluetoothAdapter) {
        while (running) {
            val s = try {
                a.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP)
            } catch (e: IOException) {
                fail("can't offer the port: ${e.message}")
                running = false
                return
            }
            server = s
            _state.value = State.Listening(runCatching { a.name }.getOrNull() ?: "this phone")
            val c = try { s.accept() } catch (_: IOException) { null }
            runCatching { s.close() }
            server = null
            if (c == null) continue
            serveSocket(c)
        }
    }

    /** a paired device's port: connected, again after it drops (a little later each time it fails) */
    private fun connect(a: BluetoothAdapter, address: String) {
        val d = try { a.getRemoteDevice(address) } catch (e: IllegalArgumentException) {
            fail("not a Bluetooth address: $address")
            running = false
            return
        }
        var wait = 2_000L
        while (running) {
            val label = runCatching { d.name }.getOrNull() ?: address
            _state.value = State.Connecting(label)
            val s = try {
                runCatching { a.cancelDiscovery() }
                d.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
            } catch (e: IOException) {
                _state.value = State.Failed("$label: ${e.message ?: "no connection"}")
                null
            } catch (e: RuntimeException) {
                _state.value = State.Failed("$label: $e")
                null
            }
            if (s != null) {
                wait = 2_000L
                serveSocket(s, label)
            }
            if (!running) break
            try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
            wait = (wait * 2).coerceAtMost(60_000L)
        }
    }

    /** [c] served by the bridge until it closes, or told BUSY if the modem's in use; [known] its name */
    private fun serveSocket(c: BluetoothSocket, known: String? = null) {
        val d = c.remoteDevice
        val name = known ?: runCatching { d?.name }.getOrNull() ?: d?.address ?: "a device"
        socket = c
        val p = Port("bluetooth", name, c.inputStream, c.outputStream) { c.close() }
        if (!bridge.tryClaim(p)) {
            runCatching {
                c.outputStream.write("\r\nBUSY: the modem is in use\r\n".toByteArray())
                c.close()
            }
            socket = null
            return
        }
        _state.value = State.Connected(name)
        try {
            bridge.serve(p, null)
        } finally {
            socket = null
        }
    }

    companion object {
        /** the Serial Port Profile's service class */
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "D-Modem serial port"

        /** the permission a bridge needs (Android 12 on: BLUETOOTH_CONNECT, asked for) */
        fun permissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

        /** whether the app has [permissions] */
        fun hasPermission(context: Context): Boolean =
            permissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        /** the paired devices: (name, address) */
        fun paired(context: Context): List<Pair<String, String>> {
            if (!hasPermission(context)) return emptyList()
            val a = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
            return runCatching {
                a.bondedDevices.map { (it.name ?: it.address) to it.address }.sortedBy { it.first.lowercase() }
            }.getOrDefault(emptyList())
        }
    }
}
