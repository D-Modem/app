// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.neodesktop.dmodem.settings.AppPalette
import com.neodesktop.dmodem.settings.Appearance
import com.neodesktop.dmodem.settings.AppearanceStore
import com.neodesktop.dmodem.settings.DarkMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Light, dark and black, in each palette: the schemes, and the app drawn in them into build/screens/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppearanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val store get() = AppearanceStore(ctx)

    private fun shot(name: String) {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tab(name: String) = compose.onNode(hasText(name) and isSelectable()).performClick()

    @Test
    fun everyPaletteReadsInLightAndDark() {
        for (p in AppPalette.entries.filter { it != AppPalette.DYNAMIC }) {
            val light = Palettes.scheme(p, dark = false)
            val dark = Palettes.scheme(p, dark = true)
            assertTrue("$p light surface", light.surface.luminance() > 0.85f)
            assertTrue("$p dark surface", dark.surface.luminance() < 0.02f)
            // text on its backgrounds: WCAG contrast of at least 4.5:1
            fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float {
                val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
                return (hi + 0.05f) / (lo + 0.05f)
            }
            for (s in listOf(light, dark)) {
                assertTrue("$p onPrimary", contrast(s.primary, s.onPrimary) >= 4.5f)
                assertTrue("$p onSurface", contrast(s.surface, s.onSurface) >= 4.5f)
                assertTrue("$p onPrimaryContainer", contrast(s.primaryContainer, s.onPrimaryContainer) >= 4.5f)
            }
        }
        val black = Palettes.black(Palettes.scheme(AppPalette.AMBER, dark = true))
        assertEquals(0f, black.surface.luminance())
    }

    @Test
    fun theAppInEach() {
        val looks = listOf(
            "light-violet" to Appearance(DarkMode.LIGHT, AppPalette.BASELINE),
            "dark-phosphor" to Appearance(DarkMode.DARK, AppPalette.PHOSPHOR),
            "black-amber" to Appearance(DarkMode.DARK, AppPalette.AMBER, black = true),
            "dark-ocean" to Appearance(DarkMode.DARK, AppPalette.OCEAN),
        )
        for ((name, a) in looks) {
            runBlocking { store.save(a) }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForIdle()
                shot("theme-$name-home")
                if (name == "dark-ocean" || name == "light-violet") {
                    tab("Settings")
                    compose.onNodeWithText("Black background").performScrollTo()
                    shot("theme-$name-settings")
                }
            }
        }
    }

    @Test
    fun chosenInSettings() {
        runBlocking { store.save(Appearance()) }
        ActivityScenario.launch(MainActivity::class.java).use {
            tab("Settings")
            compose.onNodeWithText("Black background").performScrollTo()
            compose.onNodeWithText("Dark").performClick()
            compose.onNodeWithText("Teal").performClick()
            compose.waitForIdle()
            val a = runBlocking { store.appearance.first() }
            assertEquals(DarkMode.DARK, a.dark)
            assertEquals(AppPalette.TEAL, a.palette)
            compose.onNodeWithText("System default").performClick()
            compose.waitForIdle()
            assertEquals(DarkMode.SYSTEM, runBlocking { store.appearance.first() }.dark)
        }
    }
}
