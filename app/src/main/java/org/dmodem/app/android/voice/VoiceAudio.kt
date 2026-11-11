// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import org.dmodem.app.jobs.VoiceJob
import org.dmodem.app.voice.VOICE_RATE

/**
 * A voice call's sound on the phone: the far end's samples to the
 * earpiece or the loudspeaker (an AudioTrack, as a call's), the
 * microphone's to the line (an AudioRecord with the phone's echo
 * cancelling, VOICE_COMMUNICATION); each on a thread of its own, 20 ms
 * at a time. The phone in its call mode while it lasts.
 */
class VoiceAudio(private val context: Context, private val job: VoiceJob) {
    @Volatile private var running = false
    private var play: Thread? = null
    private var mic: Thread? = null
    private val am = context.getSystemService(AudioManager::class.java)
    private var oldMode = AudioManager.MODE_NORMAL

    /** the speaker on (the far end heard), and the microphone if [talk] */
    @Synchronized
    fun start(talk: Boolean, speaker: Boolean) {
        if (!running) {
            running = true
            oldMode = am.mode
            runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
            setSpeaker(speaker)
            play = Thread({ playLoop() }, "voice-speaker").apply { isDaemon = true; start() }
        }
        if (talk && mic == null) mic = Thread({ micLoop() }, "voice-mic").apply { isDaemon = true; start() }
    }

    /** the loudspeaker (true) or the earpiece */
    fun setSpeaker(on: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { am.setCommunicationDevice(it) }
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
            }
        }
    }

    /** all stopped, the phone's sound as it was */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        play?.join(500)
        mic?.join(500)
        play = null
        mic = null
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            am.mode = oldMode
        }
    }

    /** the far end's samples to the speaker (silence when none came): about 100 ms behind at most */
    private fun playLoop() {
        val min = AudioTrack.getMinBufferSize(VOICE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(VOICE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(maxOf(min, FRAME * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val buf = ShortArray(FRAME)
        try {
            t.play()
            while (running) {
                // (the far end ahead of the speaker: its oldest dropped)
                if (job.readable > BEHIND_MAX) job.keepReadable(FRAME * 2)
                val n = job.read(buf, 0, FRAME)
                if (n < FRAME) buf.fill(0, n, FRAME)
                t.write(buf, 0, FRAME)
            }
        } catch (e: RuntimeException) {
            org.dmodem.app.android.modem.ModemState.log.add("voice: the speaker: $e")
        } finally {
            runCatching { t.stop() }
            t.release()
        }
    }

    /** the microphone's samples to the line */
    @SuppressLint("MissingPermission")
    private fun micLoop() {
        val min = AudioRecord.getMinBufferSize(VOICE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, VOICE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 2 * 4))
        } catch (e: RuntimeException) {
            org.dmodem.app.android.modem.ModemState.log.add("voice: no microphone: $e")
            return
        }
        val buf = ShortArray(FRAME)
        try {
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                org.dmodem.app.android.modem.ModemState.log.add("voice: no microphone (is it allowed?)")
                return
            }
            r.startRecording()
            while (running) {
                val n = r.read(buf, 0, FRAME)
                if (n > 0) job.write(buf, 0, n) else if (n < 0) break
            }
        } finally {
            runCatching { r.stop() }
            r.release()
        }
    }

    companion object {
        /** 20 ms */
        const val FRAME = VOICE_RATE / 50
        private const val BEHIND_MAX = VOICE_RATE / 4
    }
}

/** A WAV's samples (8000 Hz) played to the loudspeaker, or the microphone recorded: the greeting, the messages. */
object Sounds {
    @Volatile private var playing: Thread? = null
    @Volatile private var stopPlay = false
    @Volatile private var recording = false

    /** [pcm] played (what was playing stopped); [done] at its end, on the player's thread */
    fun play(pcm: ShortArray, done: () -> Unit = {}) {
        stop()
        stopPlay = false
        playing = Thread({
            val min = AudioTrack.getMinBufferSize(VOICE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(VOICE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, VOICE_RATE / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                t.play()
                var at = 0
                while (at < pcm.size && !stopPlay) {
                    val n = minOf(VoiceAudio.FRAME * 5, pcm.size - at)
                    t.write(pcm, at, n)
                    at += n
                }
                // (what's in its buffer heard out)
                if (!stopPlay) Thread.sleep(250)
            } catch (_: Exception) {
            } finally {
                runCatching { t.stop() }
                t.release()
                done()
            }
        }, "voice-play").apply { isDaemon = true; start() }
    }

    /** what's playing stopped */
    fun stop() {
        stopPlay = true
        playing?.join(1000)
        playing = null
    }

    /** the microphone recorded until [stopRecording] or [maxS]: its samples, or null without a microphone (on this thread) */
    @SuppressLint("MissingPermission")
    fun record(maxS: Int): ShortArray? {
        val min = AudioRecord.getMinBufferSize(VOICE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, VOICE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200))
        }.getOrNull() ?: return null
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return null
        }
        val out = ShortArray(maxS * VOICE_RATE)
        var n = 0
        recording = true
        try {
            r.startRecording()
            while (recording && n < out.size) {
                val k = r.read(out, n, minOf(VoiceAudio.FRAME, out.size - n))
                if (k < 0) break
                n += k
            }
        } finally {
            recording = false
            runCatching { r.stop() }
            r.release()
        }
        return out.copyOf(n)
    }

    /** the recording ended */
    fun stopRecording() {
        recording = false
    }
}
