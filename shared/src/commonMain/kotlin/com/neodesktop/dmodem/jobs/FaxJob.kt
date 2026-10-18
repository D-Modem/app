// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.jobs

import com.neodesktop.dmodem.fax.Class1
import com.neodesktop.dmodem.fax.FaxException
import com.neodesktop.dmodem.fax.FaxPage
import com.neodesktop.dmodem.fax.FaxPdf
import com.neodesktop.dmodem.fax.FaxProgress
import com.neodesktop.dmodem.fax.T30
import com.neodesktop.dmodem.io.IOException
import com.neodesktop.dmodem.io.background
import com.neodesktop.dmodem.io.sleepMs
import com.neodesktop.dmodem.transfer.Incoming
import com.neodesktop.dmodem.transfer.TransferCancelled
import kotlin.concurrent.Volatile

/**
 * A fax call on the modem (the Android app's Fax, for iOS): pages sent to
 * a number, or a ringing call answered and its pages saved as a PDF. The
 * modem to class 1 first, back to data and on hook after.
 */
class FaxJob(out: DteOut, log: LogOut, changed: Changed) : DteJob(out, log, changed) {
    data class State(
        val sending: Boolean,
        val progress: FaxProgress,
        /** null while it goes; "" when it went through, else why not */
        val result: String? = null,
        /** the received fax's file name */
        val file: String = "",
        val number: String = "",
    ) {
        val running: Boolean get() = result == null
    }

    @Volatile var state: State? = null
        private set

    private fun update(p: FaxProgress) {
        state = state?.copy(progress = p)
        changed()
    }

    /** [pages] faxed to [number] */
    fun send(number: String, pages: List<FaxPage>, ident: String, ecm: Boolean, maxBps: Int) {
        start(sending = true, number = number.trim(), maxBps = maxBps) { c, rates ->
            if (pages.isEmpty()) throw FaxException("the document has no pages")
            update(FaxProgress("Dialling $number", total = pages.size))
            val r = c.command("D${number.trim()}", 120_000)
            if (r != "CONNECT") throw FaxException(dialResult(r, "no fax machine answered"))
            T30.send(c, pages, ident, rates, ecm = ecm) { update(it) } to ""
        }
    }

    /** the call ringing answered as a fax, its pages to [into] as [fileName] (a PDF) */
    fun answer(ident: String, ecm: Boolean, maxBps: Int, into: Incoming, fileName: String) {
        start(sending = false, number = "", maxBps = maxBps) { c, rates ->
            update(FaxProgress("Answering"))
            val r = c.command("A", 30_000)
            if (r != "CONNECT") throw FaxException(if (r == null) "no fax answered" else "no fax: $r")
            val got = ArrayList<FaxPage>()
            val p = try {
                T30.receive(c, ident, rates, { update(it) }, ecm = ecm) { pg, _ -> got.add(pg) }
            } catch (e: IOException) {
                // (the pages that came are kept)
                if (got.isEmpty()) throw e
                FaxProgress("Received part", pages = got.size)
            }
            p to save(into, fileName, got)
        }
    }

    /** the call stopped (hung up) */
    fun cancel() = io.cancel("cancelled")

    private fun save(into: Incoming, name: String, pages: List<FaxPage>): String {
        val (out, actual) = into.create(name, -1)
        try {
            out.use { FaxPdf.write(pages, it) }
            into.done(actual, 0)
        } catch (e: IOException) {
            into.abandon(actual)
            throw e
        }
        return actual
    }

    private fun start(sending: Boolean, number: String, maxBps: Int, body: (Class1, List<T30.Rate>) -> Pair<FaxProgress, String>) {
        state = State(sending, FaxProgress("Starting"), number = number)
        changed()
        background("fax") {
            var result = ""
            var file = ""
            var last = FaxProgress()
            val c = Class1(io) { log("fax: $it") }
            try {
                // (X3: a SIP line has no dial tone to wait for)
                if (c.command("E0V1X3") != "OK" || c.command("+FCLASS=1") != "OK") throw FaxException("the modem has no fax mode")
                val rates = T30.modemRates(c, maxBps)
                val (p, f) = body(c, rates)
                last = p
                file = f
            } catch (e: TransferCancelled) {
                result = "cancelled"
            } catch (e: IOException) {
                result = e.message ?: e.toString()
            } catch (e: RuntimeException) {
                result = e.toString()
            } finally {
                // the call over, the modem back to data
                raw("\u0018")
                sleepMs(300)
                raw("ATH\r")
                sleepMs(500)
                raw("AT+FCLASS=0\r")
                sleepMs(300)
                leftover = Latin1.string(io.leftover())
                val st = state
                state = st?.copy(progress = if (result.isEmpty()) last else st.progress, result = result, file = file)
                changed()
            }
        }
    }
}
