// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ssh

import com.neodesktop.dmodem.terminal.TerminalLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Against OpenSSH itself (sshd, ssh, ssh-keygen, ssh-add), where it's
 * installed (skipped where it isn't): the keys ssh-keygen reads back, the
 * port forwards both ways, the SOCKS proxy, the forwarded agent used by
 * ssh on the server to log in onward, and ssh-copy-id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenSshInteropTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var sshd: Process? = null
    private var port = 0
    private lateinit var keys: SshKeys
    private lateinit var home: File
    private val links = ArrayList<SshLink>()
    private val asked = AtomicInteger()

    private fun have(tool: String) = listOf("/usr/bin", "/usr/sbin", "/bin", "/usr/local/bin").any { File(it, tool).canExecute() }

    private fun run(vararg cmd: String, input: String? = null): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (input != null) p.outputStream.use { it.write(input.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    @Before
    fun setUp() {
        SshSecurity.ensure()
        keys = SshKeys(tmp.newFolder("keys"), PlainBox())
    }

    /** sshd on a free port, logging in [user] (this process's) by the keys in [home]/authorized_keys */
    private fun startSshd() {
        assumeTrue("OpenSSH's sshd isn't installed", have("sshd") && have("ssh") && have("ssh-add"))
        val root = System.getProperty("user.name") == "root"
        // (as another user sshd logs in only that user, and wants its privilege separation directory made)
        assumeTrue("sshd's /run/sshd is missing", root || File("/run/sshd").isDirectory)
        val dir = tmp.newFolder("sshd")
        home = tmp.newFolder("home")
        val hostKey = File(dir, "host_ed25519")
        assertEquals(0, run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", hostKey.path).first)
        port = ServerSocket(0).use { it.localPort }
        val cfg = File(dir, "sshd_config")
        cfg.writeText(
            """
            Port $port
            ListenAddress 127.0.0.1
            HostKey ${hostKey.path}
            PidFile ${dir.path}/sshd.pid
            AuthorizedKeysFile ${home.path}/authorized_keys
            PermitRootLogin yes
            PasswordAuthentication no
            KbdInteractiveAuthentication no
            PubkeyAuthentication yes
            StrictModes no
            AllowTcpForwarding yes
            AllowAgentForwarding yes
            GatewayPorts no
            UsePAM no
            LogLevel ERROR
            """.trimIndent() + "\n",
        )
        if (root) File("/run/sshd").mkdirs()
        sshd = ProcessBuilder("/usr/sbin/sshd", "-D", "-e", "-f", cfg.path).redirectErrorStream(true)
            .redirectOutput(File(dir, "sshd.log")).start()
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return
            Thread.sleep(50)
        }
        val why = "sshd didn't start: " + File(dir, "sshd.log").readText()
        // (as root it must; as another user it may not be allowed to here)
        if (root) throw AssertionError(why) else assumeTrue(why, false)
    }

    @After
    fun tearDown() {
        links.forEach { it.close() }
        sshd?.destroy()
        sshd?.waitFor(5, TimeUnit.SECONDS)
    }

    private fun authorize(k: SshKey) = File(home, "authorized_keys").appendText(k.publicKey + "\n")

    private fun connect(
        k: SshKey,
        forwards: List<ForwardSpec> = emptyList(),
        agent: AgentKeys? = null,
    ): SshLink {
        val known = KnownHosts(tmp.newFile()) { _, _ -> Answer(accepted = true) }
        val l = SshLink(
            "127.0.0.1", port, System.getProperty("user.name"), keys.provider(k.id), known,
            ask = { _, _ -> asked.incrementAndGet(); Answer() },
            forwardSpecs = forwards, agent = agent,
        )
        links.add(l)
        l.connect()
        val end = System.currentTimeMillis() + 20_000
        while (l.state.value is TerminalLink.State.Connecting && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(TerminalLink.State.Open, l.state.value)
        return l
    }

    /** a TCP server here that answers each line with "echo: line" */
    private fun echoServer(): Int {
        val s = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (true) {
                val c = try { s.accept() } catch (_: Exception) { break }
                Thread {
                    c.use {
                        val r = it.getInputStream().bufferedReader()
                        val w = it.getOutputStream()
                        while (true) {
                            val line = r.readLine() ?: break
                            w.write("echo: $line\n".toByteArray()); w.flush()
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return s.localPort
    }

    private fun roundTrip(s: Socket, line: String): String {
        s.soTimeout = 10_000
        s.getOutputStream().write("$line\n".toByteArray())
        s.getOutputStream().flush()
        return s.getInputStream().bufferedReader().readLine()
    }

    private fun bound(l: SshLink, spec: ForwardSpec): Int {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            val st = l.forwards.value.firstOrNull { it.spec == spec }
            if (st?.listening == true) return st.boundPort
            if (st?.error != null) throw AssertionError("$spec: ${st.error}")
            Thread.sleep(20)
        }
        throw AssertionError("$spec not listening: ${l.forwards.value}")
    }

    // ssh-keygen

    @Test
    fun keysAsSshKeygenReadsThem() {
        assumeTrue("ssh-keygen isn't installed", have("ssh-keygen"))
        for (kind in KeyKind.entries) {
            val k = keys.generate(kind, "test ${kind.name}", comment = "me@phone")
            assertTrue(k.publicKey.endsWith(" me@phone"))
            for (pass in listOf("", "correct horse")) {
                val f = tmp.newFile()
                f.writeText(keys.exportPrivate(k.id, pass))
                f.setReadable(false, false); f.setReadable(true, true)
                val (code, out) = run("ssh-keygen", "-y", "-P", pass, "-f", f.path)
                assertEquals("$kind '$pass': $out", 0, code)
                assertEquals("$kind", k.publicKey.split(" ").take(2), out.trim().split(" ").take(2))
                // and our own import reads it back
                assertEquals(k.fingerprint, keys.import(f.readText(), pass, "again").fingerprint)
            }
            if (kind == KeyKind.ED25519) {
                // the wrong passphrase: refused by both
                val f = tmp.newFile().apply { writeText(keys.exportPrivate(k.id, "right")) }
                assertFalse(run("ssh-keygen", "-y", "-P", "wrong", "-f", f.path).first == 0)
            }
            // fingerprints and randomart as ssh-keygen -lv shows them
            val pub = tmp.newFile().apply { writeText(k.publicKey + "\n") }
            val pk = keys.keyPair(k.id).public
            val (_, lv) = run("ssh-keygen", "-lv", "-f", pub.path)
            assertTrue("$kind: $lv", lv.startsWith("${KeyGen.bits(pk)} ${k.fingerprint} me@phone (${KeyGen.typeName(pk)})"))
            assertEquals("$kind", lv.trim().lines().drop(1).joinToString("\n"), KeyGen.randomart(pk))
            val (_, md5) = run("ssh-keygen", "-l", "-E", "md5", "-f", pub.path)
            assertTrue("$kind: $md5", md5.contains(KeyGen.md5(pk)))
            keys.delete(k.id)
        }
    }

    // forwards

    @Test
    fun localRemoteAndSocksForwards() {
        startSshd()
        val k = keys.generate(KeyKind.ED25519, "phone").also { authorize(it) }
        val echo = echoServer()
        val local = ForwardSpec.parse("L 0:127.0.0.1:$echo")!!
        val remote = ForwardSpec.parse("R 0:127.0.0.1:$echo")!!
        val socks = ForwardSpec.parse("D 0")!!
        val l = connect(k, listOf(local, remote, socks))

        // -L: here → the server → the echo server
        Socket("127.0.0.1", bound(l, local)).use { assertEquals("echo: one", roundTrip(it, "one")) }
        // -R: the server listens (here too: the same machine) → back through us → the echo server
        Socket("127.0.0.1", bound(l, remote)).use { assertEquals("echo: two", roundTrip(it, "two")) }
        // -D: SOCKS 5 (Java's own client, the host by name)
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", bound(l, socks)))
        Socket(proxy).use {
            it.connect(InetSocketAddress.createUnresolved("localhost", echo), 10_000)
            assertEquals("echo: three", roundTrip(it, "three"))
        }
        // SOCKS 4a by hand: 0.0.0.1 and the host's name after the user id
        Socket("127.0.0.1", bound(l, socks)).use { s ->
            val o = s.getOutputStream()
            o.write(byteArrayOf(4, 1, (echo shr 8).toByte(), echo.toByte(), 0, 0, 0, 1) + "me".toByteArray() + 0 + "localhost".toByteArray() + 0)
            val reply = ByteArray(8).also { DataInputStream(s.getInputStream()).readFully(it) }
            assertEquals(0x5a, reply[1].toInt())
            assertEquals("echo: four", roundTrip(s, "four"))
        }
        // SOCKS 5 to nothing there: refused (5: connection refused)
        Socket("127.0.0.1", bound(l, socks)).use { s ->
            val o = s.getOutputStream()
            val i = DataInputStream(s.getInputStream())
            o.write(byteArrayOf(5, 1, 0))
            assertEquals(0, ByteArray(2).also { i.readFully(it) }[1].toInt())
            val dead = ServerSocket(0).use { it.localPort }
            o.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (dead shr 8).toByte(), dead.toByte()))
            assertEquals(5, ByteArray(10).also { i.readFully(it) }[1].toInt())
        }
        val counts = l.forwards.value.associate { it.spec to it.total }
        assertEquals(1, counts[local]); assertEquals(1, counts[remote]); assertEquals(3, counts[socks])

        // one added while connected, and one stopped
        val later = ForwardSpec(ForwardSpec.Type.LOCAL, null, 0, "localhost", echo)
        assertFalse("the same forward twice", l.addForward(local))
        assertTrue(l.addForward(later))
        Socket("127.0.0.1", bound(l, later)).use { assertEquals("echo: five", roundTrip(it, "five")) }
        val p = bound(l, local)
        l.removeForward(local)
        assertTrue("closed", runCatching { Socket("127.0.0.1", p).close() }.isFailure)
        assertTrue(l.forwards.value.none { it.spec == local })

        // a port already taken: its error, the others unharmed
        val taken = ServerSocket(0)
        val clash = ForwardSpec(ForwardSpec.Type.LOCAL, null, taken.localPort, "127.0.0.1", echo)
        assertFalse(l.addForward(clash))
        assertNotNull(l.forwards.value.first { it.spec == clash }.error)
        taken.close()
    }

    // the agent

    @Test
    fun theForwardedAgentLogsInOnward() {
        startSshd()
        val k = keys.generate(KeyKind.ED25519, "phone").also { authorize(it) }
        val rsa = keys.generate(KeyKind.RSA_3072, "rsa").also { authorize(it) }
        val confirms = AtomicInteger()
        var allow = true
        val agent = AgentKeys(
            list = { keys.keys.value.map { it.name to keys.keyPair(it.id) } },
            confirm = { _, _ -> confirms.incrementAndGet(); allow },
        )
        val l = connect(k, agent = agent)
        assertTrue(l.agentForwarded)

        // the server sees the app's keys in the agent
        val (code, listed) = l.exec("ssh-add -L")!!
        assertEquals(listed, 0, code)
        assertTrue(listed, listed.contains(k.publicKey.split(" ")[1]))
        assertTrue(listed, listed.contains(rsa.publicKey.split(" ")[1]))

        // and ssh there logs in onward (to the same sshd) with them: a signature, confirmed
        val hop = "ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o BatchMode=yes " +
            "-o IdentitiesOnly=no -p $port ${System.getProperty("user.name")}@127.0.0.1 echo hop-ok"
        val (c2, out2) = l.exec(hop)!!
        assertEquals(out2, 0, c2)
        assertTrue(out2, out2.contains("hop-ok"))
        assertTrue(confirms.get() >= 1)

        // RSA through the agent (rsa-sha2-256/512, as OpenSSH asks for them)
        val rsaOnly = tmp.newFile("rsa.pub").apply { writeText(rsa.publicKey + "\n") }
        val (c3, out3) = l.exec(hop.replace("-o IdentitiesOnly=no", "-o IdentitiesOnly=yes -i ${rsaOnly.path}"))!!
        assertEquals(out3, 0, c3)

        // refused by the user: no signature, no login
        allow = false
        val (c4, out4) = l.exec(hop)!!
        assertTrue(out4, c4 != 0)
        assertTrue(l.events.value.any { it.contains("refused") })
    }

    @Test
    fun noAgentUnlessAsked() {
        startSshd()
        val k = keys.generate(KeyKind.ED25519, "phone").also { authorize(it) }
        val l = connect(k)
        assertFalse(l.agentForwarded)
        val (code, _) = l.exec("ssh-add -L")!!
        // 2: no agent to talk to
        assertEquals(2, code)
    }

    // ssh-copy-id

    @Test
    fun copyIdPutsTheKeyOnTheServerOnce() {
        startSshd()
        val first = keys.generate(KeyKind.ED25519, "first").also { authorize(it) }
        val second = keys.generate(KeyKind.ECDSA_P256, "second")
        val l = connect(first)
        repeat(2) {
            val (code, out) = l.exec(KeyGen.copyIdCommand(second.publicKey).replace("~/.ssh/authorized_keys", "${home.path}/authorized_keys").replace("mkdir -p ~/.ssh", "mkdir -p ${home.path}"))!!
            assertEquals(out, 0, code)
        }
        assertEquals(1, File(home, "authorized_keys").readLines().count { it.contains(second.publicKey.split(" ")[1]) })
        connect(second)
    }
}
