// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.neodesktop.dmodem.local.LocalShell
import com.neodesktop.dmodem.local.LocalShellTest
import com.neodesktop.dmodem.local.Pty
import com.neodesktop.dmodem.terminal.Terminal
import com.neodesktop.dmodem.terminal.TerminalLink
import com.neodesktop.dmodem.terminal.TerminalSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A local shell from Home, in its own tab; its exit. (mksh, Android's shell, on the host: where it is installed.) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LocalShellUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    /** skipped without mksh; the host's pty library loaded, the local shell on mksh with a home to list */
    @Before
    fun setUp() {
        assumeTrue("mksh isn't installed", File("/usr/bin/mksh").canExecute())
        Pty.load(LocalShellTest.hostPty())
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        Terminal.localShell = { LocalShell(files, cache, shell = "/usr/bin/mksh", base = mapOf("PATH" to "/usr/bin:/bin", "USER" to "u0_a187")) }
        // something in the home to list
        File(files, "home/projects").mkdirs()
        File(files, "home/notes.txt").apply { parentFile!!.mkdirs(); writeText("hello\n") }
        File(files, "home/backup.sh").writeText("#!/system/bin/sh\n")
    }

    /** the sessions but the modem's closed */
    @After
    fun tearDown() {
        Terminal.sessions.value.filter { it.kind != TerminalSession.Kind.MODEM }.forEach { Terminal.close(it) }
    }

    /** the screen as it is, into build/screens/[name].png */
    private fun shot(name: String) {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** [s]'s screen, as text */
    private fun screen(s: TerminalSession) =
        synchronized(s.emulator) { (0 until s.emulator.rows).joinToString("\n") { s.emulator.line(it).text() } }

    /** waited until [s]'s screen has [text], or an AssertionError with the screen after 10 seconds */
    private fun waitShell(s: TerminalSession, text: String) {
        val end = System.currentTimeMillis() + 10_000
        while (!screen(s).contains(text)) {
            if (System.currentTimeMillis() > end) throw AssertionError("no '$text' in:\n" + screen(s))
            Thread.sleep(20)
        }
    }

    /** a shell opened from Home and used, drawn; its exit status shown, a new one in its place */
    @Test
    fun aShellFromHome() {
        compose.onNodeWithText("Shell").performScrollTo().performClick()
        compose.waitUntil(10_000) { Terminal.active.value?.kind == TerminalSession.Kind.LOCAL }
        val s = Terminal.active.value!!
        waitShell(s, "u0_a187@dmodem:~$")
        s.send("ls -la --color=always\r")
        waitShell(s, "notes.txt")
        s.send("echo \$TERM; printf '\\033[38;5;208mcolour 208\\033[0m \\033[1;35mbold magenta\\033[0m\\n'; cd projects\r")
        waitShell(s, "u0_a187@dmodem:~/projects$")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Shell", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("shell-1")

        s.send("exit 4\r")
        val end = System.currentTimeMillis() + 10_000
        while (s.link.state.value !is TerminalLink.State.Closed && System.currentTimeMillis() < end) Thread.sleep(20)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("New shell").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Exited (4)", substring = true).assertExists()
        shot("shell-2-exited")
        // a new one in its place
        compose.onNodeWithText("New shell").performClick()
        compose.waitUntil(10_000) { Terminal.active.value !== s && Terminal.active.value?.kind == TerminalSession.Kind.LOCAL }
        assertEquals(1, Terminal.sessions.value.count { it.kind == TerminalSession.Kind.LOCAL })
    }
}
