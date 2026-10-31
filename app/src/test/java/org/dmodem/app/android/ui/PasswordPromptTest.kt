// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.dmodem.app.android.ssh.Answer
import org.dmodem.app.android.ssh.Prompt
import org.dmodem.app.android.terminal.Terminal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The questions with text fields, at Robolectric's default screen: at the
 * other tests' (w411dp-h891dp-xxhdpi) a dialog holding a text field never
 * lets Robolectric go idle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PasswordPromptTest {
    @get:Rule
    val compose = createComposeRule()

    /** this test's question, if still asked, cancelled */
    @After
    fun tearDown() = Terminal.prompts.cancel(this)

    /** [p] asked on a thread, the prompt shown: where its answer comes */
    private fun asked(p: Prompt): AtomicReference<Answer> {
        val got = AtomicReference<Answer>()
        Thread { got.set(Terminal.prompts.ask(p, this)) }.start()
        while (Terminal.prompts.current.value == null) Thread.sleep(5)
        compose.setContent { DModemTheme { PromptHost() } }
        return got
    }

    /** waited until something with [text] in it is shown */
    private fun waitFor(text: String) =
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** the dialog as it is, into build/screens/[name].png */
    private fun shotDialog(name: String) {
        compose.waitForIdle()
        val bmp = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** [r]'s answer, once there (or 5 seconds) */
    private fun <T> answerOf(r: AtomicReference<T>): T {
        val end = System.currentTimeMillis() + 5000
        while (r.get() == null && System.currentTimeMillis() < end) Thread.sleep(10)
        return r.get()
    }

    /** a password typed and remembered: the answer has it, to be kept; drawn */
    @Test
    fun aPasswordRemembered() {
        val got = asked(Prompt.Password("Password", "alice@pi.lan", canRemember = true))
        waitFor("Log in")
        compose.onNode(hasSetTextAction()).performTextReplacement("raspberry")
        compose.onNodeWithText("Remember", substring = true).performClick()
        shotDialog("prompt-password")
        compose.onNodeWithText("Log in").performClick()
        val a = answerOf(got)
        assertEquals("raspberry", a.text)
        assertTrue(a.remember)
    }

    /** the server's question answered in its field: the answer's text; drawn */
    @Test
    fun theServersQuestions() {
        val got = asked(Prompt.Questions("pi.lan", "Two-factor login", listOf("Verification code: " to true)))
        waitFor("Verification code")
        compose.onNode(hasSetTextAction()).performTextReplacement("123456")
        shotDialog("prompt-questions")
        compose.onNodeWithText("OK").performClick()
        assertEquals(listOf("123456"), answerOf(got).texts)
    }
}
