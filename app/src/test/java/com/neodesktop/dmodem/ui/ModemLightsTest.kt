// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The front panel's lights in their states: build/screens/lights.png; what's lit said for accessibility. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ModemLightsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theirStates() {
        compose.setContent {
            Column(
                Modifier.testTag("all").background(Color(0xfff5f5f7)).padding(16.dp).width(380.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // off; ready (answering calls); online with data both ways, error corrected; the compact strip online
                Panel(Lights())
                Panel(Lights(mr = true, tr = true, cs = true, aa = true))
                Panel(Lights(mr = true, tr = true, cs = true, aa = true, cd = true, oh = true, rd = true, sd = false, arq = true))
                Panel(Lights(mr = true, tr = true, cs = true, cd = true, oh = true, sd = true, arq = true), compact = true)
            }
        }
        compose.waitForIdle()
        val bmp = compose.onNodeWithTag("all").captureToImage().asAndroidBitmap()
        File("build/screens").mkdirs()
        File("build/screens/lights.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithContentDescription("Modem lights lit: MR, TR, CS, AA, CD, OH, RD, ARQ/FAX").assertExists()
        // a lit LED is bright red in the picture; an off one dark
        var bright = 0
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
            val p = bmp.getPixel(x, y)
            if ((p shr 16 and 0xff) > 230 && (p shr 8 and 0xff) in 40..120 && (p and 0xff) < 90) bright++
        }
        assertTrue("lit LEDs drawn: $bright", bright > 50)
    }
}
