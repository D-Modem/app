// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.transfer

import com.neodesktop.dmodem.local.LocalLink
import com.neodesktop.dmodem.local.LocalShellTest
import com.neodesktop.dmodem.local.Pty
import com.neodesktop.dmodem.terminal.TerminalSession
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Transfers in a terminal session, a shell on a pty: a remote's sz
 * noticed and received by itself, its rz asking for files and sent them,
 * the screen the shell's again afterwards.
 */
class SessionTransferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var session: TerminalSession? = null

    companion object {
        /** skipped without lrzsz; the host's pty library loaded */
        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue("lrzsz isn't installed", File("/usr/bin/sz").canExecute())
            Pty.load(LocalShellTest.hostPty())
        }
    }

    /** the session closed */
    @After
    fun tearDown() {
        session?.close()
    }

    /** a session on /bin/sh in [dir], started */
    private fun shell(dir: File): TerminalSession {
        val l = LocalLink(listOf("/bin/sh"), mapOf("PATH" to "/usr/bin:/bin", "HOME" to dir.path, "PS1" to "$ "), dir.path, 80, 24)
        val s = TerminalSession(l, kind = TerminalSession.Kind.LOCAL) { null }
        session = s
        assertTrue(l.start())
        return s
    }

    /** [s]'s screen, as text */
    private fun screen(s: TerminalSession) = synchronized(s.emulator) { (0 until s.emulator.rows).joinToString("\n") { s.emulator.line(it).text() } }

    /** waited until [ok] of [s]'s screen, or an AssertionError naming [what] with the screen after [secs] */
    private fun waitFor(what: String, s: TerminalSession, secs: Int = 60, ok: (String) -> Boolean) {
        val end = System.currentTimeMillis() + secs * 1000
        while (System.currentTimeMillis() < end) {
            if (ok(screen(s))) return
            Thread.sleep(50)
        }
        throw AssertionError("$what; the screen:\n${screen(s)}")
    }

    /** a remote's sz noticed and received by itself, the shell's output after it and typing reaching it again */
    @Test
    fun aRemoteSzReceivedByItself() {
        val src = File(tmp.newFolder("remote"), "data.bin").apply { writeBytes(Random(5).nextBytes(150_000)) }
        val into = FolderIncoming(tmp.newFolder("downloads"))
        val s = shell(src.parentFile!!)
        s.incoming = { into }
        waitFor("the prompt", s) { it.contains("$ ") }
        s.send("sz -b data.bin; echo after-the-transfer\r")
        waitFor("the transfer's end", s) { it.contains("[ZMODEM: received 1 file(s)]") }
        waitFor("the shell's output after it", s) { it.contains("after-the-transfer\n") || it.lines().any { l -> l.trim() == "after-the-transfer" } }
        assertArrayEquals(src.readBytes(), into.received.single().readBytes())
        assertEquals("", s.transferState.value?.result)
        // (and what's typed reaches the shell again)
        s.send("echo typed-again\r")
        waitFor("typing after it", s) { it.lines().any { l -> l.trim() == "typed-again" } }
    }

    /** a remote's rz asking for files by ZMODEM, and sent them */
    @Test
    fun aRemoteRzAsksForFiles() {
        val dir = tmp.newFolder("remote")
        val local = File(tmp.newFolder("phone"), "upload.txt").apply { writeText("from the phone\n".repeat(500)) }
        val s = shell(dir)
        var wanted: Protocol? = null
        s.onSendWanted = { p -> wanted = p; s.sendFiles(p, listOf(Outgoing.of(local))) }
        waitFor("the prompt", s) { it.contains("$ ") }
        s.send("rz -b\r")
        waitFor("the transfer's end", s) { it.contains("[ZMODEM: sent 1 file(s)]") }
        assertEquals(Protocol.ZMODEM, wanted)
        val got = File(dir, "upload.txt")
        val end = System.currentTimeMillis() + 10_000
        while (got.length() < local.length() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertArrayEquals(local.readBytes(), got.readBytes())
    }

    /** a remote's gkermit -s noticed and received by itself */
    @Test
    fun aKermitSendReceivedByItself() {
        assumeTrue(File("/usr/bin/gkermit").canExecute())
        val src = File(tmp.newFolder("remote"), "k.dat").apply { writeBytes(Random(6).nextBytes(40_000)) }
        val into = FolderIncoming(tmp.newFolder("downloads"))
        val s = shell(src.parentFile!!)
        s.incoming = { into }
        waitFor("the prompt", s) { it.contains("$ ") }
        s.send("gkermit -q -i -P -s k.dat\r")
        waitFor("the transfer's end", s) { it.contains("[Kermit: received 1 file(s)]") }
        assertArrayEquals(src.readBytes(), into.received.single().readBytes())
    }
}
