// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.neodesktop.dmodem.ssh.Answer
import com.neodesktop.dmodem.ssh.ForwardSpec
import com.neodesktop.dmodem.ssh.KeyKind
import com.neodesktop.dmodem.ssh.PlainBox
import com.neodesktop.dmodem.ssh.Prompt
import com.neodesktop.dmodem.ssh.SshLink
import com.neodesktop.dmodem.ssh.SshSecurity
import com.neodesktop.dmodem.terminal.DialEntry
import com.neodesktop.dmodem.terminal.EntryKind
import com.neodesktop.dmodem.terminal.SshAuth
import com.neodesktop.dmodem.terminal.Terminal
import com.neodesktop.dmodem.terminal.TerminalSession
import kotlinx.coroutines.runBlocking
import org.apache.sshd.agent.local.ProxyAgentFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.ServerSocket

private fun saveShot(bmp: Bitmap, name: String) {
    File("build/screens").mkdirs()
    File("build/screens/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** An SSH session's forwards and agent, as the session's panel shows them (a real server: Apache MINA). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SshSessionPanelTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: SshServer

    @Before
    fun setUp() {
        SshSecurity.ensure()
        server = SshServer.setUpDefaultServer()
        server.port = 0
        server.keyPairProvider = SimpleGeneratorHostKeyProvider(tmp.root.toPath().resolve("hostkey")).apply { algorithm = "EC" }
        server.publickeyAuthenticator = AcceptAllPublickeyAuthenticator.INSTANCE
        server.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        server.agentFactory = ProxyAgentFactory()
        server.shellFactory = ShellFactory { SshUiTest.Shell() }
        server.start()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        File(ctx.filesDir, "ssh").deleteRecursively()
        Terminal.init(ctx, PlainBox())
    }

    @After
    fun tearDown() {
        Terminal.sessions.value.filter { it.kind != TerminalSession.Kind.MODEM }.forEach { Terminal.close(it) }
        server.stop(true)
    }

    @Test
    fun forwardsAndTheAgent() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val key = Terminal.keys.generate(KeyKind.ED25519, "pixel")
        val socks = ServerSocket(0).use { it.localPort }
        val local = ServerSocket(0).use { it.localPort }
        val entry = DialEntry(
            name = "Build box", kind = EntryKind.SSH, host = "127.0.0.1", port = server.port, user = "alice",
            auth = SshAuth.KEY, keyId = key.id,
            forwards = listOf("L $local:db.internal:5432", "D $socks", "R 0:localhost:8080"),
            agentForwarding = true,
        )
        // the host key accepted as the user would
        val user = Thread {
            val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end) {
                (Terminal.prompts.current.value as? Prompt.HostKey)?.let { Terminal.prompts.answer(it, Answer(accepted = true, remember = true)); return@Thread }
                Thread.sleep(20)
            }
        }.apply { isDaemon = true; start() }
        runBlocking { assertEquals(null, Terminal.open(ctx, entry)) }
        user.join(20_000)
        val link = Terminal.active.value!!.link as SshLink
        val end = System.currentTimeMillis() + 20_000
        while ((link.forwards.value.count { it.listening } < 3 || !link.agentForwarded) && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(link.forwards.value.toString(), 3, link.forwards.value.count { it.listening })
        assertTrue("agent", link.agentForwarded)
        // a SOCKS connection that fails (nothing there), to have one in the counts
        java.net.Socket(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", socks))).use { s ->
            runCatching { s.connect(java.net.InetSocketAddress.createUnresolved("localhost", ServerSocket(0).use { it.localPort }), 5000) }
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNode(hasText("Terminal") and isSelectable()).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Build box").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("More").performClick()
            compose.onNodeWithText("Port forwards, agent, keys").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Forwarded", substring = true).fetchSemanticsNodes().isNotEmpty() }
            saveShot(compose.onNode(isDialog()).captureToImage().asAndroidBitmap(), "ssh-tools-session")
        }
    }
}

/** The dialogs with text fields (at Robolectric's default screen: see PasswordPromptTest). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SshToolsDialogsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        SshSecurity.ensure()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        File(ctx.filesDir, "ssh").deleteRecursively()
        Terminal.init(ctx, PlainBox())
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        saveShot(compose.onNode(isDialog()).captureToImage().asAndroidBitmap(), name)
    }

    @Test
    fun anEntrysForwardsAndAgent() {
        var saved: DialEntry? = null
        val e = DialEntry(
            name = "Build box", kind = EntryKind.SSH, host = "build.example.org", user = "alice",
            forwards = listOf("L 5432:db.internal:5432", "D 1080"), agentForwarding = true,
        )
        compose.setContent { DModemTheme { EntryDialog(e, onSave = { saved = it }, onDismiss = {}) } }
        compose.onNodeWithText("Forward the agent (ssh -A)").performScrollTo()
        shot("ssh-tools-entry")
        compose.onNodeWithContentDescription("Remove D 1080").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("L 5432:db.internal:5432"), saved!!.forwards)
        assertTrue(saved!!.agentForwarding && saved!!.agentConfirm)
    }

    @Test
    fun aForwardMade() {
        var made: ForwardSpec? = null
        compose.setContent { DModemTheme { ForwardDialog(onAdd = { made = it }, onDismiss = {}) } }
        compose.onNodeWithText("Remote").performClick()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("2222")
        fields[2].performTextReplacement("22")
        shot("ssh-tools-forward")
        compose.onNodeWithText("Add").performClick()
        assertEquals(ForwardSpec(ForwardSpec.Type.REMOTE, null, 2222, "localhost", 22), made)
    }

    @Test
    fun aKeysDetails() {
        val k = Terminal.keys.generate(KeyKind.ED25519, "pixel", comment = "alice@pixel")
        compose.setContent { DModemTheme { KeyDetailsDialog(k, onDelete = {}, onDone = {}) } }
        compose.onNodeWithText("[ED25519 256]", substring = true).assertExists()
        shot("ssh-tools-key")
    }

    @Test
    fun anExportAsPutty() {
        val k = Terminal.keys.generate(KeyKind.ED25519, "pixel", comment = "alice@pixel")
        compose.setContent { DModemTheme { ExportDialog(k) {} } }
        compose.onNodeWithText("OpenSSH (ssh, ssh-keygen)").performClick()
        compose.onNodeWithText("PuTTY .ppk (PuTTY 0.75 and on)").performClick()
        compose.onNodeWithText("PuTTY .ppk (PuTTY 0.75 and on)").assertExists()
        shot("ssh-tools-export-ppk")
    }
}
