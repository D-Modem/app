// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.local

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * A Linux system to run in the local terminal: Alpine's minimal root file
 * system (its package manager, apk, to add the rest), run with proot. The
 * app can't run programs it has written itself (Android 10 on); proot is
 * the app's own (libproot.so, in its native library directory), and runs
 * the system's programs through its loader (libproot-loader.so), which
 * maps them rather than executing them.
 *
 * Inside: root (proot's fake one), the device's /dev, /proc and /sys, and
 * the phone's shared storage at /sdcard when the app may read it.
 */
class LinuxSystem(
    /** where it lives: rootfs/ in it */
    val dir: File,
    /** the app's native library directory: libproot.so and libproot-loader.so (a host build's, in the tests) */
    private val programDir: File,
    /** proot's own temporary files */
    private val tmpDir: File,
    /** Alpine's name for the machine: aarch64, armv7, x86_64, x86 */
    val arch: String,
    /** the phone's shared storage, when the app may use it (bound at /sdcard) */
    private val storage: () -> File? = { null },
    /** the name servers for /etc/resolv.conf (the phone's, or public ones) */
    private val nameServers: () -> List<String> = { DEFAULT_DNS },
    /** more for the system's environment (the tests' proxy) */
    private val extraEnv: Map<String, String> = emptyMap(),
    private val mirror: String = MIRROR,
    private val branch: String = BRANCH,
    /** whether a /proc file can be read here (Android hides some): ones that can't are given made-up ones */
    private val readable: (File) -> Boolean = { it.canRead() && runCatching { it.readBytes(); true }.getOrDefault(false) },
) {
    val rootfs: File get() = File(dir, "rootfs")
    private val marker: File get() = File(dir, ".installed")

    val installed: Boolean get() = marker.exists() && File(rootfs, "bin").exists()

    /** the version installed ("3.22.6"), or null */
    val version: String? get() = runCatching { File(rootfs, "etc/alpine-release").readText().trim() }.getOrNull()

    data class Release(val file: String, val version: String, val sha256: String, val size: Long) {
        val name get() = "Alpine Linux $version"
    }

    /** the latest minimal root file system for [arch], from Alpine's own list */
    fun latestRelease(): Release {
        val yaml = fetchText("$mirror/$branch/releases/$arch/latest-releases.yaml")
        // (a list of releases, one per flavour: the minirootfs one's fields)
        val blocks = yaml.split(Regex("\n-\\s*\n|\n- ")).filter { it.contains("flavor: alpine-minirootfs") }
        val b = blocks.firstOrNull() ?: throw IOException("no minimal root file system for $arch")
        fun field(k: String) = Regex("(?m)^\\s*$k:\\s*(\\S+)").find(b)?.groupValues?.get(1)
        return Release(
            field("file") ?: throw IOException("no file in Alpine's list"),
            field("version") ?: "?",
            field("sha256") ?: throw IOException("no checksum in Alpine's list"),
            field("size")?.toLongOrNull() ?: -1,
        )
    }

    /**
     * Installed: downloaded, its checksum checked, unpacked and set up.
     * [progress]: what it's doing, and how far (0..1) when it can tell.
     */
    fun install(progress: (String, Float?) -> Unit = { _, _ -> }) {
        progress("Finding the latest Alpine Linux…", null)
        val r = latestRelease()
        dir.mkdirs()
        val tgz = File(dir, r.file)
        progress("Downloading ${r.name}…", 0f)
        download("$mirror/$branch/releases/$arch/${r.file}", tgz, r.size) { progress("Downloading ${r.name}…", it) }
        val sum = sha256(tgz)
        if (!sum.equals(r.sha256, ignoreCase = true)) {
            tgz.delete()
            throw IOException("the download's checksum doesn't match Alpine's")
        }
        progress("Unpacking…", null)
        if (rootfs.exists()) Tar.removeTree(rootfs)
        GZIPInputStream(tgz.inputStream().buffered()).use { Tar.extract(it, rootfs) }
        tgz.delete()
        configure()
        marker.writeText(r.version + "\n")
        progress("Installed ${r.name}", 1f)
    }

    /** its files set for this phone: name servers, host name, the prompt, mount points (again at each start) */
    fun configure() {
        val etc = File(rootfs, "etc")
        etc.mkdirs()
        File(etc, "resolv.conf").apply { delete() }.writeText(nameServers().joinToString("") { "nameserver $it\n" })
        File(etc, "hosts").apply { if (!exists() || !readText().contains("localhost")) writeText("127.0.0.1 localhost\n::1 localhost\n") }
        File(etc, "hostname").takeIf { !it.exists() }?.writeText("dmodem\n")
        File(etc, "profile.d").mkdirs()
        File(etc, "profile.d/dmodem.sh").writeText(PROFILE)
        for (d in listOf("root", "tmp", "sdcard", "dev", "proc", "sys")) File(rootfs, d).mkdirs()
        File(rootfs, "tmp").setWritable(true, false)
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
            "SHELL" to "/bin/sh",
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR" to "/tmp",
        )
        env.putAll(extraEnv)
        for ((k, v) in env) c.add("$k=$v")
        c.addAll(listOf("/bin/sh", "-l"))
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
        if (!installed) throw IOException("Alpine Linux isn't installed")
        configure()
        return LocalLink(command(), environment(), dir.path, cols, rows)
    }

    // ------------------------------------------------------------------

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "D-Modem for Android")
        if (c.responseCode != 200) throw IOException("$url: HTTP ${c.responseCode}")
        return c
    }

    private fun fetchText(url: String): String = open(url).let { c -> c.inputStream.use { it.readBytes().decodeToString() } }

    private fun download(url: String, to: File, size: Long, progress: (Float) -> Unit) {
        val c = open(url)
        val total = if (size > 0) size else c.contentLengthLong
        val part = File(to.path + ".part")
        c.inputStream.use { i ->
            part.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var shown = -1
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != shown) { shown = pct; progress(done.toFloat() / total) }
                    }
                }
            }
        }
        if (!part.renameTo(to)) throw IOException("can't keep the download")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MIRROR = "https://dl-cdn.alpinelinux.org/alpine"
        const val BRANCH = "v3.22"
        val DEFAULT_DNS = listOf("1.1.1.1", "8.8.8.8")

        /** Alpine's machine for an Android ABI */
        fun archFor(abi: String): String? = when (abi) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "armv7"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> null
        }

        /** a coloured prompt, a few aliases; /sdcard named if it's there */
        val PROFILE = """
            |# D-Modem: this phone's Alpine Linux (rewritten at each start: put your own in /etc/profile.d/local.sh)
            |PS1='\[\e[1;31m\]\u@alpine\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\${'$'} '
            |alias ls='ls --color=auto'
            |alias ll='ls -l'
            |alias la='ls -la'
            |""".trimMargin()

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
