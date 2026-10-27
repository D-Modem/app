// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.terminal.Terminal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The app's screens drawn (Robolectric's native graphics), into build/screens/ to look at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotsTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** the whole screen drawn into build/screens/[name].png */
    private fun shot(name: String) {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** home, terminal, dial, settings and log drawn, with some modem text in them */
    @Test
    fun screens() {
        // some text from a "modem" for the console and the terminal
        ModemState.console.append("ATX3\r\r\nOK\r\nATD5551234\r\r\nCONNECT 52000/V92\r\n")
        ModemState.log.add("dmodem0: created: 8000 Hz, USA")
        ModemState.log.add("sip: SIP on 192.168.1.20:5060 (wlan0)")
        ModemState.log.add("sip: registered with sip.example.com (200 OK)")
        synchronized(Terminal.session.emulator) {
            Terminal.session.emulator.appendText(
                "\u001b[1;37;44m╔══════════════════════════════╗\u001b[0m\r\n" +
                    "\u001b[1;37;44m║   Welcome to D-Modem BBS     ║\u001b[0m\r\n" +
                    "\u001b[1;37;44m╚══════════════════════════════╝\u001b[0m\r\n" +
                    "\u001b[33m[M]\u001b[0messages  \u001b[33m[F]\u001b[0miles  \u001b[33m[G]\u001b[0moodbye\r\n" +
                    "\u001b[32mSelect:\u001b[0m ",
            )
        }
        shot("1-home")
        compose.onAllNodesWithText("Terminal")[0].performClick()
        shot("2-terminal")
        compose.onAllNodesWithText("Dial")[0].performClick()
        shot("3-dial")
        compose.onAllNodesWithText("Settings")[0].performClick()
        shot("4-settings")
        compose.onAllNodesWithText("Log")[0].performClick()
        shot("5-log")
    }
}
