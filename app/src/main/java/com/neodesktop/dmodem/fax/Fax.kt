// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import android.content.Context
import android.net.Uri
import com.neodesktop.dmodem.bridge.Bridges
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.FaxSettings
import com.neodesktop.dmodem.settings.FaxSettingsStore
import com.neodesktop.dmodem.settings.ModemSettings
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
    /** the record as JSON, for the history's file */
    fun toJson(): JSONObject = JSONObject().put("id", id).put("time", time).put("sending", sending).put("number", number)
        .put("remote", remote).put("pages", pages).put("result", result).put("file", file)

    companion object {
        /** a record from its JSON */
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

    /** the faxes sent and received, kept in the app's faxes.json */
    fun history(context: Context): JsonListStore<FaxRecord> = synchronized(this) {
        history ?: JsonListStore(File(context.applicationContext.filesDir, "faxes.json"), FaxRecord::toJson, FaxRecord::fromJson, FaxRecord::id)
            .also { history = it }
    }

    val running: Boolean get() = _state.value?.running == true

    /**
     * (a mobile call's speech codec spoils the fax modulations: through
     * AMR-NB 12.2, the best of them, V.27ter's training at 2400 bit/s
     * comes with about one bit in sixteen wrong, more than ECM can mend;
     * CellFaxTest)
     */
    private const val CELL_NO_FAX = "fax doesn't go through a mobile call (its speech codec spoils the fax signals): use a SIP account or the line to another phone"

    /** why a fax can't start now, or null */
    private fun busy(): String? = when {
        ModemState.run.value != ModemState.Run.RUNNING -> "the modem is off"
        running -> "a fax call is going on"
        ModemState.line == ModemSettings.LINE_CELL -> CELL_NO_FAX
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
            val p = T30.send(c, pages, s.ident, rates, ecm = s.ecm) { update(it) }
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
            receive(app, c, s)
        }
        return null
    }

    /**
     * a call the modem answered and found to be a fax machine's (adaptive
     * answer, AT+FAA=1: its FAX, +FCLASS=1, then CONNECT as after +FTH=3,
     * for our first frames): the DTE taken now (on its reader's thread,
     * before the CONNECT goes elsewhere), [first] what came after FAX, and
     * the pages received
     */
    fun answeredAdaptively(first: ByteArray) {
        if (running) return
        val app = contextOrNull() ?: return
        start(sending = false, number = "", setUp = false, first = first) { c, s ->
            update(FaxProgress("Answering"))
            val r = c.result(10_000)
            if (r != "CONNECT") throw FaxException(if (r == null) "the fax didn't start" else "no fax: $r")
            receive(app, c, s)
        }
    }

    /** the pages received (on a call answered, the modem sending our frames), saved as a PDF and recorded */
    private fun receive(app: Context, c: Class1, s: FaxSettings): Pair<FaxProgress, String> {
        val got = ArrayList<FaxPage>()
        val p = try {
            T30.receive(c, s.ident, rates, { update(it) }, ecm = s.ecm) { pg, _ -> got.add(pg) }
        } catch (e: IOException) {
            // the pages that came are kept
            if (got.isEmpty()) throw e
            FaxProgress("Received part", pages = got.size)
        }
        val name = save(app, got)
        record(app, false, "", p, "", name)
        return p to name
    }

    /** the call stopped (hung up) */
    fun cancel() {
        io?.cancel("cancelled")
    }

    /** (for the screenshots: a call's state shown) */
    internal fun show(s: State?) {
        _state.value = s
    }

    /** the last call's state cleared, once it's over */
    fun dismiss() {
        if (!running) _state.value = null
    }

    private var rates: List<T30.Rate> = emptyList()

    /** the call's progress shown as [p] */
    private fun update(p: FaxProgress) {
        _state.value = _state.value?.copy(progress = p)
    }

    /** a dial's result (null: none came) as the reason the fax didn't go */
    private fun dialResult(r: String?) = when (r) {
        null -> "no answer"
        "BUSY" -> "busy"
        "NO ANSWER" -> "no answer"
        "NO CARRIER" -> "no fax machine answered"
        "NO DIALTONE", "NO DIAL TONE" -> "no line"
        else -> r.lowercase()
    }

    /** a call on the modem, its DTE ours while it lasts: [body] given the class 1 modem, set up */
    private fun start(
        sending: Boolean,
        number: String,
        /** the modem set up for fax first (false: it's in class 1 already, answering) */
        setUp: Boolean = true,
        /** what the modem said before the line was ours */
        first: ByteArray? = null,
        body: (Class1, FaxSettings) -> Pair<FaxProgress, String>,
    ) {
        val hub = ModemState.dte
        lateinit var listener: TerminalLink.Listener
        val line = TransferIo { b -> if (!hub.writeAs(listener, b)) throw IOException("the modem has gone") }
        listener = TerminalLink.Listener { d, n -> line.feed(d, 0, n) }
        if (!hub.claim(listener)) {
            _state.value = State(sending, FaxProgress(), "the modem is in use", number = number)
            return
        }
        io = line
        first?.let { if (it.isNotEmpty()) line.feed(it, 0, it.size) }
        _state.value = State(sending, FaxProgress("Starting"), number = number)
        thread = Thread({
            var result = ""
            var file = ""
            var last = FaxProgress()
            val c = Class1(line) { ModemState.log.add("fax: $it") }
            try {
                val s = runBlocking { FaxSettingsStore(contextOrThrow()).current() }
                // (X3: a SIP line has no dial tone to wait for)
                if (setUp) {
                    if (c.command("E0V1X3") != "OK" || c.command("+FCLASS=1") != "OK") throw FaxException("the modem has no fax mode")
                    rates = T30.modemRates(c, s.maxBps)
                } else {
                    // (no commands now: the modem's waiting for our frames. D-Modem-RE has every rate)
                    rates = T30.RATES.filter { it.bps <= s.maxBps }
                }
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

    /** the application's context; a FaxException before init */
    private fun contextOrThrow(): Context = app ?: throw FaxException("not set up")
    /** the application's context, or null before init */
    private fun contextOrNull(): Context? = app

    /** adaptive answer's word for a fax call (ATV1), its line alone */
    private val FAX_RESULT = Regex("(?:^|[\\r\\n])FAX\\r\\n")

    /** the application's context; ringing calls watched for (answered as faxes when the settings say so) */
    fun init(context: Context) {
        app = context.applicationContext
        if (watching) return
        watching = true
        ModemState.dte.addListener(TerminalLink.Listener { data, n ->
            val text = String(data, 0, n, Charsets.ISO_8859_1)
            // a call answered adaptively that's a fax machine's: ours, before its CONNECT goes to the terminal
            FAX_RESULT.find(text)?.let { m ->
                if (!running) answeredAdaptively(data.copyOfRange(m.range.last + 1, n))
                return@Listener
            }
            if (text.contains("RING") && !running) {
                val a = app ?: return@Listener
                val s = runCatching { runBlocking { FaxSettingsStore(a).current() } }.getOrNull() ?: return@Listener
                // (every call answered: adaptively, fax or data as it turns out; else as a fax)
                if (s.answer) Thread { if (ModemState.adaptiveAnswer) ModemState.answer() else answer(a) }.start()
            }
        })
    }

    /** [pages] saved as a PDF in Downloads/D-Modem: its file's name */
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

    /** the call put in the history */
    private fun record(context: Context, sending: Boolean, number: String, p: FaxProgress, result: String, file: String) {
        history(context).put(
            FaxRecord(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), sending, number, p.remote, p.pages, result, file),
        )
    }
}
