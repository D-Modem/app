// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.modem

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** the line's recordings: where they go, the newest first, the last few kept */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LineRecordingsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun newestFirstLastKept() {
        val base = LineRecordings.path(ctx)!!
        for (t in listOf("20261026-110000", "20261026-120000", "20261025-090000", "20261026-130000")) {
            File("$base-$t.wav").writeText("RIFF")
        }
        File(File(base).parentFile, "capture.txt").writeText("not one")
        assertEquals(
            listOf("line-20261026-130000.wav", "line-20261026-120000.wav", "line-20261026-110000.wav", "line-20261025-090000.wav"),
            LineRecordings.all(ctx).map { it.name },
        )
        LineRecordings.prune(ctx, 2)
        assertEquals(listOf("line-20261026-130000.wav", "line-20261026-120000.wav"), LineRecordings.all(ctx).map { it.name })
    }
}
