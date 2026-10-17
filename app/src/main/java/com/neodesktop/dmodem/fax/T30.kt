// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import java.io.IOException

/** how a fax call went, as it goes */
data class FaxProgress(
    val phase: String = "",
    /** pages done (sent and confirmed, or received) */
    val pages: Int = 0,
    val total: Int = 0,
    val bps: Int = 0,
    val fine: Boolean = false,
    /** the other machine's identity (its CSI or TSI) */
    val remote: String = "",
)

/** a fax call that didn't work: why */
class FaxException(msg: String) : IOException(msg)

/**
 * The T.30 procedure for group 3 fax on a class 1 modem ([Class1]): the
 * calling machine sends (no polling), the answering one receives, in
 * MH coding, without error correction mode. The frames' octets as the
 * class 1 modem has them (the first bit on the line at the bottom).
 */
object T30 {
    // the FCFs: the calling machine's (after a DIS) have X, the bottom bit, set
    const val DIS = 0x80
    const val CSI = 0x40
    const val NSF = 0x20
    const val DCS = 0x82
    const val TSI = 0x42
    const val CFR = 0x84
    const val FTT = 0x44
    const val EOM = 0x8e
    const val MPS = 0x4e
    const val EOP = 0x2e
    const val MCF = 0x8c
    const val RTP = 0xcc
    const val RTN = 0x4c
    const val DCN = 0xfa
    const val CRP = 0x1a
    const val X = 0x01

    /** a rate: its DCS code (bits 11-14), its modulation's class 1 codes (long training, and for the pages) */
    data class Rate(val bps: Int, val code: Int, val family: Int, val trainMod: Int, val pageMod: Int)

    val RATES = listOf(
        Rate(14400, 0x8, 2, 145, 146), Rate(12000, 0xa, 2, 121, 122),
        Rate(9600, 0x9, 2, 97, 98), Rate(7200, 0xb, 2, 73, 74),
        Rate(9600, 0x1, 1, 96, 96), Rate(7200, 0x3, 1, 72, 72),
        Rate(4800, 0x2, 0, 48, 48), Rate(2400, 0x0, 0, 24, 24),
    )

    fun bit(fif: ByteArray, n: Int): Boolean = (n - 1) / 8 < fif.size && (fif[(n - 1) / 8].toInt() shr ((n - 1) % 8)) and 1 == 1

    fun setBit(fif: ByteArray, n: Int) {
        fif[(n - 1) / 8] = (fif[(n - 1) / 8].toInt() or (1 shl ((n - 1) % 8))).toByte()
    }

    private fun rateBits(fif: ByteArray) =
        (if (bit(fif, 11)) 1 else 0) or (if (bit(fif, 12)) 2 else 0) or (if (bit(fif, 13)) 4 else 0) or (if (bit(fif, 14)) 8 else 0)

    /** the rates a DIS offers */
    fun disOffers(fif: ByteArray, r: Rate): Boolean {
        val v = rateBits(fif)
        return when (r.family) {
            2 -> v == 0xb || v == 0xf || (v and 0x8) != 0
            1 -> v and 0x1 != 0
            else -> r.bps == 2400 || v and 0x2 != 0
        }
    }

    /** the minimum scan line time a DIS asks for (ms) */
    fun disMinMs(fif: ByteArray): Int {
        val ms = intArrayOf(20, 40, 10, 10, 5, 40, 20, 0)
        val c = (if (bit(fif, 21)) 4 else 0) or (if (bit(fif, 22)) 2 else 0) or (if (bit(fif, 23)) 1 else 0)
        return ms[c]
    }

    /** our DIS: a receiver of MH, the [rates], fine, A4, 5 ms minimum scan time */
    fun dis(rates: List<Rate>): ByteArray {
        val f = ByteArray(3)
        setBit(f, 10)
        // (V.17 in a DIS is V.17 up to 14400: offered only when that is)
        val v17 = rates.any { it.family == 2 && it.bps == 14400 }
        val v29 = rates.any { it.family == 1 }
        val code = if (v17) 0xb else if (v29) 0x3 else 0x2
        if (code and 1 != 0) setBit(f, 11)
        if (code and 2 != 0) setBit(f, 12)
        if (code and 4 != 0) setBit(f, 13)
        if (code and 8 != 0) setBit(f, 14)
        setBit(f, 15)
        // minimum scan time 5 ms (none would do for us; efax divides by it, crashing on 0)
        setBit(f, 21)
        return f
    }

    /** a DCS: the rate, fine or not, the minimum scan time agreed */
    fun dcs(r: Rate, fine: Boolean, minMs: Int): ByteArray {
        val f = ByteArray(3)
        setBit(f, 10)
        if (r.code and 1 != 0) setBit(f, 11)
        if (r.code and 2 != 0) setBit(f, 12)
        if (r.code and 4 != 0) setBit(f, 13)
        if (r.code and 8 != 0) setBit(f, 14)
        if (fine) setBit(f, 15)
        when (minMs) {
            40 -> setBit(f, 23)
            10 -> setBit(f, 22)
            5 -> setBit(f, 21)
            0 -> { setBit(f, 21); setBit(f, 22); setBit(f, 23) }
        }
        return f
    }

    /** the rate a DCS says, among [RATES] */
    fun dcsRate(fif: ByteArray): Rate? {
        val v = rateBits(fif)
        return RATES.firstOrNull { it.code == v }
    }

    /** a DCS's minimum scan time (ms) */
    fun dcsMinMs(fif: ByteArray): Int {
        val c = (if (bit(fif, 21)) 4 else 0) or (if (bit(fif, 22)) 2 else 0) or (if (bit(fif, 23)) 1 else 0)
        return when (c) { 1 -> 40; 2 -> 10; 4 -> 5; 7 -> 0; else -> 20 }
    }

    /** a CSI or TSI's 20 characters: the last first */
    fun ident(id: String): ByteArray {
        val s = id.filter { it.isDigit() || it == '+' || it == ' ' }.take(20)
        return ByteArray(20) { k -> if (k < s.length) s[s.length - 1 - k].code.toByte() else ' '.code.toByte() }
    }

    fun readIdent(fif: ByteArray): String =
        fif.take(20).reversed().map { b -> val c = b.toInt() and 0xff; if (c in 32..126) c.toChar() else ' ' }.joinToString("").trim()

    /** the rates the modem has (AT+FTM=?'s list, in fax mode, before a call), up to [maxBps] */
    fun modemRates(c: Class1, maxBps: Int = 14400): List<Rate> {
        val r = c.command("+FTM=?")
        val list = c.lastInfo?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet().orEmpty()
        val all = if (r != "OK" || list.isEmpty()) RATES.filter { it.family < 2 } else RATES.filter { it.trainMod in list && it.pageMod in list }
        return all.filter { it.bps <= maxBps }.ifEmpty { RATES.filter { it.bps <= 2400 } }
    }

    // ------------------------------------------------------------------

    /** a training check's (TCF's) octets: 1.5 s of zeros at the rate */
    fun tcf(bps: Int) = ByteArray(bps * 3 / 2 / 8)

    /** a TCF that came good: most of it, zeros most of the time */
    fun tcfGood(d: ByteArray, bps: Int): Boolean {
        val want = bps * 3 / 2 / 8
        if (d.size < want / 2) return false
        // (its start and end let go: the carrier settling, and stopping)
        val mid = d.copyOfRange(d.size / 8, d.size - d.size / 8)
        val zeros = mid.count { it.toInt() == 0 }
        return zeros >= mid.size * 9 / 10
    }

    // ------------------------------------------------------------------
    // sending

    /**
     * [pages] sent on a call the modem has dialled (its CONNECT seen: it
     * is receiving the answerer's frames): DIS, then DCS and training at
     * the best rate both have (lower ones if it fails), each page with
     * its MPS or EOP and the answer to it
     */
    fun send(
        c: Class1,
        pages: List<FaxPage>,
        ident: String,
        /** the modem's rates ([modemRates], asked before the call: not during it) */
        have: List<Rate>,
        progress: (FaxProgress) -> Unit = {},
    ): FaxProgress {
        if (pages.isEmpty()) throw FaxException("no pages")
        var p = FaxProgress("Waiting for the fax machine", total = pages.size)
        progress(p)
        // phase B: the answerer's frames, up to its DIS (as the modem got them since its CONNECT)
        var dis: ByteArray? = null
        var remote = ""
        var first = true
        val t1 = System.currentTimeMillis() + 35_000
        while (dis == null && System.currentTimeMillis() < t1) {
            val f = if (first) c.readFrame(10_000) else c.receiveFrame()
            first = false
            if (f == null) continue
            if (!f.ok) continue
            when (f.fcf and X.inv()) {
                CSI -> remote = readIdent(f.fif)
                DIS -> dis = f.fif
                DCN -> throw FaxException("the fax machine hung up")
            }
        }
        val d = dis ?: throw FaxException("no fax machine answered")
        val rates = have.filter { disOffers(d, it) }
        if (rates.isEmpty()) throw FaxException("no rate in common with the fax machine")
        val fine = pages.first().fine && bit(d, 15)
        val minMs = disMinMs(d)
        p = p.copy(remote = remote, fine = fine)

        // training: down the rates until one takes
        var ri = 0
        fun train(): Rate {
            var tries = 0
            while (ri < rates.size) {
                val r = rates[ri]
                p = p.copy(phase = "Training at ${r.bps} bit/s", bps = r.bps)
                progress(p)
                c.command("+FTS=8")
                if (!c.transmitFrames(listOf(Class1.frame(TSI or X, ident(ident)), Class1.frame(DCS or X, dcs(r, fine, minMs))))) {
                    throw FaxException("the modem didn't send the DCS")
                }
                c.command("+FTS=8")
                if (c.command("+FTM=${r.trainMod}", 10_000) != "CONNECT") throw FaxException("the modem didn't train")
                c.sendData(tcf(r.bps))
                if (c.result(10_000) != "OK") throw FaxException("the training didn't go out")
                val f = c.receiveFrame(4_000)
                when (f?.fcf?.and(X.inv())) {
                    CFR -> return r
                    FTT -> { ri++; tries = 0 }
                    DCN -> throw FaxException("the fax machine hung up")
                    // (no answer: the same again, then a lower rate)
                    else -> if (++tries >= 3) { ri++; tries = 0 }
                }
            }
            throw FaxException("the training failed at every rate")
        }
        var rate = train()

        var i = 0
        var resent = 0
        while (i < pages.size) {
            val page = pages[i]
            p = p.copy(phase = "Sending page ${i + 1} of ${pages.size}", bps = rate.bps)
            progress(p)
            val data = T4.encode(page, rate.bps * minMs / 1000)
            if (c.command("+FTM=${rate.pageMod}", 10_000) != "CONNECT") throw FaxException("the modem didn't send the page")
            c.sendData(data)
            if (c.result(120_000) != "OK") throw FaxException("the page didn't go out")
            val post = if (i == pages.lastIndex) EOP else MPS
            var answer: Int? = null
            for (attempt in 0 until 3) {
                c.command("+FTS=8")
                if (!c.transmitFrames(listOf(Class1.frame(post or X)))) throw FaxException("the modem didn't send the end of the page")
                val f = c.receiveFrame(4_000) ?: continue
                if (!f.ok) continue
                answer = f.fcf and X.inv()
                break
            }
            when (answer) {
                MCF, RTP -> {
                    i++
                    resent = 0
                    p = p.copy(pages = i)
                    progress(p)
                    // (RTP: good, but train again for the next)
                    if (answer == RTP && i < pages.size) rate = train()
                }
                RTN -> {
                    // not good: trained again (lower if that fails), the page again
                    if (++resent > 2) throw FaxException("page ${i + 1} wasn't received after three tries")
                    rate = train()
                }
                DCN -> throw FaxException("the fax machine hung up after page ${i + 1}")
                null -> throw FaxException("no answer to page ${i + 1}")
                else -> throw FaxException("the fax machine answered page ${i + 1} with %02x".format(answer))
            }
        }
        // phase E
        c.command("+FTS=8")
        c.transmitFrames(listOf(Class1.frame(DCN or X)))
        p = p.copy(phase = "Sent")
        progress(p)
        return p
    }


    // ------------------------------------------------------------------
    // receiving

    /**
     * a fax received on a call the modem has answered (its CONNECT seen:
     * it is sending frames): CSI and DIS, the sender's DCS and training,
     * each page ([page] as it comes) and its MCF, until EOP and DCN
     */
    fun receive(
        c: Class1,
        ident: String,
        /** the modem's rates ([modemRates], asked before the call) */
        have: List<Rate>,
        progress: (FaxProgress) -> Unit = {},
        page: (FaxPage, Int) -> Unit,
    ): FaxProgress {
        var p = FaxProgress("Answering")
        progress(p)
        val ours = listOf(Class1.frame(CSI, ident(ident)), Class1.frame(DIS, dis(have)))
        var pages = 0
        var firstDis = true

        // phase B (again after an EOM): DIS until a DCS and a good training
        outer@ while (true) {
            var dcs: ByteArray? = null
            var tries = 0
            while (dcs == null) {
                if (++tries > 4) throw FaxException(if (pages == 0) "no fax came" else "the sender stopped")
                val sent = if (firstDis) c.sendFrames(ours) else c.transmitFrames(ours)
                firstDis = false
                if (!sent) throw FaxException("the modem didn't send the DIS")
                // the sender's frames, to its final one
                while (true) {
                    val f = c.receiveFrame() ?: break
                    if (!f.ok) continue
                    when (f.fcf and X.inv()) {
                        TSI -> p = p.copy(remote = readIdent(f.fif))
                        DCS -> dcs = f.fif
                        DCN -> if (pages > 0) return p.copy(phase = "Received") else throw FaxException("the sender hung up")
                    }
                    if (f.final) break
                }
            }
            // its training, its pages
            var d = dcs!!
            while (true) {
                val rate = dcsRate(d) ?: throw FaxException("the sender asked for an unknown rate")
                val fine = bit(d, 15)
                p = p.copy(phase = "Training at ${rate.bps} bit/s", bps = rate.bps, fine = fine)
                progress(p)
                val r = c.command("+FRM=${rate.trainMod}", 10_000)
                val good = if (r == "CONNECT") {
                    val t = c.readData(5_000) ?: ByteArray(0)
                    c.result(3000)
                    tcfGood(t, rate.bps)
                } else {
                    false
                }
                if (!good) {
                    if (r == "+FCERROR") {
                        // (frames, not the training: a DCS again, say)
                        val f = c.receiveFrame()
                        if (f != null && f.fcf and X.inv() == DCS) { d = f.fif; continue }
                    }
                    c.transmitFrames(listOf(Class1.frame(FTT)))
                    val f = c.receiveFrame()
                    if (f != null && f.ok && f.fcf and X.inv() == DCS) { d = f.fif; continue }
                    continue@outer
                }
                c.transmitFrames(listOf(Class1.frame(CFR)))
                // the pages at this rate
                while (true) {
                    p = p.copy(phase = "Receiving page ${pages + 1}")
                    progress(p)
                    val m = c.command("+FRM=${rate.pageMod}", 15_000)
                    if (m == "+FCERROR") {
                        // the sender didn't hear the CFR: its DCS again
                        val f = c.receiveFrame()
                        if (f != null && f.fcf and X.inv() == DCS) { d = f.fif; break }
                        throw FaxException("the page didn't come")
                    }
                    if (m != "CONNECT") throw FaxException("the page didn't come ($m)")
                    val data = c.readData(30_000) ?: throw FaxException("the page stopped coming")
                    c.result(3000)
                    val got = T4.decode(data, FaxPage.WIDTH, fine)
                    // the post-message command (MPS, EOP, EOM)
                    var post: Int? = null
                    for (attempt in 0 until 3) {
                        val f = c.receiveFrame() ?: continue
                        if (!f.ok) continue
                        post = f.fcf and X.inv()
                        break
                    }
                    val okPage = got.page.rows > 0 && got.badRows * 10 < got.page.rows.coerceAtLeast(1)
                    if (post == null) throw FaxException("no end to page ${pages + 1}")
                    if (okPage) {
                        pages++
                        page(got.page, pages)
                        p = p.copy(pages = pages)
                        progress(p)
                    }
                    c.command("+FTS=8")
                    c.transmitFrames(listOf(Class1.frame(if (okPage) MCF else RTN)))
                    if (!okPage) {
                        // (it trains again: its DCS)
                        val f = c.receiveFrame()
                        if (f != null && f.fcf and X.inv() == DCS) { d = f.fif; break }
                        throw FaxException("page ${pages + 1} was bad and wasn't sent again")
                    }
                    when (post) {
                        MPS -> continue
                        EOM -> continue@outer
                        EOP -> {
                            // its DCN
                            c.receiveFrame()
                            return p.copy(phase = "Received")
                        }
                        else -> throw FaxException("the sender ended page $pages with %02x".format(post))
                    }
                }
            }
        }
    }
}
