// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.modem

import org.dmodem.app.android.NativeModem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the modem is doing, for the UI and the service's notification:
 * the service writes it, anyone observes it. The DTE hub is the way to
 * the modem itself.
 */
object ModemState {

    enum class Run { STOPPED, STARTING, RUNNING, STOPPING }

    /** on hook, no call */
    val IDLE = NativeModem.Status(NativeModem.Link.IDLE, false, 0, 0)

    private val _run = MutableStateFlow(Run.STOPPED)
    val run: StateFlow<Run> = _run.asStateFlow()

    private val _status = MutableStateFlow(IDLE)
    val status: StateFlow<NativeModem.Status> = _status.asStateFlow()

    /** Why the modem last stopped by itself, or failed to start (null: it didn't). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The modem's DTE, shared by the console, the terminal and the bridges. */
    val dte = DteHub()

    /** the line the running modem is on (ModemSettings.LINE_*) */
    @Volatile var line: String = org.dmodem.app.android.settings.ModemSettings.LINE_SIP

    /** the modem's speaker on the phone's (ATM, ATL) */
    val speaker = ModemSpeaker()

    /** the country's call progress tones, and when they play */
    val tones = CallTones()
    val progress = CallProgress(tones)

    /** calls answered as fax or data alike (AT+FAA=1): no "answer as fax" to offer */
    @Volatile var adaptiveAnswer = true

    /** a call ringing in, for the notification and the popup */
    val incoming = IncomingCalls()

    /** the ringing call answered (ATA): false if the modem isn't there to take it */
    fun answer(): Boolean {
        incoming.end()
        return dte.write("ATA\r")
    }

    /** The log's last lines (the modem's and SIP's). */
    val log = LineBuffer(MAX_LOG)

    /** The text the modem sent the DTE lately (the status screen's console). */
    val console = TextBuffer(MAX_CONSOLE)

    /** [r] the modem's run now */
    internal fun setRun(r: Run) {
        _run.value = r
    }

    /** [s] the modem's status now */
    internal fun setStatus(s: NativeModem.Status) {
        _status.value = s
    }

    /** [e] the error to show now (null: none) */
    internal fun setError(e: String?) {
        _error.value = e
    }

    private const val MAX_LOG = 2000
    private const val MAX_CONSOLE = 16 * 1024
}

/** a log line, and when it came ([System.currentTimeMillis]) */
data class LogLine(val ms: Long, val text: String)

/**
 * The last [max] lines, as flows of snapshots (each line added makes a new one): their texts, and
 * [entries], with when each came (as [clock] has it).
 */
class LineBuffer(private val max: Int, private val clock: () -> Long = System::currentTimeMillis) {
    private val lines = ArrayDeque<LogLine>()
    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow.asStateFlow()
    private val _entries = MutableStateFlow<List<LogLine>>(emptyList())
    val entries: StateFlow<List<LogLine>> = _entries.asStateFlow()

    /** [line] added, stamped with [clock], the oldest dropped past [max] */
    @Synchronized
    fun add(line: String) {
        lines.addLast(LogLine(clock(), line))
        while (lines.size > max) lines.removeFirst()
        _entries.value = lines.toList()
        _flow.value = _entries.value.map { it.text }
    }

    /** every line dropped */
    @Synchronized
    fun clear() {
        lines.clear()
        _entries.value = emptyList()
        _flow.value = emptyList()
    }
}

/** The last [max] characters of a stream of text. */
class TextBuffer(private val max: Int) {
    private val sb = StringBuilder()
    private val _flow = MutableStateFlow("")
    val flow: StateFlow<String> = _flow.asStateFlow()

    /** [s] added, the oldest characters dropped past [max] */
    @Synchronized
    fun append(s: String) {
        sb.append(s)
        if (sb.length > max) sb.delete(0, sb.length - max)
        _flow.value = sb.toString()
    }

    /** the text dropped */
    @Synchronized
    fun clear() {
        sb.setLength(0)
        _flow.value = ""
    }
}
