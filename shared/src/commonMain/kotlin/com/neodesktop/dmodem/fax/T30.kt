// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import com.neodesktop.dmodem.io.*


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
    /** in error correction mode (T.30 Annex A) */
    val ecm: Boolean = false,
)

/** a fax call that didn't work: why */
class FaxException(msg: String) : IOException(msg)

/**
 * The T.30 procedure for group 3 fax on a class 1 modem ([Class1]): the
 * calling machine sends (no polling), the answering one receives, in
 * MH coding, with error correction mode when both have it (T.30 Annex
 * A: the page in blocks of numbered HDLC frames on the fast modulation,
 * [EcmHdlc], those that didn't come asked for again) or without. The
 * frames' octets as the class 1 modem has them (the first bit on the
 * line at the bottom).
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
    // error correction mode's
    const val PPS = 0xbe
    const val PPR = 0xbc
    const val RR = 0x6e
    const val RNR = 0xec
    const val CTC = 0x12
    const val CTR = 0xc4
    const val EOR = 0xce
    const val ERR = 0x1c
    const val FCD = 0x06
    const val RCP = 0x86
    const val X = 0x01

    /** an ECM frame's page data (256 octets: DCS bit 28 clear) and a block's frames at most */
    const val ECM_SIZE = 256
    const val ECM_FRAMES = 256
    /** PPRs for a block before CTC, CTCs before EOR */
    private const val ECM_PPRS = 4
    private const val ECM_CTCS = 3

    /** a rate: its DCS code (bits 11-14), its modulation's class 1 codes (long training, and for the pages) */
    data class Rate(val bps: Int, val code: Int, val family: Int, val trainMod: Int, val pageMod: Int)

    val RATES = listOf(
        Rate(14400, 0x8, 2, 145, 146), Rate(12000, 0xa, 2, 121, 122),
        Rate(9600, 0x9, 2, 97, 98), Rate(7200, 0xb, 2, 73, 74),
        Rate(9600, 0x1, 1, 96, 96), Rate(7200, 0x3, 1, 72, 72),
        Rate(4800, 0x2, 0, 48, 48), Rate(2400, 0x0, 0, 24, 24),
    )

    /** whether a FIF's bit [n] is set (T.30's numbering: 1 is the first octet's bottom bit; false past its end) */
    fun bit(fif: ByteArray, n: Int): Boolean = (n - 1) / 8 < fif.size && (fif[(n - 1) / 8].toInt() shr ((n - 1) % 8)) and 1 == 1

    /** a FIF's bit [n] set (T.30's numbering) */
    fun setBit(fif: ByteArray, n: Int) {
        fif[(n - 1) / 8] = (fif[(n - 1) / 8].toInt() or (1 shl ((n - 1) % 8))).toByte()
    }

    /** a DIS or DCS's rate field, bits 11-14, as a number (bit 11 the lowest) */
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

    /** our DIS: a receiver of MH, the [rates], fine, A4, 5 ms minimum scan time; error correction mode if [ecm] */
    fun dis(rates: List<Rate>, ecm: Boolean = false): ByteArray {
        val f = ByteArray(if (ecm) 4 else 3)
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
        if (ecm) { setBit(f, 24); setBit(f, 27) }
        return f
    }

    /** a DCS: the rate, fine or not, the minimum scan time agreed; error correction mode (256 octet frames) if [ecm] */
    fun dcs(r: Rate, fine: Boolean, minMs: Int, ecm: Boolean = false): ByteArray {
        val f = ByteArray(if (ecm) 4 else 3)
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
        if (ecm) { setBit(f, 24); setBit(f, 27) }
        return f
    }

    /** a DIS or DCS with error correction mode */
    fun ecm(fif: ByteArray) = bit(fif, 24) && bit(fif, 27)

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

    /** a CSI or TSI's text: its 20 characters back the right way, what isn't printable as spaces, trimmed */
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
        /** error correction mode when the receiver has it */
        ecm: Boolean = true,
        progress: (FaxProgress) -> Unit = {},
    ): FaxProgress {
        if (pages.isEmpty()) throw FaxException("no pages")
        var p = FaxProgress("Waiting for the fax machine", total = pages.size)
        progress(p)
        // phase B: the answerer's frames, up to its DIS (as the modem got them since its CONNECT)
        var dis: ByteArray? = null
        var remote = ""
        var first = true
        val t1 = monotonicMs() + 35_000
        while (dis == null && monotonicMs() < t1) {
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
        val useEcm = ecm && ecm(d)
        // (ECM has no minimum row time)
        val minMs = if (useEcm) 0 else disMinMs(d)
        p = p.copy(remote = remote, fine = fine, ecm = useEcm)

        // training: down the rates until one takes
        var ri = 0
        /** TSI, DCS and training at each rate in turn (three tries without an answer) until a CFR: that rate */
        fun train(): Rate {
            var tries = 0
            while (ri < rates.size) {
                val r = rates[ri]
                p = p.copy(phase = "Training at ${r.bps} bit/s", bps = r.bps)
                progress(p)
                c.command("+FTS=8")
                if (!c.transmitFrames(listOf(Class1.frame(TSI or X, ident(ident)), Class1.frame(DCS or X, dcs(r, fine, minMs, useEcm))))) {
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

        /** a command (a control frame) until a good answer: up to three times, RNR asked again with RR (60 s) */
        fun ask(cmd: ByteArray): Class1.Frame? {
            var frame = cmd
            var rnrs = 0
            var attempt = 0
            while (attempt < 3) {
                c.command("+FTS=8")
                if (!c.transmitFrames(listOf(frame))) throw FaxException("the modem didn't send a command")
                val f = c.receiveFrame(4_000)
                if (f == null || !f.ok || f.fcf and X.inv() == CRP) { attempt++; continue }
                if (f.fcf and X.inv() == RNR) {
                    if (++rnrs > 60) throw FaxException("the fax machine wasn't ready")
                    sleepMs(1000)
                    frame = Class1.frame(RR or X)
                    attempt = 0
                    continue
                }
                return f
            }
            return null
        }

        /** frames on the page's modulation (after CTC, with the long training), RCP three times after */
        fun burst(frames: List<ByteArray>, long: Boolean) {
            val rcp = Class1.frame(RCP)
            val bits = EcmHdlc.encode(frames + listOf(rcp, rcp, rcp), EcmHdlc.flagsFor(rate.bps, 200))
            if (c.command("+FTM=${if (long) rate.trainMod else rate.pageMod}", 10_000) != "CONNECT") {
                throw FaxException("the modem didn't send the page")
            }
            c.sendData(bits)
            if (c.result(120_000) != "OK") throw FaxException("the page didn't go out")
        }

        /** a page in error correction mode ([pageNo] of the call), its blocks each until all came; its end with [post] */
        fun sendEcm(data: ByteArray, post: Int, pageNo: Int) {
            val perBlock = ECM_SIZE * ECM_FRAMES
            val blocks = maxOf(1, (data.size + perBlock - 1) / perBlock)
            for (blk in 0 until blocks) {
                val start = blk * perBlock
                val len = minOf(perBlock, data.size - start).coerceAtLeast(0)
                val n = maxOf(1, (len + ECM_SIZE - 1) / ECM_SIZE)
                // FCD: the frame's number, its 256 octets (the last padded)
                val frames = List(n) { k ->
                    val f = Class1.frame(FCD, ByteArray(1 + ECM_SIZE))
                    f[3] = k.toByte()
                    val from = start + k * ECM_SIZE
                    val m = minOf(ECM_SIZE, data.size - from).coerceAtLeast(0)
                    data.copyInto(f, 4, from, from + m)
                    f
                }
                val last = blk == blocks - 1
                val pps = Class1.frame(PPS or X, byteArrayOf(if (last) (post or X).toByte() else 0, pageNo.toByte(), blk.toByte(), (n - 1).toByte()))
                val send = BooleanArray(n) { true }
                var pprs = 0
                var ctcs = 0
                var long = false
                block@ while (true) {
                    burst(frames.filterIndexed { k, _ -> send[k] }, long)
                    long = false
                    var f = ask(pps) ?: throw FaxException("no answer to page ${pageNo + 1}'s block ${blk + 1}")
                    while (true) {
                        when (f.fcf and X.inv()) {
                            MCF -> break@block
                            PPR -> {
                                val map = f.fif
                                var any = false
                                for (k in 0 until n) {
                                    send[k] = k / 8 < map.size && (map[k / 8].toInt() shr (k % 8)) and 1 == 1
                                    any = any || send[k]
                                }
                                if (!any) {
                                    // (nothing missing after all: ask again)
                                    f = ask(pps) ?: throw FaxException("no answer to page ${pageNo + 1}'s block ${blk + 1}")
                                    continue
                                }
                                if (++pprs < ECM_PPRS) continue@block
                                pprs = 0
                                if (++ctcs <= ECM_CTCS) {
                                    // continue to correct: a rate lower if there is one, the long training
                                    if (ri + 1 < rates.size) ri++
                                    rate = rates[ri]
                                    p = p.copy(phase = "Sending page ${pageNo + 1} at ${rate.bps} bit/s", bps = rate.bps)
                                    progress(p)
                                    val ctc = Class1.frame(CTC or X, byteArrayOf(0, (rate.code shl 2).toByte()))
                                    val r = ask(ctc)
                                    if (r?.fcf?.and(X.inv()) != CTR) throw FaxException("the fax machine didn't take CTC")
                                    long = true
                                    continue@block
                                }
                                // given up on the block: what came of it stays
                                val eor = Class1.frame(EOR or X, byteArrayOf(if (last) (post or X).toByte() else 0))
                                val r = ask(eor)
                                if (r?.fcf?.and(X.inv()) != ERR) throw FaxException("page ${pageNo + 1} didn't go through")
                                break@block
                            }
                            DCN -> throw FaxException("the fax machine hung up during page ${pageNo + 1}")
                            else -> throw FaxException("the fax machine answered page ${pageNo + 1}'s block with ${hex2(f.fcf)}")
                        }
                    }
                }
            }
        }

        var i = 0
        var resent = 0
        while (i < pages.size) {
            val page = pages[i]
            p = p.copy(phase = "Sending page ${i + 1} of ${pages.size}", bps = rate.bps)
            progress(p)
            if (useEcm) {
                sendEcm(T4.encode(page, 0), if (i == pages.lastIndex) EOP else MPS, i)
                i++
                p = p.copy(pages = i)
                progress(p)
                continue
            }
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
                else -> throw FaxException("the fax machine answered page ${i + 1} with ${hex2(answer)}")
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
        /** error correction mode offered */
        ecm: Boolean = true,
        page: (FaxPage, Int) -> Unit,
    ): FaxProgress {
        var p = FaxProgress("Answering")
        progress(p)
        val ours = listOf(Class1.frame(CSI, ident(ident)), Class1.frame(DIS, dis(have, ecm)))
        var pages = 0
        var firstDis = true
        // (ECM: the page and block of our last MCF, for a PPS again that missed it)
        var lastMcf: Pair<Int, Int>? = null
        // (ECM: the rate a CTC changed to, until the next DCS)
        var ecmRate: Rate? = null

        /** what ended an ECM page: its post command (MPS, EOP, EOM), a DCS (training again), the sender's DCN */
        class EcmEnd(val post: Int = -1, val dcs: ByteArray? = null, val dcn: Boolean = false)

        /** a page in error correction mode at [r0]: its blocks, our PPR for frames missing, MCF; the page to [page] */
        fun receiveEcm(r0: Rate, fine: Boolean): EcmEnd {
            var rate = r0
            val doc = Bytes()
            val got = arrayOfNulls<ByteArray>(ECM_FRAMES)
            var count = 0
            var long = false
            var last: ByteArray? = null
            /** our answer [fr] sent (and kept, to send again) */
            fun respond(fr: ByteArray) {
                last = fr
                c.command("+FTS=8")
                c.transmitFrames(listOf(fr))
            }
            /** the page's data decoded and, unless empty or over 5% bad rows, to [page]; the page ended by [post] */
            fun pageDone(post: Int): EcmEnd {
                val dec = T4.decode(doc.toByteArray(), FaxPage.WIDTH, fine)
                if (dec.page.rows > 0 && dec.badRows * 20 < dec.page.rows) {
                    pages++
                    page(dec.page, pages)
                    p = p.copy(pages = pages)
                    progress(p)
                }
                return EcmEnd(post = post)
            }
            while (true) {
                p = p.copy(phase = "Receiving page ${pages + 1}", bps = rate.bps)
                progress(p)
                val m = c.command("+FRM=${if (long) rate.trainMod else rate.pageMod}", 15_000)
                long = false
                var ctl: Class1.Frame? = null
                when (m) {
                    "CONNECT" -> {
                        val data = c.readData(120_000) ?: throw FaxException("the page stopped coming")
                        c.result(3000)
                        for (f in EcmHdlc.decode(data)) {
                            if (f.size < 4 || f[0] != 0xff.toByte() || (f[2].toInt() and 0xfe) != FCD) continue
                            got[f[3].toInt() and 0xff] = f.copyOfRange(4, f.size)
                        }
                        for (attempt in 0 until 3) {
                            val f = c.receiveFrame() ?: continue
                            if (f.ok) { ctl = f; break }
                        }
                    }
                    // (V.21 instead: a command again, or the DCS)
                    "+FCERROR" -> ctl = c.receiveFrame()?.takeIf { it.ok }
                    else -> throw FaxException("the page didn't come ($m)")
                }
                val f = ctl ?: throw FaxException("no end to a block of page ${pages + 1}")
                val fif = f.fif
                when (f.fcf and X.inv()) {
                    PPS -> {
                        if (fif.size < 4) continue
                        val post = fif[0].toInt() and 0xfe
                        val at = (fif[1].toInt() and 0xff) to (fif[2].toInt() and 0xff)
                        if (at == lastMcf && got.all { it == null }) {
                            // our MCF didn't get there
                            respond(Class1.frame(MCF))
                            if (post != 0) return EcmEnd(post = post)
                            continue
                        }
                        count = maxOf(count, (fif[3].toInt() and 0xff) + 1)
                        val map = ByteArray(32)
                        var missing = 0
                        for (k in 0 until count) if (got[k] == null) { map[k / 8] = (map[k / 8].toInt() or (1 shl (k % 8))).toByte(); missing++ }
                        if (missing > 0) {
                            respond(Class1.frame(PPR, map))
                            continue
                        }
                        for (k in 0 until count) doc.write(got[k]!!)
                        got.fill(null)
                        count = 0
                        lastMcf = at
                        respond(Class1.frame(MCF))
                        if (post != 0) return pageDone(post)
                    }
                    CTC -> {
                        // continue to correct, maybe at another rate: the long training next
                        dcsRate(fif)?.let { rate = it; ecmRate = it }
                        long = true
                        respond(Class1.frame(CTR))
                    }
                    EOR -> {
                        // the sender gave up on the block: what came of it lost
                        got.fill(null)
                        count = 0
                        respond(Class1.frame(ERR))
                        val post = if (fif.isNotEmpty()) fif[0].toInt() and 0xfe else 0
                        if (post != 0) return pageDone(post)
                    }
                    RR -> last?.let { respond(it) }
                    DCS -> return EcmEnd(dcs = fif)
                    DCN -> return EcmEnd(dcn = true)
                    else -> throw FaxException("the sender sent ${hex2(f.fcf)} during page ${pages + 1}")
                }
            }
        }

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
                val inEcm = ecm && ecm(d)
                ecmRate = null
                p = p.copy(phase = "Training at ${rate.bps} bit/s", bps = rate.bps, fine = fine, ecm = inEcm)
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
                if (inEcm) {
                    // the pages in error correction mode
                    var e: EcmEnd
                    while (true) {
                        e = receiveEcm(ecmRate ?: rate, fine)
                        if (e.post != MPS) break
                    }
                    when {
                        e.dcs != null -> { d = e.dcs; continue }
                        e.dcn -> if (pages > 0) return p.copy(phase = "Received") else throw FaxException("the sender hung up")
                        e.post == EOM -> continue@outer
                        e.post == EOP -> {
                            c.receiveFrame()
                            return p.copy(phase = "Received")
                        }
                        else -> throw FaxException("the sender ended page $pages with ${hex2(e.post)}")
                    }
                }
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
                        else -> throw FaxException("the sender ended page $pages with ${hex2(post)}")
                    }
                }
            }
        }
    }
}
