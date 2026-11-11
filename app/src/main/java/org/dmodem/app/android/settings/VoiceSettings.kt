// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Voice calls and the answering machine. */
data class VoiceSettings(
    /** the answering machine takes the calls nobody answers (off until the user turns it on) */
    val voicemail: Boolean = false,
    /** after this many rings */
    val rings: Int = 4,
    /** a message this long at most (s) */
    val maxSeconds: Int = 120,
    /** a call's sound on the loudspeaker (else the earpiece) */
    val speaker: Boolean = true,
)

private val Context.voiceStore: DataStore<Preferences> by preferencesDataStore("voice")

class VoiceSettingsStore(private val context: Context) {
    private object K {
        val voicemail = booleanPreferencesKey("voicemail")
        val rings = intPreferencesKey("rings")
        val maxSeconds = intPreferencesKey("max_seconds")
        val speaker = booleanPreferencesKey("speaker")
    }

    /** the settings stored in [p], the defaults for what isn't */
    private fun read(p: Preferences): VoiceSettings {
        val d = VoiceSettings()
        return VoiceSettings(p[K.voicemail] ?: d.voicemail, p[K.rings] ?: d.rings, p[K.maxSeconds] ?: d.maxSeconds, p[K.speaker] ?: d.speaker)
    }

    val settings: Flow<VoiceSettings> = context.voiceStore.data.map { p -> read(p) }

    /** what's stored now */
    suspend fun current(): VoiceSettings = settings.first()

    /** a change made to what's stored now */
    suspend fun update(change: (VoiceSettings) -> VoiceSettings) {
        context.voiceStore.edit { p ->
            val s = change(read(p))
            p[K.voicemail] = s.voicemail
            p[K.rings] = s.rings.coerceIn(1, 10)
            p[K.maxSeconds] = s.maxSeconds.coerceIn(10, 600)
            p[K.speaker] = s.speaker
        }
    }
}
