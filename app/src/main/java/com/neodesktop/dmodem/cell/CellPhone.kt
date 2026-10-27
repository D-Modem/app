// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.cell

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.Executors

/** the phone's mobile calls, as the modem's line needs them */
interface CellPhone {
    enum class State { IDLE, RINGING, OFFHOOK }

    val state: StateFlow<State>
    /** a call to [number] placed; throws if it can't be */
    fun dial(number: String)
    /** the call ringing answered */
    fun answer()
    /** the call there is ended */
    fun hangUp()
    /** the microphone muted (a modem call: only the modem's sound on it) or not */
    fun muteMic(on: Boolean) {}
    /** whatever it holds let go (nothing, by default) */
    fun close() {}
}

/**
 * The phone's calls through Telecom (CALL_PHONE, ANSWER_PHONE_CALLS) and
 * their state through Telephony (READ_PHONE_STATE).
 */
@SuppressLint("MissingPermission") // (checked: missing())
class AndroidCellPhone(context: Context) : CellPhone {
    private val app = context.applicationContext
    private val telecom = app.getSystemService(TelecomManager::class.java)
    private val telephony = app.getSystemService(TelephonyManager::class.java)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(CellPhone.State.IDLE)
    override val state: StateFlow<CellPhone.State> = _state.asStateFlow()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "cell-state").apply { isDaemon = true } }
    private var callback: Any? = null
    private var micWas: Boolean? = null

    init {
        if (missing().isEmpty() && Build.VERSION.SDK_INT >= 31) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                /** Telephony's call state as [state] */
                override fun onCallStateChanged(s: Int) {
                    _state.value = when (s) {
                        TelephonyManager.CALL_STATE_RINGING -> CellPhone.State.RINGING
                        TelephonyManager.CALL_STATE_OFFHOOK -> CellPhone.State.OFFHOOK
                        else -> CellPhone.State.IDLE
                    }
                }
            }
            runCatching { telephony.registerTelephonyCallback(executor, cb) }.onSuccess { callback = cb }
        }
    }

    /** the runtime permissions the line needs and hasn't got */
    fun missing(): List<String> = PERMISSIONS.filter { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** a call to [number] placed through Telecom; an IOException if the app may not */
    override fun dial(number: String) {
        if (missing().isNotEmpty()) throw IOException("calls aren't allowed (the app's permissions)")
        try {
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (e: SecurityException) {
            throw IOException("calls aren't allowed: ${e.message}")
        }
    }

    /** the ringing call answered through Telecom (quietly not, if it can't be) */
    override fun answer() {
        runCatching { telecom.acceptRingingCall() }
    }

    /** the call ended through Telecom (Android 9 on; else nothing) */
    override fun hangUp() {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 28) runCatching { telecom.endCall() }
    }

    /** the microphone muted, its state before kept; unmuted: as it was before */
    override fun muteMic(on: Boolean) {
        if (on) {
            if (micWas == null) micWas = audio.isMicrophoneMute
            audio.isMicrophoneMute = true
        } else {
            micWas?.let { audio.isMicrophoneMute = it }
            micWas = null
        }
    }

    /** Telephony's callback unregistered, its thread stopped */
    override fun close() {
        if (Build.VERSION.SDK_INT >= 31) {
            (callback as? TelephonyCallback)?.let { runCatching { telephony.unregisterTelephonyCallback(it) } }
        }
        callback = null
        executor.shutdown()
    }

    companion object {
        val PERMISSIONS = listOf(
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.ANSWER_PHONE_CALLS,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.RECORD_AUDIO,
        )
    }
}
