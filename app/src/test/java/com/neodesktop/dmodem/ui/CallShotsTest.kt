// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.neodesktop.dmodem.modem.ModemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A call ringing in: the popup over the app, ignored, answered: build/screens/call-*.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CallShotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun aCallRingingIn() {
        ModemState.incoming.end()
        ModemState.incoming.caller("D-Modem on Pixel 8")
        ModemState.incoming.modemText("\r\nRING\r\n")
        compose.waitForIdle()
        compose.onNodeWithText("Incoming call").assertExists()
        compose.onNodeWithText("D-Modem on Pixel 8").assertExists()
        val bmp = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(File("build/screens").apply { mkdirs() }, "call-1-incoming.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // ignored: out of sight while it rings on
        compose.onNodeWithText("Ignore").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Incoming call").assertDoesNotExist()
        ModemState.incoming.modemText("RING\r\n")
        compose.waitForIdle()
        compose.onNodeWithText("Incoming call").assertDoesNotExist()
        assertTrue(ModemState.incoming.state.value!!.ignored)
        // the next call: answered, to the terminal (no modem here to say ATA to)
        ModemState.incoming.end()
        ModemState.incoming.caller(null)
        ModemState.incoming.modemText("RING\r\n")
        compose.waitForIdle()
        compose.onNodeWithText("A SIP call").assertExists()
        compose.onNodeWithText("Answer").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Incoming call").assertDoesNotExist()
        assertNull(ModemState.incoming.state.value)
        assertEquals(true, compose.onAllNodes(androidx.compose.ui.test.hasText("Terminal")).fetchSemanticsNodes().isNotEmpty())
    }
}
