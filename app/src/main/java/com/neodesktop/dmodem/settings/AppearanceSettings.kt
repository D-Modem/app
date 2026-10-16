// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class DarkMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark"),
}

/**
 * The app's colours (its menus, bars and dialogs; the terminal has its own):
 * the wallpaper's (Android 12 on), or one made from a hue and its chroma.
 */
enum class AppPalette(val label: String, val hue: Double, val chroma: Double) {
    DYNAMIC("Wallpaper", 0.0, 0.0),
    BASELINE("Violet", 300.0, 40.0),
    PHOSPHOR("Phosphor", 140.0, 48.0),
    AMBER("Amber", 70.0, 56.0),
    OCEAN("Ocean", 255.0, 44.0),
    TEAL("Teal", 195.0, 36.0),
    ROSE("Rose", 10.0, 48.0),
    GRAPHITE("Graphite", 250.0, 6.0),
}

data class Appearance(
    val dark: DarkMode = DarkMode.SYSTEM,
    val palette: AppPalette = AppPalette.DYNAMIC,
    /** dark mode's surfaces black (OLED screens) */
    val black: Boolean = false,
)

private val Context.appearanceStore: DataStore<Preferences> by preferencesDataStore("appearance")

class AppearanceStore(private val context: Context) {
    private object K {
        val dark = stringPreferencesKey("dark")
        val palette = stringPreferencesKey("palette")
        val black = booleanPreferencesKey("black")
    }

    val appearance: Flow<Appearance> = context.appearanceStore.data.map { p ->
        val d = Appearance()
        Appearance(
            dark = p[K.dark]?.let { runCatching { DarkMode.valueOf(it) }.getOrNull() } ?: d.dark,
            palette = p[K.palette]?.let { runCatching { AppPalette.valueOf(it) }.getOrNull() } ?: d.palette,
            black = p[K.black] ?: d.black,
        )
    }

    suspend fun save(a: Appearance) {
        context.appearanceStore.edit { p ->
            p[K.dark] = a.dark.name
            p[K.palette] = a.palette.name
            p[K.black] = a.black
        }
    }
}
