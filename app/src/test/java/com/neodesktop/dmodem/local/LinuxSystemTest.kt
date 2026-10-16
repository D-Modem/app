// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.local

import com.neodesktop.dmodem.terminal.TerminalLink
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Alpine Linux in the local terminal, as the app runs it, on this host:
 * proot built for it by native-proot/build.sh, the root file system
 * downloaded from Alpine (where it can be reached), shells on a pty.
 * What only a phone can tell (its SELinux rules, its /proc) isn't here.
 */
class LinuxSystemTest {

    companion object {
        private lateinit var base: File
        private lateinit var programs: File
        private lateinit var storage: File
        private lateinit var system: LinuxSystem

        private fun hostArch(): String? = when (System.getProperty("os.arch")) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64" -> "aarch64"
            else -> null
        }

        /** the proxy this host reaches the net by, for apk inside (none on a phone) */
        private fun proxyEnv(): Map<String, String> {
            val out = HashMap<String, String>()
            for (k in listOf("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY")) System.getenv(k)?.let { out[k.lowercase()] = it }
            // (a proxy that re-signs TLS: its CA, for apk)
            val ca = File("/etc/ssl/certs/ca-certificates.crt")
            if (out.isNotEmpty() && ca.exists()) out["SSL_CERT_FILE"] = "/host-ca.crt"
            return out
        }

        @BeforeClass
        @JvmStatic
        fun install() {
            val arch = hostArch()
            assumeTrue("no Alpine for ${System.getProperty("os.arch")}", arch != null)
            assumeTrue("Alpine can't be reached", runCatching { (URL(LinuxSystem.MIRROR).openConnection().apply { connectTimeout = 10_000 }).getInputStream().close(); true }.getOrDefault(false))
            for (tool in listOf("cc", "make", "gawk", "curl")) {
                assumeTrue("$tool isn't installed (to build proot)", System.getenv("PATH").split(':').any { File(it, tool).canExecute() })
            }
            Pty.load(LocalShellTest.hostPty())
            base = Files.createTempDirectory("linux").toFile()
            programs = File(base, "lib")
            val script = listOf(File("../native-proot/build.sh"), File("native-proot/build.sh")).first { it.exists() }
            val p = ProcessBuilder(script.path, "host", programs.path).redirectErrorStream(true).start()
            val log = p.inputStream.readBytes().decodeToString()
            p.waitFor(10, TimeUnit.MINUTES)
            assertEquals(log, 0, p.exitValue())
            storage = File(base, "storage").apply { mkdirs() }
            File(storage, "Download").mkdirs()
            File(storage, "Download/from-the-phone.txt").writeText("hello from shared storage\n")
            system = LinuxSystem(
                File(base, "alpine"), programs, File(base, "tmp"), arch!!,
                storage = { storage },
                nameServers = { listOf("9.9.9.9") },
                extraEnv = proxyEnv(),
            )
            val steps = ArrayList<String>()
            system.install { what, _ -> if (steps.lastOrNull() != what) steps.add(what) }
            assertTrue(steps.toString(), steps.last().startsWith("Installed Alpine Linux"))
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            if (::base.isInitialized) Tar.removeTree(base)
        }
    }

    /** the shell's output, its colours left out; the cursor's position told when asked (busybox's shell asks, as a terminal answers) */
    private class Screen(private val link: LocalLink) : TerminalLink.Listener {
        private val sb = StringBuilder()
        override fun onData(data: ByteArray, length: Int) {
            val t = String(data, 0, length)
            if (t.contains("\u001b[6n")) link.write("\u001b[1;1R".toByteArray())
            synchronized(sb) { sb.append(t) }
        }
        val text get() = synchronized(sb) { sb.toString().replace(Regex("\u001b\\[[0-9;?]*[A-Za-z]"), "") }
    }

    private fun shell(): Pair<LocalLink, Screen> {
        // (the host's CA bound in for its proxy: the test's own addition)
        val l = system.link(100, 30)
        val s = Screen(l)
        l.addListener(s)
        assertTrue(l.start())
        return l to s
    }

    private fun waitFor(what: String, s: Screen, secs: Int = 20, cond: (String) -> Boolean) {
        val end = System.currentTimeMillis() + secs * 1000L
        while (!cond(s.text)) {
            if (System.currentTimeMillis() > end) throw AssertionError("$what; the screen:\n${s.text}")
            Thread.sleep(20)
        }
    }

    @Test
    fun installedAndSetUp() {
        assertTrue(system.installed)
        assertTrue(system.version!!.matches(Regex("3\\.22\\.\\d+")))
        assertEquals("nameserver 9.9.9.9\n", File(system.rootfs, "etc/resolv.conf").readText())
        assertTrue(File(system.rootfs, "etc/profile.d/dmodem.sh").readText().contains("@alpine"))
        // the made-up /proc files only for those Android hides (none hidden here)
        assertFalse(system.command().any { it.endsWith(":/proc/stat") })
        val hidden = LinuxSystem(system.dir, programs, File(base, "tmp"), system.arch, readable = { !it.path.endsWith("stat") })
        assertTrue(hidden.command().any { it.endsWith(":/proc/stat") })
    }

    @Test
    fun aRootShellWithThePhonesStorage() {
        val (l, s) = shell()
        waitFor("the prompt", s) { it.contains("root@alpine") && it.contains("~#") }
        l.write("cat /etc/alpine-release; id -u; echo \$TERM; cat /sdcard/Download/from-the-phone.txt; echo made-in-linux > /sdcard/Download/back.txt\r".toByteArray())
        waitFor("its answers", s) { it.contains("hello from shared storage") && it.contains("xterm-256color") }
        assertTrue(s.text, s.text.contains(system.version!!))
        val end = System.currentTimeMillis() + 5000
        val back = File(storage, "Download/back.txt")
        while (!back.exists() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals("made-in-linux\n", back.readText())
        // the size, and hard links (proot's link2symlink)
        l.write("stty size; ln /etc/hostname /root/hl && cat /root/hl\r".toByteArray())
        val hostname = File(system.rootfs, "etc/hostname").readText().trim()
        waitFor("size and link", s) { it.contains("30 100") && it.contains("\r\n$hostname\r\n") }
        l.write("exit\r".toByteArray())
        val end2 = System.currentTimeMillis() + 10_000
        while (l.state.value !is TerminalLink.State.Closed && System.currentTimeMillis() < end2) Thread.sleep(20)
        assertEquals(0, l.exitStatus)
    }

    @Test
    fun apkAddsPackages() {
        val (l, s) = shell()
        waitFor("the prompt", s) { it.contains("~#") }
        val ca = if (proxyEnv().containsKey("SSL_CERT_FILE")) "cp /sdcard/../host-ca.crt / 2>/dev/null; " else ""
        // (behind this host's TLS-re-signing proxy: its CA copied in from the storage folder)
        if (ca.isNotEmpty()) File("/etc/ssl/certs/ca-certificates.crt").copyTo(File(system.rootfs, "host-ca.crt"), overwrite = true)
        l.write("apk update -q && apk add -q lua5.4 && lua5.4 -e 'print(\"lua says \" .. 6*7)'\r".toByteArray())
        waitFor("lua from apk", s, secs = 180) { it.contains("lua says 42") }
    }

    @Test
    fun removedLeavingTheStorage() {
        val other = LinuxSystem(File(base, "other"), programs, File(base, "tmp2"), system.arch, storage = { storage })
        File(other.rootfs, "bin").mkdirs()
        File(other.dir, ".installed").writeText("x")
        Files.createSymbolicLink(File(other.rootfs, "phone").toPath(), storage.toPath())
        other.remove()
        assertFalse(other.dir.exists())
        assertTrue(File(storage, "Download/from-the-phone.txt").exists())
    }
}
