// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.modem

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A call ringing in, for the notification and the app's popup: the
 * modem's RINGs (a SIP call, the other phone's, the mobile call's), who
 * it's from when the line knows (the other phone's name); over when the
 * modem goes off hook (answered: here, by a computer on a bridge, by
 * itself) or the RINGs stop (the caller gave up). Ignored, it stays out
 * of sight until it's over.
 */
class IncomingCalls(private val clock: () -> Long = System::currentTimeMillis) {
    data class Call(
        /** who's calling, if the line says ("D-Modem on Pixel 8", "192.168.1.20") */
        val from: String?,
        /** when it began ringing (ms) */
        val since: Long,
        /** the RINGs so far */
        val rings: Int = 1,
        /** put out of sight by the user (it rings on) */
        val ignored: Boolean = false,
    )

    private val _state = MutableStateFlow<Call?>(null)
    /** the call ringing now (ignored or not), or null */
    val state: StateFlow<Call?> = _state.asStateFlow()

    private val line = StringBuilder()
    private var lastRing = 0L
    private var from: String? = null
    private var offhook = false

    /** what the modem said */
    @Synchronized
    fun modemText(s: String) {
        for (c in s) {
            if (c == '\r' || c == '\n') {
                if (line.toString().trim() == "RING") ring()
                line.clear()
            } else if (line.length < 40) {
                line.append(c)
            }
        }
    }

    /** a RING counted (none while off hook): a new call, or one more ring of this one */
    private fun ring() {
        if (offhook) return
        lastRing = clock()
        val c = _state.value
        _state.value = c?.copy(rings = c.rings + 1, from = c.from ?: from) ?: Call(from, lastRing)
    }

    /** who's calling, as the line has it (the other phone ringing here), or null when it isn't */
    @Synchronized
    fun caller(name: String?) {
        from = name
        val c = _state.value
        if (name != null && c != null && c.from == null) _state.value = c.copy(from = name)
    }

    /** the modem's hook: off hook, the call's over (answered) */
    @Synchronized
    fun hook(offhook: Boolean) {
        this.offhook = offhook
        if (offhook) end()
    }

    /** a second gone by: no RING for [GAP_MS], the caller gave up */
    @Synchronized
    fun tick() {
        if (_state.value != null && clock() - lastRing > GAP_MS) end()
    }

    /** out of sight until it's over */
    @Synchronized
    fun ignore() {
        _state.value = _state.value?.copy(ignored = true)
    }

    /** over (the modem stopped, say) */
    @Synchronized
    fun end() {
        _state.value = null
        from = null
    }

    companion object {
        /** RINGs come some 6 s apart (the ring's cadence): this long without one, it's stopped */
        const val GAP_MS = 8000L
    }
}
