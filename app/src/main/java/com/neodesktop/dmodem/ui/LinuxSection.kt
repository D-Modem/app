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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.local.Distro
import com.neodesktop.dmodem.local.Linux
import com.neodesktop.dmodem.local.LinuxSystem
import com.neodesktop.dmodem.terminal.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Linux systems: those installed (opened, removed), more added, the phone's storage in them. */
@Composable
fun LinuxSection(onOpened: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val work by Linux.work.collectAsStateWithLifecycle()
    val changes by Linux.changes.collectAsStateWithLifecycle()
    var systems by remember { mutableStateOf<List<LinuxSystem>>(emptyList()) }
    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(changes) {
        systems = withContext(Dispatchers.IO) { Linux.systems(context) }
        sizes = withContext(Dispatchers.IO) { systems.associate { it.id to Linux.size(it) } }
    }
    // (the permission comes back from the system's own page: looked at again on return)
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val storage = remember(resumed) { Linux.storageGranted(context) }
    var removing by remember { mutableStateOf<LinuxSystem?>(null) }
    var adding by remember { mutableStateOf(false) }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumed++ }
    val busy = work is Linux.Work.Busy

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Linux", style = MaterialTheme.typography.titleMedium)
        if (Linux.machine() == null) {
            Text("There is no Linux built for this phone's processor.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        if (systems.isEmpty()) {
            Text(
                "Linux in the terminal, run with proot: Alpine, Debian, Ubuntu, Fedora, Arch and more, each with its package manager.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        for (sys in systems) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(sys.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(sizes[sys.id]?.let { formatSize(it) }, sys.info?.source?.substringAfter("://")?.substringBefore('/')).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = {
                            scope.launch {
                                val err = Terminal.openLinux(context, sys.id)
                                if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show() else onOpened()
                            }
                        }) { Text("Open a shell") }
                        TextButton(enabled = !busy, onClick = { removing = sys }) { Text("Remove") }
                    }
                }
            }
        }
        WorkLine(work)
        OutlinedButton(enabled = !busy, onClick = { Linux.clearFailure(); adding = true }) {
            Text(if (systems.isEmpty()) "Install a Linux" else "Add another Linux")
        }
        SwitchRow(
            "The phone's storage at /sdcard",
            if (storage) "Allowed: Linux sees the phone's files (Download, DCIM, …)" else "Needs \"All files access\" for the app",
            storage,
        ) { want ->
            if (want != storage) {
                val i = Linux.storageSettingsIntent(context)
                if (i != null) {
                    runCatching { context.startActivity(i) }
                } else if (want) {
                    askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
        if (systems.isNotEmpty()) {
            Text(
                "In each: /root is home, /sdcard the phone's storage, and /dev/ttyDM0 the modem as a serial port " +
                    "(minicom -D /dev/ttyDM0, pppd, efax: the program that opens it has the modem until it closes it).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    removing?.let { sys ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${sys.name}?") },
            text = { Text("All of it goes: what was installed in it, and the files in its /root. The phone's own storage isn't touched.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    Terminal.sessions.value.filter { it.linuxId == sys.id }.forEach { Terminal.close(it) }
                    Linux.remove(context, sys.id)
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    if (adding) {
        AddLinuxDialog(
            onInstalled = { sys ->
                adding = false
                scope.launch {
                    val err = Terminal.openLinux(context, sys.id)
                    if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show() else onOpened()
                }
            },
            onDismiss = { adding = false },
        )
    }
}

/** what Linux is doing: its step with a progress bar, or why it failed; nothing when idle */
@Composable
private fun WorkLine(work: Linux.Work) {
    when (work) {
        is Linux.Work.Busy -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(work.what, style = MaterialTheme.typography.bodySmall)
            if (work.fraction != null) LinearProgressIndicator(progress = { work.fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is Linux.Work.Failed -> Text("Failed: ${work.why}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Linux.Work.Idle -> {}
    }
}

/**
 * A Linux to install: one of the catalog's (those built for this phone's
 * processor), or a root file system from any URL (with its SHA-256, if
 * known); installed here, then [onInstalled]
 */
@Composable
fun AddLinuxDialog(onInstalled: (LinuxSystem) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val work by Linux.work.collectAsStateWithLifecycle()
    var available by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) { Linux.systems(context).map { it.id }.toSet() }
        available = withContext(Dispatchers.IO) { Linux.available(context) }
    }
    var picked by remember { mutableStateOf<String?>(null) }
    // the install this dialog started: opened when Linux says it's done
    var started by remember { mutableStateOf<String?>(null) }
    val done by Linux.done.collectAsStateWithLifecycle()
    LaunchedEffect(done, started) {
        val id = started
        if (id != null && done == id) {
            started = null
            onInstalled(Linux.system(context, id))
        }
    }
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var customSha by remember { mutableStateOf("") }
    val custom = picked == CUSTOM
    val urlOk = customUrl.trim().let { it.startsWith("https://") || it.startsWith("http://") }
    val shaOk = customSha.isBlank() || customSha.trim().matches(Regex("[0-9a-fA-F]{64}"))
    val busy = work is Linux.Work.Busy
    val ready = !busy && when {
        custom -> customName.isNotBlank() && urlOk && shaOk && Distro.customId(customName) !in installed
        else -> picked != null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install a Linux") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (d in Distro.CATALOG) {
                    val can = available?.get(d.id) != false && d.id !in installed
                    Choice(
                        title = d.name,
                        about = when {
                            d.id in installed -> "Installed"
                            available?.get(d.id) == false -> "Not built for this phone's processor"
                            else -> d.about
                        },
                        selected = picked == d.id, enabled = can && !busy,
                    ) { picked = d.id }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Choice("From a URL…", "A root file system tarball: .tar, .tar.gz or .tar.xz", custom, !busy) { picked = CUSTOM }
                if (custom) {
                    OutlinedTextField(customName, { customName = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        customUrl, { customUrl = it.trim() }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        isError = customUrl.isNotEmpty() && !urlOk,
                    )
                    OutlinedTextField(
                        customSha, { customSha = it.trim() }, label = { Text("SHA-256 (optional, checked if given)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), isError = !shaOk,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    if (Distro.customId(customName) in installed) {
                        Text("One by that name is installed already", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                WorkLine(work)
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = {
                val d = if (custom) {
                    Distro.Custom(Distro.customId(customName), customName.trim(), customUrl.trim(), customSha.trim().ifEmpty { null })
                } else {
                    Distro.byId(picked!!) ?: return@TextButton
                }
                Linux.clearFailure()
                started = d.id
                Linux.install(context, d)
            }) { Text("Install") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (busy) "Hide" else "Cancel") } },
    )
}

/** asked for a Linux shell with more than one installed: which */
@Composable
fun PickLinuxDialog(systems: List<LinuxSystem>, onPick: (LinuxSystem) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open a Linux shell") },
        text = {
            Column {
                for (sys in systems) {
                    TextButton(onClick = { onPick(sys) }, modifier = Modifier.fillMaxWidth()) {
                        Text(sys.name, Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("Install another…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** a radio button with a title and a line about it, greyed when not [enabled] */
@Composable
private fun Choice(title: String, about: String, selected: Boolean, enabled: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onPick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val CUSTOM = "\u0000custom"

/** a size in GB, MB or KB */
private fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}
