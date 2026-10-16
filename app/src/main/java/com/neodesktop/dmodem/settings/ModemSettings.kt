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
) {
    /** Enough to start: an account. */
    val complete: Boolean get() = user.isNotBlank() && domain.isNotBlank()

    companion object {
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
        }
    }
}
