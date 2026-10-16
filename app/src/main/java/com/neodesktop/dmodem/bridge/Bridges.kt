// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.bridge

import android.content.Context
import com.neodesktop.dmodem.NativeModem
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.BridgeSettings
import com.neodesktop.dmodem.terminal.TerminalLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The modem's bridges while the modem service runs: TCP and Bluetooth
 * as the settings have them, one client on the modem at a time between
 * them; the modem's lines (DSR, DCD, RI) from its state.
 */
// (the Bluetooth bridge holds the application's context, which lives as long as the process)
@android.annotation.SuppressLint("StaticFieldLeak")
object Bridges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signals = MutableStateFlow(LineSignals())

    val serial = SerialBridge(ModemState.dte, signals.asStateFlow(), ::hangUp)

    private val _tcp = MutableStateFlow<TcpBridge.State>(TcpBridge.State.Stopped)
    val tcpState: StateFlow<TcpBridge.State> = _tcp.asStateFlow()
    private val _bt = MutableStateFlow<BluetoothBridge.State>(BluetoothBridge.State.Stopped)
    val btState: StateFlow<BluetoothBridge.State> = _bt.asStateFlow()

    private var tcp: TcpBridge? = null
    private var tcpJob: Job? = null
    private var bt: BluetoothBridge? = null
    private var btJob: Job? = null
    private var applied: BridgeSettings? = null
    private var watching = false

    /** "RING" from the modem: RI on for a while */
    @Volatile private var ringUntil = 0L

    private fun watch() {
        if (watching) return
        watching = true
        scope.launch {
            combine(ModemState.run, ModemState.status) { r, s -> r to s }.collect { (r, s) -> update(r, s) }
        }
        ModemState.dte.addListener(TerminalLink.Listener { data, n ->
            if (String(data, 0, n, Charsets.ISO_8859_1).contains("RING")) {
                ringUntil = System.currentTimeMillis() + 6_000
                update(ModemState.run.value, ModemState.status.value)
                scope.launch {
                    kotlinx.coroutines.delay(6_100)
                    update(ModemState.run.value, ModemState.status.value)
                }
            }
        })
    }

    private fun update(r: ModemState.Run, s: NativeModem.Status) {
        signals.value = LineSignals(
            ready = r == ModemState.Run.RUNNING,
            offhook = s.offhook,
            carrier = s.link == NativeModem.Link.ONLINE,
            ringing = System.currentTimeMillis() < ringUntil && !s.offhook,
        )
    }

    /** the bridges as [s] has them: those changed started again, the rest left as they are */
    @Synchronized
    fun apply(context: Context, s: BridgeSettings) {
        watch()
        serial.hangUpOnClose = s.hangUpOnClose
        val old = applied
        applied = s
        val tcpSame = old != null && old.tcp == s.tcp && old.tcpPort == s.tcpPort && old.tcpAnyAddress == s.tcpAnyAddress &&
            old.tcpTelnet == s.tcpTelnet && old.tcpPassword == s.tcpPassword
        if (!tcpSame || (s.tcp && tcp == null)) {
            stopTcp()
            if (s.tcp) {
                val t = TcpBridge(serial, s.tcpPort, s.tcpAnyAddress, s.tcpTelnet, s.tcpPassword)
                tcp = t
                tcpJob = scope.launch { t.state.collect { _tcp.value = it } }
                t.start()
            }
        }
        val btSame = old != null && old.bluetooth == s.bluetooth && old.btConnectTo == s.btConnectTo
        if (!btSame || (s.bluetooth && bt == null)) {
            stopBt()
            if (s.bluetooth) {
                val b = BluetoothBridge(context.applicationContext, serial, s.btConnectTo.ifBlank { null })
                bt = b
                btJob = scope.launch { b.state.collect { _bt.value = it } }
                b.start()
            }
        }
    }

    @Synchronized
    fun stopAll() {
        stopTcp()
        stopBt()
        applied = null
    }

    private fun stopTcp() {
        tcp?.stop()
        tcp = null
        tcpJob?.cancel()
        _tcp.value = TcpBridge.State.Stopped
    }

    private fun stopBt() {
        bt?.stop()
        bt = null
        btJob?.cancel()
        _bt.value = BluetoothBridge.State.Stopped
    }

    /**
     * the call hung up as a DTE's DTR going would: online, +++ between its
     * guard times then ATH; dialling or off hook, ATH
     */
    fun hangUp() {
        val dte = ModemState.dte
        if (signals.value.carrier) {
            Thread.sleep(GUARD_MS)
            dte.write("+++")
            Thread.sleep(GUARD_MS)
        }
        dte.write("\rATH\r")
    }

    private const val GUARD_MS = 1200L
}
