// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.terminal

import android.content.Context
import com.neodesktop.dmodem.NativeModem
import com.neodesktop.dmodem.modem.DteHub
import com.neodesktop.dmodem.modem.ModemService
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.TerminalSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The app's terminal: its session, its dialing directory and its snippets. */
object Terminal {
    lateinit var session: TerminalSession
        private set
    lateinit var directory: JsonListStore<DialEntry>
        private set
    lateinit var snippets: JsonListStore<Snippet>
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        session = TerminalSession(ModemState.dte) { app.getExternalFilesDir("captures") }
        directory = JsonListStore(
            File(app.filesDir, "directory.json"), DialEntry::toJson, DialEntry::fromJson, DialEntry::id,
        )
        snippets = JsonListStore(
            File(app.filesDir, "snippets.json"), Snippet::toJson, Snippet::fromJson, Snippet::id, Snippet.DEFAULTS,
        )
    }

    /** the captures' folder */
    fun capturesDir(context: Context): File? = context.applicationContext.getExternalFilesDir("captures")

    /**
     * An entry dialled: the modem started if it is off, the entry's
     * character set and backspace on the session, its init commands, then
     * ATD. Null when the dial went out, else why it didn't.
     */
    suspend fun dial(context: Context, e: DialEntry): String? {
        if (e.number.isBlank()) return "no number to dial"
        if (ModemState.run.value != ModemState.Run.RUNNING) {
            // (an error of an earlier start isn't this one's)
            ModemState.setError(null)
            ModemService.start(context)
            val up = withTimeoutOrNull(START_TIMEOUT_MS) {
                ModemState.run.first { it == ModemState.Run.RUNNING || (it == ModemState.Run.STOPPED && ModemState.error.value != null) }
            }
            if (up != ModemState.Run.RUNNING) return ModemState.error.value ?: "the modem didn't start"
            // (its init commands go first)
            withContext(Dispatchers.IO) { sendAndWait(null, 800) }
        }
        if (ModemState.status.value.link != NativeModem.Link.IDLE || ModemState.status.value.offhook) {
            return "the modem is in a call: hang up first"
        }
        val t = TerminalSettingsStore(context).current()
        session.charset = e.charset ?: t.charset
        session.keys.backspace = e.backspace ?: t.backspace
        for (cmd in e.initCommands.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            withContext(Dispatchers.IO) { sendAndWait(cmd + "\r", 2000) }
        }
        session.send("ATD" + e.number.trim() + "\r")
        directory.put(e.copy(lastDialed = System.currentTimeMillis(), dialCount = e.dialCount + 1))
        return null
    }

    /** text sent (if any), then until the modem says OK (or ERROR), or ms go by */
    private fun sendAndWait(text: String?, ms: Long) {
        val got = CountDownLatch(1)
        val seen = StringBuilder()
        val l = DteHub.Listener { d, n ->
            synchronized(seen) {
                seen.append(String(d, 0, n, Charsets.ISO_8859_1))
                if (seen.contains("OK") || seen.contains("ERROR")) got.countDown()
            }
        }
        ModemState.dte.addListener(l)
        try {
            if (text != null) session.send(text)
            got.await(ms, TimeUnit.MILLISECONDS)
        } finally {
            ModemState.dte.removeListener(l)
        }
    }

    private const val START_TIMEOUT_MS = 20_000L
}
