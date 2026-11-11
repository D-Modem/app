// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.voice

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.nio.ByteOrder
import org.dmodem.app.voice.SoundFile

/**
 * Sound files the phone's own decoders read (MP3, AAC/M4A, FLAC, Ogg
 * Vorbis, Opus, AMR and the rest it has), as a voice call's samples:
 * 8000 Hz, one channel. For the greeting, when [SoundFile] doesn't read
 * the file itself.
 */
object SystemSound {
    /** the most decoded (s): a greeting is shorter */
    private const val MAX_S = 300

    /** how long decoding may take (ms), whatever the file */
    private const val TIME_MS = 30_000L

    /** [uri]'s first sound track decoded, at 8000 Hz, one channel; null if the phone can't */
    fun decode(c: Context, uri: Uri): ShortArray? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(c, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                return drain(ex, codec, fmt)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } catch (e: Exception) {
            return null
        } finally {
            ex.release()
        }
    }

    /** the track fed to [codec] and what it gives collected, then mixed and resampled */
    private fun drain(ex: MediaExtractor, codec: MediaCodec, fmt: MediaFormat): ShortArray? {
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var float = false
        var pcm = ShortArray(1 shl 16)
        var n = 0
        val info = MediaCodec.BufferInfo()
        var fed = false
        val until = SystemClock.elapsedRealtime() + TIME_MS
        while (SystemClock.elapsedRealtime() < until && n < MAX_S.toLong() * rate * channels) {
            if (!fed) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val got = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (got < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        fed = true
                    } else {
                        codec.queueInputBuffer(i, 0, got, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else if (o >= 0) {
                val b = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                b.position(info.offset)
                b.limit(info.offset + info.size)
                val k = if (float) info.size / 4 else info.size / 2
                if (n + k > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, n + k))
                if (float) {
                    val fb = b.asFloatBuffer()
                    for (j in 0 until k) pcm[n + j] = (fb.get(j) * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                } else {
                    b.asShortBuffer().get(pcm, n, k)
                }
                n += k
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        if (n == 0 || channels < 1 || rate <= 0) return null
        return SoundFile.mix(pcm, n / channels, channels, rate)
    }
}
