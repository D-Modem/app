// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.neodesktop.dmodem.NativeModem
import com.neodesktop.dmodem.fax.Fax
import com.neodesktop.dmodem.fax.FaxProgress
import com.neodesktop.dmodem.fax.FaxRecord
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.ModemSettings
import com.neodesktop.dmodem.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The fax on Home (a call going, the last ones), and its settings: build/screens/fax-*.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class FaxShotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** the screen (or the dialog, when [dialog]) as it is, into build/screens/[name].png */
    private fun shot(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        val bmp = node.captureToImage().asAndroidBitmap()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Home with a fax being sent and the last ones, drawn */
    @Test
    fun faxOnHome() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        runBlocking { SettingsStore(ctx).save(ModemSettings(user = "alice", password = "x", domain = "sip.example.net")) }
        ModemState.setRun(ModemState.Run.RUNNING)
        ModemState.setStatus(ModemState.IDLE)
        val h = Fax.history(ctx)
        val now = System.currentTimeMillis()
        h.put(FaxRecord("1", now - 86_400_000, true, "18005550142", "+1 800 555 0142", 3, ""))
        h.put(FaxRecord("2", now - 7_200_000, false, "", "+44 20 7946 0958", 2, "", "fax-20261016-101500.pdf"))
        h.put(FaxRecord("3", now - 600_000, true, "5550100", "", 0, "busy"))
        Fax.show(Fax.State(true, FaxProgress("Sending page 2 of 3", pages = 1, total = 3, bps = 14400, remote = "+1 800 555 0142"), number = "18005550142"))
        // (adaptive answer: no "Answer as fax", a fax call is one by itself)
        compose.onNodeWithText("Send a fax…").performScrollTo()
        shot("fax-1-home")
        Fax.show(null)
    }

    /** Home connected to the internet, with its address and counts, drawn */
    @Test
    fun internetOnHome() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        runBlocking { com.neodesktop.dmodem.settings.IspSettingsStore(ctx).save(com.neodesktop.dmodem.settings.IspSettings("18005551000", "alice", "x")) }
        ModemState.setRun(ModemState.Run.RUNNING)
        ModemState.setStatus(ModemState.IDLE)
        val cfg = com.neodesktop.dmodem.ppp.Ppp.IpConfig(
            java.net.InetAddress.getByName("192.0.2.42") as java.net.Inet4Address, null,
            listOf(java.net.InetAddress.getByName("192.0.2.1") as java.net.Inet4Address), 1500,
        )
        com.neodesktop.dmodem.ppp.Internet.show(
            com.neodesktop.dmodem.ppp.Internet.State("", cfg, "CONNECT 50666/V92/LAPM/V44", System.currentTimeMillis() - 754_000, 1_843_200, 96_512),
        )
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        val bmp = compose.onNodeWithText("Connected to the internet").let { compose.onRoot() }.captureToImage().asAndroidBitmap()
        File("build/screens").mkdirs()
        File("build/screens/internet-1-home.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        com.neodesktop.dmodem.ppp.Internet.show(null)
    }

    /** the fax settings page, drawn */
    @Test
    fun faxSettings() {
        SettingsNav.open(SettingsPage.FAX)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Fastest rate").performScrollTo()
        shot("fax-3-settings")
    }
}
