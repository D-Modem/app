// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** How the modem faxes. */
data class FaxSettings(
    /** this fax machine's number, as the other one is told (its TSI and CSI: digits, + and spaces) */
    val ident: String = "",
    /** calls that ring are answered as faxes (else they're the terminal's) */
    val answer: Boolean = false,
    /** the rate at most (14400 V.17, 9600 V.29, 4800 V.27ter) */
    val maxBps: Int = 14400,
    /** pages sent at 196 lines to the inch (else 98) */
    val fine: Boolean = true,
    /** error correction mode (T.30 Annex A) when the other machine has it: what a noisy line spoils, sent again */
    val ecm: Boolean = true,
)

private val Context.faxStore: DataStore<Preferences> by preferencesDataStore("fax")

class FaxSettingsStore(private val context: Context) {
    private object K {
        val ident = stringPreferencesKey("ident")
        val answer = booleanPreferencesKey("answer")
        val maxBps = intPreferencesKey("max_bps")
        val fine = booleanPreferencesKey("fine")
        val ecm = booleanPreferencesKey("ecm")
    }

    /** the fax settings stored in [p], the defaults for what isn't */
    private fun read(p: Preferences): FaxSettings {
        val d = FaxSettings()
        return FaxSettings(p[K.ident] ?: d.ident, p[K.answer] ?: d.answer, p[K.maxBps] ?: d.maxBps, p[K.fine] ?: d.fine, p[K.ecm] ?: d.ecm)
    }

    val settings: Flow<FaxSettings> = context.faxStore.data.map { p -> read(p) }

    /** what's stored now */
    suspend fun current(): FaxSettings = settings.first()

    /** a change made to what's stored now (two quick changes don't undo each other) */
    suspend fun update(change: (FaxSettings) -> FaxSettings) {
        context.faxStore.edit { p ->
            val s = change(read(p))
            write(p, s)
        }
    }

    /** [s] into [p] (the ident only digits, + and spaces, 20 at most) */
    private fun write(p: MutablePreferences, s: FaxSettings) {
        p[K.ident] = s.ident.filter { it.isDigit() || it == '+' || it == ' ' }.take(20)
        p[K.answer] = s.answer
        p[K.maxBps] = s.maxBps
        p[K.fine] = s.fine
        p[K.ecm] = s.ecm
    }
}
