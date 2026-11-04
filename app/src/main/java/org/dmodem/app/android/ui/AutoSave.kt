// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Where settings are saved from: a scope that outlives the screen, so a change made just before
 * the page closes is still stored. (Main.immediate: saves start in the order they're made.)
 */
object SettingsSaving {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

/**
 * Settings saved as they change: [value] stored with [save] a moment ([delayMs]) after its last
 * change, so typing isn't saved a letter at a time, and at once when the screen closes.
 * The value it starts with is taken as already stored. Returns what's stored now.
 */
@Composable
fun <T> rememberAutoSave(value: T, delayMs: Long = 500, save: suspend (T) -> Unit): T {
    var stored by remember { mutableStateOf(value) }
    val latest by rememberUpdatedState(value)
    val saveIt by rememberUpdatedState(save)
    LaunchedEffect(value) {
        if (value == stored) return@LaunchedEffect
        delay(delayMs)
        stored = value
        SettingsSaving.scope.launch { saveIt(value) }
    }
    DisposableEffect(Unit) {
        onDispose {
            val v = latest
            if (v != stored) {
                stored = v
                SettingsSaving.scope.launch { saveIt(v) }
            }
        }
    }
    return stored
}
