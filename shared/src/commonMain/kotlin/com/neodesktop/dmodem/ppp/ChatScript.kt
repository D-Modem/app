// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ppp

import com.neodesktop.dmodem.io.*

import com.neodesktop.dmodem.transfer.TransferIo

/**
 * A dial-up login script, as chat(8) has them, run on the call before
 * PPP: expect and send strings in turn ("ogin: \U assword: \P"), quoted
 * when they have spaces; an expect's alternative after "-" ("ogin:--ogin:":
 * nothing came, a return sent and it looked for again); TIMEOUT <s>, and
 * ABORT <text> (what ends the login if it comes); "" expects nothing.
 *
 * Sent strings end with a return unless \c ends them; in either kind
 * \r \n \t \\ \s (a space) \N (none), \d (a second's wait) and \p (a
 * quarter's) in a sent one, \U the user name and \P the password, ^X a
 * control character.
 */
class ChatScript(private val script: String, private val user: String, private val password: String) {

    /** the script's tokens, its quotes taken off (an empty string kept: "") */
    fun tokens(): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val s = script
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '#' && (i == 0 || s[i - 1] == '\n') -> { while (i < s.length && s[i] != '\n') i++ }
                c == '"' || c == '\'' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < s.length && s[i] != c) {
                        if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i]).append(s[i + 1]); i += 2 } else sb.append(s[i++])
                    }
                    i++
                    out.add(sb.toString())
                }
                else -> {
                    val sb = StringBuilder()
                    while (i < s.length && !s[i].isWhitespace()) {
                        if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i]).append(s[i + 1]); i += 2 } else sb.append(s[i++])
                    }
                    out.add(sb.toString())
                }
            }
        }
        return out
    }

    /** a string's escapes undone; for a sent one, whether a return follows ([sending]) */
    private fun unescape(t: String, sending: Boolean): Pair<String, Boolean> {
        val sb = StringBuilder()
        var cr = sending
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                when (val e = t[i + 1]) {
                    'r' -> sb.append('\r')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    's' -> sb.append(' ')
                    '\\' -> sb.append('\\')
                    'c' -> cr = false
                    'N' -> {}
                    'U' -> sb.append(user)
                    'P' -> sb.append(password)
                    // (the waits: marks for run())
                    'd' -> sb.append(WAIT_SECOND)
                    'p' -> sb.append(WAIT_QUARTER)
                    else -> sb.append(e)
                }
                i += 2
            } else if (c == '^' && i + 1 < t.length && t[i + 1] in '@'..'_') {
                sb.append((t[i + 1].code - 64).toChar())
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString() to cr
    }

    /**
     * the script run on [io] (the call's octets, after CONNECT); throws
     * when an expect isn't met (its timeout) or an ABORT string comes.
     * [log]: what it does (the password left out)
     */
    fun run(io: TransferIo, log: (String) -> Unit = {}) {
        val t = tokens()
        var timeoutMs = 45_000L
        val aborts = ArrayList<String>()
        var i = 0
        var expecting = true
        while (i < t.size) {
            val tok = t[i]
            when (tok.uppercase()) {
                "TIMEOUT" -> { timeoutMs = (t.getOrNull(i + 1)?.toLongOrNull() ?: 45) * 1000; i += 2; continue }
                "ABORT" -> { t.getOrNull(i + 1)?.let { aborts.add(unescape(it, false).first) }; i += 2; continue }
            }
            if (expecting) {
                // "a-b-c": a; if it doesn't come, send b and expect c
                val alts = tok.split('-')
                var ok = false
                var k = 0
                while (k < alts.size) {
                    val want = unescape(alts[k], false).first
                    log("expect \"$want\"")
                    if (want.isEmpty() || expect(io, want, aborts, timeoutMs)) { ok = true; break }
                    if (k + 1 >= alts.size) break
                    send(io, alts[k + 1], log)
                    k += 2
                }
                if (!ok) throw IOException("the login script waited for \"${unescape(alts.last(), false).first}\" in vain")
            } else {
                send(io, tok, log)
            }
            expecting = !expecting
            i++
        }
    }

    private fun send(io: TransferIo, tok: String, log: (String) -> Unit) {
        val (text, cr) = unescape(tok, true)
        log("send \"" + (if (tok.contains("\\P")) "(the password)" else text.replace("\r", "\\r")) + "\"")
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) io.write(sb.toString().let { t -> ByteArray(t.length) { t[it].code.toByte() } })
            sb.setLength(0)
        }
        for (c in text) {
            when (c) {
                WAIT_SECOND -> { flush(); sleepMs(1000) }
                WAIT_QUARTER -> { flush(); sleepMs(250) }
                else -> sb.append(c)
            }
        }
        if (cr) sb.append('\r')
        flush()
    }

    /** octets read until [want] comes (true), an ABORT string (an exception), or the time is up (false) */
    private fun expect(io: TransferIo, want: String, aborts: List<String>, timeoutMs: Long): Boolean {
        val end = monotonicMs() + timeoutMs
        val seen = StringBuilder()
        while (true) {
            val left = end - monotonicMs()
            if (left <= 0) return false
            val c = io.read(left)
            if (c < 0) return false
            seen.append(c.toChar())
            if (seen.endsWith(want)) return true
            aborts.firstOrNull { seen.endsWith(it) }?.let { throw IOException("the login script stopped at \"${it.trim()}\"") }
            if (seen.length > 4096) seen.deleteRange(0, 2048)
        }
    }

    companion object {
        private const val WAIT_SECOND = ''
        private const val WAIT_QUARTER = ''
    }
}
