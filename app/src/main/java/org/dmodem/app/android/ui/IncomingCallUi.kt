// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.dmodem.app.android.modem.IncomingCalls

/**
 * A call ringing in, over whatever the app shows: who's calling (caller
 * ID's name and number, or the line's name for the caller), and what to
 * do: answer it (the terminal, ATA: with adaptive answer a fax machine's
 * call goes to the fax receiver by itself), answer
 * it as a fax (without adaptive answer), or let it ring (out of sight
 * until it's over).
 */
@Composable
fun IncomingCallDialog(
    call: IncomingCalls.Call,
    /** the line's word for a caller it doesn't name */
    caller: String,
    onAnswer: () -> Unit,
    onFax: () -> Unit,
    onIgnore: () -> Unit,
    /** answer as fax offered (adaptive answer off: a plain answer is a data call's) */
    offerFax: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onIgnore,
        icon = { Icon(Icons.Filled.Call, null) },
        title = { Text("Incoming call") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(call.who ?: caller, style = MaterialTheme.typography.titleMedium)
                call.detail?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                Text(
                    "Calling the modem" + if (call.rings > 1) " (${call.rings} rings)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!offerFax) {
                    Text("A fax machine's call goes to the fax receiver by itself", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onAnswer, Modifier.fillMaxWidth()) { Text("Answer") }
                if (offerFax) OutlinedButton(onClick = onFax, Modifier.fillMaxWidth()) { Text("Answer as fax") }
                TextButton(
                    onClick = onIgnore,
                    Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Ignore") }
            }
        },
    )
}
