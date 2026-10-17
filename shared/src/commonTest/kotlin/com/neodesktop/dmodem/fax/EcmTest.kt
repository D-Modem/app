// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import com.neodesktop.dmodem.io.background
import com.neodesktop.dmodem.io.Latch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Error correction mode (T.30 Annex A): ECM's HDLC on the fast
 * modulations ([EcmHdlc]); calls between our sender and our receiver on
 * [FakeFaxLine]'s modems, the line damaging what it's told to: the
 * frames that didn't come asked for again (PPR), a lower rate after
 * four tries (CTC), the block given up (EOR); without ECM when one side
 * doesn't have it.
 */
class EcmTest {

    @Test
    fun hdlcThereAndBack() {
        val r = Random(7)
        // (flags and ones in the data: stuffing)
        val frames = List(20) { k ->
            byteArrayOf(0xff.toByte(), 0x03, 0x06, k.toByte()) +
                ByteArray(256) { i -> if (k % 3 == 0) 0x7e else if (k % 3 == 1) 0xff.toByte() else r.nextInt(256).toByte() }
        }
        val bits = EcmHdlc.encode(frames, preambleFlags = 30)
        val back = EcmHdlc.decode(bits)
        assertEquals(frames.size, back.size)
        for (i in frames.indices) assertContentEquals(frames[i], back[i])
        // a bit wrong in the middle: that frame (or one either side) lost, the rest there
        val bad = bits.copyOf()
        bad[bad.size / 2] = (bad[bad.size / 2].toInt() xor 0x10).toByte()
        val got = EcmHdlc.decode(bad)
        assertTrue(got.size in frames.size - 2 until frames.size, "${got.size}")
        assertTrue(got.all { g -> frames.any { it.contentEquals(g) } })
    }

    /** a burst with its first frame spoilt (past the 200 ms of flags before it: 360 octets at most) */
    private fun spoil(d: ByteArray) = d.copyOf().also { it[400] = (it[400].toInt() xor 0x55).toByte() }

    /** a page of noise: big in MH (several ECM blocks) */
    private fun noisyPage(rows: Int, seed: Int): FaxPage {
        val p = FaxPage(FaxPage.WIDTH, rows, fine = true)
        val r = Random(seed)
        for (y in 0 until rows) for (x in 0 until p.width step 4) if (r.nextBoolean()) for (k in 0 until 4) p.set(x + k, y, true)
        return p
    }

    private class Result(val sent: FaxProgress?, val received: FaxProgress?, val pages: List<FaxPage>, val txError: Throwable?, val rxError: Throwable?)

    private fun call(line: FakeFaxLine, pages: List<FaxPage>, txEcm: Boolean = true, rxEcm: Boolean = true): Result {
        val tx = line.a.c1
        val rx = line.b.c1
        val have = T30.modemRates(tx)
        T30.modemRates(rx)
        val got = ArrayList<FaxPage>()
        var received: FaxProgress? = null
        var rxError: Throwable? = null
        val done = Latch()
        rx.send("A")
        assertEquals("CONNECT", rx.result(5000))
        background("receiver") {
            try {
                received = T30.receive(rx, "+1 555 0199", have, ecm = rxEcm) { pg, _ -> got.add(pg) }
            } catch (e: Throwable) {
                rxError = e
            }
            done.countDown()
        }
        tx.send("D")
        assertEquals("CONNECT", tx.result(5000))
        var sent: FaxProgress? = null
        var txError: Throwable? = null
        try {
            sent = T30.send(tx, pages, "+1 555 0100", have, ecm = txEcm)
        } catch (e: Throwable) {
            txError = e
        }
        done.await()
        line.stop()
        return Result(sent, received, got, txError, rxError)
    }

    private fun assertPages(want: List<FaxPage>, r: Result) {
        assertEquals(null, r.txError)
        assertEquals(null, r.rxError)
        assertEquals(want.size, r.sent!!.pages)
        assertEquals(want.size, r.pages.size)
        for (i in want.indices) {
            assertEquals(want[i].rows, r.pages[i].rows)
            assertContentEquals(want[i].bits, r.pages[i].bits, "page ${i + 1}")
        }
    }

    @Test
    fun aCallInEcm() {
        val line = FakeFaxLine()
        val pages = listOf(noisyPage(1200, 1), FaxTestPage.of(true, rows = 400, seed = 2))
        val r = call(line, pages)
        assertPages(pages, r)
        assertTrue(r.sent!!.ecm)
        assertTrue(r.received!!.ecm)
        val seen = line.seen()
        // (no lower rate asked for)
        assertTrue(seen.none { it == "A>13" }, "$seen")
        // the noisy page in blocks: PPS more than once for it, MCF to each, no PPR
        assertTrue(seen.count { it == "A>bf" } >= 3, "$seen")
        assertTrue(seen.none { it == "B>bc" }, "$seen")
        assertTrue("B>8c" in seen)
    }

    @Test
    fun theFramesThatDidNotComeAgain() {
        val line = FakeFaxLine()
        // the first block, the first time: three places spoilt
        line.damage = { _, n, d ->
            if (n == 1) d.copyOf().also { for (at in listOf(5000, 20000, 40000)) if (at < it.size) it[at] = (it[at].toInt() xor 0x55).toByte() } else d
        }
        val pages = listOf(noisyPage(300, 3))
        val r = call(line, pages)
        assertPages(pages, r)
        val seen = line.seen()
        assertEquals(1, seen.count { it == "B>bc" }, "$seen")
        assertEquals(14400, r.sent!!.bps)
    }

    @Test
    fun aLowerRateAfterFourTries() {
        val line = FakeFaxLine()
        // every burst spoilt four times: then the rate down a step (CTC, CTR), with the long training
        line.damage = { _, n, d ->
            if (n in 1..4) spoil(d) else d
        }
        val pages = listOf(FaxTestPage.of(true, rows = 400, seed = 4))
        val r = call(line, pages)
        assertPages(pages, r)
        val seen = line.seen()
        assertEquals(4, seen.count { it == "B>bc" }, "$seen")
        assertTrue("A>13" in seen, "$seen")
        assertTrue("B>c4" in seen, "$seen")
        assertEquals(12000, r.sent!!.bps)
    }

    @Test
    fun aBlockGivenUp() {
        val line = FakeFaxLine()
        // the first page's first block never gets through: EOR, ERR, and the call goes on
        var spoilt = 0
        line.damage = { _, n, d ->
            if (spoilt < 16 && n >= 1) { spoilt++; spoil(d) } else d
        }
        val pages = listOf(FaxTestPage.of(true, rows = 400, seed = 5), FaxTestPage.of(true, rows = 200, seed = 6))
        val r = call(line, pages)
        assertEquals(null, r.txError)
        assertEquals(null, r.rxError)
        val seen = line.seen()
        assertTrue("A>cf" in seen, "$seen")
        assertTrue("B>1c" in seen, "$seen")
        assertEquals(2, r.sent!!.pages)
        // (the second page whole)
        assertContentEquals(pages[1].bits, r.pages.last().bits)
    }

    @Test
    fun withoutEcmWhenTheReceiverHasNone() {
        val line = FakeFaxLine()
        val pages = listOf(FaxTestPage.of(false, rows = 300, seed = 7))
        val r = call(line, pages, rxEcm = false)
        assertPages(pages, r)
        assertTrue(!r.sent!!.ecm)
        assertTrue(line.seen().none { it == "A>bf" })
    }
}
