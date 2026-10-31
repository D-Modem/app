// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.dmodem.app.android.ssh.Answer
import org.dmodem.app.android.ssh.KeyKind
import org.dmodem.app.android.ssh.Prompt
import org.dmodem.app.android.ssh.PlainBox
import org.dmodem.app.android.ssh.SshSecurity
import org.dmodem.app.android.terminal.DialEntry
import org.dmodem.app.android.terminal.EntryKind
import org.dmodem.app.android.terminal.Terminal
import org.dmodem.app.android.terminal.TerminalLink
import org.dmodem.app.android.terminal.TerminalSession
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
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
import java.io.InputStream
import java.io.OutputStream

/**
 * SSH from the app's UI to a real SSH server (Apache MINA, in this JVM):
 * the host key and the password asked in dialogs and answered with taps,
 * the shell's output in a tab of its own; the screens into build/screens/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SshUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: SshServer

    /** a shell with a coloured prompt that runs "ls" and "uname" (and echoes the rest) */
    class Shell : Command {
        private lateinit var inp: InputStream
        private lateinit var out: OutputStream
        private var exit: ExitCallback? = null
        /** the client's keystrokes, read by the shell's thread */
        override fun setInputStream(i: InputStream) { inp = i }
        /** where the shell's output goes */
        override fun setOutputStream(o: OutputStream) { out = o }
        /** (nothing goes to stderr) */
        override fun setErrorStream(e: OutputStream) {}
        /** told when the input ends */
        override fun setExitCallback(cb: ExitCallback) { exit = cb }
        /** [s] written to the client and flushed */
        private fun say(s: String) = synchronized(out) { out.write(s.toByteArray()); out.flush() }
        private val prompt = "\u001b[1;32malice@raspberrypi\u001b[0m:\u001b[1;34m~ \u001b[0m$ "
        /** a login banner, the client's TERM and size, the prompt; then a thread that echoes, edits and runs each line */
        override fun start(channel: ChannelSession, env: Environment) {
            val cols = env.env[Environment.ENV_COLUMNS]
            say("Linux raspberrypi 6.12.25-v8+ #1 SMP PREEMPT aarch64\r\n\r\nLast login: Fri Oct 16 09:12:44 2026 from 192.168.1.20\r\n")
            say("TERM=${env.env[Environment.ENV_TERM]} ${cols}x${env.env[Environment.ENV_LINES]}\r\n")
            say(prompt)
            Thread {
                val line = StringBuilder()
                try {
                    while (true) {
                        val b = inp.read()
                        if (b < 0) break
                        if (b == '\r'.code) {
                            say("\r\n")
                            when (line.toString().trim()) {
                                "ls" -> say(
                                    "\u001b[1;34mDesktop\u001b[0m  \u001b[1;34mDocuments\u001b[0m  \u001b[1;32mbackup.sh\u001b[0m  " +
                                        "notes.txt  \u001b[1;31marchive.tar.gz\u001b[0m  \u001b[1;36mlink\u001b[0m\r\n",
                                )
                                "uname -a" -> say("Linux raspberrypi 6.12.25-v8+ #1 SMP PREEMPT aarch64 GNU/Linux\r\n")
                                "" -> {}
                                else -> say("${line}: command not found\r\n")
                            }
                            line.setLength(0)
                            say(prompt)
                        } else if (b == 0x7f) {
                            if (line.isNotEmpty()) { line.setLength(line.length - 1); say("\b \b") }
                        } else {
                            line.append(b.toChar())
                            say(b.toChar().toString())
                        }
                    }
                } catch (_: Exception) {
                }
                exit?.onExit(0)
            }.start()
        }
        /** (nothing to free: the thread ends with the input) */
        override fun destroy(channel: ChannelSession) {}
    }

    /** a local SSH server taking alice's password, with [Shell]; the terminal with no SSH files */
    @Before
    fun setUp() {
        SshSecurity.ensure()
        server = SshServer.setUpDefaultServer()
        server.port = 0
        server.keyPairProvider = SimpleGeneratorHostKeyProvider(tmp.root.toPath().resolve("hostkey")).apply { algorithm = "EC" }
        server.passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == "alice" && p == "raspberry" }
        server.shellFactory = ShellFactory { Shell() }
        server.start()
        // the app's terminal without the Android Keystore (Robolectric has none)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        File(ctx.filesDir, "ssh").deleteRecursively()
        Terminal.init(ctx, PlainBox())
    }

    /** the SSH sessions closed and the server stopped */
    @After
    fun tearDown() {
        Terminal.sessions.value.filter { it.kind != TerminalSession.Kind.MODEM }.forEach { Terminal.close(it) }
        server.stop(true)
    }

    /** the whole screen drawn into build/screens/[name].png */
    private fun shot(name: String) {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** the bottom bar's item (not a heading of the same name) */
    private fun tab(name: String) = compose.onNode(hasText(name) and isSelectable()).performClick()

    /** (up to [ms]) until some node shows [t] */
    private fun waitForText(t: String, ms: Long = 20_000) {
        compose.waitUntil(ms) { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** the connection's questions answered as the user would (the dialogs: PromptHostTest) */
    private fun answering(password: String): Thread = Thread {
        val end = System.currentTimeMillis() + 30_000
        var done = 0
        while (done < 2 && System.currentTimeMillis() < end) {
            when (val p = Terminal.prompts.current.value) {
                is Prompt.HostKey -> { Terminal.prompts.answer(p, Answer(accepted = true, remember = true)); done++ }
                is Prompt.Password -> { Terminal.prompts.answer(p, Answer(accepted = true, text = password, remember = true)); done++ }
                else -> {}
            }
            Thread.sleep(20)
        }
    }.apply { isDaemon = true; start() }

    /** the text on [s]'s screen, row by row */
    private fun shell(s: TerminalSession) =
        synchronized(s.emulator) { (0 until s.emulator.rows).joinToString("\n") { s.emulator.line(it).text() } }

    /** (up to 20 s) until [s]'s screen shows [text], or an error with what it shows */
    private fun waitShell(s: TerminalSession, text: String) {
        val end = System.currentTimeMillis() + 20_000
        while (!shell(s).contains(text)) {
            if (System.currentTimeMillis() > end) throw AssertionError("no '$text' (${s.link.state.value}, ${Terminal.prompts.current.value}) in:\n" + shell(s))
            Thread.sleep(20)
        }
    }

    /** an SSH entry logs in with the host key and password kept, gets its own tab, and offers to reconnect when the server goes */
    @Test
    fun sshFromTheDirectory() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val entry = DialEntry(name = "Raspberry Pi", kind = EntryKind.SSH, host = "127.0.0.1", port = server.port, user = "alice")
        Terminal.directory.put(entry)
        Terminal.directory.put(DialEntry(name = "Level 29 BBS", number = "18005551229"))
        Terminal.directory.put(DialEntry(name = "Synchronet demo", kind = EntryKind.TELNET, host = "vert.synchro.net"))
        Terminal.keys.generate(KeyKind.ED25519, "pixel-dmodem")

        // logged in: the host key kept, the password typed and kept
        val user = answering("raspberry")
        runBlocking { assertEquals(null, Terminal.open(ctx, entry)) }
        val s = Terminal.active.value!!
        waitShell(s, "alice@raspberrypi")
        user.join(5000)
        s.send("ls\r")
        waitShell(s, "backup.sh")
        assertTrue(shell(s), shell(s).contains("TERM=xterm-256color"))
        assertEquals("the password kept (sealed)", "raspberry", Terminal.passwords.get(entry.id))
        assertEquals(1, Terminal.knownHosts.all().size)

        // the app: the connection in its own tab beside the modem's
        ActivityScenario.launch(MainActivity::class.java).use {
            tab("Terminal")
            waitForText("SSH alice@127.0.0.1")
            shot("ssh-4-terminal")
            tab("Dial")
            waitForText("Raspberry Pi")
            shot("ssh-1-directory")
            tab("Settings")
            compose.waitForIdle()

            // the server goes: the bar to reconnect
            server.stop(true)
            val end = System.currentTimeMillis() + 20_000
            while (s.link.state.value !is TerminalLink.State.Closed && System.currentTimeMillis() < end) Thread.sleep(20)
            assertTrue("closed: ${s.link.state.value}", s.link.state.value is TerminalLink.State.Closed)
            tab("Terminal")
            waitForText("Reconnect")
            shot("ssh-5-disconnected")
        }
    }
}
