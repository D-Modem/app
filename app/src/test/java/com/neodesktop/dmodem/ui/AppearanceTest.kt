// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
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

    /** the screen as it is, into build/screens/[name].png */
    private fun shot(name: String) {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** the tab or choice with the text [name], clicked */
    private fun tab(name: String) = compose.onNode(hasText(name) and isSelectable()).performClick()

    /** each palette's light and dark: light and dark surfaces, text on them at 4.5:1 or more; black's surface black */
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

    /** the app's home (and settings, for two) drawn in four of the looks, into build/screens/ */
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
                    SettingsNav.open(SettingsPage.APPEARANCE); tab("Settings")
                    compose.onNodeWithText("Black background").performScrollTo()
                    shot("theme-$name-settings")
                }
            }
        }
    }

    /** waited until the saved appearance is [want], or an AssertionError after 5 seconds */
    private fun saved(want: (Appearance) -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (true) {
            compose.waitForIdle()
            val a = runBlocking { store.appearance.first() }
            if (want(a)) return
            if (System.currentTimeMillis() > end) throw AssertionError("not saved: $a")
            Thread.sleep(50)
        }
    }

    /** dark, a palette and the system's default chosen in the Appearance settings, and saved */
    @Test
    fun chosenInSettings() {
        runBlocking { store.save(Appearance()) }
        ActivityScenario.launch(MainActivity::class.java).use {
            SettingsNav.open(SettingsPage.APPEARANCE); tab("Settings")
            compose.onNodeWithText("Black background").performScrollTo()
            compose.onNodeWithText("Dark").performClick()
            compose.onNodeWithText("Teal").performClick()
            // (saved in the background: waited for)
            saved { it.dark == DarkMode.DARK && it.palette == AppPalette.TEAL }
            compose.onNodeWithText("System default").performClick()
            saved { it.dark == DarkMode.SYSTEM }
        }
    }
}
