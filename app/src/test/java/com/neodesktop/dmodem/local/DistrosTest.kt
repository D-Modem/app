// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** [f]'s SHA-256, in hex */
private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

/**
 * The distributions' sources read (from made-up indexes: no network), and
 * root file systems installed from local tarballs as the downloads would
 * bring them. (Robolectric for org.json.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DistrosTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val arm64 = Machine.forAbi("arm64-v8a")!!

    /** URLs answered from a map, downloads copied from local files */
    private class FakeHttp(val pages: Map<String, String>, val files: Map<String, File> = emptyMap()) : Http() {
        val asked = ArrayList<String>()
        /** the page at [url], kept as asked; a 404 if there's none */
        override fun text(url: String): String { asked.add(url); return pages[url] ?: throw IOException("404 $url") }
        /** the local file's size for [url], or -1 */
        override fun size(url: String): Long = files[url]?.length() ?: -1
        /** the local file for [url] copied to [to]: its SHA-256 */
        override fun download(url: String, to: File, size: Long, progress: (Float) -> Unit): String {
            val f = files[url] ?: throw IOException("404 $url")
            f.copyTo(to, overwrite = true)
            progress(1f)
            return sha256(f)
        }
    }


    private val index = """
        debian;bookworm;arm64;default;20261016_05:24;/images/debian/bookworm/arm64/default/20261016_05:24/
        debian;trixie;arm64;cloud;20261016_05:24;/images/debian/trixie/arm64/cloud/20261016_05:24/
        debian;trixie;arm64;default;20261016_05:24;/images/debian/trixie/arm64/default/20261016_05:24/
        debian;trixie;amd64;default;20261016_05:24;/images/debian/trixie/amd64/default/20261016_05:24/
        fedora;43;arm64;default;20261015_20:33;/images/fedora/43/arm64/default/20261015_20:33/
        fedora;44;arm64;default;20261015_20:33;/images/fedora/44/arm64/default/20261015_20:33/
        fedora;9;arm64;default;20261015_20:33;/images/fedora/9/arm64/default/x/
        archlinux;current;amd64;default;20261016_04:18;/images/archlinux/current/amd64/default/20261016_04:18/
    """.trimIndent()

    /** the Linux Containers' index read: the newest Fedora by number, its checksum; Debian only for the machines it's built for, Arch not for this one */
    @Test
    fun theLinuxContainersIndex() {
        val rows = Distro.parseLxcIndex(index)
        assertEquals(8, rows.size)
        val sha = "a".repeat(64)
        val dir = "${Distro.LXC_MIRROR}/images/fedora/44/arm64/default/20261015_20:33"
        val http = FakeHttp(
            mapOf(
                "${Distro.LXC_MIRROR}/meta/1.0/index-system" to index,
                "$dir/SHA256SUMS" to "$sha  rootfs.tar.xz\n${"b".repeat(64)}  rootfs.squashfs\n",
            ),
        )
        // the newest Fedora (44, not 9: numbers compared as numbers)
        val r = Distro.byId("fedora")!!.resolve(arm64, http)
        assertEquals("$dir/rootfs.tar.xz", r.url)
        assertEquals(sha, r.sha256)
        assertEquals("Fedora 44", r.name)
        // Debian's named release, and only for the machines it's built for
        val debian = Distro.byId("debian") as Distro.Lxc
        assertTrue(debian.availableFor(arm64, rows))
        assertFalse(debian.availableFor(Machine.forAbi("armeabi-v7a")!!, rows))
        assertFalse((Distro.byId("archlinux") as Distro.Lxc).availableFor(arm64, rows))
        val e = runCatching { Distro.byId("archlinux")!!.resolve(arm64, http) }.exceptionOrNull()
        assertTrue(e?.message, e?.message!!.contains("isn't built for this phone's processor"))
    }

    /** Ubuntu's newest point release for the machine, by number, with its checksum */
    @Test
    fun ubuntusNewestPointRelease() {
        val base = "${Distro.UBUNTU_MIRROR}/24.04/release"
        val http = FakeHttp(
            mapOf(
                "$base/SHA256SUMS" to """
                    ${"1".repeat(64)} *ubuntu-base-24.04.3-base-arm64.tar.gz
                    ${"2".repeat(64)} *ubuntu-base-24.04.10-base-arm64.tar.gz
                    ${"3".repeat(64)} *ubuntu-base-24.04.5-base-arm64.tar.gz
                    ${"4".repeat(64)} *ubuntu-base-24.04.11-base-amd64.tar.gz
                """.trimIndent(),
            ),
        )
        val r = Distro.byId("ubuntu")!!.resolve(arm64, http)
        assertEquals("$base/ubuntu-base-24.04.10-base-arm64.tar.gz", r.url)
        assertEquals("2".repeat(64), r.sha256)
        assertEquals("Ubuntu 24.04.10 LTS", r.name)
    }

    /** a custom system's ID from its name, lower-case and hyphenated */
    @Test
    fun customNames() {
        assertEquals("custom-my-arch-build-2", Distro.customId("My Arch build #2"))
        assertEquals("custom-linux", Distro.customId("!!!"))
    }

    // installs from local tarballs ----------------------------------------

    /** a tiny system: what an apt distribution's image has, under [top] if given */
    private fun tree(top: String? = null): File {
        val t = tmp.newFolder()
        val root = if (top != null) File(t, top).apply { mkdirs() } else t
        File(root, "usr/bin").mkdirs()
        File(root, "usr/bin/bash").writeText("#!bash\n")
        File(root, "usr/bin/env").writeText("#!env\n")
        // usrmerge: /bin a relative link to usr/bin
        Files.createSymbolicLink(File(root, "bin").toPath(), Paths.get("usr/bin"))
        File(root, "etc/apt/apt.conf.d").mkdirs()
        File(root, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\ndaemon:x:1:1::/:/usr/sbin/nologin\n")
        File(root, "etc/os-release").writeText("PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\nID=debian\n")
        Files.createSymbolicLink(File(root, "etc/resolv.conf").toPath(), Paths.get("../run/systemd/resolve/stub-resolv.conf"))
        File(root, "etc/pacman.conf").writeText("[options]\nHoldPkg = pacman\nCheckSpace\n\n[core]\nInclude = x\n")
        return t
    }

    /** [dir] as a tarball made by tar, gzipped or xz'd for "gz" and "xz" */
    private fun tar(dir: File, compress: String): File {
        assumeTrue("no tar here", File("/usr/bin/tar").canExecute() || File("/bin/tar").canExecute())
        val plain = File(tmp.root, "t-${System.nanoTime()}.tar")
        val p = ProcessBuilder("tar", "-cf", plain.path, "-C", dir.path, ".").redirectErrorStream(true).start()
        p.waitFor()
        assertEquals(0, p.exitValue())
        return when (compress) {
            "gz" -> File(plain.path + ".gz").also { o -> GZIPOutputStream(o.outputStream()).use { it.write(plain.readBytes()) } }
            "xz" -> File(plain.path + ".xz").also { o -> XZOutputStream(o.outputStream(), LZMA2Options()).use { it.write(plain.readBytes()) } }
            else -> plain
        }
    }

    /** a system under the test's folder, its name server 10.0.0.53 */
    private fun system(id: String) = LinuxSystem(File(tmp.root, "linux/$id"), tmp.root, tmp.newFolder(), nameServers = { listOf("10.0.0.53") })

    /** installed from a .tar.gz, a .tar.xz (its system in one folder, moved up) and a plain .tar, the download gone */
    @Test
    fun installedFromEachKindOfArchive() {
        for (kind in listOf("gz", "xz", "tar")) {
            val f = tar(tree(top = if (kind == "xz") "debian-rootfs" else null), kind)
            val url = "https://example.org/rootfs.$kind"
            val sys = system("test-$kind")
            sys.install(Rootfs(url, sha256(f), f.length(), "Test $kind", "1"), FakeHttp(emptyMap(), mapOf(url to f)))
            assertTrue(kind, sys.installed)
            assertEquals("Test $kind", sys.name)
            // (xz's had its system in one folder: moved up)
            assertTrue(kind, File(sys.rootfs, "etc/passwd").isFile)
            assertFalse(File(sys.dir, "rootfs.download").exists())
        }
    }

    /** set up for proot: the resolver a file, apt's and pacman's sandboxes off (once), mtab, the prompt, root's shell through links resolved inside */
    @Test
    fun setUpForProot() {
        val f = tar(tree(), "gz")
        val url = "https://example.org/r.tar.gz"
        val sys = system("debian")
        sys.install(Rootfs(url, null, -1, "Debian", "13"), FakeHttp(emptyMap(), mapOf(url to f)))
        val etc = File(sys.rootfs, "etc")
        // the resolver: a file, not systemd's link
        assertFalse(Files.isSymbolicLink(File(etc, "resolv.conf").toPath()))
        assertEquals("nameserver 10.0.0.53\n", File(etc, "resolv.conf").readText())
        assertTrue(File(etc, "apt/apt.conf.d/99-dmodem-proot").readText().contains("APT::Sandbox::User \"root\";"))
        val pacman = File(etc, "pacman.conf").readText()
        assertTrue(pacman, pacman.contains("[options]\nDisableSandbox") && pacman.contains("#CheckSpace"))
        sys.configure()
        assertEquals("again: once", 1, Regex("DisableSandbox").findAll(File(etc, "pacman.conf").readText()).count())
        assertEquals("/proc/self/mounts", Files.readSymbolicLink(File(etc, "mtab").toPath()).toString())
        assertTrue(File(etc, "profile.d/dmodem.sh").readText().contains("\\u@debian"))
        // root's shell: bash, found through the /bin -> usr/bin link
        assertEquals("/bin/bash", sys.loginShell())
        assertTrue(sys.command().takeLast(2) == listOf("/bin/bash", "-l"))
        assertTrue(sys.existsInside("/usr/bin/../bin/env"))
        assertFalse(sys.existsInside("/bin/zsh"))
        // root's plain sh (Void's) with bash there: bash, the shell the profile's prompt is for
        val passwd = File(etc, "passwd")
        val before = passwd.readText()
        passwd.writeText("root:x:0:0:root:/root:/bin/sh\n")
        assertEquals("/bin/bash", sys.loginShell())
        passwd.writeText(before)
        // an absolute link: resolved inside, not on the host
        Files.createSymbolicLink(File(sys.rootfs, "usr/bin/sh").toPath(), Paths.get("/usr/bin/bash"))
        assertTrue(sys.existsInside("/bin/sh"))
        Files.createSymbolicLink(File(sys.rootfs, "usr/bin/host-only").toPath(), Paths.get(tmp.root.path))
        assertFalse(sys.existsInside("/bin/host-only/linux"))
    }

    /** a wrong checksum leaves nothing; a tarball that isn't a Linux system isn't installed */
    @Test
    fun aWrongChecksumOrNotASystemLeavesNothing() {
        val f = tar(tree(), "gz")
        val url = "https://example.org/r.tar.gz"
        val sys = system("bad")
        val e = runCatching { sys.install(Rootfs(url, "0".repeat(64), -1, "Bad", "1"), FakeHttp(emptyMap(), mapOf(url to f))) }.exceptionOrNull()
        assertTrue(e?.message, e?.message!!.contains("checksum doesn't match"))
        assertFalse(sys.dir.exists())
        // a tarball of something else
        val other = tmp.newFolder().apply { File(this, "README").writeText("hi") }
        val g = tar(other, "gz")
        val e2 = runCatching { sys.install(Rootfs(url, null, -1, "Not Linux", "1"), FakeHttp(emptyMap(), mapOf(url to g))) }.exceptionOrNull()
        assertTrue(e2?.message, e2?.message!!.contains("isn't a Linux root file system"))
        assertFalse(sys.installed)
        assertNull(sys.info)
    }

    /** an Alpine installed by the first release is still known by its name */
    @Test
    fun theFirstReleasesAlpineStillKnown() {
        val sys = system("alpine")
        File(sys.rootfs, "etc").mkdirs()
        File(sys.rootfs, "etc/alpine-release").writeText("3.22.6\n")
        File(sys.dir, ".installed").writeText("3.22.6\n")
        assertTrue(sys.installed)
        assertEquals("Alpine Linux 3.22.6", sys.name)
    }
}
