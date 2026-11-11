// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.terminal

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.dmodem.app.terminal.MusicNote
import java.util.concurrent.LinkedBlockingQueue

/**
 * ANSI music played as a PC speaker did: each note a square wave, one
 * after another on a thread of its own, so the screen goes on while it
 * plays (PLAY's "music background").
 */
class MusicPlayer {
    private val queue = LinkedBlockingQueue<List<MusicNote>>()
    @Volatile
    private var thread: Thread? = null

    /** [notes] played after what's playing */
    fun play(notes: List<MusicNote>) {
        queue.put(notes)
        if (thread == null) start()
    }

    /** what's queued dropped (the session's end) */
    fun stop() {
        queue.clear()
        thread?.interrupt()
        thread = null
    }

    /** the thread that plays the queue, until it's been empty a while */
    @Synchronized
    private fun start() {
        if (thread != null) return
        thread = Thread({
            val track = runCatching { track() }.getOrNull()
            try {
                track?.play()
                while (true) {
                    val notes = queue.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: break
                    for (n in notes) {
                        if (Thread.currentThread().isInterrupted) return@Thread
                        track?.let { write(it, n) }
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                runCatching { track?.stop() }
                track?.release()
                thread = null
            }
        }, "ansi-music").apply { isDaemon = true; start() }
    }

    /** a stream of 16-bit mono at [RATE] */
    private fun track(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, RATE / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** a note's samples (its square wave, then its silence) written to [t] */
    private fun write(t: AudioTrack, n: MusicNote) {
        val on = RATE * n.ms / 1000
        val off = RATE * n.restMs / 1000
        val buf = ShortArray(on + off)
        if (n.hz > 0) {
            val half = RATE / n.hz / 2
            for (i in 0 until on) buf[i] = if ((i / half).toInt() % 2 == 0) LEVEL else (-LEVEL).toShort()
        }
        t.write(buf, 0, buf.size)
    }

    companion object {
        private const val RATE = 22050
        /** a quarter of full scale: a speaker's beep, not a blast */
        private const val LEVEL: Short = 8000
    }
}
