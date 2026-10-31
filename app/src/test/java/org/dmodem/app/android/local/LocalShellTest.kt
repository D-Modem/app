// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.local

import org.dmodem.app.android.terminal.TerminalLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The local shell's pseudo-terminal, with pty.c built for the host (where
 * a C compiler is) and real shells on it: sh, and mksh (Android's own
 * shell) with the app's start-up file where it is installed.
 */
class LocalShellTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val links = ArrayList<LocalLink>()

    companion object {
        /** pty.c built for the host and loaded, once for the class */
        @BeforeClass
        @JvmStatic
        fun buildPty() = Pty.load(hostPty())

        /** pty.c built for the host (a fresh copy each time: a library loads in one class loader only) */
        fun hostPty(): String {
            val cc = listOf("/usr/bin/cc", "/usr/bin/gcc", "/usr/bin/clang").firstOrNull { File(it).canExecute() }
            assumeTrue("no C compiler to build pty.c for the host", cc != null)
            val javaHome = File(System.getProperty("java.home"))
            val include = listOf(File(javaHome, "include"), File(javaHome.parentFile, "include")).firstOrNull { File(it, "jni.h").exists() }
            assumeTrue("no jni.h", include != null)
            val src = listOf(File("src/main/cpp/pty.c"), File("app/src/main/cpp/pty.c")).first { it.exists() }
            val out = File.createTempFile("libdmodem_pty", ".so").apply { deleteOnExit() }
            val p = ProcessBuilder(
                cc!!, "-shared", "-fPIC", "-Wall", "-I${include!!.path}", "-I${File(include, "linux").path}", src.path, "-o", out.path,
            ).redirectErrorStream(true).start()
            val msg = p.inputStream.readBytes().decodeToString()
            p.waitFor(60, TimeUnit.SECONDS)
            assertEquals(msg, 0, p.exitValue())
            return out.path
        }
    }

    /** the shells started closed */
    @After
    fun tearDown() = links.forEach { it.close() }

    private class Screen : TerminalLink.Listener {
        private val sb = StringBuilder()
        /** kept, as it came */
        override fun onData(data: ByteArray, length: Int) {
            synchronized(sb) { sb.append(String(data, 0, length)) }
        }
        val text get() = synchronized(sb) { sb.toString() }
        /** what came so far forgotten */
        fun clear() = synchronized(sb) { sb.setLength(0) }
    }

    /** [link] started, a [Screen] taking what it says */
    private fun start(link: LocalLink): Screen {
        links.add(link)
        val s = Screen()
        link.addListener(s)
        assertTrue(link.start())
        return s
    }

    /** waits until [cond] holds for [s]'s text, up to 10 s; fails with the screen */
    private fun waitFor(what: String, s: Screen, cond: (String) -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond(s.text)) {
            if (System.currentTimeMillis() > end) throw AssertionError("$what; the screen:\n${s.text}")
            Thread.sleep(10)
        }
    }

    /** an interactive /bin/sh in the test's folder, [cols] by [rows] */
    private fun sh(cols: Int = 80, rows: Int = 24) =
        LocalLink(listOf("/bin/sh", "-i"), mapOf("PATH" to "/usr/bin:/bin", "PS1" to "$ ", "TERM" to "xterm-256color"), tmp.root.path, cols, rows)

    /** [s] typed on the shell's terminal */
    private fun LocalLink.type(s: String) = assertTrue(write(s.toByteArray()))

    /** a shell on its own pty: its output, its window's size and changes, the directory it started in */
    @Test
    fun aShellOnItsOwnTerminal() {
        val l = sh()
        val s = start(l)
        l.type("echo hi \$((1+2)); tty\r")
        waitFor("its output", s) { it.contains("hi 3") && it.contains("/dev/pts/") }
        // the window's size, and its changes
        l.type("stty size\r")
        waitFor("80x24", s) { it.contains("24 80") }
        l.resize(100, 30)
        l.type("stty size\r")
        waitFor("100x30", s) { it.contains("30 100") }
        // in the directory it was started in
        l.type("pwd\r")
        waitFor("its directory", s) { it.contains(tmp.root.canonicalPath) || it.contains(tmp.root.path) }
    }

    /** ^C interrupts the running job, not the shell */
    @Test
    fun ctrlCInterruptsTheJobNotTheShell() {
        val l = sh()
        val s = start(l)
        l.type("sleep 100; echo after\r")
        Thread.sleep(300)
        l.type("\u0003")
        l.type("echo still here\r")
        // ("still here" printed: not the typed line's echo)
        waitFor("the shell still there", s) { t -> t.lines().any { it.trim().endsWith("still here") && !it.contains("echo") } }
        assertTrue(s.text, s.text.lines().none { it.trim() == "after" })
        assertEquals(TerminalLink.State.Open, l.state.value)
    }

    /** the shell's exit: closed with its status, nothing written after */
    @Test
    fun itsExitAndItsStatus() {
        val l = sh()
        start(l)
        l.type("exit 3\r")
        val end = System.currentTimeMillis() + 10_000
        while (l.state.value !is TerminalLink.State.Closed && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(TerminalLink.State.Closed("exited (3)"), l.state.value)
        assertEquals(3, l.exitStatus)
        assertTrue("nothing written after", !l.write("x".toByteArray()))
    }

    /** closed: the shell and its background jobs hung up */
    @Test
    fun closedHangsUpTheShellAndItsJobs() {
        val l = sh()
        val s = start(l)
        val marker = File(tmp.root, "job")
        // a job that would write the marker after a while, unless hung up with the shell
        l.type("(sleep 3; touch ${marker.path}) &\r")
        // (the shell's own output, not the terminal's echo of what was typed: "started" only once it has run the line)
        l.type("echo st''arted\r")
        waitFor("the job started", s) { t -> t.lines().any { it.trim().endsWith("started") && !it.contains("echo") } }
        l.close()
        val end = System.currentTimeMillis() + 5_000
        while (l.state.value !is TerminalLink.State.Closed && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(TerminalLink.State.Closed(null), l.state.value)
        Thread.sleep(3500)
        assertTrue("the job went with it", !marker.exists())
    }

    /** a shell that can't be run: status 127 and the exec failure on the screen */
    @Test
    fun noSuchShell() {
        val l = LocalLink(listOf("/nonexistent/sh"), emptyMap(), null)
        val s = start(l)
        val end = System.currentTimeMillis() + 5_000
        while (l.state.value !is TerminalLink.State.Closed && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(127, l.exitStatus)
        assertTrue(s.text, s.text.contains("exec failed: /nonexistent/sh"))
    }

    /** mksh with the app's start-up file: the coloured prompt, the environment, the aliases; a start-up file the user changed kept */
    @Test
    fun theAppsShellWithItsStartUpFile() {
        assumeTrue("mksh (Android's shell) isn't installed", File("/usr/bin/mksh").canExecute())
        val shell = LocalShell(tmp.newFolder("files"), tmp.newFolder("cache"), shell = "/usr/bin/mksh", base = mapOf("PATH" to "/usr/bin:/bin", "USER" to "u0_a123"))
        val l = shell.link(80, 24)
        val s = start(l)
        // the prompt: user, the home as ~, in colour
        waitFor("the prompt", s) { it.contains("u0_a123@dmodem") && it.contains("~") }
        assertTrue(s.text, s.text.contains("\u001b[1;32m"))
        l.type("echo \$TERM \$COLORTERM \$LANG; echo home=\$HOME; mkdir -p d/e && cd d/e\r")
        // (C.UTF-8: a UTF-8 locale both Android's libc and glibc have)
        waitFor("the environment", s) { it.contains(Regex("xterm-256color truecolor C\\.UTF-8")) && it.contains("home=${shell.home.path}") }
        waitFor("the prompt follows cd", s) { it.contains("~/d/e") }
        // the aliases
        s.clear()
        l.type("alias ll\r")
        waitFor("ll", s) { it.contains("ls -l") }
        // the start-up file kept as the user leaves it
        shell.rc.writeText("PS1='mine> '\n")
        shell.prepare()
        assertEquals("PS1='mine> '\n", shell.rc.readText())
    }
}
