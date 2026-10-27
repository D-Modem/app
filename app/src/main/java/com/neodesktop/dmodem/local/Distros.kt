// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.local

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A root file system to download: where, its checksum (if known), its size (if known), what it is. */
data class Rootfs(
    val url: String,
    val sha256: String?,
    val size: Long,
    /** "Debian 13 (trixie)" */
    val name: String,
    /** the release or build ("20261016_05:24", "3.22.6") */
    val version: String,
)

/** The phone's processor, as each source names it. */
data class Machine(val abi: String, val alpine: String, val lxc: String, val ubuntu: String?) {
    companion object {
        /** the processor of Android ABI [abi] as the sources name it, or null if none has it */
        fun forAbi(abi: String): Machine? = when (abi) {
            "arm64-v8a" -> Machine(abi, "aarch64", "arm64", "arm64")
            "armeabi-v7a" -> Machine(abi, "armv7", "armhf", "armhf")
            "x86_64" -> Machine(abi, "x86_64", "amd64", "amd64")
            "x86" -> Machine(abi, "x86", "i386", null)
            else -> null
        }
    }
}

/**
 * The distributions offered: Alpine from Alpine's own release list,
 * Ubuntu from its base images, the rest from the Linux Containers
 * project's images (images.linuxcontainers.org: built daily, with their
 * SHA-256 sums). A custom one is any tarball's URL.
 */
sealed class Distro(
    /** its directory's name, and its prompt's host */
    val id: String,
    val name: String,
    /** a line for the list: its package manager, how big */
    val about: String,
) {
    /** the root file system to download for [m]: its URL, checksum and size, looked up with [http] */
    abstract fun resolve(m: Machine, http: Http): Rootfs

    class Alpine(private val mirror: String = ALPINE_MIRROR, private val branch: String = ALPINE_BRANCH) :
        Distro("alpine", "Alpine Linux", "apk · about 3 MB · musl, small and quick") {
        /** the minirootfs in Alpine's latest-releases.yaml for [m]'s processor */
        override fun resolve(m: Machine, http: Http): Rootfs {
            val base = "$mirror/$branch/releases/${m.alpine}"
            val yaml = http.text("$base/latest-releases.yaml")
            // (a list of releases, one per flavour: the minirootfs one's fields)
            val b = yaml.split(Regex("\n-\\s*\n|\n- ")).firstOrNull { it.contains("flavor: alpine-minirootfs") }
                ?: throw IOException("no Alpine for ${m.alpine}")
            /** field [k]'s value in the release's entry, or null */
            fun field(k: String) = Regex("(?m)^\\s*$k:\\s*(\\S+)").find(b)?.groupValues?.get(1)
            val version = field("version") ?: "?"
            return Rootfs(
                "$base/${field("file") ?: throw IOException("no file in Alpine's list")}",
                field("sha256") ?: throw IOException("no checksum in Alpine's list"),
                field("size")?.toLongOrNull() ?: -1,
                "Alpine Linux $version", version,
            )
        }
    }

    /** Ubuntu's base image (its own, ~30 MB): the newest point release of [release] */
    class Ubuntu(private val release: String = "24.04", private val mirror: String = UBUNTU_MIRROR) :
        Distro("ubuntu", "Ubuntu $release LTS", "apt · about 30 MB") {
        /** the newest point release's base tarball for [m] in the release's SHA256SUMS */
        override fun resolve(m: Machine, http: Http): Rootfs {
            val arch = m.ubuntu ?: throw IOException("no Ubuntu for ${m.abi}")
            val base = "$mirror/$release/release"
            val sums = http.text("$base/SHA256SUMS")
            val re = Regex("([0-9a-f]{64}) \\*?(ubuntu-base-([0-9.]+)-base-$arch\\.tar\\.gz)")
            val newest = re.findAll(sums).maxByOrNull { versionKey(it.groupValues[3]) }
                ?: throw IOException("no Ubuntu $release for $arch")
            val (sha, file, version) = newest.destructured
            return Rootfs("$base/$file", sha, http.size("$base/$file"), "Ubuntu $version LTS", version)
        }
    }

    /**
     * A Linux Containers image: [release] of [distro], or (null) its
     * newest: "current", "tumbleweed", or the highest number
     */
    class Lxc(id: String, name: String, about: String, private val distro: String, private val release: String? = null) :
        Distro(id, name, about) {
        /** the image's rootfs.tar.xz from the Linux Containers index for [m], its checksum from its SHA256SUMS */
        override fun resolve(m: Machine, http: Http): Rootfs {
            val rows = lxcIndex(http).filter { it.distro == distro && it.arch == m.lxc && it.variant == "default" }
            val row = (if (release != null) rows.firstOrNull { it.release == release } else newest(rows))
                ?: throw IOException("$name isn't built for this phone's processor (${m.lxc})")
            val dir = LXC_MIRROR + row.path.trimEnd('/')
            val sums = http.text("$dir/SHA256SUMS")
            val sha = Regex("([0-9a-f]{64})\\s+\\*?rootfs\\.tar\\.xz").find(sums)?.groupValues?.get(1)
                ?: throw IOException("no checksum for $name's image")
            val shown = if (release == null && row.release.firstOrNull()?.isDigit() == true) "$name ${row.release}" else name
            return Rootfs("$dir/rootfs.tar.xz", sha, http.size("$dir/rootfs.tar.xz"), shown, "${row.release} ${row.build}")
        }

        /** whether there is one for [m] (from the index) */
        fun availableFor(m: Machine, index: List<LxcRow>): Boolean =
            index.any { it.distro == distro && it.arch == m.lxc && it.variant == "default" && (release == null || it.release == release) }

        /** the newest of [rows]: "current", "tumbleweed", else the highest numbered release, else the first */
        private fun newest(rows: List<LxcRow>): LxcRow? =
            rows.firstOrNull { it.release == "current" } ?: rows.firstOrNull { it.release == "tumbleweed" }
                ?: rows.filter { it.release.firstOrNull()?.isDigit() == true }.maxByOrNull { versionKey(it.release) }
                ?: rows.firstOrNull()
    }

    /** any root file system tarball (.tar, .tar.gz or .tar.xz) by its URL */
    class Custom(id: String, name: String, private val url: String, private val sha256: String?) :
        Distro(id, name, url) {
        /** the tarball at its URL, its checksum as given (if any) */
        override fun resolve(m: Machine, http: Http): Rootfs =
            Rootfs(url, sha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }, http.size(url), name, "custom")
    }

    data class LxcRow(val distro: String, val release: String, val arch: String, val variant: String, val build: String, val path: String)

    companion object {
        const val ALPINE_MIRROR = "https://dl-cdn.alpinelinux.org/alpine"
        const val ALPINE_BRANCH = "v3.22"
        const val UBUNTU_MIRROR = "https://cdimage.ubuntu.com/ubuntu-base/releases"
        const val LXC_MIRROR = "https://images.linuxcontainers.org"

        val CATALOG: List<Distro> = listOf(
            Alpine(),
            Lxc("debian", "Debian 13 (trixie)", "apt · about 85 MB", "debian", "trixie"),
            Ubuntu(),
            Lxc("fedora", "Fedora", "dnf · about 90 MB", "fedora"),
            Lxc("archlinux", "Arch Linux", "pacman · about 170 MB", "archlinux"),
            Lxc("opensuse", "openSUSE Tumbleweed", "zypper · about 40 MB", "opensuse", "tumbleweed"),
            Lxc("kali", "Kali Linux", "apt · about 75 MB · security tools", "kali"),
            Lxc("void", "Void Linux", "xbps · about 120 MB", "voidlinux"),
            Lxc("rocky", "Rocky Linux", "dnf · about 100 MB · RHEL-compatible", "rockylinux"),
            Lxc("alma", "AlmaLinux", "dnf · about 80 MB · RHEL-compatible", "almalinux"),
            Lxc("devuan", "Devuan", "apt · about 80 MB · Debian without systemd", "devuan"),
        )

        /** the catalog's distribution with [id], or null */
        fun byId(id: String): Distro? = CATALOG.firstOrNull { it.id == id }

        /** the Linux Containers images' index: distro;release;arch;variant;build;path */
        fun lxcIndex(http: Http): List<LxcRow> = parseLxcIndex(http.text("$LXC_MIRROR/meta/1.0/index-system"))

        /** the index's rows: distro;release;arch;variant;build;path (other lines skipped) */
        fun parseLxcIndex(text: String): List<LxcRow> = text.lineSequence().mapNotNull { l ->
            val f = l.trim().split(';')
            if (f.size >= 6) LxcRow(f[0], f[1], f[2], f[3], f[4], f[5]) else null
        }.toList()

        /** "24.04.5" sortable: each number in turn */
        fun versionKey(v: String): String = v.split('.', '-').joinToString(".") { it.padStart(8, '0') }

        /** a custom system's id from its name */
        fun customId(name: String): String =
            "custom-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "linux" }.take(40)
    }
}

/** What the systems are fetched with. */
open class Http {
    /** a GET of [url], redirects followed; an IOException unless it's 200 */
    open fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "D-Modem for Android")
        if (c.responseCode != 200) throw IOException("$url: HTTP ${c.responseCode}")
        return c
    }

    /** [url]'s body as text */
    open fun text(url: String): String = open(url).let { c -> c.inputStream.use { it.readBytes().decodeToString() } }

    /** its size from a HEAD request, or -1 */
    open fun size(url: String): Long = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.connectTimeout = 20_000
        c.setRequestProperty("User-Agent", "D-Modem for Android")
        if (c.responseCode == 200) c.contentLengthLong else -1
    } catch (_: IOException) {
        -1
    }

    /** [url] to [to] (through a .part file), its SHA-256 back; [progress] 0..1 when the size is known */
    open fun download(url: String, to: File, size: Long, progress: (Float) -> Unit): String {
        val c = open(url)
        val total = if (size > 0) size else c.contentLengthLong
        val part = File(to.path + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        c.inputStream.use { i ->
            part.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var shown = -1
                while (true) {
                    if (Thread.interrupted()) throw IOException("cancelled")
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    md.update(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != shown) { shown = pct; progress(done.toFloat() / total) }
                    }
                }
            }
        }
        if (!part.renameTo(to)) throw IOException("can't keep the download")
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
