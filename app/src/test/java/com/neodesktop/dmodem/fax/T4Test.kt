// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** T.4's MH coding both ways; T.30's DIS/DCS bits and identities. */
class T4Test {

    companion object {
        /** a test page: a frame, bars of every width, text-like dashes, a checkerboard, a black row */
        fun testPage(fine: Boolean, rows: Int = 400, seed: Int = 1): FaxPage {
            val p = FaxPage(FaxPage.WIDTH, rows, fine)
            val r = Random(seed)
            for (y in 0 until rows) for (x in 0 until p.width) {
                val on = when {
                    x < 4 || x >= p.width - 4 || y < 4 || y >= rows - 4 -> true
                    y in 20..60 -> (x / ((y - 19) % 9 + 1)) % 2 == 0
                    y in 80..200 -> (x / 6 + y / 10) % 7 == 0 || r.nextInt(97) == 0
                    y in 220..300 -> (x / 3 + y / 3) % 2 == 0
                    y == 320 -> true
                    else -> false
                }
                if (on) p.set(x, y, true)
            }
            return p
        }
    }

    @Test
    fun aPageThereAndBack() {
        for (fine in listOf(false, true)) {
            val page = testPage(fine)
            val data = T4.encode(page)
            val back = T4.decode(data, fine = fine)
            assertEquals(0, back.badRows)
            assertEquals(page.rows, back.page.rows)
            assertArrayEquals(page.bits, back.page.bits)
        }
    }

    @Test
    fun theMinimumScanTimeAsFill() {
        val page = FaxPage(FaxPage.WIDTH, 10, false)
        // an all-white row: 1728 (9 bits) + 0 (8 bits) + EOL (12): 29 bits; at least 200 asked
        val plain = T4.encode(page)
        val filled = T4.encode(page, minBits = 200)
        assertTrue(filled.size >= 10 * 200 / 8)
        assertTrue(plain.size < filled.size)
        assertEquals(10, T4.decode(filled, fine = false).page.rows)
    }

    @Test
    fun aDamagedRowIsTheOneBeforeItAgain() {
        val page = testPage(false, rows = 120)
        val data = T4.encode(page)
        // a byte in the middle spoilt: a row or two lost, the rest still there
        data[data.size / 2] = (data[data.size / 2].toInt() xor 0xff).toByte()
        val back = T4.decode(data, fine = false)
        assertTrue("bad rows: ${back.badRows}", back.badRows in 1..3)
        assertEquals(page.rows, back.page.rows)
    }

    @Test
    fun theCodesArePrefixFree() {
        // (decoding random data never loops nor reads past the end)
        val junk = Random(3).nextBytes(20_000)
        T4.decode(junk, fine = false)
    }

    @Test
    fun disAndDcs() {
        val dis = T30.dis(T30.RATES)
        assertTrue(T30.bit(dis, 10))
        assertTrue(T30.bit(dis, 15))
        assertFalse(T30.bit(dis, 16))
        assertEquals(5, T30.disMinMs(dis))
        assertTrue(T30.RATES.all { T30.disOffers(dis, it) })
        val v29 = T30.dis(T30.RATES.filter { it.family < 2 })
        assertFalse(T30.disOffers(v29, T30.RATES.first()))
        assertTrue(T30.disOffers(v29, T30.RATES.first { it.bps == 9600 && it.family == 1 }))
        for (r in T30.RATES) {
            val d = T30.dcs(r, true, 10)
            assertEquals(r, T30.dcsRate(d))
            assertEquals(10, T30.dcsMinMs(d))
            assertTrue(T30.bit(d, 15))
        }
        // the identity: last character first, spaces after
        val id = T30.ident("+1 555 0100")
        assertEquals('0'.code.toByte(), id[0])
        assertEquals("+1 555 0100", T30.readIdent(id))
    }
}
