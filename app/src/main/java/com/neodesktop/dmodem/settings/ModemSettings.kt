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

/** The modem's settings: the SIP account and how the modem runs. */
data class ModemSettings(
    /** the SIP account: user, password, registrar or proxy (host[:port]) */
    val user: String = "",
    val password: String = "",
    val domain: String = "",
    /** the local SIP port */
    val sipPort: Int = 5060,
    /** register, and answer calls (RING on the DTE, ATA or ATS0=n) */
    val listen: Boolean = false,
    /** fax calls may go over to T.38 */
    val t38: Boolean = true,
    /** the modem's country by name (empty: USA) */
    val country: String = "",
    /** AT commands sent to the modem each time it starts (empty: none) */
    val initCommands: String = "ATX3",
    /** the modem's log level (0 errors .. 5), SIP's (0 none .. 4 the SIP messages) */
    val logLevel: Int = 0,
    val sipLogLevel: Int = 1,
    /** the modem's line: a SIP account, or another phone with the app over Wi-Fi or Bluetooth */
    val line: String = LINE_SIP,
    /** the other phone to call: host[:port] on Wi-Fi, a paired phone's address on Bluetooth */
    val peerAddress: String = "",
    /** the paired phone's name, to show */
    val peerName: String = "",
    /** the TCP port phones call each other on */
    val peerPort: Int = 5070,
    /** the modem's speaker on the phone's: the dialling and the handshake heard (as ATM and ATL have it) */
    val speaker: Boolean = true,
    /** the modulation on mobile calls (+MS): V21 (300 bit/s, what a speech codec carries), V22, V22B */
    val cellSpeed: String = "V21",
) {
    /** the line is another phone (or this phone's mobile calls), not SIP: the app carries its samples */
    val peer: Boolean get() = line != LINE_SIP

    /** Enough to start: an account (or the line another phone's). */
    val complete: Boolean get() = peer || (user.isNotBlank() && domain.isNotBlank())

    /** the commands sent as the modem starts: the init commands, on mobile calls the modulation first */
    fun startCommands(): String {
        val init = initCommands.trim()
        if (line != LINE_CELL) return init
        val ms = "AT+MS=$cellSpeed"
        return when {
            init.isEmpty() -> ms
            init.startsWith("AT", ignoreCase = true) -> "$ms;" + init.substring(2)
            else -> init.lines().joinToString("\r") + "\r" + ms
        }
    }

    companion object {
        const val LINE_SIP = "sip"
        const val LINE_WIFI = "wifi"
        const val LINE_BLUETOOTH = "bluetooth"
        const val LINE_CELL = "cell"

        /**
         * An account as `user:password@host[:port]` (dmodemd's SIP_LOGIN): null if it isn't one.
         * The password may have an @: the domain is after the last.
         */
        fun parseLogin(login: String): Triple<String, String, String>? {
            val at = login.lastIndexOf('@')
            val colon = login.indexOf(':')
            if (at < 0 || colon < 0 || colon > at) return null
            val user = login.substring(0, colon)
            val domain = login.substring(at + 1)
            if (user.isEmpty() || domain.isEmpty()) return null
            return Triple(user, login.substring(colon + 1, at), domain)
        }
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("modem")

/** The settings in the app's DataStore (its private storage). */
class SettingsStore(private val context: Context) {

    private object Keys {
        val user = stringPreferencesKey("user")
        val password = stringPreferencesKey("password")
        val domain = stringPreferencesKey("domain")
        val sipPort = intPreferencesKey("sip_port")
        val listen = booleanPreferencesKey("listen")
        val t38 = booleanPreferencesKey("t38")
        val country = stringPreferencesKey("country")
        val initCommands = stringPreferencesKey("init_commands")
        val logLevel = intPreferencesKey("log_level")
        val sipLogLevel = intPreferencesKey("sip_log_level")
        val line = stringPreferencesKey("line")
        val peerAddress = stringPreferencesKey("peer_address")
        val peerName = stringPreferencesKey("peer_name")
        val peerPort = intPreferencesKey("peer_port")
        val speaker = booleanPreferencesKey("speaker")
        val cellSpeed = stringPreferencesKey("cell_speed")
    }

    val settings: Flow<ModemSettings> = context.dataStore.data.map { p ->
        val d = ModemSettings()
        ModemSettings(
            user = p[Keys.user] ?: d.user,
            password = p[Keys.password] ?: d.password,
            domain = p[Keys.domain] ?: d.domain,
            sipPort = p[Keys.sipPort] ?: d.sipPort,
            listen = p[Keys.listen] ?: d.listen,
            t38 = p[Keys.t38] ?: d.t38,
            country = p[Keys.country] ?: d.country,
            initCommands = p[Keys.initCommands] ?: d.initCommands,
            logLevel = p[Keys.logLevel] ?: d.logLevel,
            sipLogLevel = p[Keys.sipLogLevel] ?: d.sipLogLevel,
            line = p[Keys.line] ?: d.line,
            peerAddress = p[Keys.peerAddress] ?: d.peerAddress,
            peerName = p[Keys.peerName] ?: d.peerName,
            peerPort = p[Keys.peerPort] ?: d.peerPort,
            speaker = p[Keys.speaker] ?: d.speaker,
            cellSpeed = p[Keys.cellSpeed] ?: d.cellSpeed,
        )
    }

    suspend fun current(): ModemSettings = settings.first()

    suspend fun save(s: ModemSettings) {
        context.dataStore.edit { p ->
            p[Keys.user] = s.user.trim()
            p[Keys.password] = s.password
            p[Keys.domain] = s.domain.trim()
            p[Keys.sipPort] = s.sipPort
            p[Keys.listen] = s.listen
            p[Keys.t38] = s.t38
            p[Keys.country] = s.country.trim()
            p[Keys.initCommands] = s.initCommands.trim()
            p[Keys.logLevel] = s.logLevel
            p[Keys.sipLogLevel] = s.sipLogLevel
            p[Keys.line] = s.line
            p[Keys.peerAddress] = s.peerAddress.trim()
            p[Keys.peerName] = s.peerName
            p[Keys.peerPort] = s.peerPort
            p[Keys.speaker] = s.speaker
            p[Keys.cellSpeed] = s.cellSpeed
        }
    }
}
