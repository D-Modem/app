// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.local

import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * A Linux system to run in the local terminal (Alpine, Debian, Ubuntu,
 * Fedora, Arch and the rest of [Distro.CATALOG], or one from any URL),
 * run with proot. The app can't run programs it has written itself
 * (Android 10 on); proot is the app's own (libproot.so, in its native
 * library directory), and runs the system's programs through its loader
 * (libproot-loader.so), which maps them rather than executing them.
 *
 * Inside: root (proot's fake one) with its own login shell, the device's
 * /dev, /proc and /sys, and the phone's shared storage at /sdcard when the
 * app may read it. Each system in a directory of its own: rootfs/ and
 * system.json (what it is).
 */
class LinuxSystem(
    /** where it lives: rootfs/ and system.json in it; its name is the system's id */
    val dir: File,
    /** the app's native library directory: libproot.so and libproot-loader.so (a host build's, in the tests) */
    private val programDir: File,
    /** proot's own temporary files */
    private val tmpDir: File,
    /** the phone's shared storage, when the app may use it (bound at /sdcard) */
    private val storage: () -> File? = { null },
    /** the name servers for /etc/resolv.conf (the phone's, or public ones) */
    private val nameServers: () -> List<String> = { DEFAULT_DNS },
    /** more for the system's environment (the tests' proxy) */
    private val extraEnv: Map<String, String> = emptyMap(),
    /** whether a /proc file can be read here (Android hides some): ones that can't are given made-up ones */
    private val readable: (File) -> Boolean = { it.canRead() && runCatching { it.readBytes(); true }.getOrDefault(false) },
) {
    val id: String get() = dir.name
    val rootfs: File get() = File(dir, "rootfs")
    private val infoFile: File get() = File(dir, "system.json")

    /** what it is, as installed */
    data class Info(val name: String, val version: String, val source: String, val installed: Long)

    val info: Info?
        get() = runCatching {
            val o = JSONObject(infoFile.readText())
            Info(o.getString("name"), o.optString("version"), o.optString("source"), o.optLong("installed"))
        }.getOrNull() ?: legacyInfo()

    /** (the first release's Alpine: a marker file, no system.json) */
    private fun legacyInfo(): Info? {
        val m = File(dir, ".installed")
        if (!m.exists()) return null
        val v = runCatching { File(rootfs, "etc/alpine-release").readText().trim() }.getOrNull() ?: m.readText().trim()
        return Info("Alpine Linux $v", v, Distro.ALPINE_MIRROR, m.lastModified())
    }

    val installed: Boolean get() = info != null && File(rootfs, "etc").isDirectory

    val name: String get() = info?.name ?: id

    /**
     * Installed from [r]: downloaded, its checksum checked (when there is
     * one), unpacked and set up. [progress]: what it's doing, and how far
     * (0..1) when it can tell.
     */
    fun install(r: Rootfs, http: Http = Http(), progress: (String, Float?) -> Unit = { _, _ -> }) {
        dir.mkdirs()
        if (r.size > 0) {
            // (unpacked, a compressed system takes about four times its download)
            val need = r.size * 5
            if (dir.usableSpace in 1 until need) throw IOException("not enough space: ${need shr 20} MB needed, ${dir.usableSpace shr 20} MB free")
        }
        val archive = File(dir, "rootfs.download")
        try {
            progress("Downloading ${r.name}…", 0f)
            val sum = http.download(r.url, archive, r.size) { progress("Downloading ${r.name}…", it) }
            if (r.sha256 != null && !sum.equals(r.sha256, ignoreCase = true)) {
                throw IOException("the download's checksum doesn't match (${sum.take(12)}…, not ${r.sha256.take(12)}…)")
            }
            progress("Unpacking ${r.name}…", null)
            if (rootfs.exists()) Tar.removeTree(rootfs)
            openArchive(archive).use { Tar.extract(it, rootfs) }
            hoist()
            if (!File(rootfs, "etc").isDirectory || !(File(rootfs, "bin").exists() || File(rootfs, "usr/bin").isDirectory)) {
                throw IOException("that isn't a Linux root file system (no /etc and /bin in it)")
            }
            configure()
            infoFile.writeText(
                JSONObject().put("name", r.name).put("version", r.version).put("source", r.url)
                    .put("sha256", r.sha256 ?: "").put("installed", System.currentTimeMillis()).toString(2),
            )
            progress("Installed ${r.name}", 1f)
        } catch (e: Exception) {
            // (half a system is no use: nothing kept)
            runCatching { Tar.removeTree(dir) }
            throw e
        } finally {
            archive.delete()
        }
    }

    /** the archive, uncompressed as its first bytes say: gzip, xz, or a plain tar */
    private fun openArchive(f: File): InputStream {
        val i = BufferedInputStream(f.inputStream(), 1 shl 16)
        i.mark(8)
        val head = ByteArray(6)
        val n = i.read(head)
        i.reset()
        return when {
            n >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() -> GZIPInputStream(i, 1 shl 16)
            n >= 6 && head.contentEquals(byteArrayOf(0xfd.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0)) ->
                BufferedInputStream(XZInputStream(i), 1 shl 16)
            n >= 3 && head[0] == 'B'.code.toByte() && head[1] == 'Z'.code.toByte() && head[2] == 'h'.code.toByte() -> {
                i.close(); throw IOException("bzip2 archives aren't supported: use .tar.gz or .tar.xz")
            }
            n >= 4 && head[0] == 0x28.toByte() && head[1] == 0xb5.toByte() && head[2] == 0x2f.toByte() && head[3] == 0xfd.toByte() -> {
                i.close(); throw IOException("zstd archives aren't supported: use .tar.gz or .tar.xz")
            }
            else -> i
        }
    }

    /** a tarball with the system in one folder ("debian-rootfs/etc", …): its contents moved up */
    private fun hoist() {
        if (File(rootfs, "etc").isDirectory) return
        val only = rootfs.listFiles()?.singleOrNull() ?: return
        if (Files.isSymbolicLink(only.toPath()) || !File(only, "etc").isDirectory) return
        val tmp = File(dir, "rootfs.hoist")
        Files.move(only.toPath(), tmp.toPath())
        Files.delete(rootfs.toPath())
        Files.move(tmp.toPath(), rootfs.toPath())
    }

    /**
     * Its files set for this phone and for proot (again at each start):
     * name servers, hosts, the prompt, mount points; apt and pacman told
     * not to drop to users or sandboxes proot can't give them
     */
    fun configure() {
        val etc = File(rootfs, "etc")
        etc.mkdirs()
        // (a link to systemd's resolver, as many have it, replaced by a file)
        File(etc, "resolv.conf").toPath().let { Files.deleteIfExists(it) }
        File(etc, "resolv.conf").writeText(nameServers().joinToString("") { "nameserver $it\n" })
        File(etc, "hosts").apply {
            if (Files.isSymbolicLink(toPath()) || !exists() || !readText().contains("localhost")) {
                Files.deleteIfExists(toPath())
                writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
            }
        }
        val mtab = File(etc, "mtab").toPath()
        if (!Files.exists(mtab, LinkOption.NOFOLLOW_LINKS)) runCatching { Files.createSymbolicLink(mtab, Paths.get("/proc/self/mounts")) }
        File(etc, "profile.d").mkdirs()
        File(etc, "profile.d/dmodem.sh").writeText(profile(shortName()))
        // apt: no dropping to the _apt user (proot's root can't)
        if (File(etc, "apt").isDirectory) {
            File(etc, "apt/apt.conf.d").mkdirs()
            File(etc, "apt/apt.conf.d/99-dmodem-proot").writeText(
                "// D-Modem: under proot, apt can't switch to its own user\nAPT::Sandbox::User \"root\";\n",
            )
        }
        // pacman: no sandbox (Landlock) or disk space check (proot's mounts aren't the phone's), and its
        // keyring made on the first start (the images come without one: nothing could be installed)
        File(etc, "pacman.conf").takeIf { it.isFile }?.let { conf ->
            var t = conf.readText()
            t = t.replace(Regex("(?m)^CheckSpace\\s*$"), "#CheckSpace")
            if (!Regex("(?m)^DisableSandbox").containsMatchIn(t)) t = t.replaceFirst("[options]", "[options]\nDisableSandbox")
            conf.writeText(t)
            File(etc, "profile.d/00-dmodem-pacman-keyring.sh").writeText(PACMAN_KEYRING)
        }
        for (d in listOf("root", "tmp", "sdcard", "dev", "proc", "sys")) File(rootfs, d).mkdirs()
        File(rootfs, "tmp").setWritable(true, false)
    }

    /** the prompt's host: the distribution's own short name */
    private fun shortName(): String {
        val osRelease = listOf("etc/os-release", "usr/lib/os-release").map { File(rootfs, it) }.firstOrNull { it.isFile }
        val osId = osRelease?.readLines()?.firstOrNull { it.startsWith("ID=") }?.removePrefix("ID=")?.trim('"', ' ')
        return (osId ?: id).replace(Regex("[^A-Za-z0-9._-]"), "").ifEmpty { "linux" }
    }

    /** root's login shell, as the system's /etc/passwd has it (where it is there), else /bin/sh */
    fun loginShell(): String {
        val passwd = File(rootfs, "etc/passwd")
        val shell = runCatching { passwd.readLines().firstOrNull { it.startsWith("root:") }?.split(':')?.getOrNull(6) }.getOrNull()
        return shell?.takeIf { it.startsWith("/") && existsInside(it) } ?: "/bin/sh"
    }

    /** [path] is in the system: its links followed as the system sees them (an absolute one from its own root) */
    fun existsInside(path: String): Boolean {
        var todo = ArrayDeque(path.split('/').filter { it.isNotEmpty() })
        val at = ArrayList<String>()
        var hops = 0
        while (todo.isNotEmpty()) {
            val c = todo.removeFirst()
            if (c == ".") continue
            if (c == "..") { at.removeLastOrNull(); continue }
            val f = rootfs.toPath().resolve((at + c).joinToString("/"))
            if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) return false
            if (Files.isSymbolicLink(f)) {
                if (++hops > 40) return false
                val t = Files.readSymbolicLink(f).toString()
                if (t.startsWith("/")) at.clear()
                todo = ArrayDeque(t.split('/').filter { it.isNotEmpty() } + todo)
            } else {
                at.add(c)
            }
        }
        return true
    }

    /** the system removed, all of it (links removed, never followed: /sdcard's among them) */
    fun remove() = Tar.removeTree(dir)

    /** made-up /proc files for those Android hides from apps (programs like top read them) */
    private fun fakeProc(): List<Pair<File, String>> {
        val fake = File(dir, "proc")
        val out = ArrayList<Pair<File, String>>()
        for ((name, content) in FAKE_PROC) {
            if (readable(File("/proc/$name"))) continue
            val f = File(fake, name)
            f.parentFile?.mkdirs()
            f.writeText(content)
            out.add(f to "/proc/$name")
        }
        return out
    }

    /** proot's command: the system's login shell, as root, in /root */
    fun command(): List<String> {
        val shell = loginShell()
        val c = arrayListOf(
            File(programDir, "libproot.so").path,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs.path,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
        )
        for ((f, at) in fakeProc()) c.addAll(listOf("-b", "${f.path}:$at"))
        storage()?.let { c.addAll(listOf("-b", "${it.path}:/sdcard")) }
        c.addAll(listOf("-w", "/root", "/usr/bin/env", "-i"))
        val env = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to shell,
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR" to "/tmp",
        )
        env.putAll(extraEnv)
        for ((k, v) in env) c.add("$k=$v")
        c.addAll(listOf(shell, "-l"))
        return c
    }

    /** proot's own environment: where its loader is, its temporary files */
    fun environment(): Map<String, String> {
        tmpDir.mkdirs()
        return linkedMapOf(
            "PROOT_LOADER" to File(programDir, "libproot-loader.so").path,
            "PROOT_TMP_DIR" to tmpDir.path,
            "PATH" to "/system/bin:/system/xbin:/usr/bin:/bin",
            "HOME" to dir.path,
        )
    }

    /** a link to a new shell in the system (configured again first: the name servers may have changed) */
    fun link(cols: Int = 80, rows: Int = 24): LocalLink {
        if (!installed) throw IOException("$name isn't installed")
        configure()
        return LocalLink(command(), environment(), dir.path, cols, rows)
    }

    companion object {
        val DEFAULT_DNS = listOf("1.1.1.1", "8.8.8.8")

        /** a coloured prompt (bash's and busybox's sh's escapes), a few aliases */
        fun profile(host: String) = """
            |# D-Modem: rewritten at each start (put your own in /etc/profile.d/local.sh, or ~/.profile)
            |PS1='\[\e[1;31m\]\u@$host\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\${'$'} '
            |alias ls='ls --color=auto'
            |alias ll='ls -l'
            |alias la='ls -la'
            |""".trimMargin()

        /** pacman's keyring made once, on the first login */
        const val PACMAN_KEYRING = """# D-Modem: pacman's keyring, made once (the image has none)
if [ ! -e /etc/pacman.d/gnupg/pubring.gpg ] && [ ! -e /etc/pacman.d/gnupg/pubring.kbx ] && command -v pacman-key >/dev/null 2>&1; then
    echo "Setting up pacman's keyring (once)..."
    pacman-key --init >/dev/null 2>&1 && pacman-key --populate >/dev/null 2>&1
fi
"""

        /** /proc files Android keeps from apps, as proot-distro makes them up */
        val FAKE_PROC = linkedMapOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "stat" to "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\n" +
                "intr 63361 0 0 0 0 0 0 0 0 0 0 0 0\nctxt 38014093\nbtime 1694292441\nprocesses 26442\n" +
                "procs_running 1\nprocs_blocked 0\nsoftirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n",
            "uptime" to "124.08 932.80\n",
            "version" to "Linux version 6.1.0 (proot@dmodem) #1 SMP PREEMPT\n",
            "vmstat" to "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\n",
            "sys/kernel/cap_last_cap" to "40\n",
            "sys/fs/inotify/max_user_watches" to "4096\n",
        )
    }
}
