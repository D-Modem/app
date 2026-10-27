// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.local

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * A Linux system's tar unpacked as it is: directories, files with their
 * modes, symbolic links as they are (their targets the system's own,
 * absolute ones too), hard links (as links where the file system allows,
 * else copies); ustar, pax and GNU long names. Nothing written outside the
 * destination: no "..", and no path through a link already unpacked.
 * Devices and fifos are left out (proot gives the system the device's
 * own /dev).
 */
object Tar {

    class Stats {
        var files = 0
        var dirs = 0
        var links = 0
        var bytes = 0L
    }

    /** [input]'s tar unpacked into [dest], [onEntry] told each entry's name: how much of each kind */
    fun extract(input: InputStream, dest: File, onEntry: (String) -> Unit = {}): Stats {
        val root = dest.toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)
        val stats = Stats()
        val dirModes = ArrayList<Pair<Path, Int>>()
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String> = emptyMap()
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break
            if (!checksumOk(header)) throw IOException("not a tar (bad header checksum)")
            val type = header[156].toInt().toChar()
            val size = number(header, 124, 12)
            when (type) {
                'L' -> { longName = cString(readData(input, size)); continue }
                'K' -> { longLink = cString(readData(input, size)); continue }
                'x' -> { pax = paxRecords(readData(input, size)); continue }
                'g' -> { readData(input, size); continue }
            }
            val ustarName = run {
                val name = cString(header, 0, 100)
                val prefix = if (String(header, 257, 5, Charsets.US_ASCII) == "ustar") cString(header, 345, 155) else ""
                if (prefix.isNotEmpty()) "$prefix/$name" else name
            }
            val name = pax["path"] ?: longName ?: ustarName
            val link = pax["linkpath"] ?: longLink ?: cString(header, 157, 100)
            val entrySize = pax["size"]?.toLongOrNull() ?: size
            val mode = number(header, 100, 8).toInt() and 0xfff
            longName = null; longLink = null; pax = emptyMap()

            val target = resolve(root, name)
            if (target == null) {
                skip(input, entrySize)
                continue
            }
            onEntry(name)
            when (type) {
                '5' -> {
                    Files.createDirectories(target)
                    dirModes.add(target to mode)
                    stats.dirs++
                }
                '2' -> {
                    Files.createDirectories(target.parent)
                    Files.deleteIfExists(target)
                    Files.createSymbolicLink(target, Paths.get(link))
                    stats.links++
                }
                '1' -> {
                    val from = resolve(root, link) ?: throw IOException("hard link out of the tree: $name -> $link")
                    Files.createDirectories(target.parent)
                    Files.deleteIfExists(target)
                    try {
                        Files.createLink(target, from)
                    } catch (_: Exception) {
                        Files.copy(from, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                    }
                    stats.links++
                }
                '0', '\u0000', '7' -> {
                    Files.createDirectories(target.parent)
                    Files.deleteIfExists(target)
                    Files.newOutputStream(target).use { out ->
                        copy(input, out, entrySize)
                    }
                    skipPadding(input, entrySize)
                    setMode(target, mode)
                    stats.files++
                    stats.bytes += entrySize
                    continue
                }
                else -> {} // devices, fifos: not here
            }
            skip(input, entrySize)
        }
        // the directories' own modes last: a read-only one would have stopped what went into it
        for ((d, m) in dirModes.asReversed()) setMode(d, m or 0b111_000_000)
        return stats
    }

    /** where [name] goes under [root]; null for one that would leave it (or go through a link) */
    private fun resolve(root: Path, name: String): Path? {
        val clean = name.trimStart('/').removePrefix("./").trimEnd('/')
        if (clean.isEmpty() || clean == ".") return null
        val p = root.resolve(clean).normalize()
        if (!p.startsWith(root) || p == root) return null
        // no component on the way (the entry's parents) a link: writing through it could leave the tree
        var cur = root
        for (part in root.relativize(p.parent ?: root)) {
            cur = cur.resolve(part)
            if (Files.isSymbolicLink(cur)) return null
        }
        return p
    }

    /** [mode]'s permission bits set on [p] (the owner's read and write always kept), if the file system allows */
    private fun setMode(p: Path, mode: Int) {
        val perms = HashSet<PosixFilePermission>()
        val all = PosixFilePermission.values() // OWNER_READ .. OTHERS_EXECUTE: bits 8..0
        for (i in 0 until 9) if (mode and (1 shl (8 - i)) != 0) perms.add(all[i])
        // (the app owns it all: owner's read and write kept, or it couldn't be updated or removed)
        perms.add(PosixFilePermission.OWNER_READ)
        perms.add(PosixFilePermission.OWNER_WRITE)
        try {
            Files.setPosixFilePermissions(p, perms)
        } catch (_: Exception) {
        }
    }

    /**
     * [dir] removed, every file in it; links removed as links, never
     * followed (one may lead to the phone's shared storage)
     */
    fun removeTree(dir: File) {
        val root = dir.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(root)) {
            Files.delete(root)
            return
        }
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                /** the directory made writable first, so its entries can be removed */
                override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // (a read-only directory's entries can't be removed until it's writable)
                    runCatching { d.toFile().setWritable(true, true); d.toFile().setExecutable(true, true); d.toFile().setReadable(true, true) }
                    return FileVisitResult.CONTINUE
                }

                /** a file or link removed */
                override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(f)
                    return FileVisitResult.CONTINUE
                }

                /** one that can't be read removed anyway */
                override fun visitFileFailed(f: Path, exc: IOException): FileVisitResult {
                    Files.deleteIfExists(f)
                    return FileVisitResult.CONTINUE
                }

                /** the directory removed, now empty */
                override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                    Files.delete(d)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    // ------------------------------------------------------------------

    /** [b] filled from [i]: false at a clean end before any of it, EOFException if cut short */
    private fun readFully(i: InputStream, b: ByteArray): Boolean {
        var n = 0
        while (n < b.size) {
            val r = i.read(b, n, b.size - n)
            if (r < 0) {
                if (n == 0) return false
                throw EOFException("tar cut short")
            }
            n += r
        }
        return true
    }

    /** an extended header's data of [size] read, with its padding */
    private fun readData(i: InputStream, size: Long): ByteArray {
        if (size > 1 shl 20) throw IOException("tar extended header too big")
        val b = ByteArray(size.toInt())
        if (size > 0 && !readFully(i, b)) throw EOFException("tar cut short")
        skipPadding(i, size)
        return b
    }

    /** [size] bytes of [i] copied to [o] */
    private fun copy(i: InputStream, o: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("tar cut short")
            o.write(buf, 0, n)
            left -= n
        }
    }

    /** [size] bytes and their padding passed over */
    private fun skip(i: InputStream, size: Long) {
        // (OutputStream.nullOutputStream is Android 13's)
        copy(i, object : java.io.OutputStream() { override fun write(b: Int) {}; override fun write(b: ByteArray, off: Int, len: Int) {} }, size)
        skipPadding(i, size)
    }

    /** the zeros after [size] bytes of data, to the next 512-byte block, passed over */
    private fun skipPadding(i: InputStream, size: Long) {
        val pad = ((512 - size % 512) % 512).toInt()
        if (pad > 0) readFully(i, ByteArray(pad))
    }

    /** the NUL-terminated UTF-8 string in [len] bytes of [b] from [off] */
    private fun cString(b: ByteArray, off: Int = 0, len: Int = b.size): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    /** an octal field, or GNU's base-256 for big ones */
    private fun number(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in 1 until len) v = (v shl 8) or (b[off + i].toLong() and 0xff)
            return v
        }
        val s = cString(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    /** whether header [h]'s checksum (its bytes summed, the checksum field as spaces) is right */
    private fun checksumOk(h: ByteArray): Boolean {
        val want = number(h, 148, 8)
        var sum = 0L
        for (i in h.indices) sum += if (i in 148 until 156) 32 else (h[i].toInt() and 0xff)
        return sum == want
    }

    /** pax's "len key=value\n" records */
    private fun paxRecords(b: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < b.size) {
            val sp = (i until b.size).firstOrNull { b[it] == ' '.code.toByte() } ?: break
            val len = String(b, i, sp - i, Charsets.US_ASCII).toIntOrNull() ?: break
            if (len <= 0 || i + len > b.size) break
            val rec = String(b, sp + 1, i + len - sp - 2, Charsets.UTF_8)
            val eq = rec.indexOf('=')
            if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
            i += len
        }
        return out
    }
}
