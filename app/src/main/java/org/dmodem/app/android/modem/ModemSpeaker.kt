// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.modem

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream

/**
 * The modem's speaker on the phone's: the line as a modem's speaker had
 * it (the dial tone, the dialling, the other end ringing, the answer tone
 * and the training's hiss), on when the modem says (ATM1, its default:
 * from off hook until CONNECT; ATM2 always, ATM0 never) at ATL's volume.
 * [enabled] off: silent whatever the modem says.
 *
 * The sound comes from the native modem's thread on a socket ([start]),
 * played here on a thread of its own: the modem never waits for it.
 */
class ModemSpeaker(
    /** where the sound goes (an AudioTrack's, or the tests') */
    private val newSink: () -> Sink = { TrackSink() },
) {
    /** a player: 8000 Hz, 16 bit, mono */
    interface Sink {
        /** [n] bytes of [pcm] played (may wait for room) */
        fun write(pcm: ByteArray, n: Int)
        /** 0..1 */
        fun volume(v: Float)
        /** the player let go */
        fun close()
    }

    @Volatile var enabled = true
    private val _level = MutableStateFlow(0)
    /** the modem's speaker level now (0 off, 1..3) */
    val level: StateFlow<Int> = _level.asStateFlow()

    private var sink: Sink? = null
    private val lock = Any()

    /** the modem's word: [level] 0 off, else ATL's */
    fun setLevel(level: Int) {
        _level.value = level
        synchronized(lock) {
            if (level <= 0) {
                sink?.close()
                sink = null
            } else {
                sink?.volume(gain(level))
            }
        }
    }

    /** the sound from [input] until it ends, played while on (a thread of its own) */
    fun start(input: InputStream): Thread = Thread({
        val buf = ByteArray(8192)
        while (true) {
            val n = try { input.read(buf) } catch (_: IOException) { -1 }
            if (n < 0) break
            play(buf, n)
        }
    }, "modem-speaker").apply { isDaemon = true; start() }

    /** a block of the sound: played if the speaker is on, else let go */
    fun play(pcm: ByteArray, n: Int) {
        val l = _level.value
        val s = synchronized(lock) {
            if (l <= 0 || !enabled) {
                sink?.close()
                sink = null
                return
            }
            sink ?: runCatching { newSink() }.getOrNull()?.also { it.volume(gain(l)); sink = it }
        } ?: return
        // (outside the lock: a player's write may wait for room)
        runCatching { s.write(pcm, n) }
    }

    companion object {
        /** ATL's levels: low, medium, high */
        fun gain(level: Int): Float = when (level) {
            1 -> 0.3f
            2 -> 0.6f
            else -> 1f
        }
    }

    /** the phone's speaker (media's stream and volume) */
    internal class TrackSink : Sink {
        private val track: AudioTrack = run {
            val min = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(8000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                // (a tenth of a second or so: the sound keeps up with the line)
                .setBufferSizeInBytes(maxOf(min, 1600))
                .build()
                .apply { play() }
        }

        /** [n] bytes of [pcm] to the track (blocking until there's room) */
        override fun write(pcm: ByteArray, n: Int) {
            track.write(pcm, 0, n)
        }

        /** the track's volume, 0..1 */
        override fun volume(v: Float) {
            track.setVolume(v)
        }

        /** the track stopped, what's left in it dropped, and released */
        override fun close() {
            runCatching { track.pause(); track.flush() }
            track.release()
        }
    }
}
