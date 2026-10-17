// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import android.content.Context
import android.net.Uri
import com.neodesktop.dmodem.bridge.Bridges
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.FaxSettings
import com.neodesktop.dmodem.settings.FaxSettingsStore
import com.neodesktop.dmodem.terminal.JsonListStore
import com.neodesktop.dmodem.terminal.TerminalLink
import com.neodesktop.dmodem.transfer.DownloadsIncoming
import com.neodesktop.dmodem.transfer.TransferCancelled
import com.neodesktop.dmodem.transfer.TransferIo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** a fax sent or received, for the list of them */
data class FaxRecord(
    val id: String,
    val time: Long,
    val sending: Boolean,
    /** the number dialled (sending), the other machine's identity */
    val number: String,
    val remote: String,
    val pages: Int,
    /** "": it went through; else why not */
    val result: String,
    /** the received fax's file in Downloads (its name) */
    val file: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("time", time).put("sending", sending).put("number", number)
        .put("remote", remote).put("pages", pages).put("result", result).put("file", file)

    companion object {
        fun fromJson(o: JSONObject) = FaxRecord(
            o.getString("id"), o.getLong("time"), o.getBoolean("sending"), o.optString("number"), o.optString("remote"),
            o.optInt("pages"), o.optString("result"), o.optString("file"),
        )
    }
}

/**
 * Fax calls on the modem: a document sent to a number, a ringing call
 * answered as a fax (by hand, or each one when the settings say so),
 * the received pages saved as a PDF in Downloads/D-Modem. A call has the
 * modem's DTE to itself while it lasts (the terminal and the bridges
 * see nothing of it).
 */
// (it holds the application's context, which lives as long as the process does)
@android.annotation.SuppressLint("StaticFieldLeak")
object Fax {
    /** what the fax call is doing; null when none is (or has been) */
    data class State(
        val sending: Boolean,
        val progress: FaxProgress,
        val result: String? = null,
        /** the received fax's file name (in Downloads/D-Modem) */
        val file: String = "",
        val number: String = "",
    ) {
        val running: Boolean get() = result == null
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    @Volatile private var io: TransferIo? = null
    @Volatile private var thread: Thread? = null
    private var history: JsonListStore<FaxRecord>? = null
    private var watching = false

    fun history(context: Context): JsonListStore<FaxRecord> = synchronized(this) {
        history ?: JsonListStore(File(context.applicationContext.filesDir, "faxes.json"), FaxRecord::toJson, FaxRecord::fromJson, FaxRecord::id)
            .also { history = it }
    }

    val running: Boolean get() = _state.value?.running == true

    /** why a fax can't start now, or null */
    private fun busy(): String? = when {
        ModemState.run.value != ModemState.Run.RUNNING -> "the modem is off"
        running -> "a fax call is going on"
        ModemState.status.value.offhook -> "the modem is in a call"
        Bridges.serial.client.value != null -> "the modem is in use by ${Bridges.serial.client.value?.name}"
        else -> null
    }

    /** [uris]' pages faxed to [number]: null when it's started, else why it can't */
    fun send(context: Context, number: String, uris: List<Uri>): String? {
        busy()?.let { return it }
        if (number.isBlank()) return "no number"
        val app = context.applicationContext
        start(sending = true, number = number.trim()) { c, s ->
            update(FaxProgress("Preparing the document"))
            val pages = uris.flatMap { FaxDocuments.pages(app, it, s.fine) }
            if (pages.isEmpty()) throw FaxException("the document has no pages")
            update(FaxProgress("Dialling $number", total = pages.size))
            val r = c.command("D$number", 120_000)
            if (r != "CONNECT") throw FaxException(dialResult(r))
            val p = T30.send(c, pages, s.ident, rates) { update(it) }
            record(app, true, number, p, "", "")
            p to ""
        }
        return null
    }

    /** the call ringing now answered as a fax: null when it's started, else why it can't */
    fun answer(context: Context): String? {
        busy()?.takeIf { !it.startsWith("the modem is in a call") }?.let { return it }
        val app = context.applicationContext
        start(sending = false, number = "") { c, s ->
            update(FaxProgress("Answering"))
            val r = c.command("A", 30_000)
            if (r != "CONNECT") throw FaxException(if (r == null) "no fax answered" else "no fax: $r")
            val got = ArrayList<FaxPage>()
            val p = try {
                T30.receive(c, s.ident, rates, { update(it) }) { pg, _ -> got.add(pg) }
            } catch (e: IOException) {
                // the pages that came are kept
                if (got.isEmpty()) throw e
                FaxProgress("Received part", pages = got.size)
            }
            val name = save(app, got)
            record(app, false, "", p, "", name)
            p to name
        }
        return null
    }

    /** the call stopped (hung up) */
    fun cancel() {
        io?.cancel("cancelled")
    }

    /** (for the screenshots: a call's state shown) */
    internal fun show(s: State?) {
        _state.value = s
    }

    fun dismiss() {
        if (!running) _state.value = null
    }

    private var rates: List<T30.Rate> = emptyList()

    private fun update(p: FaxProgress) {
        _state.value = _state.value?.copy(progress = p)
    }

    private fun dialResult(r: String?) = when (r) {
        null -> "no answer"
        "BUSY" -> "busy"
        "NO ANSWER" -> "no answer"
        "NO CARRIER" -> "no fax machine answered"
        "NO DIALTONE", "NO DIAL TONE" -> "no line"
        else -> r.lowercase()
    }

    /** a call on the modem, its DTE ours while it lasts: [body] given the class 1 modem, set up */
    private fun start(sending: Boolean, number: String, body: (Class1, FaxSettings) -> Pair<FaxProgress, String>) {
        val hub = ModemState.dte
        lateinit var listener: TerminalLink.Listener
        val line = TransferIo { b -> if (!hub.writeAs(listener, b)) throw IOException("the modem has gone") }
        listener = TerminalLink.Listener { d, n -> line.feed(d, 0, n) }
        if (!hub.claim(listener)) {
            _state.value = State(sending, FaxProgress(), "the modem is in use", number = number)
            return
        }
        io = line
        _state.value = State(sending, FaxProgress("Starting"), number = number)
        thread = Thread({
            var result = ""
            var file = ""
            var last = FaxProgress()
            val c = Class1(line) { ModemState.log.add("fax: $it") }
            try {
                val s = runBlocking { FaxSettingsStore(contextOrThrow()).current() }
                // (X3: a SIP line has no dial tone to wait for)
                if (c.command("E0V1X3") != "OK" || c.command("+FCLASS=1") != "OK") throw FaxException("the modem has no fax mode")
                rates = T30.modemRates(c, s.maxBps)
                val (p, f) = body(c, s)
                last = p
                file = f
            } catch (e: TransferCancelled) {
                result = "cancelled"
            } catch (e: IOException) {
                result = e.message ?: e.toString()
            } catch (e: RuntimeException) {
                result = e.toString()
            } finally {
                // the call over, the modem back to data: around the cancel (the line's own writes refused then)
                fun raw(s: String) { hub.writeAs(listener, s.toByteArray()) }
                raw("\u0018")
                Thread.sleep(300)
                raw("ATH\r")
                Thread.sleep(500)
                raw("AT+FCLASS=0\r")
                Thread.sleep(300)
                io = null
                hub.release(listener)
                val st = _state.value
                if (result.isNotEmpty() && st != null) {
                    contextOrNull()?.let { record(it, st.sending, st.number, st.progress, result, "") }
                }
                _state.value = st?.copy(progress = if (result.isEmpty()) last else st.progress, result = result, file = file)
            }
        }, "fax").apply { isDaemon = true; start() }
    }

    // (the application's context, for the settings and the files: set by init)
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var app: Context? = null

    private fun contextOrThrow(): Context = app ?: throw FaxException("not set up")
    private fun contextOrNull(): Context? = app

    /** the application's context; ringing calls watched for (answered as faxes when the settings say so) */
    fun init(context: Context) {
        app = context.applicationContext
        if (watching) return
        watching = true
        ModemState.dte.addListener(TerminalLink.Listener { data, n ->
            if (String(data, 0, n, Charsets.ISO_8859_1).contains("RING") && !running) {
                val a = app ?: return@Listener
                val s = runCatching { runBlocking { FaxSettingsStore(a).current() } }.getOrNull() ?: return@Listener
                if (s.answer) Thread { answer(a) }.start()
            }
        })
    }

    private fun save(context: Context, pages: List<FaxPage>): String {
        val name = "fax-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".pdf"
        val into = DownloadsIncoming(context)
        val (out, actual) = into.create(name, -1)
        try {
            out.use { FaxPdf.write(pages, it) }
            into.done(actual, System.currentTimeMillis() / 1000)
        } catch (e: IOException) {
            into.abandon(actual)
            throw e
        }
        return actual
    }

    private fun record(context: Context, sending: Boolean, number: String, p: FaxProgress, result: String, file: String) {
        history(context).put(
            FaxRecord(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), sending, number, p.remote, p.pages, result, file),
        )
    }
}
