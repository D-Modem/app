// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.peer.PeerLine
import com.neodesktop.dmodem.peer.Peers
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

/** The line to another phone: its settings, Home on a call: build/screens/peer-*.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PeerShotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun save(s: ModemSettings) {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        runBlocking { SettingsStore(ctx).save(s) }
    }

    @Test
    fun theSettings() {
        save(ModemSettings(line = ModemSettings.LINE_WIFI, peerAddress = "192.168.1.20", listen = true))
        SettingsNav.open(SettingsPage.LINE)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Find phones on this network").performScrollTo()
        shot("peer-1-settings")
        compose.onNodeWithText("Another phone with D-Modem, on Wi-Fi").assertExists()
    }

    @Test
    fun theLineFromItsMenu() {
        save(ModemSettings(line = ModemSettings.LINE_SIP))
        SettingsNav.open(SettingsPage.LINE)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("A SIP account (calls over the internet)").performScrollTo().performClick()
        compose.onNodeWithText("Another phone with D-Modem, on Wi-Fi").performClick()
        compose.onNodeWithText("Find phones on this network").performScrollTo().assertExists()
        shot("peer-0-line-menu")
    }

    @Test
    fun noLine() {
        save(ModemSettings(line = ModemSettings.LINE_SIP))
        SettingsNav.open(SettingsPage.LINE)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("A SIP account (calls over the internet)").performScrollTo().performClick()
        compose.onNodeWithText("No line (the modem on its own)").performClick()
        compose.onNodeWithText("a dial gets NO DIALTONE", substring = true).assertExists()
        // (no SIP account asked for)
        compose.onNodeWithText("SIP account (saved with the button below)").assertDoesNotExist()
        shot("peer-4-no-line")
    }

    @Test
    fun aUsbModem() {
        save(ModemSettings(line = ModemSettings.LINE_SIP))
        SettingsNav.open(SettingsPage.LINE)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("A SIP account (calls over the internet)").performScrollTo().performClick()
        compose.onNodeWithText("A phone line, through a USB modem").performClick()
        compose.onNodeWithText("pico-serial Line stick", substring = true).assertExists()
        // (none on Robolectric's bus)
        compose.onNodeWithText("Plugged in: none").assertExists()
        compose.onNodeWithText("SIP account (saved with the button below)").assertDoesNotExist()
        shot("peer-5-usb-modem")
    }

    @Test
    fun homeOnACall() {
        save(ModemSettings(line = ModemSettings.LINE_WIFI, peerAddress = "192.168.1.20:5070", peerName = "D-Modem on Pixel 8", listen = true))
        ModemState.setRun(ModemState.Run.RUNNING)
        Peers.show(PeerLine.State.Connected("192.168.1.20:5070", caller = true))
        compose.onNodeWithText("another phone on Wi-Fi").performScrollTo()
        shot("peer-2-home")
        compose.onNodeWithText("On a call with D-Modem on Pixel 8").assertExists()
        Peers.show(PeerLine.State.Off)
        ModemState.setRun(ModemState.Run.STOPPED)
    }

    @Test
    fun theMobileCallLine() {
        save(ModemSettings(line = ModemSettings.LINE_CELL, listen = true))
        SettingsNav.open(SettingsPage.LINE)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Calls ringing here go to the modem").performScrollTo()
        shot("peer-3-cell-settings")
        // (V.cvm first, the default; V.21 for other modems)
        compose.onNodeWithText("About 1200 bit/s (V.cvm, made for mobile calls)", substring = true).assertExists()
        compose.onNodeWithText("300 bit/s (V.21): what any modem's call carries").assertExists()
    }
}
