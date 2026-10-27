// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.cell

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** a mobile call's sound: what the far end says (in), what we say to it (out), 8000 Hz 16 bit mono */
interface CallStreams : Closeable {
    val input: InputStream
    val output: OutputStream
}

/** the sound of the phone's mobile calls, where the phone lets an app have it */
interface CallAudio {
    /** null: it can be had; else why not, for the user */
    fun unavailable(): String?
    /** the active call's sound; throws if there's none (no call up yet, or not allowed) */
    fun open(): CallStreams
}

/**
 * Android's call audio interception (13 on): the downlink extracted, the
 * uplink injected, by AudioManager's system calls, for a privileged app
 * with CALL_AUDIO_INTERCEPTION on a phone whose audio supports it
 * (isPstnCallAudioInterceptable). The calls are the platform's system
 * API: by reflection.
 */
class AndroidCallAudio(context: Context) : CallAudio {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)

    /** why the call's sound can't be had (Android before 13, not privileged, the phone's audio not interceptable), or null */
    override fun unavailable(): String? = when {
        Build.VERSION.SDK_INT < 33 -> "needs Android 13 or later"
        app.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED ->
            "D-Modem isn't a privileged app (call audio needs it: root, or a ROM that has it)"
        !interceptable() -> "this phone's audio doesn't let apps have mobile calls' sound"
        else -> null
    }

    /** whether the phone's audio says mobile calls' sound can be intercepted (false if it can't be asked) */
    private fun interceptable(): Boolean = runCatching {
        AudioManager::class.java.getMethod("isPstnCallAudioInterceptable").invoke(audio) as Boolean
    }.getOrDefault(false)

    /** the downlink's AudioRecord and the uplink's AudioTrack, started, as the call's streams; an IOException if unavailable */
    @SuppressLint("MissingPermission")
    override fun open(): CallStreams {
        unavailable()?.let { throw IOException(it) }
        val format = AudioFormat.Builder()
            .setSampleRate(8000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val outFormat = AudioFormat.Builder()
            .setSampleRate(8000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val record = try {
            AudioManager::class.java.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)
                .invoke(audio, format) as AudioRecord
        } catch (e: Exception) {
            throw IOException("the call's sound: ${(e.cause ?: e).message}")
        }
        val track = try {
            AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
                .invoke(audio, outFormat) as AudioTrack
        } catch (e: Exception) {
            record.release()
            throw IOException("the call's sound: ${(e.cause ?: e).message}")
        }
        record.startRecording()
        track.play()
        return object : CallStreams {
            @Volatile private var open = true
            override val input = object : InputStream() {
                /** one octet of the far end's sound, or -1 once closed */
                override fun read(): Int {
                    val b = ByteArray(1)
                    return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
                }
                private val two = ByteArray(2)
                private var odd = -1
                /** the far end's sound: whole samples where [len] allows, an odd octet kept for the next read; -1 once closed */
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!open) return -1
                    if (len == 0) return 0
                    // (a sample's second octet left from a one-octet read)
                    if (odd >= 0) {
                        b[off] = odd.toByte()
                        odd = -1
                        return 1
                    }
                    if (len == 1) {
                        if (record.read(two, 0, 2) < 2) return -1
                        b[off] = two[0]
                        odd = two[1].toInt() and 0xff
                        return 1
                    }
                    // (whole samples)
                    val n = record.read(b, off, len and 1.inv())
                    return if (n < 0) -1 else n
                }
            }
            override val output = object : OutputStream() {
                /** one octet to the call */
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                /** our sound to the call, all of it; an IOException once closed */
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (!open) throw IOException("the call is over")
                    var at = off
                    while (at < off + len) {
                        val n = track.write(b, at, off + len - at)
                        if (n < 0) throw IOException("the call's sound: $n")
                        at += n
                    }
                }
            }
            /** the record and track stopped and released (once) */
            override fun close() {
                if (!open) return
                open = false
                runCatching { record.stop() }
                record.release()
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    companion object {
        const val PERMISSION = "android.permission.CALL_AUDIO_INTERCEPTION"
    }
}
