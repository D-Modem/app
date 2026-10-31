// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ssh

import org.dmodem.app.android.terminal.TerminalLink
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.StringWriter
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The SSH client against a real SSH server in the same JVM (Apache MINA
 * SSHD), whose shell says its TERM and size, echoes what comes, and tells
 * the window's changes. (Robolectric for org.json, which the keys keep
 * their list in.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SshLinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: SshServer
    private val allowedKeys = CopyOnWriteArrayList<PublicKey>()
    private val prompts = CopyOnWriteArrayList<Prompt>()

    /** the shell: "TERM=.. COLS=.. LINES=..", then "echo: <line>" for each line, "WINCH c r" */
    class Shell : Command {
        private lateinit var inp: InputStream
        private lateinit var out: OutputStream
        private var exit: ExitCallback? = null
        /** the channel's input, kept */
        override fun setInputStream(i: InputStream) { inp = i }
        /** the channel's output, kept */
        override fun setOutputStream(o: OutputStream) { out = o }
        /** stderr: not used */
        override fun setErrorStream(e: OutputStream) {}
        /** how to say the shell's ended, kept */
        override fun setExitCallback(cb: ExitCallback) { exit = cb }
        /** [s] written to the client */
        private fun say(s: String) = synchronized(out) { out.write(s.toByteArray()); out.flush() }
        /** the shell started: its TERM and size said, window changes told, each line echoed until "exit" or the end */
        override fun start(channel: ChannelSession, env: Environment) {
            val e = env.env
            say("TERM=${e[Environment.ENV_TERM]} COLS=${e[Environment.ENV_COLUMNS]} LINES=${e[Environment.ENV_LINES]}\r\n")
            env.addSignalListener({ _, _ ->
                say("WINCH ${env.env[Environment.ENV_COLUMNS]} ${env.env[Environment.ENV_LINES]}\r\n")
            }, org.apache.sshd.server.Signal.WINCH)
            Thread {
                val line = ByteArrayOutputStream()
                try {
                    while (true) {
                        val b = inp.read()
                        if (b < 0) break
                        if (b == '\r'.code) {
                            val l = line.toString()
                            line.reset()
                            if (l == "exit") break
                            say("echo: $l\r\n")
                        } else {
                            line.write(b)
                        }
                    }
                } catch (_: Exception) {
                }
                exit?.onExit(0)
            }.start()
        }
        /** nothing to let go */
        override fun destroy(channel: ChannelSession) {}
    }

    /** the server started on [port] with an EC host key in [hostKeyFile]: alice by password, bob by [allowedKeys], our [Shell] */
    private fun startServer(port: Int = 0, hostKeyFile: String = "hostkey") {
        SshSecurity.ensure()
        server = SshServer.setUpDefaultServer()
        server.port = port
        server.keyPairProvider = SimpleGeneratorHostKeyProvider(tmp.root.toPath().resolve(hostKeyFile)).apply { algorithm = "EC" }
        server.passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == "alice" && p == "secret" }
        server.publickeyAuthenticator = PublickeyAuthenticator { u, k, _ -> u == "bob" && allowedKeys.any { it == k } }
        server.shellFactory = ShellFactory { Shell() }
        server.start()
    }

    /** the server started, on a free port */
    @Before
    fun setUp() = startServer()

    /** the server stopped */
    @After
    fun tearDown() {
        server.stop(true)
    }

    /** a user that answers the questions with these, and keeps them all (the host key, the password) */
    private fun user(password: String? = "secret", acceptHost: Boolean = true): (Prompt, Any?) -> Answer = { p, _ ->
        prompts.add(p)
        when (p) {
            is Prompt.HostKey -> Answer(accepted = acceptHost, remember = true)
            is Prompt.Password -> Answer(accepted = password != null, text = password, remember = true)
            is Prompt.Questions -> Answer(accepted = password != null, texts = listOf(password ?: ""))
            is Prompt.Confirm -> Answer(accepted = true)
        }
    }

    /** waited until [cond], or an AssertionError naming [what] after [secs] */
    private fun waitFor(what: String, secs: Int = 20, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + secs * 1000
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private class Screen : TerminalLink.Listener {
        val text = StringBuffer()
        /** what came, appended as text */
        override fun onData(data: ByteArray, length: Int) { text.append(String(data, 0, length)) }
    }

    /** an SSH link to the test server as [user], 100x30, with a [saved] password and the ones it keeps added to [kept] */
    private fun link(
        user: String,
        ask: (Prompt, Any?) -> Answer,
        known: KnownHosts,
        key: net.schmizz.sshj.userauth.keyprovider.KeyProvider? = null,
        saved: String? = null,
        kept: MutableList<String> = mutableListOf(),
    ) = SshLink("127.0.0.1", server.port, user, key, known, ask, savedPassword = saved, keepPassword = { kept.add(it) }, cols = 100, rows = 30)

    /** a password login, the host key asked and kept, the password kept; the shell's TERM and size, echo and window change; again, nothing asked */
    @Test
    fun passwordLoginAndTheHostKeyKeptOnFirstUse() {
        val known = KnownHosts(tmp.newFile("known_hosts")) { p, o -> user()(p, o) }
        val kept = mutableListOf<String>()
        val l = link("alice", user(), known, kept = kept)
        val screen = Screen()
        l.addListener(screen)
        l.connect()
        waitFor("the shell") { screen.text.contains("TERM=") }
        assertTrue(screen.text.toString(), screen.text.contains("TERM=xterm-256color COLS=100 LINES=30"))
        assertEquals(TerminalLink.State.Open, l.state.value)
        assertTrue(prompts.any { it is Prompt.HostKey && it.oldFingerprint == null })
        assertTrue(prompts.any { it is Prompt.Password })
        assertEquals(listOf("secret"), kept)
        assertEquals(1, known.keysFor("127.0.0.1", server.port).size)

        l.write("hello\r".toByteArray())
        waitFor("the echo") { screen.text.contains("echo: hello") }
        l.resize(132, 43)
        waitFor("the window change") { screen.text.contains("WINCH 132 43") }
        l.close()
        waitFor("closed") { l.state.value is TerminalLink.State.Closed }
        assertNull((l.state.value as TerminalLink.State.Closed).reason)

        // again: the host known, the kept password used, nothing asked
        prompts.clear()
        val l2 = link("alice", user(password = null), known, saved = "secret")
        val s2 = Screen()
        l2.addListener(s2)
        l2.connect()
        waitFor("the shell again") { s2.text.contains("TERM=") }
        assertTrue("nothing asked: $prompts", prompts.isEmpty())
        l2.close()
    }

    /** a saved password that's wrong: the password asked for, and logged in */
    @Test
    fun aWrongPasswordIsAskedAgain() {
        val known = KnownHosts(tmp.newFile("known_hosts")) { p, o -> user()(p, o) }
        val l = link("alice", user(), known, saved = "wrong")
        val screen = Screen()
        l.addListener(screen)
        l.connect()
        waitFor("the shell") { screen.text.contains("TERM=") }
        assertTrue(prompts.any { it is Prompt.Password })
        l.close()
    }

    /** no password given: the link closed with an authentication failure */
    @Test
    fun noPasswordFailsTheLogin() {
        val known = KnownHosts(tmp.newFile("known_hosts")) { p, o -> user()(p, o) }
        val l = link("alice", user(password = null), known)
        l.connect()
        waitFor("closed") { l.state.value is TerminalLink.State.Closed }
        val r = (l.state.value as TerminalLink.State.Closed).reason
        assertNotNull(r)
        assertTrue(r!!, r.contains("authentication", ignoreCase = true))
    }

    /** another key at a known host: asked as CHANGED with the old fingerprint, refused, no password asked, the old key still kept */
    @Test
    fun aChangedHostKeyIsCaughtAndRefused() {
        // (the host keys are asked through KnownHosts' own question: accepted at first, then refused)
        var acceptHost = true
        val known = KnownHosts(tmp.newFile("known_hosts")) { p, o -> user(acceptHost = acceptHost)(p, o) }
        val l = link("alice", user(), known)
        val screen = Screen()
        l.addListener(screen)
        l.connect()
        waitFor("the shell") { screen.text.contains("TERM=") }
        l.close()
        val port = server.port
        server.stop(true)
        // the same address, another key
        startServer(port, "hostkey2")
        prompts.clear()
        acceptHost = false
        val l2 = link("alice", user(), known)
        l2.connect()
        waitFor("closed") { l2.state.value is TerminalLink.State.Closed }
        val hk = prompts.filterIsInstance<Prompt.HostKey>().single()
        assertTrue("no password asked of a host refused: $prompts", prompts.none { it is Prompt.Password })
        assertNotNull("the old key shown", hk.oldFingerprint)
        assertTrue(hk.title, hk.title.contains("CHANGED"))
        assertTrue(hk.fingerprint.startsWith("SHA256:"))
        val r = (l2.state.value as TerminalLink.State.Closed).reason!!
        assertTrue(r, r.contains("host key"))
        // the old key still the one kept
        assertEquals(1, known.keysFor("127.0.0.1", port).size)
    }

    /** each kind of key made here logging in, no password asked; the key list there again in a new store */
    @Test
    fun keyLoginWithAKeyMadeHere() {
        val keys = SshKeys(tmp.newFolder("keys"), PlainBox())
        for (kind in KeyKind.entries) {
            val k = keys.generate(kind, "phone ${kind.name}")
            val prefix = when (kind.algorithm) {
                "Ed25519" -> "ssh-ed25519 "
                "RSA" -> "ssh-rsa "
                else -> "ecdsa-sha2-nistp${kind.bits} "
            }
            assertTrue(k.publicKey, k.publicKey.startsWith(prefix))
            allowedKeys.clear()
            allowedKeys.add(keys.keyPair(k.id).public)
            val known = KnownHosts(tmp.newFile("known_hosts_${kind.name}")) { p, o -> user()(p, o) }
            prompts.clear()
            val l = link("bob", user(), known, key = keys.provider(k.id))
            val screen = Screen()
            l.addListener(screen)
            l.connect()
            waitFor("the shell with ${kind.label}") { screen.text.contains("TERM=") || l.state.value is TerminalLink.State.Closed }
            assertEquals("${kind.label}: ${l.state.value}", TerminalLink.State.Open, l.state.value)
            assertTrue("no password asked", prompts.none { it is Prompt.Password })
            l.close()
        }
        // the list survives (a new store on the same folder)
        assertEquals(KeyKind.entries.size, SshKeys(keys.let { tmp.root.resolve("keys") }, PlainBox()).keys.value.size)
    }

    /** OpenSSH keys with and without a passphrase and a PEM RSA key imported, their fingerprints, and a login with one */
    @Test
    fun importedKeysOpenSshWithAPassphraseAndPem() {
        val keys = SshKeys(tmp.newFolder("keys"), PlainBox())
        // an OpenSSH key with a passphrase (as ssh-keygen makes them)
        val pair = KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair()
        val ctx = OpenSSHKeyEncryptionContext().apply { setPassword("hunter2"); cipherName = "AES"; cipherMode = "CTR"; cipherType = "256" }
        val openssh = ByteArrayOutputStream().also { OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "me@laptop", ctx, it) }.toString()
        assertTrue(openssh.contains("BEGIN OPENSSH PRIVATE KEY"))
        val k1 = keys.import(openssh, "hunter2", "laptop key")
        assertEquals(KeyFormat.fingerprint(pair.public), k1.fingerprint)
        // and without one
        val plain = ByteArrayOutputStream().also { OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "x", null, it) }.toString()
        assertEquals(k1.fingerprint, keys.import(plain, null, "same").fingerprint)
        // a PEM (PKCS#8) RSA key
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = StringWriter().also { w -> JcaPEMWriter(w).use { it.writeObject(rsa.private) } }.toString()
        val k3 = keys.import(pem, null, "pem")
        assertTrue(k3.publicKey.startsWith("ssh-rsa "))

        // and they log in
        allowedKeys.add(pair.public)
        val known = KnownHosts(tmp.newFile("known_hosts")) { p, o -> user()(p, o) }
        val l = link("bob", user(), known, key = keys.provider(k1.id))
        val screen = Screen()
        l.addListener(screen)
        l.connect()
        waitFor("the shell") { screen.text.contains("TERM=") }
        l.close()
    }

    /** a kept password through the [SecretBox]: given back, not in the file in the clear, removed */
    @Test
    fun keptPasswordsAreSealed() {
        val box = object : SecretBox {
            /** [plain] XORed with 0x5a */
            override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
            /** [sealed] XORed back */
            override fun open(sealed: ByteArray) = seal(sealed)
        }
        val f = tmp.newFile("passwords.json")
        val p = PasswordStore(f, box)
        p.put("entry1", "s3cret!")
        assertEquals("s3cret!", p.get("entry1"))
        assertTrue("not in the clear", !f.readText().contains("s3cret"))
        p.remove("entry1")
        assertNull(p.get("entry1"))
    }
}
