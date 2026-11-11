// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.voice

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.dmodem.app.android.DModemApp
import org.dmodem.app.android.R
import org.dmodem.app.android.bridge.Bridges
import org.dmodem.app.android.fax.Fax
import org.dmodem.app.android.modem.ModemState
import org.dmodem.app.android.ppp.DialIn
import org.dmodem.app.android.ppp.Internet
import org.dmodem.app.android.settings.FaxSettingsStore
import org.dmodem.app.android.settings.VoiceSettings
import org.dmodem.app.android.settings.VoiceSettingsStore
import org.dmodem.app.android.terminal.TerminalLink
import org.dmodem.app.jobs.VoiceJob
import org.dmodem.app.transfer.FolderIncoming
import org.dmodem.app.voice.VOICE_RATE
import org.dmodem.app.voice.Wav
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Voice calls on the modem (its V.253 voice mode, the shared VoiceJob):
 * a number called, or the call ringing answered, with the phone's
 * earpiece or loudspeaker and microphone; and the answering machine,
 * when the user has turned it on, answering a call nobody has after the
 * rings set, its messages kept in the app's own storage (voicemail/, WAV
 * files) and nowhere else. A call has the modem's DTE to itself while it
 * lasts.
 */
// (it holds the application's context, which lives as long as the process does)
@android.annotation.SuppressLint("StaticFieldLeak")
object Voice {
    /** a message the answering machine took */
    data class Message(val file: File, val time: Long, val caller: String, val seconds: Int)

    private val _state = MutableStateFlow<VoiceJob.State?>(null)
    /** the call going, or how the last one ended (null: none) */
    val state: StateFlow<VoiceJob.State?> = _state.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    /** the answering machine's messages, newest first */
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _greeting = MutableStateFlow(0)
    /** the greeting's length (s; 0: none, callers hear the beep alone) */
    val greeting: StateFlow<Int> = _greeting.asStateFlow()

    private val _listening = MutableStateFlow(false)
    /** a message being left heard on the loudspeaker */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _speaker = MutableStateFlow(true)
    val speaker: StateFlow<Boolean> = _speaker.asStateFlow()

    /** the message or greeting playing (its file), or null */
    private val _playing = MutableStateFlow<File?>(null)
    val playing: StateFlow<File?> = _playing.asStateFlow()

    @Volatile private var job: VoiceJob? = null
    @Volatile private var audio: VoiceAudio? = null
    @Volatile private var settings = VoiceSettings()
    @Volatile private var faxAnswers = false
    private var app: Context? = null
    private var watching = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val running: Boolean get() = _state.value?.running == true

    /** the messages' folder */
    private fun folder(c: Context) = File(c.filesDir, "voicemail").apply { mkdirs() }
    /** the greeting's file */
    private fun greetingFile(c: Context) = File(c.filesDir, "voicemail-greeting.wav")

    /** why a call can't start now, or null; [answering]: the call ringing is the one */
    private fun busy(answering: Boolean): String? = when {
        ModemState.run.value != ModemState.Run.RUNNING -> "the modem is off"
        running -> "a voice call is going on"
        Fax.running -> "a fax call is going on"
        Internet.running -> "the modem is connected to the internet"
        !answering && ModemState.status.value.offhook -> "the modem is in a call"
        Bridges.serial.client.value != null -> "the modem is in use by ${Bridges.serial.client.value?.name}"
        ModemState.dte.claimed -> "the modem is in use"
        else -> null
    }

    /** [number] called: null when it's started, else why it can't */
    fun dial(context: Context, number: String): String? {
        busy(false)?.let { return it }
        if (number.isBlank()) return "no number"
        return start(context) { it.dial(number) }
    }

    /** the call ringing answered, to talk: null when it's started, else why it can't */
    fun answer(context: Context): String? {
        busy(true)?.let { return it }
        val caller = callerNumber()
        ModemState.incoming.end()
        return start(context) { it.answer(caller) }
    }

    /** the call ringing answered by the answering machine: null when it's started, else why it can't */
    fun takeMessage(context: Context): String? {
        busy(true)?.let { return it }
        val c = context.applicationContext
        val caller = callerNumber()
        ModemState.incoming.end()
        val greeting = runCatching { greetingFile(c).takeIf { it.exists() }?.readBytes()?.let { Wav.decode(it) } }.getOrNull()
        val name = VoiceJob.messageName(SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()), caller)
        return start(context) { it.takeMessage(greeting, settings.maxSeconds, VoiceJob.MESSAGE_SILENCE_S, FolderIncoming(folder(c)), name, caller) }
    }

    /** caller ID's number for the call ringing, or "" */
    private fun callerNumber(): String =
        ModemState.incoming.state.value?.number?.takeIf { it.isNotBlank() && it != "P" && it != "O" } ?: ""

    /** the answering machine's call taken: the call on the speaker and microphone */
    fun pickUp() {
        val j = job ?: return
        if (j.pickUp()) {
            _listening.value = false
            audio?.start(talk = true, speaker = _speaker.value)
        }
    }

    /** a message being left heard (or not) on the loudspeaker */
    fun listen(on: Boolean) {
        val j = job ?: return
        if (j.state?.machine != true) return
        _listening.value = on
        if (on) audio?.start(talk = false, speaker = true) else stopAudio()
    }

    /** the call hung up */
    fun hangUp() {
        job?.hangUp()
    }

    /** DTMF digit [d] played to the far end */
    fun dtmf(d: Char) {
        job?.dtmf(d)
    }

    /** our microphone muted (the far end hears silence) or not */
    fun mute(on: Boolean) {
        _muted.value = on
        job?.muted = on
    }

    /** the call's sound on the loudspeaker (else the earpiece), kept for the next call */
    fun loudspeaker(on: Boolean) {
        _speaker.value = on
        audio?.setSpeaker(on)
        scope.launch { app?.let { a -> VoiceSettingsStore(a).update { it.copy(speaker = on) } } }
    }

    /** the last call's outcome cleared, once it's over */
    fun dismiss() {
        if (!running) _state.value = null
    }

    /** a job on the modem's DTE, [begin] it: null, or why it couldn't */
    private fun start(context: Context, begin: (VoiceJob) -> Unit): String? {
        val c = context.applicationContext
        app = c
        val hub = ModemState.dte
        lateinit var listener: TerminalLink.Listener
        lateinit var j: VoiceJob
        j = VoiceJob(
            { s -> hub.writeAs(listener, s.toByteArray(Charsets.ISO_8859_1)) },
            { ModemState.log.add(it) },
            { changed(c, j, listener) },
        )
        listener = TerminalLink.Listener { d, n -> j.feed(String(d, 0, n, Charsets.ISO_8859_1)) }
        if (!hub.claim(listener)) return "the modem is in use"
        job = j
        _muted.value = false
        _listening.value = false
        _speaker.value = settings.speaker
        audio = VoiceAudio(c, j)
        begin(j)
        return null
    }

    /** the job's state changed (on its thread): the sound started or stopped; at its end, the DTE given back */
    private fun changed(c: Context, j: VoiceJob, listener: TerminalLink.Listener) {
        val s = j.state ?: return
        val before = _state.value
        _state.value = s
        if (s.phase == VoiceJob.Phase.TALKING && before?.phase != VoiceJob.Phase.TALKING) {
            audio?.start(talk = true, speaker = _speaker.value)
        }
        if (s.phase == VoiceJob.Phase.ENDING) stopAudio()
        if (!s.running && job === j) {
            stopAudio()
            audio = null
            job = null
            _listening.value = false
            ModemState.dte.release(listener)
            reload(c)
            if (s.message.isNotEmpty()) notifyMessage(c, s)
        }
    }

    /** the call's sound stopped */
    private fun stopAudio() {
        audio?.stop()
    }

    // ------------------------------------------------------------------
    // messages and the greeting

    /** the messages read from their folder, the greeting's length */
    fun reload(context: Context) {
        val c = context.applicationContext
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        _messages.value = (folder(c).listFiles { f -> f.name.endsWith(".wav") } ?: emptyArray())
            .map { f ->
                val t = runCatching { format.parse(f.name.take(15))?.time }.getOrNull() ?: f.lastModified()
                Message(f, t, VoiceJob.messageCaller(f.name), Wav.seconds(f.length()))
            }
            .sortedByDescending { it.time }
        val g = greetingFile(c)
        _greeting.value = if (g.exists()) maxOf(1, Wav.seconds(g.length())) else 0
    }

    /** [m] deleted */
    fun delete(context: Context, m: Message) {
        if (_playing.value == m.file) stopPlaying()
        m.file.delete()
        reload(context)
    }

    /** [f] (a WAV) played on the loudspeaker; again, it stops */
    fun play(f: File) {
        if (_playing.value == f) {
            stopPlaying()
            return
        }
        val pcm = runCatching { Wav.decode(f.readBytes()) }.getOrNull() ?: return
        _playing.value = f
        Sounds.play(pcm) { if (_playing.value == f) _playing.value = null }
    }

    /** what's playing stopped */
    fun stopPlaying() {
        _playing.value = null
        Sounds.stop()
    }

    /** the greeting played */
    fun playGreeting(context: Context) {
        val g = greetingFile(context.applicationContext)
        if (g.exists()) play(g)
    }

    private val _recording = MutableStateFlow(false)
    /** the greeting being recorded from the microphone */
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** the greeting recorded from the microphone (until [stopRecording], 60 s at most): false without one */
    fun recordGreeting(context: Context) {
        if (_recording.value) return
        val c = context.applicationContext
        stopPlaying()
        _recording.value = true
        Thread({
            try {
                val pcm = Sounds.record(GREETING_MAX_S)
                if (pcm == null) {
                    ModemState.log.add("voice: no microphone for the greeting")
                } else if (pcm.size >= VOICE_RATE / 2) {
                    greetingFile(c).writeBytes(Wav.encode(pcm))
                }
            } finally {
                _recording.value = false
                reload(c)
            }
        }, "voice-greeting").apply { isDaemon = true; start() }
    }

    /** the greeting's recording ended (it's kept) */
    fun stopRecording() = Sounds.stopRecording()

    /**
     * the greeting from a sound file the user chose (WAV, AIFF and .au read
     * here, the rest by the phone's decoders): null, or why not. Slow for a
     * long MP3: off the main thread
     */
    fun chooseGreeting(context: Context, uri: Uri): String? {
        val c = context.applicationContext
        val data = runCatching { c.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return "it can't be read"
        val pcm = Wav.decode(data) ?: SystemSound.decode(c, uri) ?: return "it isn't a sound file this phone can play"
        if (pcm.isEmpty()) return "it has no sound"
        val n = minOf(pcm.size, GREETING_MAX_S * VOICE_RATE)
        greetingFile(c).writeBytes(Wav.encode(pcm, n))
        reload(c)
        return null
    }

    /** no greeting: callers hear the beep alone */
    fun clearGreeting(context: Context) {
        greetingFile(context.applicationContext).delete()
        reload(context)
    }

    /** a new message's notification: from whom, how long; a tap opens the app */
    private fun notifyMessage(c: Context, s: VoiceJob.State) {
        val open = PendingIntent.getActivity(
            c, 3, Intent(c, org.dmodem.app.android.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, DModemApp.CHANNEL_MODEM)
            .setSmallIcon(R.drawable.ic_modem)
            .setContentTitle("New voice message")
            .setContentText("From ${s.number.ifEmpty { "an unknown caller" }}, ${s.messageSeconds} s")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(MESSAGE_NOTIFICATION_ID, n) }
    }

    // ------------------------------------------------------------------

    /** the settings watched, the messages read, and ringing calls answered by the answering machine when it's on */
    fun init(context: Context) {
        val c = context.applicationContext
        app = c
        if (watching) return
        watching = true
        scope.launch { VoiceSettingsStore(c).settings.collect { settings = it; _speaker.value = it.speaker } }
        scope.launch { FaxSettingsStore(c).settings.collect { faxAnswers = it.answer } }
        scope.launch { reload(c) }
        ModemState.dte.addListener(TerminalLink.Listener { data, n -> ringing(c, String(data, 0, n, Charsets.ISO_8859_1)) })
    }

    private val line = StringBuilder()
    private var rings = 0
    private var lastRing = 0L

    /**
     * the modem's text: its RINGs counted, and after the rings set the call answered by the answering
     * machine (when it's on, and nothing else answers calls: the fax settings' answering, dial-in)
     */
    @Synchronized
    private fun ringing(c: Context, text: String) {
        for (ch in text) {
            if (ch != '\r' && ch != '\n') {
                if (line.length < 64) line.append(ch)
                continue
            }
            val l = line.toString().trim()
            line.setLength(0)
            if (l != "RING") continue
            val now = System.currentTimeMillis()
            if (now - lastRing > RING_GAP_MS) rings = 0
            lastRing = now
            rings++
            val s = settings
            if (s.voicemail && rings == s.rings && !faxAnswers && !DialIn.enabled && !running) {
                rings = 0
                Thread { takeMessage(c)?.let { ModemState.log.add("voice: the answering machine didn't answer: $it") } }.start()
            }
        }
    }

    private const val RING_GAP_MS = 8000L
    private const val GREETING_MAX_S = 60
    private const val MESSAGE_NOTIFICATION_ID = 41
}
