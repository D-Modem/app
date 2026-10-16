// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.local.Linux
import com.neodesktop.dmodem.terminal.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Linux system: installed or not, its shell, the phone's storage in it, its removal. */
@Composable
fun LinuxSection(onOpened: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val work by Linux.work.collectAsStateWithLifecycle()
    val changes by Linux.changes.collectAsStateWithLifecycle()
    val system = remember(changes) { Linux.system(context) }
    val installed = remember(changes, work) { system.installed }
    var size by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(changes, installed) { size = if (installed) withContext(Dispatchers.IO) { Linux.size(context) } else null }
    // (the permission comes back from the system's own page: looked at again on return)
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val storage = remember(resumed) { Linux.storageGranted(context) }
    var removing by remember { mutableStateOf(false) }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumed++ }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Linux", style = MaterialTheme.typography.titleMedium)
        if (Linux.arch() == null) {
            Text("There is no Alpine Linux for this phone's processor.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Text(
            if (installed) "Alpine Linux ${system.version ?: ""} · ${size?.let { formatSize(it) } ?: "…"}"
            else "Alpine Linux in the terminal, run with proot: a root shell, and apk for its thousands of packages (about 3 MB to download).",
            style = MaterialTheme.typography.bodySmall,
        )
        when (val w = work) {
            is Linux.Work.Busy -> {
                Text(w.what, style = MaterialTheme.typography.bodyMedium)
                if (w.fraction != null) LinearProgressIndicator(progress = { w.fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is Linux.Work.Failed -> Text("Failed: ${w.why}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Linux.Work.Idle -> {}
        }
        val busy = work is Linux.Work.Busy
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (installed) {
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        val err = Terminal.openLinux(context)
                        if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show() else onOpened()
                    }
                }) { Text("Open a shell") }
                OutlinedButton(enabled = !busy, onClick = { removing = true }) { Text("Remove") }
            } else {
                Button(enabled = !busy, onClick = { Linux.clearFailure(); Linux.install(context) }) { Text("Install Alpine Linux") }
            }
        }
        SwitchRow(
            "The phone's storage at /sdcard",
            if (storage) "Allowed: Linux sees the phone's files (Download, DCIM, …)" else "Needs \"All files access\" for the app",
            storage,
        ) { want ->
            if (want && !storage) {
                val i = Linux.storageSettingsIntent(context)
                if (i != null) {
                    runCatching { context.startActivity(i) }
                } else {
                    askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            } else if (!want && storage) {
                // (only the system takes it back)
                Linux.storageSettingsIntent(context)?.let { runCatching { context.startActivity(it) } }
            }
        }
        if (installed) {
            Text(
                "In it: /root is home, /sdcard the phone's storage. \"apk add python3\" and the like add programs.",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Default,
            )
        }
    }

    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove Alpine Linux?") },
            text = { Text("All of it goes: what was installed with apk, and the files in /root. The phone's own storage isn't touched.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = false
                    Terminal.sessions.value.filter { it.kind == com.neodesktop.dmodem.terminal.TerminalSession.Kind.LINUX }.forEach { Terminal.close(it) }
                    Linux.remove(context)
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

/** asked for a Linux shell before Linux is installed: installed here, then opened */
@Composable
fun LinuxInstallDialog(onInstalled: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val work by Linux.work.collectAsStateWithLifecycle()
    val changes by Linux.changes.collectAsStateWithLifecycle()
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(changes) {
        if (started && Linux.system(context).installed) onInstalled()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install Alpine Linux?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A small Linux (about 3 MB to download) to run in the terminal, with apk to add programs: python3, git, vim, gcc and thousands more.")
                when (val w = work) {
                    is Linux.Work.Busy -> {
                        Text(w.what, style = MaterialTheme.typography.bodySmall)
                        if (w.fraction != null) LinearProgressIndicator(progress = { w.fraction }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is Linux.Work.Failed -> Text("Failed: ${w.why}", color = MaterialTheme.colorScheme.error)
                    Linux.Work.Idle -> {}
                }
            }
        },
        confirmButton = {
            TextButton(enabled = work !is Linux.Work.Busy, onClick = {
                started = true
                Linux.clearFailure()
                Linux.install(context)
            }) { Text(if (work is Linux.Work.Failed) "Try again" else "Install") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (work is Linux.Work.Busy) "Hide" else "Cancel") } },
    )
}

private fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}
