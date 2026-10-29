// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.neodesktop.dmodem.NativeModem
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.ModemSettings
import com.neodesktop.dmodem.settings.SettingsStore
import com.neodesktop.dmodem.terminal.DialEntry
import com.neodesktop.dmodem.terminal.TermCharset
import com.neodesktop.dmodem.terminal.Terminal
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Home in its states, drawn into build/screens/ to look at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DashboardShotsTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** the screen as it is, into build/screens/[name].png */
    private fun shot(name: String) {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Home on a fresh install, drawn: Set up SIP and Start without a line */
    @Test
    fun firstRun() {
        // (the store and the directory are the process's: as a fresh install has them)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        runBlocking { SettingsStore(ctx).save(ModemSettings()) }
        Terminal.directory.items.value.forEach { Terminal.directory.remove(it) }
        ModemState.console.clear()
        ModemState.setRun(ModemState.Run.STOPPED)
        ModemState.setStatus(ModemState.IDLE)
        compose.waitForIdle()
        shot("home-1-first-run")
        // no SIP account yet: set one up, or start the modem on its own
        compose.onNodeWithText("Set up SIP").assertExists()
        compose.onNodeWithText("Start without a line").assertExists()
    }

    /** Home with an account and a directory, ready, then online, drawn */
    @Test
    fun readyAndOnline() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        runBlocking {
            SettingsStore(ctx).save(ModemSettings(user = "alice", password = "x", domain = "sip.example.net", listen = true))
        }
        val hour = 3_600_000L
        val now = System.currentTimeMillis()
        Terminal.directory.put(DialEntry(name = "Level 29 BBS", number = "18005551229", charset = TermCharset.CP437, lastDialed = now - 2 * hour, dialCount = 7))
        Terminal.directory.put(DialEntry(name = "NIST time service", number = "3034944774", initCommands = "AT+MS=132,0,4800,9600", lastDialed = now - 50 * hour, dialCount = 2))
        Terminal.directory.put(DialEntry(name = "Dial-up ISP", number = "5551000", lastDialed = now - 200 * hour, dialCount = 15))
        Terminal.directory.put(DialEntry(name = "Friend's D-Modem", number = "bob", dialCount = 0))
        ModemState.console.append("ATX3\r\r\nOK\r\n")
        ModemState.setRun(ModemState.Run.RUNNING)
        ModemState.setStatus(NativeModem.Status(NativeModem.Link.IDLE, false, 0, 0))
        shot("home-2-ready")

        ModemState.console.append("ATD18005551229\r\r\nCONNECT 52000/V92\r\n")
        ModemState.setStatus(NativeModem.Status(NativeModem.Link.ONLINE, true, 33600, 52000))
        compose.mainClock.advanceTimeBy(1000)
        shot("home-3-online")
        ModemState.setRun(ModemState.Run.STOPPED)
        ModemState.setStatus(ModemState.IDLE)
    }
}
