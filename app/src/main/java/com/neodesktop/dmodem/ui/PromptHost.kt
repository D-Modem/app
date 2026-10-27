// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.ssh.Answer
import com.neodesktop.dmodem.ssh.Prompt
import com.neodesktop.dmodem.terminal.Terminal

/** The connections' questions to the user, as dialogs (over whatever screen is up). */
@Composable
fun PromptHost() {
    val p by Terminal.prompts.current.collectAsStateWithLifecycle()
    when (val q = p) {
        null -> {}
        is Prompt.HostKey -> HostKeyDialog(q)
        is Prompt.Password -> PasswordDialog(q)
        is Prompt.Questions -> QuestionsDialog(q)
        is Prompt.Confirm -> AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false),
            onDismissRequest = { Terminal.prompts.answer(q, Answer()) },
            title = { Text(q.title) },
            text = { Text(q.message) },
            confirmButton = { TextButton(onClick = { Terminal.prompts.answer(q, Answer(accepted = true)) }) { Text(q.yes) } },
            dismissButton = { TextButton(onClick = { Terminal.prompts.answer(q, Answer()) }) { Text("Refuse") } },
        )
    }
}

/** a new or changed host key's question: its fingerprint (and the kept one's, if changed); accept and keep, once, or cancel */
@Composable
private fun HostKeyDialog(q: Prompt.HostKey) {
    val changed = q.oldFingerprint != null
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = { Terminal.prompts.answer(q, Answer(accepted = false)) },
        title = { Text(q.title, color = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val where = if (q.port == 22) q.host else "${q.host}:${q.port}"
                if (changed) {
                    Text(
                        "The key $where presents is not the one kept for it. Someone may be in the middle of the " +
                            "connection, or the server's key was replaced. Don't connect unless you know which.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text("Kept:", fontWeight = FontWeight.Bold)
                    Text(q.oldFingerprint ?: "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("Now:", fontWeight = FontWeight.Bold)
                } else {
                    Text("$where hasn't been seen before. Check that its key's fingerprint is the server's:")
                }
                Text(q.keyType, style = MaterialTheme.typography.labelMedium)
                Text(q.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { Terminal.prompts.answer(q, Answer(accepted = true, remember = true)) }) {
                Text(if (changed) "Replace and connect" else "Accept and keep")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { Terminal.prompts.answer(q, Answer(accepted = true, remember = false)) }) { Text("Once") }
                TextButton(onClick = { Terminal.prompts.answer(q, Answer(accepted = false)) }) { Text("Cancel") }
            }
        },
    )
}

/** a password asked for, with Remember when it can be kept */
@Composable
private fun PasswordDialog(q: Prompt.Password) {
    var text by remember(q) { mutableStateOf("") }
    var remember by remember(q) { mutableStateOf(false) }
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = { Terminal.prompts.answer(q, Answer()) },
        title = { Text(q.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(q.message)
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (q.canRemember) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = remember, role = Role.Checkbox) { remember = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = remember, onCheckedChange = null)
                        Text("Remember (kept encrypted on this device)")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { Terminal.prompts.answer(q, Answer(accepted = true, text = text, remember = remember)) }) { Text("Log in") }
        },
        dismissButton = { TextButton(onClick = { Terminal.prompts.answer(q, Answer()) }) { Text("Cancel") } },
    )
}

/** the server's questions, each in its field (hidden unless it echoes) */
@Composable
private fun QuestionsDialog(q: Prompt.Questions) {
    val answers = remember(q) { q.questions.map { mutableStateOf("") } }
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = { Terminal.prompts.answer(q, Answer()) },
        title = { Text(q.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (q.instruction.isNotBlank()) Text(q.instruction)
                for ((i, pair) in q.questions.withIndex()) {
                    val (question, echo) = pair
                    var a by answers[i]
                    OutlinedTextField(
                        value = a, onValueChange = { a = it }, label = { Text(question.trim()) }, singleLine = true,
                        visualTransformation = if (echo) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { Terminal.prompts.answer(q, Answer(accepted = true, texts = answers.map { it.value })) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { Terminal.prompts.answer(q, Answer()) }) { Text("Cancel") } },
    )
}
