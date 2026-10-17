// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The dial-up ISP: its number, the PPP login, AT commands before the dial. */
data class IspSettings(
    val number: String = "",
    val user: String = "",
    val password: String = "",
    val initCommands: String = "",
) {
    val complete: Boolean get() = number.isNotBlank()
}

private val Context.ispStore: DataStore<Preferences> by preferencesDataStore("isp")

class IspSettingsStore(private val context: Context) {
    private object K {
        val number = stringPreferencesKey("number")
        val user = stringPreferencesKey("user")
        val password = stringPreferencesKey("password")
        val init = stringPreferencesKey("init")
    }

    val settings: Flow<IspSettings> = context.ispStore.data.map { p ->
        IspSettings(p[K.number] ?: "", p[K.user] ?: "", p[K.password] ?: "", p[K.init] ?: "")
    }

    suspend fun current(): IspSettings = settings.first()

    suspend fun save(s: IspSettings) {
        context.ispStore.edit { p ->
            p[K.number] = s.number.trim()
            p[K.user] = s.user.trim()
            p[K.password] = s.password
            p[K.init] = s.initCommands.trim()
        }
    }
}
