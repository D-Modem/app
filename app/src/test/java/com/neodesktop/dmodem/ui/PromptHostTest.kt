// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.neodesktop.dmodem.ssh.Answer
import com.neodesktop.dmodem.ssh.Prompt
import com.neodesktop.dmodem.ssh.PromptBroker
import com.neodesktop.dmodem.terminal.Terminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The host-key questions as dialogs over the app, each tapped through, and drawn
 * into build/screens/. One dialog per test: in Robolectric, once a dialog's
 * window has gone the next one's composition doesn't run (a leftover sync
 * barrier on the main looper); on Android one dialog after another is
 * ordinary.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PromptHostTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun asked(p: Prompt): AtomicReference<Answer> {
        val got = AtomicReference<Answer>()
        Thread { got.set(Terminal.prompts.ask(p, this)) }.start()
        return got
    }

    /** a question left unanswered by a failure doesn't block the next test's */
    @After
    fun tearDown() = Terminal.prompts.cancel(this)

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shotDialog(name: String) {
        compose.waitForIdle()
        val bmp = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** (not compose's waitUntil: no idling after the dialog's window has gone) */
    private fun <T> answerOf(r: AtomicReference<T>): T {
        val end = System.currentTimeMillis() + 5000
        while (r.get() == null && System.currentTimeMillis() < end) Thread.sleep(10)
        return r.get()
    }

    @Test
    fun anUnknownHostsKey() {
        val got = asked(Prompt.HostKey("Unknown host", "bbs.example.org", 2222, "ssh-ed25519", "SHA256:Ng3jZbE6x1yJr9q0sJ4Ylm5o2p8Qe7Rk0vT1uW2xY3z", null))
        waitFor("Accept and keep")
        shotDialog("prompt-host-key")
        compose.onNodeWithText("Accept and keep").performClick()
        val a = answerOf(got)
        assertTrue(a.accepted && a.remember)
    }

    @Test
    fun aChangedHostKeyOnlyOnce() {
        val got = asked(
            Prompt.HostKey(
                "The host key has CHANGED", "pi.lan", 22, "ecdsa-sha2-nistp256",
                "SHA256:9fQ2kL0mZ4bX8cV1nR7tY3uI6oP5aS2dF8gH1jK4lMn", "SHA256:Ng3jZbE6x1yJr9q0sJ4Ylm5o2p8Qe7Rk0vT1uW2xY3z",
            ),
        )
        waitFor("Replace and connect")
        shotDialog("prompt-host-key-changed")
        compose.onNodeWithText("Once").performClick()
        val a = answerOf(got)
        assertTrue(a.accepted)
        assertFalse("not kept", a.remember)
    }

    @Test
    fun anAnswerToAnotherQuestionIsIgnored() {
        val b = PromptBroker()
        val old = Prompt.Password("old", "", false)
        val now = Prompt.Password("now", "", false)
        val got = AtomicReference<Answer>()
        val t = Thread { got.set(b.ask(now)) }
        t.start()
        while (b.current.value == null) Thread.sleep(5)
        b.answer(old, Answer(accepted = false))
        Thread.sleep(100)
        assertEquals(null, got.get())
        b.answer(now, Answer(accepted = true, text = "x"))
        t.join(5000)
        assertEquals("x", got.get().text)
    }
}
