// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.peer

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.neodesktop.dmodem.NativeModem
import com.neodesktop.dmodem.settings.ModemSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import com.neodesktop.dmodem.io.Sink
import com.neodesktop.dmodem.io.Source
import com.neodesktop.dmodem.io.asSink
import com.neodesktop.dmodem.io.asSource

/** the native modem's line socket as a [Duplex] (closing it wakes a read blocked on it) */
class FdDuplex(private val pfd: ParcelFileDescriptor, override val name: String = "modem") : Duplex {
    override val input: Source = FileInputStream(pfd.fileDescriptor).asSource()
    override val output: Sink = FileOutputStream(pfd.fileDescriptor).asSink()
    /** the socket shut down both ways (waking a blocked read), then closed */
    override fun close() {
        runCatching { Os.shutdown(pfd.fileDescriptor, OsConstants.SHUT_RDWR) }
        pfd.close()
    }
}

/**
 * The modem's line to another phone while the modem runs on one (the
 * settings' line Wi-Fi or Bluetooth): the [PeerLine] on its transport,
 * offered on the network (NSD) when it takes calls on Wi-Fi.
 */
@SuppressLint("StaticFieldLeak") // (the application's context)
object Peers {
    private var line: PeerLine? = null
    private var nsd: NsdManager.RegistrationListener? = null
    private var context: Context? = null

    private val _state = MutableStateFlow<PeerLine.State>(PeerLine.State.Off)
    val state: StateFlow<PeerLine.State> = _state.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** the line for [s]; false (and [error]) if it can't take calls as asked */
    @Synchronized
    fun start(context: Context, s: ModemSettings, log: (String) -> Unit): Boolean {
        stop()
        val app = context.applicationContext
        this.context = app
        _error.value = null
        val wifi = s.line == ModemSettings.LINE_WIFI
        val cell = s.line == ModemSettings.LINE_CELL
        val transport: PeerTransport = when {
            wifi -> TcpPeerTransport(s.peerPort)
            cell -> com.neodesktop.dmodem.cell.CellTransport(
                com.neodesktop.dmodem.cell.AndroidCellPhone(app),
                com.neodesktop.dmodem.cell.AndroidCallAudio(app),
                log = { log("mobile call: $it") },
            )
            else -> BluetoothPeerTransport(app)
        }
        val l = PeerLine(
            transport,
            events = { NativeModem.peerEvent(it) },
            target = { dial ->
                when {
                    wifi -> PeerLine.wifiTarget(dial, s.peerAddress)
                    // (the number dialled, as a phone's: digits, + * #)
                    cell -> dial.filter { it.isDigit() || it in "+*#" }.ifEmpty { null }
                    else -> s.peerAddress.ifBlank { null }
                }
            },
            log = { log("line: $it") },
            // (a mobile call has the phone network's own: none of ours)
            pin = if (cell) "" else s.peerPin.trim(),
            _state = _state,
            _lastError = _error,
        )
        line = l
        val ok = try {
            l.start(listen = s.listen)
            true
        } catch (e: Exception) {
            _error.value = "can't take calls on ${transport.label}: ${e.message ?: e}"
            log(_error.value!!)
            l.start(listen = false)
            false
        }
        if (wifi && s.listen && ok) advertise(app, s.peerPort)
        return ok
    }

    /** the line stopped and no longer offered on the network */
    @Synchronized
    fun stop() {
        line?.stop()
        line = null
        nsd?.let { l -> runCatching { context?.getSystemService(NsdManager::class.java)?.unregisterService(l) } }
        nsd = null
        _state.value = PeerLine.State.Off
    }

    /** (the screenshots: a state shown) */
    internal fun show(st: PeerLine.State) { _state.value = st }

    /** the modem off hook: its line (from the native modem's thread) */
    fun lineStarted(caller: Boolean, dial: String, pfd: ParcelFileDescriptor) {
        val l = line
        if (l == null) {
            pfd.close()
            return
        }
        l.lineStarted(caller, dial, FdDuplex(pfd))
    }

    /** this phone on the network as one that takes calls */
    private fun advertise(context: Context, port: Int) {
        val m = context.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "D-Modem on ${Build.MODEL}"
            serviceType = TcpPeerTransport.SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            /** registered: nothing to do */
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            /** not registered: nothing to do (the line still takes calls by address) */
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
            /** unregistered: nothing to do */
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            /** not unregistered: nothing to do */
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        runCatching { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onSuccess { nsd = l }
    }
}

/**
 * Phones with the app taking calls on this network (NSD): each found
 * to [found] as (its name, host:port), until [stop].
 */
class PeerFinder(context: Context, private val found: (String, String) -> Unit) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null

    /** the search started: each phone found resolved, then to [found] */
    fun start() {
        val m = nsd ?: return
        val l = object : NsdManager.DiscoveryListener {
            /** started: nothing to do */
            override fun onDiscoveryStarted(type: String) {}
            /** stopped: nothing to do */
            override fun onDiscoveryStopped(type: String) {}
            /** not started: nothing found */
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            /** not stopped: nothing to do */
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            /** one gone: left in the list (nothing to do) */
            override fun onServiceLost(info: NsdServiceInfo) {}
            /** one found: resolved, for its address */
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                runCatching {
                    m.resolveService(info, object : NsdManager.ResolveListener {
                        /** not resolved: left out */
                        override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                        /** its host (bracketed, if IPv6) and port to [found], with its name */
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = i.host?.hostAddress ?: return
                            val h = if (host.contains(':')) "[$host]" else host
                            found(i.serviceName, "$h:${i.port}")
                        }
                    })
                }
            }
        }
        runCatching { m.discoverServices(TcpPeerTransport.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }.onSuccess { listener = l }
    }

    /** the search stopped */
    fun stop() {
        listener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        listener = null
    }
}
