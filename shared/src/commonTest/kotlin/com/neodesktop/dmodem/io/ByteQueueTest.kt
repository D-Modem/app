// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.io

import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals

/** A ByteQueue between two threads loses nothing, whatever its reads' timeouts. */
class ByteQueueTest {
    private class Flag { @Volatile var on = false }

    /** chunks put while the reader's short waits keep timing out all come, in order */
    @Test
    fun nothingLostToATimeout() {
        val q = ByteQueue()
        val n = 3000
        val done = Flag()
        background("writer") {
            for (i in 0 until n) {
                q.put(byteArrayOf((i shr 8).toByte(), i.toByte()))
                // (now and then just as the reader's wait runs out)
                if (i % 3 == 0) sleepMs(1)
            }
            done.on = true
        }
        var next = 0
        val end = monotonicMs() + 30_000
        while (next < n && monotonicMs() < end) {
            val b = q.poll(1) ?: continue
            val i = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
            assertEquals(next, i, "chunk $next")
            next++
        }
        assertEquals(n, next)
    }
}
