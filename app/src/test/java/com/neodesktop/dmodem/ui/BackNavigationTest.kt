// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Back: to Home from the other tabs; on Home, the app to the background (not closed). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** the back button pressed */
    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** back from each other tab to Home; on Home, the app to the background without finishing */
    @Test
    fun backGoesHomeThenToTheBackground() {
        // (Home: no SIP account yet)
        compose.onNodeWithText("Set up SIP").assertExists()
        for (tab in listOf("Terminal", "Dial", "Settings", "Log")) {
            compose.onNode(
                androidx.compose.ui.test.hasText(tab) and androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab,
                ),
            ).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Set up SIP").assertDoesNotExist()
            back()
            compose.onNodeWithText("Set up SIP").assertExists()
        }
        // on Home: the task to the back, the activity still there
        back()
        compose.activityRule.scenario.onActivity { a ->
            assertFalse(a.isFinishing)
        }
    }

    /** the settings menu to a page and back to it, then Home; Home's Set up SIP to the line's page, the Settings tab to its menu again */
    @Test
    fun settingsMenuToAPageAndBack() {
        tab("Settings")
        // the menu: its pages, what each is set to
        compose.onNodeWithText("SIP: no account set up yet").assertExists()
        compose.onNodeWithText("Fax").assertExists()
        compose.onNodeWithText("Linux").assertExists()
        // a page; back to the menu, back again to Home
        compose.onNodeWithText("Dial-up internet").performScrollTo().performClick()
        // (the page: the menu gone, its title)
        gone("The ISP to dial; dial-in for callers")
        compose.onNodeWithText("Dial-up internet").assertExists()
        back()
        shown("The ISP to dial; dial-in for callers")
        back()
        shown("Set up SIP")
        // Home's Set up SIP: the line's page
        compose.onNodeWithText("Set up SIP").performClick()
        shown("Line")
        gone("SIP: no account set up yet")
        // the Settings tab again: its menu
        tab("Settings")
        shown("SIP: no account set up yet")
    }

    /** waited until [text] is shown */
    private fun shown(text: String) = compose.waitUntil(5000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /** waited until [text] is gone */
    private fun gone(text: String) = compose.waitUntil(5000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    /** the tab named [name], clicked */
    private fun tab(name: String) {
        compose.onNode(
            androidx.compose.ui.test.hasText(name) and androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab,
            ),
        ).performClick()
        compose.waitForIdle()
    }
}
