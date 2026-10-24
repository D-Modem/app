// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

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
}
