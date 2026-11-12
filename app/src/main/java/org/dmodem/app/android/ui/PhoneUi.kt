// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dmodem.app.android.modem.ModemState
import org.dmodem.app.android.ppp.DialIn
import org.dmodem.app.android.settings.FaxSettingsStore
import org.dmodem.app.android.settings.SettingsStore
import org.dmodem.app.android.settings.VoiceSettingsStore
import org.dmodem.app.android.voice.Voice
import org.dmodem.app.io.monotonicMs
import org.dmodem.app.jobs.VoiceJob
import java.text.DateFormat
import java.util.Date

/** [then] run once the microphone is allowed (asked for if it isn't yet); a toast if it's refused */
@Composable
fun rememberMicrophone(): ((() -> Unit) -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending
        pending = null
        if (ok) p?.invoke() else Toast.makeText(context, "A call needs the microphone", Toast.LENGTH_LONG).show()
    }
    return { then ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            then()
        } else {
            pending = then
            ask.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** [why] (a call that couldn't start) in a toast, if any */
private fun toast(context: Context, why: String?) {
    if (why != null) Toast.makeText(context, why, Toast.LENGTH_LONG).show()
}

/** The home screen's phone: call a number, the call as it goes, the answering machine's messages. */
@Composable
fun PhoneSection(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val state by Voice.state.collectAsStateWithLifecycle()
    val run by ModemState.run.collectAsStateWithLifecycle()
    val ringing by ModemState.incoming.state.collectAsStateWithLifecycle()
    val messages by Voice.messages.collectAsStateWithLifecycle()
    val store = remember { VoiceSettingsStore(context.applicationContext) }
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val modem by remember { SettingsStore(context.applicationContext) }.settings.collectAsStateWithLifecycle(initialValue = null)
    val mic = rememberMicrophone()
    var callDialog by remember { mutableStateOf(false) }
    var sipDialog by remember { mutableStateOf(false) }
    val on = run == ModemState.Run.RUNNING
    val free = on && state?.running != true

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state?.let { VoiceCallCard(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // (anonymous SIP has no number to call: a SIP address alone)
            if (modem?.let { it.sip && it.anonymous } != true) {
                Button(onClick = { callDialog = true }, enabled = free) { Text("Call a number…") }
            }
            if (modem?.sip == true) {
                Button(onClick = { sipDialog = true }, enabled = free) { Text("Call a SIP address…") }
            }
            if (ringing != null && free) {
                OutlinedButton(onClick = { mic { toast(context, Voice.answer(context)) } }) { Text("Answer") }
                OutlinedButton(onClick = { toast(context, Voice.takeMessage(context)) }) { Text("Take a message") }
            }
        }
        if (!on) Text("The modem is off: start it to call", style = MaterialTheme.typography.bodySmall)
        settings?.let { s ->
            Text(
                if (s.voicemail) "Answering machine on: it answers after ${s.rings} rings" else "Answering machine off",
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!s.voicemail && messages.isEmpty()) {
                TextButton(onClick = onOpenSettings) { Text("Set up the answering machine") }
            }
        }
        for (m in messages.take(5)) MessageRow(m)
        if (messages.size > 5) {
            TextButton(onClick = onOpenSettings) { Text("All ${messages.size} messages") }
        }
    }
    if (callDialog) {
        CallDialog(onDismiss = { callDialog = false }) { number ->
            callDialog = false
            mic { toast(context, Voice.dial(context, number)) }
        }
    }
    if (sipDialog) {
        CallDialog(sip = true, onDismiss = { sipDialog = false }) { address ->
            sipDialog = false
            mic { toast(context, Voice.dial(context, sipAddress(address))) }
        }
    }
}

/** the call now or just ended: who, its phase or outcome, its time; hang up, mute, the loudspeaker, the keypad, or listen and pick up */
@Composable
private fun VoiceCallCard(st: VoiceJob.State) {
    val listening by Voice.listening.collectAsStateWithLifecycle()
    val muted by Voice.muted.collectAsStateWithLifecycle()
    val speaker by Voice.speaker.collectAsStateWithLifecycle()
    val mic = rememberMicrophone()
    var keypad by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(monotonicMs()) }
    LaunchedEffect(st.running) {
        while (st.running) {
            now = monotonicMs()
            delay(500)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val who = st.number.ifEmpty { if (st.calling) "the number" else "an unknown caller" }
            Text(
                when {
                    st.machine -> "Answering machine: $who"
                    st.calling -> "Call to $who"
                    else -> "Call from $who"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            val time = if (st.since > 0 && st.running) " · " + clock((now - st.since) / 1000) else ""
            Text(
                when {
                    !st.running && st.message.isNotEmpty() -> "Took a message of ${st.messageSeconds} s"
                    !st.running && st.result == "" -> if (st.machine) "No message left" else "Call ended"
                    !st.running -> "Stopped: ${st.result}"
                    else -> when (st.phase) {
                        VoiceJob.Phase.STARTING -> "Starting"
                        VoiceJob.Phase.DIALLING -> "Calling…"
                        VoiceJob.Phase.ANSWERING -> "Answering"
                        VoiceJob.Phase.TALKING -> "In a call" + if (muted) " (muted)" else ""
                        VoiceJob.Phase.GREETING -> "Playing the greeting"
                        VoiceJob.Phase.RECORDING -> "Taking a message"
                        VoiceJob.Phase.ENDING -> "Hanging up"
                    } + time
                } + if (st.digits.isNotEmpty()) " · keys ${st.digits}" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (st.running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { Voice.hangUp() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Hang up") }
                    if (st.machine) {
                        FilterChip(selected = listening, onClick = { Voice.listen(!listening) }, label = { Text("Listen") })
                        OutlinedButton(onClick = { mic { Voice.pickUp() } }) { Text("Pick up") }
                    } else if (st.phase == VoiceJob.Phase.TALKING) {
                        FilterChip(selected = muted, onClick = { Voice.mute(!muted) }, label = { Text("Mute") })
                        FilterChip(selected = speaker, onClick = { Voice.loudspeaker(!speaker) }, label = { Text("Speaker") })
                        FilterChip(selected = keypad, onClick = { keypad = !keypad }, label = { Text("Keypad") })
                    }
                }
                if (keypad && !st.machine && st.phase == VoiceJob.Phase.TALKING) Keypad { Voice.dtmf(it) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { Voice.dismiss() }) { Text("OK") }
                }
            }
        }
    }
}

/** the phone's twelve keys: [onKey] for each pressed */
@Composable
private fun Keypad(onKey: (Char) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in listOf("123", "456", "789", "*0#")) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (k in row) {
                    OutlinedButton(onClick = { onKey(k) }, Modifier.weight(1f)) { Text(k.toString(), style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

/** m:ss */
private fun clock(s: Long) = "%d:%02d".format(s / 60, s % 60)

/** a message: from whom, when, how long; play it, delete it */
@Composable
private fun MessageRow(m: Voice.Message) {
    val context = LocalContext.current
    val playing by Voice.playing.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { Voice.play(m.file) }) {
            if (playing == m.file) Text("■", style = MaterialTheme.typography.titleMedium) else Icon(Icons.Filled.PlayArrow, "Play")
        }
        Column(Modifier.weight(1f)) {
            Text(m.caller.ifEmpty { "Unknown caller" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(m.time)) + " · " + clock(m.seconds.toLong()),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, "Delete") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this message?") },
            confirmButton = { TextButton(onClick = { confirm = false; Voice.delete(context, m) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** [address] as D-Modem dials a SIP address as it is: with sip: before it when it has no scheme */
internal fun sipAddress(address: String): String {
    val a = address.trim()
    return if (a.startsWith("sip:", ignoreCase = true) || a.startsWith("sips:", ignoreCase = true)) a else "sip:$a"
}

/** the number to call, or ([sip]) the SIP address (user@host), dialled as it is with no account too */
@Composable
private fun CallDialog(sip: Boolean = false, onDismiss: () -> Unit, onCall: (String) -> Unit) {
    var number by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (sip) "Call a SIP address" else "Call a number") },
        text = {
            OutlinedTextField(
                value = number, onValueChange = { number = it },
                label = { Text(if (sip) "SIP address" else "Number") },
                placeholder = if (sip) ({ Text("sip:user@host") }) else null,
                supportingText = if (sip) ({ Text("Dialled as it is: user@host, or sip:user@host") }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (sip) KeyboardType.Uri else KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onCall(number) }, enabled = number.isNotBlank()) { Text("Call") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The settings' phone section: the answering machine (off until turned on), its rings, the greeting, the longest message; all messages. */
@Composable
fun VoiceSettingsSection() {
    val context = LocalContext.current
    val store = remember { VoiceSettingsStore(context.applicationContext) }
    val current by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val fax by remember { FaxSettingsStore(context.applicationContext) }.settings.collectAsStateWithLifecycle(initialValue = null)
    val greeting by Voice.greeting.collectAsStateWithLifecycle()
    val recording by Voice.recording.collectAsStateWithLifecycle()
    val messages by Voice.messages.collectAsStateWithLifecycle()
    val mic = rememberMicrophone()
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) { Voice.chooseGreeting(context, uri) }
                    ?.let { Toast.makeText(context, "That greeting: $it", Toast.LENGTH_LONG).show() }
            }
        }
    }
    LaunchedEffect(Unit) { Voice.reload(context) }
    val s = current ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Answering machine")
                Text("Takes a message when nobody answers a call. Messages stay on this phone.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = s.voicemail, onCheckedChange = { v -> SettingsSaving.scope.launch { store.update { it.copy(voicemail = v) } } })
        }
        if (s.voicemail && (fax?.answer == true || DialIn.enabled)) {
            Text(
                if (fax?.answer == true) "The fax settings answer every call first: the answering machine waits until that's off"
                else "Dial-in answers every call first: the answering machine waits until that's off",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Answer after", Modifier.weight(1f))
            TextButton(onClick = { SettingsSaving.scope.launch { store.update { it.copy(rings = it.rings - 1) } } }, enabled = s.rings > 1) { Text("−") }
            Text("${s.rings} ring${if (s.rings == 1) "" else "s"}")
            TextButton(onClick = { SettingsSaving.scope.launch { store.update { it.copy(rings = it.rings + 1) } } }, enabled = s.rings < 10) { Text("+") }
        }
        Text("Longest message", style = MaterialTheme.typography.bodyMedium)
        for ((sec, label) in listOf(60 to "1 minute", 120 to "2 minutes", 300 to "5 minutes")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.maxSeconds == sec, onClick = { SettingsSaving.scope.launch { store.update { it.copy(maxSeconds = sec) } } })
                Text(label, Modifier.padding(start = 8.dp))
            }
        }
        Text(
            when {
                recording -> "Recording the greeting: speak now"
                greeting > 0 -> "Greeting: $greeting s"
                else -> "Greeting: none, callers hear a beep"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recording) {
                Button(onClick = { Voice.stopRecording() }) { Text("Stop") }
            } else {
                OutlinedButton(onClick = { mic { Voice.recordGreeting(context) } }) { Text("Record") }
                OutlinedButton(onClick = { Voice.playGreeting(context) }, enabled = greeting > 0) { Text("Play") }
                OutlinedButton(onClick = { pick.launch(arrayOf("audio/*")) }) { Text("File…") }
                TextButton(onClick = { Voice.clearGreeting(context) }, enabled = greeting > 0) { Text("None") }
            }
        }
        Text("Messages", style = MaterialTheme.typography.titleSmall)
        if (messages.isEmpty()) Text("None yet", style = MaterialTheme.typography.bodySmall)
        for (m in messages) MessageRow(m)
    }
}
