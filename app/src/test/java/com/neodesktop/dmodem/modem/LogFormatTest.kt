// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class LogFormatTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-10-26 04:12:03.417 UTC, and 23:59:59.999 that day, and the next day's first second
    private val t = 1792987923417L
    private val lastOfDay = 1793059199999L

    /** the default stamp on each line, a line for each new day */
    @Test
    fun stampedWithADayLine() = assertEquals(
        listOf("---- Monday 26 October 2026 ----", "[04:12:03.417] app: one", "[23:59:59.999] app: two",
            "---- Tuesday 27 October 2026 ----", "[00:00:00.000] app: three"),
        LogFormat.render(listOf(LogLine(t, "app: one"), LogLine(lastOfDay, "app: two"), LogLine(lastOfDay + 1, "app: three")), LogFormat.DEFAULT, utc),
    )

    /** no stamp: the lines as they are, still the day's line */
    @Test
    fun noStamp() = assertEquals(
        listOf("---- Monday 26 October 2026 ----", "app: one"),
        LogFormat.render(listOf(LogLine(t, "app: one")), "", utc),
    )

    /** another pattern's stamp; one that isn't valid, the default's */
    @Test
    fun otherPatternsAndBadOnes() {
        assertEquals("2026-10-26T04:12:03 app: one", LogFormat.render(listOf(LogLine(t, "app: one")), "yyyy-MM-dd'T'HH:mm:ss", utc)[1])
        assertFalse(LogFormat.valid("HH:mm:ss.msec"))
        assertTrue(LogFormat.valid("[HH:mm:ss.SSS]"))
        // (one that isn't a pattern: the default's)
        assertEquals("[04:12:03.417] app: one", LogFormat.render(listOf(LogLine(t, "app: one")), "HH:mm:ss.msec", utc)[1])
    }

    /** the buffer keeps its last lines, each with the time it was added */
    @Test
    fun theBufferTimesItsLines() {
        var now = t
        val b = LineBuffer(2) { now }
        b.add("a")
        now += 5
        b.add("b")
        b.add("c")
        assertEquals(listOf("b", "c"), b.flow.value)
        assertEquals(listOf(LogLine(t + 5, "b"), LogLine(t + 5, "c")), b.entries.value)
    }
}
