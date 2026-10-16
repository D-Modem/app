// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.local

import com.neodesktop.dmodem.terminal.TerminalLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Linux systems in the local terminal, as the app runs them, on this host:
 * proot built for it by native-proot/build.sh, root file systems
 * downloaded from their sources (where they can be reached), shells on a
 * pty, each distribution's package manager adding a package (Lua) and
 * running it.
 *
 * Alpine always (3 MB), and a custom URL's (the Linux Containers' Alpine,
 * an .xz); the others named in DMODEM_DISTROS ("debian,fedora", or "all"):
 * they are big. What only a phone can tell (its SELinux rules, its /proc)
 * isn't here. (Robolectric for org.json.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinuxSystemTest {

    /** set up once for the class: proot built, the storage folder, Alpine installed */
    private object Setup {
        lateinit var base: File
        lateinit var programs: File
        lateinit var storage: File
        lateinit var alpine: LinuxSystem
        var done = false
        var skipped: String? = null
        val machine = when (System.getProperty("os.arch")) {
            "amd64", "x86_64" -> Machine.forAbi("x86_64")
            "aarch64" -> Machine.forAbi("arm64-v8a")
            else -> null
        }
    }

    /** this host's proxy for the package managers inside (none on a phone) */
    private fun proxyEnv(): Map<String, String> {
        val out = HashMap<String, String>()
        for (k in listOf("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY")) System.getenv(k)?.let { out[k.lowercase()] = it }
        return out
    }

    /** behind this host's proxy (HTTPS only): the mirrors' plain http:// made https:// (the test's doing; a phone needs none) */
    private fun httpsMirrors(sys: LinuxSystem) {
        if (proxyEnv().isEmpty()) return
        val dirs = listOf("etc/apt/sources.list", "etc/apt/sources.list.d", "etc/pacman.d/mirrorlist", "etc/xbps.d", "usr/share/xbps.d", "etc/zypp/repos.d", "etc/yum.repos.d")
        for (d in dirs) {
            val f = File(sys.rootfs, d)
            val files = if (f.isDirectory) f.listFiles()?.toList() ?: emptyList() else listOf(f)
            for (x in files) if (x.isFile && !Files.isSymbolicLink(x.toPath())) x.writeText(x.readText().replace("http://", "https://"))
        }
    }

    /** behind a proxy that re-signs TLS: its CA added to the system's own bundles (the test's doing, not the app's) */
    private fun trustHostCa(sys: LinuxSystem) {
        val ca = File("/etc/ssl/certs/ca-certificates.crt")
        if (proxyEnv().isEmpty() || !ca.exists()) return
        // (the bundles themselves: the links to them followed inside the system, not on the host)
        for (b in listOf(
            "etc/ssl/certs/ca-certificates.crt", "etc/pki/tls/certs/ca-bundle.crt", "etc/ssl/ca-bundle.pem",
            "etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem", "etc/ca-certificates/extracted/tls-ca-bundle.pem",
            "var/lib/ca-certificates/ca-bundle.pem", "etc/ssl/cert.pem",
        )) {
            val f = File(sys.rootfs, b)
            if (f.isFile && !Files.isSymbolicLink(f.toPath())) f.appendText("\n" + ca.readText())
        }
    }

    private fun newSystem(id: String) = LinuxSystem(
        File(Setup.base, id), Setup.programs, File(Setup.base, "tmp"),
        storage = { Setup.storage }, nameServers = { listOf("9.9.9.9") }, extraEnv = proxyEnv(),
    )

    @Before
    fun setUp() {
        Setup.skipped?.let { assumeTrue(it, false) }
        if (Setup.done) return
        fun skip(why: String) { Setup.skipped = why; assumeTrue(why, false) }
        if (Setup.machine == null) skip("no Linux for ${System.getProperty("os.arch")}")
        if (!runCatching { URL(Distro.ALPINE_MIRROR).openConnection().apply { connectTimeout = 10_000 }.getInputStream().close(); true }.getOrDefault(false)) skip("Alpine can't be reached")
        for (tool in listOf("cc", "make", "gawk", "curl")) {
            if (System.getenv("PATH").split(':').none { File(it, tool).canExecute() }) skip("$tool isn't installed (to build proot)")
        }
        Pty.load(LocalShellTest.hostPty())
        Setup.base = Files.createTempDirectory("linux").toFile().apply { deleteOnExit() }
        Setup.programs = File(Setup.base, "lib")
        val script = listOf(File("../native-proot/build.sh"), File("native-proot/build.sh")).first { it.exists() }
        val p = ProcessBuilder(script.path, "host", Setup.programs.path).redirectErrorStream(true).start()
        val log = p.inputStream.readBytes().decodeToString()
        p.waitFor(10, TimeUnit.MINUTES)
        assertEquals(log, 0, p.exitValue())
        Setup.storage = File(Setup.base, "storage").apply { mkdirs() }
        File(Setup.storage, "Download").mkdirs()
        File(Setup.storage, "Download/from-the-phone.txt").writeText("hello from shared storage\n")
        Setup.alpine = newSystem("alpine")
        val steps = ArrayList<String>()
        Setup.alpine.install(Distro.Alpine().resolve(Setup.machine!!, Http()), Http()) { what, _ -> if (steps.lastOrNull() != what) steps.add(what) }
        assertTrue(steps.toString(), steps.last().startsWith("Installed Alpine Linux"))
        trustHostCa(Setup.alpine)
        Setup.done = true
    }

    /** the shell's output, its colours left out; the cursor's position told when asked (busybox's shell asks, as a terminal answers) */
    private class Screen(private val link: LocalLink) : TerminalLink.Listener {
        private val sb = StringBuilder()
        override fun onData(data: ByteArray, length: Int) {
            val t = String(data, 0, length)
            if (t.contains("\u001b[6n")) link.write("\u001b[1;1R".toByteArray())
            synchronized(sb) { sb.append(t) }
        }
        val text get() = synchronized(sb) { sb.toString().replace(Regex("\u001b\\[[0-9;?]*[A-Za-z]|\u001b\\][^\u0007]*\u0007"), "") }
    }

    private fun shell(sys: LinuxSystem): Pair<LocalLink, Screen> {
        val l = sys.link(100, 30)
        val s = Screen(l)
        l.addListener(s)
        assertTrue(l.start())
        return l to s
    }

    private fun waitFor(what: String, s: Screen, secs: Int = 30, cond: (String) -> Boolean) {
        val end = System.currentTimeMillis() + secs * 1000L
        while (!cond(s.text)) {
            if (System.currentTimeMillis() > end) throw AssertionError("$what; the screen:\n${s.text.takeLast(3000)}")
            Thread.sleep(50)
        }
    }

    @Test
    fun alpineInstalledAndSetUp() {
        val sys = Setup.alpine
        assertTrue(sys.installed)
        assertTrue(sys.name, sys.name.matches(Regex("Alpine Linux 3\\.22\\.\\d+")))
        assertEquals("nameserver 9.9.9.9\n", File(sys.rootfs, "etc/resolv.conf").readText())
        assertTrue(File(sys.rootfs, "etc/profile.d/dmodem.sh").readText().contains("@alpine"))
        // made-up /proc files only for those Android hides (none hidden here)
        assertFalse(sys.command().any { it.endsWith(":/proc/stat") })
        val hidden = LinuxSystem(sys.dir, Setup.programs, File(Setup.base, "tmp"), readable = { !it.path.endsWith("stat") })
        assertTrue(hidden.command().any { it.endsWith(":/proc/stat") })
    }

    @Test
    fun aRootShellWithThePhonesStorage() {
        val (l, s) = shell(Setup.alpine)
        waitFor("the prompt", s) { it.contains("root@alpine") && it.contains("~#") }
        l.write("id -u; echo \$TERM; cat /sdcard/Download/from-the-phone.txt; echo made-in-linux > /sdcard/Download/back.txt\r".toByteArray())
        waitFor("its answers", s) { it.contains("hello from shared storage") && it.contains("xterm-256color") }
        val end = System.currentTimeMillis() + 5000
        val back = File(Setup.storage, "Download/back.txt")
        while (!back.exists() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals("made-in-linux\n", back.readText())
        // the size, and hard links (proot's link2symlink)
        l.write("stty size; ln /etc/hostname /root/hl && cat /root/hl\r".toByteArray())
        val hostname = File(Setup.alpine.rootfs, "etc/hostname").readText().trim()
        waitFor("size and link", s) { it.contains("30 100") && it.contains("\r\n$hostname\r\n") }
        l.write("exit\r".toByteArray())
        val end2 = System.currentTimeMillis() + 10_000
        while (l.state.value !is TerminalLink.State.Closed && System.currentTimeMillis() < end2) Thread.sleep(20)
        assertEquals(0, l.exitStatus)
    }

    @Test
    fun apkAddsPackages() = packageManager(Setup.alpine, "alpine")

    /** a package added with the system's own package manager, and run */
    private fun packageManager(sys: LinuxSystem, id: String) {
        val (l, s) = shell(sys)
        try {
            // (root's prompt, as the distribution's own start-up files may have it: openSUSE's "host:~ #")
            waitFor("the prompt", s, 60) { it.trimEnd().endsWith("#") }
            val cmd = INSTALL_LUA[id] ?: throw AssertionError("no command for $id")
            l.write("$cmd && \$LUA -e 'print(\"lua says \" .. 6*7)'\r".toByteArray())
            waitFor("Lua from $id's package manager", s, secs = 900) { it.contains("lua says 42") }
        } finally {
            // (a failure's shell closed too, not left in a removed system)
            l.close()
        }
    }

    /** a custom URL's: the Linux Containers' Alpine (an .xz), with its checksum */
    @Test
    fun fromACustomUrl() {
        val m = Setup.machine!!
        val row = Distro.lxcIndex(Http()).first { it.distro == "alpine" && it.arch == m.lxc && it.variant == "default" && it.release.startsWith("3.") }
        val dir = Distro.LXC_MIRROR + row.path.trimEnd('/')
        val sha = Regex("([0-9a-f]{64})\\s+rootfs\\.tar\\.xz").find(Http().text("$dir/SHA256SUMS"))!!.groupValues[1]
        val d = Distro.Custom(Distro.customId("My Alpine"), "My Alpine", "$dir/rootfs.tar.xz", sha)
        val sys = newSystem(d.id)
        sys.install(d.resolve(m, Http()), Http())
        assertEquals("My Alpine", sys.name)
        val (l, s) = shell(sys)
        waitFor("its shell", s) { it.contains("root@alpine") }
        l.write("cat /etc/alpine-release\r".toByteArray())
        waitFor("its release", s) { it.contains("\r\n${row.release}.") }
        l.close()
        sys.remove()
        assertFalse(sys.dir.exists())
    }

    /** the big ones, each named in DMODEM_DISTROS: installed, a shell, Lua from its package manager, removed */
    @Test
    fun otherDistributions() {
        val want = System.getenv("DMODEM_DISTROS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        assumeTrue("DMODEM_DISTROS names none", want.isNotEmpty())
        val ids = if (want == listOf("all")) Distro.CATALOG.map { it.id } - "alpine" else want
        val failures = ArrayList<String>()
        for (id in ids) {
            val d = Distro.byId(id) ?: throw AssertionError("no distribution $id")
            val sys = newSystem(id)
            val t0 = System.currentTimeMillis()
            try {
                val r = d.resolve(Setup.machine!!, Http())
                sys.install(r, Http())
                trustHostCa(sys)
                httpsMirrors(sys)
                println("DISTRO $id: ${sys.name}, shell ${sys.loginShell()}, installed in ${(System.currentTimeMillis() - t0) / 1000}s")
                packageManager(sys, id)
                println("DISTRO $id: package manager works (${(System.currentTimeMillis() - t0) / 1000}s)")
            } catch (e: Throwable) {
                failures.add("$id: ${e.message?.take(1500)}")
            } finally {
                runCatching { sys.remove() }
            }
        }
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }

    @Test
    fun removedLeavingTheStorage() {
        val other = newSystem("other")
        File(other.rootfs, "etc").mkdirs()
        File(other.dir, ".installed").writeText("x")
        Files.createSymbolicLink(File(other.rootfs, "phone").toPath(), Setup.storage.toPath())
        other.remove()
        assertFalse(other.dir.exists())
        assertTrue(File(Setup.storage, "Download/from-the-phone.txt").exists())
    }

    companion object {
        /** Lua installed, its command in $LUA, quietly: each package manager's way */
        val INSTALL_LUA = mapOf(
            "alpine" to "apk update -q && apk add -q lua5.4 && LUA=lua5.4",
            "debian" to "apt-get update -qq && apt-get install -y -qq --no-install-recommends lua5.4 >/dev/null && LUA=lua5.4",
            "ubuntu" to "apt-get update -qq && apt-get install -y -qq --no-install-recommends lua5.4 >/dev/null && LUA=lua5.4",
            "kali" to "apt-get update -qq && apt-get install -y -qq --no-install-recommends lua5.4 >/dev/null && LUA=lua5.4",
            "devuan" to "apt-get update -qq && apt-get install -y -qq --no-install-recommends lua5.4 >/dev/null && LUA=lua5.4",
            "fedora" to "dnf install -y -q lua >/dev/null && LUA=lua",
            "rocky" to "dnf install -y -q lua >/dev/null && LUA=lua",
            "alma" to "dnf install -y -q lua >/dev/null && LUA=lua",
            "archlinux" to "pacman -Sy --noconfirm --needed lua >/dev/null && LUA=lua",
            "opensuse" to "zypper -n -q --gpg-auto-import-keys in -y lua54 >/dev/null && LUA=lua5.4",
            "void" to "xbps-install -Syu xbps >/dev/null; xbps-install -Sy lua54 >/dev/null && LUA=lua5.4",
        )
    }
}
