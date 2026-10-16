// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.settings.AppPalette
import com.neodesktop.dmodem.settings.Appearance
import com.neodesktop.dmodem.settings.AppearanceStore
import com.neodesktop.dmodem.settings.DarkMode
import kotlinx.coroutines.launch

/** The app's look: light, dark or the system's; its colours. Each change shown at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSection() {
    val context = LocalContext.current
    val store = remember { AppearanceStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val current by store.appearance.collectAsStateWithLifecycle(initialValue = null)
    val a = current ?: return
    fun set(n: Appearance) {
        scope.launch { store.save(n) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Text("Theme", style = MaterialTheme.typography.labelMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            DarkMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = a.dark == m,
                    onClick = { set(a.copy(dark = m)) },
                    shape = SegmentedButtonDefaults.itemShape(i, DarkMode.entries.size),
                ) { Text(m.label, maxLines = 1) }
            }
        }
        Text("Colours", style = MaterialTheme.typography.labelMedium)
        val palettes = AppPalette.entries.filter { it != AppPalette.DYNAMIC || Build.VERSION.SDK_INT >= 31 }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in palettes) {
                val selected = a.palette == p || (a.palette == AppPalette.DYNAMIC && p == AppPalette.BASELINE && Build.VERSION.SDK_INT < 31)
                Swatch(p, selected) { set(a.copy(palette = p)) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Black background")
                Text("In dark mode (for OLED screens)", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = a.black, onCheckedChange = { set(a.copy(black = it)) })
        }
    }
}

@Composable
private fun Swatch(p: AppPalette, selected: Boolean, onPick: () -> Unit) {
    val context = LocalContext.current
    val (main, second) = if (p == AppPalette.DYNAMIC && Build.VERSION.SDK_INT >= 31) {
        val s = dynamicLightColorScheme(context)
        s.primary to s.tertiaryContainer
    } else {
        Palettes.swatch(p) to Palettes.scheme(p, dark = false).tertiaryContainer
    }
    Column(
        Modifier.width(64.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onPick).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(second)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(main), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Text(p.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
    }
}
