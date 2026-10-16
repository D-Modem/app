// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.settings

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
)

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
    }

    val settings: Flow<BridgeSettings> = context.bridgeStore.data.map { from(it) }

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
        )
    }

    suspend fun current(): BridgeSettings = settings.first()

    /** a change made to what's stored now (two quick changes don't undo each other) */
    suspend fun update(change: (BridgeSettings) -> BridgeSettings) {
        context.bridgeStore.edit { p -> write(p, change(from(p))) }
    }

    suspend fun save(s: BridgeSettings) {
        context.bridgeStore.edit { p -> write(p, s) }
    }

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
        }
    }
}
