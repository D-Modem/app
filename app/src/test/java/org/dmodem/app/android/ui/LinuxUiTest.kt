// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.dmodem.app.android.local.Linux
import org.dmodem.app.android.local.Distro
import org.dmodem.app.android.local.Machine
import org.dmodem.app.android.local.LocalShellTest
import org.dmodem.app.android.local.Pty
import org.dmodem.app.android.local.Tar
import org.dmodem.app.android.terminal.Terminal
import org.dmodem.app.android.terminal.TerminalSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Alpine installed from Settings and its shell used (proot and Alpine for this host: see LinuxSystemTest). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LinuxUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var base: File

    /** skipped off x86_64, without Alpine's mirror or the tools; proot built for this host, a phone's storage made, Linux placed in a temporary directory */
    @Before
    fun setUp() {
        assumeTrue("not an x86_64 host", System.getProperty("os.arch") in listOf("amd64", "x86_64"))
        assumeTrue("Alpine can't be reached", runCatching { URL(Distro.ALPINE_MIRROR).openConnection().apply { connectTimeout = 10_000 }.getInputStream().close(); true }.getOrDefault(false))
        for (tool in listOf("cc", "make", "gawk", "curl")) {
            assumeTrue("$tool isn't installed", System.getenv("PATH").split(':').any { File(it, tool).canExecute() })
        }
        Pty.load(LocalShellTest.hostPty())
        base = Files.createTempDirectory("linux-ui").toFile()
        val programs = File(base, "lib")
        val script = listOf(File("../native-proot/build.sh"), File("native-proot/build.sh")).first { it.exists() }
        val p = ProcessBuilder(script.path, "host", programs.path).redirectErrorStream(true).start()
        val log = p.inputStream.readBytes().decodeToString()
        p.waitFor(10, TimeUnit.MINUTES)
        assertEquals(log, 0, p.exitValue())
        val storage = File(base, "storage").apply { mkdirs() }
        listOf("Download", "DCIM", "Documents", "Music").forEach { File(storage, it).mkdirs() }
        File(storage, "Download/readme.txt").writeText("from the phone\n")
        Linux.place = { Linux.Place(File(base, "linux"), programs, File(base, "tmp"), Machine.forAbi("x86_64"), storage = { storage }) }
    }

    /** the sessions but the modem's closed, the temporary directory removed */
    @After
    fun tearDown() {
        Terminal.sessions.value.filter { it.kind != TerminalSession.Kind.MODEM }.forEach { Terminal.close(it) }
        if (::base.isInitialized) Tar.removeTree(base)
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

    /** waited until [s]'s screen has [text], or an AssertionError with the screen after [secs] */
    private fun waitShell(s: TerminalSession, text: String, secs: Int = 20) {
        val end = System.currentTimeMillis() + secs * 1000L
        while (!screen(s).contains(text)) {
            if (System.currentTimeMillis() > end) throw AssertionError("no '$text' in:\n" + screen(s))
            Thread.sleep(50)
        }
    }

    /** Alpine picked from the catalog in the Linux settings and installed, its shell opened and used (the phone's storage in it), listed in Settings; drawn */
    @Test
    fun installedFromSettingsAndUsed() {
        SettingsNav.open(SettingsPage.LINUX)
        compose.onNode(hasText("Settings") and isSelectable()).performClick()
        compose.onNodeWithText("Install a Linux").performScrollTo()
        shot("linux-1-settings")
        compose.onNodeWithText("Install a Linux").performClick()
        // the catalog, those built for this machine known from the index
        compose.waitUntil(30_000) { compose.onAllNodesWithText("RHEL-compatible", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        saveDialog("linux-2-catalog")
        compose.onNodeWithText("Alpine Linux").performClick()
        compose.onNodeWithText("Install").performClick()
        // installed, its shell opened in the terminal
        compose.waitUntil(180_000) { Terminal.active.value?.kind == TerminalSession.Kind.LINUX }
        val s = Terminal.active.value!!
        assertEquals("alpine", s.linuxId)
        waitShell(s, "root@alpine:~#")
        s.send("cat /etc/os-release | head -2; uname -m; ls --color=always /sdcard; cat /sdcard/Download/readme.txt; apk --version\r")
        waitShell(s, "from the phone")
        waitShell(s, "apk-tools")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Alpine").fetchSemanticsNodes().isNotEmpty() }
        shot("linux-3-shell")
        // Settings lists it
        compose.onNode(hasText("Settings") and isSelectable()).performClick()
        compose.onNodeWithText("Add another Linux").performScrollTo()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Alpine Linux 3.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("linux-4-installed")
    }

    /** the dialog as it is, into build/screens/[name].png */
    private fun saveDialog(name: String) {
        val bmp = compose.onNode(androidx.compose.ui.test.isDialog()).captureToImage().asAndroidBitmap()
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
