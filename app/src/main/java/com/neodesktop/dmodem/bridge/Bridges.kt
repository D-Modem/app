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

    /** the modem as /dev/ttyDM0 in the local Linux: a client of the serial bridge too */
    val tty = ModemTty(serial) { ModemState.log.add("bridge: $it") }

    private val _tcp = MutableStateFlow<TcpBridge.State>(TcpBridge.State.Stopped)
    val tcpState: StateFlow<TcpBridge.State> = _tcp.asStateFlow()
    private val _bt = MutableStateFlow<BluetoothBridge.State>(BluetoothBridge.State.Stopped)
    val btState: StateFlow<BluetoothBridge.State> = _bt.asStateFlow()

    private var tcp: TcpBridge? = null
    private var tcpJob: Job? = null
    private val _usb = MutableStateFlow<UsbBridge.State>(UsbBridge.State.Stopped)
    val usbState: StateFlow<UsbBridge.State> = _usb.asStateFlow()
    private val _usbBoard = MutableStateFlow<PicoBoard?>(null)
    /** the pico-serial board on the USB bridge, while there's one */
    val usbBoard: StateFlow<PicoBoard?> = _usbBoard.asStateFlow()
    private var usbBoardJob: Job? = null
    private val _gadget = MutableStateFlow<UsbGadget.State>(UsbGadget.State.Stopped)
    val gadgetState: StateFlow<UsbGadget.State> = _gadget.asStateFlow()
    private var usb: UsbBridge? = null
    private var usbJob: Job? = null
    private var gadget: UsbGadget? = null
    private var gadgetJob: Job? = null

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
        // the internet (the phone as each client's ISP) or the modem
        val app = context.applicationContext
        serial.host = if (s.internet) {
            { com.neodesktop.dmodem.ppp.PppHost(dnsServers = { phoneDns(app) }, log = { ModemState.log.add("host: $it") }) }
        } else null
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
        val usbSame = old != null && old.usb == s.usb && old.usbBaud == s.usbBaud && old.usbFormat == s.usbFormat &&
            old.usbCarrierOnDtr == s.usbCarrierOnDtr && old.usbDtrHangsUp == s.usbDtrHangsUp
        if (!usbSame || (s.usb && usb == null)) {
            stopUsb()
            if (s.usb) {
                val u = UsbBridge(context.applicationContext, serial, signals.asStateFlow(), format(s.usbBaud, s.usbFormat), s.usbCarrierOnDtr, s.usbDtrHangsUp)
                usb = u
                usbJob = scope.launch { u.state.collect { _usb.value = it } }
                usbBoardJob = scope.launch { u.board.collect { _usbBoard.value = it } }
                u.start()
            }
        }
        if (old?.usbGadget != s.usbGadget || (s.usbGadget && gadget == null)) {
            stopGadget()
            if (s.usbGadget) {
                val g = UsbGadget(serial)
                gadget = g
                gadgetJob = scope.launch { g.state.collect { _gadget.value = it } }
                scope.launch { g.start() }
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
        stopUsb()
        stopGadget()
        applied = null
    }

    private fun stopTcp() {
        tcp?.stop()
        tcp = null
        tcpJob?.cancel()
        _tcp.value = TcpBridge.State.Stopped
    }

    /** [f] done to the pico-serial board on the USB bridge (off the main thread): false when there's none */
    fun withUsbBoard(f: (PicoSerial) -> Boolean) {
        val u = usb ?: return
        scope.launch { u.withBoard(f) }
    }

    private fun stopUsb() {
        usb?.stop()
        usb = null
        usbJob?.cancel()
        usbBoardJob?.cancel()
        _usbBoard.value = null
        _usb.value = UsbBridge.State.Stopped
    }

    private fun stopGadget() {
        val g = gadget
        gadget = null
        gadgetJob?.cancel()
        // (su and a rebind: off the caller's thread)
        if (g != null) scope.launch { g.stop() }
        _gadget.value = UsbGadget.State.Stopped
    }

    /** the phone's DNS servers (its default network's) */
    fun phoneDns(context: Context): List<java.net.InetAddress> {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return emptyList()
        return cm.activeNetwork?.let { cm.getLinkProperties(it) }?.dnsServers.orEmpty()
    }

    /** "8N1" as a format at [baud] */
    fun format(baud: Int, f: String): SerialFormat {
        val m = Regex("([5-8])([NEOMS])([12])").matchEntire(f.uppercase())
        return if (m == null) SerialFormat(baud) else SerialFormat(baud, m.groupValues[1].toInt(), m.groupValues[2][0], m.groupValues[3].toInt())
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
