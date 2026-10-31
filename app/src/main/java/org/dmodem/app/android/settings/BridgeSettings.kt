// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The modem's serial port for other programs and devices: on TCP, on Bluetooth. */
data class BridgeSettings(
    val tcp: Boolean = false,
    val tcpPort: Int = 2323,
    /** other devices on the network may connect (else only this phone's programs) */
    val tcpAnyAddress: Boolean = false,
    /** Telnet, with RFC 2217's port control (else raw) */
    val tcpTelnet: Boolean = false,
    /** asked for first, if set */
    val tcpPassword: String = "",
    val bluetooth: Boolean = false,
    /** a paired device's address to connect to (empty: the phone offers the port) */
    val btConnectTo: String = "",
    /** the client going hangs up the call (DTR dropped) */
    val hangUpOnClose: Boolean = true,
    /** a USB serial adapter plugged into the phone */
    val usb: Boolean = false,
    val usbBaud: Int = 115200,
    /** its character format: data bits, parity (N E O M S), stop bits ("8N1") */
    val usbFormat: String = "8N1",
    /** the adapter's DTR out is the modem's carrier (the computer's DCD, on a null modem cable) */
    val usbCarrierOnDtr: Boolean = true,
    /** the computer's DTR (the adapter's DSR) dropped hangs up */
    val usbDtrHangsUp: Boolean = true,
    /** the phone's own USB port as a serial port to a computer (root) */
    val usbGadget: Boolean = false,
    /** clients get the internet (the phone as their dial-up ISP, PPP, no call) instead of the modem */
    val internet: Boolean = false,
) {
    /** any way for a client to come */
    val anyOn: Boolean get() = tcp || bluetooth || usb || usbGadget
}

private val Context.bridgeStore: DataStore<Preferences> by preferencesDataStore("bridges")

class BridgeSettingsStore(private val context: Context) {
    private object K {
        val tcp = booleanPreferencesKey("tcp")
        val tcpPort = intPreferencesKey("tcp_port")
        val tcpAny = booleanPreferencesKey("tcp_any")
        val tcpTelnet = booleanPreferencesKey("tcp_telnet")
        val tcpPassword = stringPreferencesKey("tcp_password")
        val bt = booleanPreferencesKey("bt")
        val btConnectTo = stringPreferencesKey("bt_connect_to")
        val hangUp = booleanPreferencesKey("hang_up_on_close")
        val usb = booleanPreferencesKey("usb")
        val usbBaud = intPreferencesKey("usb_baud")
        val usbFormat = stringPreferencesKey("usb_format")
        val usbCarrierOnDtr = booleanPreferencesKey("usb_carrier_on_dtr")
        val usbDtrHangsUp = booleanPreferencesKey("usb_dtr_hangs_up")
        val usbGadget = booleanPreferencesKey("usb_gadget")
        val internet = booleanPreferencesKey("internet")
    }

    val settings: Flow<BridgeSettings> = context.bridgeStore.data.map { from(it) }

    /** the bridges' settings stored in [p], the defaults for what isn't */
    private fun from(p: Preferences): BridgeSettings {
        val d = BridgeSettings()
        return BridgeSettings(
            tcp = p[K.tcp] ?: d.tcp,
            tcpPort = p[K.tcpPort] ?: d.tcpPort,
            tcpAnyAddress = p[K.tcpAny] ?: d.tcpAnyAddress,
            tcpTelnet = p[K.tcpTelnet] ?: d.tcpTelnet,
            tcpPassword = p[K.tcpPassword] ?: d.tcpPassword,
            bluetooth = p[K.bt] ?: d.bluetooth,
            btConnectTo = p[K.btConnectTo] ?: d.btConnectTo,
            hangUpOnClose = p[K.hangUp] ?: d.hangUpOnClose,
            usb = p[K.usb] ?: d.usb,
            usbBaud = p[K.usbBaud] ?: d.usbBaud,
            usbFormat = p[K.usbFormat] ?: d.usbFormat,
            usbCarrierOnDtr = p[K.usbCarrierOnDtr] ?: d.usbCarrierOnDtr,
            usbDtrHangsUp = p[K.usbDtrHangsUp] ?: d.usbDtrHangsUp,
            usbGadget = p[K.usbGadget] ?: d.usbGadget,
            internet = p[K.internet] ?: d.internet,
        )
    }

    /** what's stored now */
    suspend fun current(): BridgeSettings = settings.first()

    /** a change made to what's stored now (two quick changes don't undo each other) */
    suspend fun update(change: (BridgeSettings) -> BridgeSettings) {
        context.bridgeStore.edit { p -> write(p, change(from(p))) }
    }

    /** [s] stored as it is */
    suspend fun save(s: BridgeSettings) {
        context.bridgeStore.edit { p -> write(p, s) }
    }

    /** [s] into [p] (the TCP port kept in range, the Bluetooth address trimmed) */
    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: BridgeSettings) {
        run {
            p[K.tcp] = s.tcp
            p[K.tcpPort] = s.tcpPort.coerceIn(1, 65535)
            p[K.tcpAny] = s.tcpAnyAddress
            p[K.tcpTelnet] = s.tcpTelnet
            p[K.tcpPassword] = s.tcpPassword
            p[K.bt] = s.bluetooth
            p[K.btConnectTo] = s.btConnectTo.trim()
            p[K.hangUp] = s.hangUpOnClose
            p[K.usb] = s.usb
            p[K.usbBaud] = s.usbBaud
            p[K.usbFormat] = s.usbFormat
            p[K.usbCarrierOnDtr] = s.usbCarrierOnDtr
            p[K.usbDtrHangsUp] = s.usbDtrHangsUp
            p[K.usbGadget] = s.usbGadget
            p[K.internet] = s.internet
        }
    }
}
