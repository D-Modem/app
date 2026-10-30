// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.modem

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A call ringing in, for the notification and the app's popup: the
 * modem's RINGs (a SIP call, the other phone's, the mobile call's), who
 * it's from when the line knows (the other phone's name), and the caller
 * ID the modem reports after the first RING (AT+VCID=1's NMBR and NAME,
 * or =2's MESG, in voice mode inside <DLE>X ... <DLE>.); over when the
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
        /** caller ID's number and name: P withheld, O not known, null not reported */
        val number: String? = null,
        val name: String? = null,
    ) {
        /** caller ID's name, else its number, else the line's name for the caller; null if none */
        val who: String?
            get() = shown(name) ?: shown(number) ?: from ?: when {
                number == "P" || name == "P" -> "Private number"
                else -> null
            }

        /** caller ID's number when [who] is the name (the line under it); null otherwise */
        val detail: String?
            get() = if (shown(name) != null) shown(number) else null
    }

    private val _state = MutableStateFlow<Call?>(null)
    /** the call ringing now (ignored or not), or null */
    val state: StateFlow<Call?> = _state.asStateFlow()

    private val line = StringBuilder()
    private var lastRing = 0L
    private var from: String? = null
    private var offhook = false
    /** caller ID reported before a call was ringing here, for the call to come (if it comes within [GAP_MS]) */
    private var number: String? = null
    private var name: String? = null
    private var cidAt = 0L

    /** what the modem said */
    @Synchronized
    fun modemText(s: String) {
        for (c in s) {
            if (c == '\r' || c == '\n') {
                val l = unpacket(line.toString())
                if (l == "RING") ring() else callerId(l)
                line.clear()
            } else if (line.length < MAX_LINE) {
                line.append(c)
            }
        }
    }

    /** a RING counted (none while off hook): a new call, or one more ring of this one */
    private fun ring() {
        if (offhook) return
        lastRing = clock()
        val c = _state.value
        if (c == null && lastRing - cidAt > GAP_MS) {
            number = null
            name = null
        }
        _state.value = c?.copy(rings = c.rings + 1, from = c.from ?: from)
            ?: Call(from, lastRing, number = number, name = name)
    }

    /** a caller ID line ("NMBR = 5551234", "NAME = ...", "MESG = 80..."), to the call ringing or the next */
    private fun callerId(l: String) {
        val eq = l.indexOf('=')
        if (eq < 0) return
        val v = l.substring(eq + 1).trim()
        when (l.substring(0, eq).trim()) {
            "NMBR" -> number = v
            "NAME" -> name = v
            "MESG" -> decode(v)?.let { (n, m) ->
                number = n ?: number
                name = m ?: name
            }
            else -> return
        }
        cidAt = clock()
        val c = _state.value ?: return
        _state.value = c.copy(number = number ?: c.number, name = name ?: c.name)
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
        number = null
        name = null
    }

    companion object {
        /** the longest line kept: a MESG's hex (258 octets) and its tag */
        const val MAX_LINE = 600

        /** a line without voice mode's <DLE>X and <DLE>. around caller ID, other <DLE> codes and spaces at its ends */
        fun unpacket(s: String): String {
            val b = StringBuilder()
            var i = 0
            while (i < s.length) {
                if (s[i] == '\u0010') i += 2 else b.append(s[i++])
            }
            return b.toString().trim()
        }

        /**
         * A caller ID message in hex (+VCID=2's MESG): an SDMF (04: date and time, then the number) or MDMF
         * one (80: 02 the number, 04 its absence's reason, 07 the name, 08 its reason), as (number, name);
         * null if it isn't one
         */
        fun decode(hex: String): Pair<String?, String?>? {
            if (hex.length < 6 || hex.length % 2 != 0) return null
            val b = try {
                ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            } catch (e: NumberFormatException) {
                return null
            }
            val len = b[1].toInt() and 0xff
            if (len + 3 > b.size) return null
            /** [n] octets at [at] as text */
            fun text(at: Int, n: Int) = String(b, at, n, Charsets.ISO_8859_1)
            return when (b[0].toInt() and 0xff) {
                0x04 -> if (len > 8) Pair(text(10, len - 8), null) else null
                0x80 -> {
                    var number: String? = null
                    var name: String? = null
                    var i = 2
                    while (i + 2 <= len + 2) {
                        val t = b[i].toInt() and 0xff
                        val n = b[i + 1].toInt() and 0xff
                        if (i + 2 + n > len + 2) break
                        val v = text(i + 2, n)
                        when (t) {
                            0x02 -> number = v
                            0x04 -> number = v.ifEmpty { "O" }
                            0x07 -> name = v
                            0x08 -> name = v.ifEmpty { "O" }
                        }
                        i += 2 + n
                    }
                    Pair(number, name)
                }
                else -> null
            }
        }

        /** a caller ID field as shown: null when withheld (P), not known (O) or empty */
        fun shown(v: String?): String? = v?.takeIf { it.isNotBlank() && it != "P" && it != "O" }

        /** RINGs come some 6 s apart (the ring's cadence): this long without one, it's stopped */
        const val GAP_MS = 8000L
    }
}
