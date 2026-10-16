// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.modem.ModemState

/** The modem's and SIP's log, the last lines (their levels in the settings). */
@Composable
fun LogScreen(modifier: Modifier = Modifier) {
    val lines by ModemState.log.flow.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1)
    }
    Column(modifier.fillMaxSize().padding(8.dp)) {
        OutlinedButton(onClick = { ModemState.log.clear() }) { Text("Clear") }
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(lines) { l ->
                Text(l, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
