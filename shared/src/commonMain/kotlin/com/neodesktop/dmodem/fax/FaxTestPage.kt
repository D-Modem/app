// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import kotlin.random.Random

/** A fax test page: a frame, bars of every width, text-like dashes, a checkerboard, a black row. */
object FaxTestPage {
    fun of(fine: Boolean, rows: Int = 400, seed: Int = 1): FaxPage {
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
