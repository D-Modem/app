// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.transfer

import com.neodesktop.dmodem.io.IOException
import com.neodesktop.dmodem.io.Sink
import com.neodesktop.dmodem.io.Source
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.F_OK
import platform.posix.FILE
import platform.posix.SEEK_END
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.mkdir
import platform.posix.unlink
import platform.posix.utimbuf
import platform.posix.utime

/**
 * Files for transfers and faxes on iOS (and Linux, the tests), by path:
 * a file to send, a folder received files go into.
 */
@OptIn(ExperimentalForeignApi::class)
object PosixFiles {
    /** the file at [path] to send, named as its last part; null when it can't be read (Swift: no exceptions) */
    fun outgoing(path: String): Outgoing? {
        val size = size(path)
        if (size < 0) return null
        return Outgoing(path.substringAfterLast('/'), size, 0) { source(path) }
    }

    /** whether there's a file (or folder) at [path] */
    fun exists(path: String): Boolean = access(path, F_OK) == 0

    /** [path]'s size, -1 when it can't be read */
    fun size(path: String): Long {
        val f = fopen(path, "rb") ?: return -1
        try {
            if (fseek(f, 0, SEEK_END) != 0) return -1
            return ftell(f).toLong()
        } finally {
            fclose(f)
        }
    }

    fun source(path: String): Source {
        val f = fopen(path, "rb") ?: throw IOException("can't open $path")
        return FileSource(f)
    }

    fun sink(path: String): Sink {
        val f = fopen(path, "wb") ?: throw IOException("can't write $path")
        return FileSink(f)
    }

    /** a name with no path in it, nothing a file system minds */
    fun safeName(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/')
            .replace(Regex("[\\x00-\\x1f:*?\"<>|]"), "_").trim().trimStart('.')
        return base.ifEmpty { "received" }.take(200)
    }

    /** [name] in [dir], or "name (2).ext" when it's taken: the name */
    fun unique(dir: String, name: String): String {
        if (!exists("$dir/$name")) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var n = 2
        while (true) {
            val c = name.substring(0, dot) + " ($n)" + name.substring(dot)
            if (!exists("$dir/$c")) return c
            n++
        }
    }

    private class FileSource(private var f: CPointer<FILE>?) : Source {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val file = f ?: return -1
            if (len <= 0) return 0
            val n = b.usePinned { fread(it.addressOf(off), 1u, len.convert(), file) }.toInt()
            return if (n == 0) -1 else n
        }

        override fun close() {
            f?.let { fclose(it) }
            f = null
        }
    }

    private class FileSink(private var f: CPointer<FILE>?) : Sink {
        override fun write(b: ByteArray, off: Int, len: Int) {
            val file = f ?: throw IOException("closed")
            if (len <= 0) return
            val n = b.usePinned { fwrite(it.addressOf(off), 1u, len.convert(), file) }.toInt()
            if (n != len) throw IOException("the disk is full")
        }

        override fun close() {
            val file = f ?: return
            f = null
            if (fclose(file) != 0) throw IOException("the disk is full")
        }
    }
}

/** received files into the folder at [dir] (made if it isn't there) */
@OptIn(ExperimentalForeignApi::class)
class PosixFolderIncoming(private val dir: String) : Incoming {
    /** the names the files got, those finished */
    val received = ArrayList<String>()

    override fun create(name: String, size: Long): Pair<Sink, String> {
        mkdir(dir, 0x1edu.convert())
        val actual = PosixFiles.unique(dir, PosixFiles.safeName(name))
        return PosixFiles.sink("$dir/$actual") to actual
    }

    override fun done(name: String, mtime: Long) {
        if (mtime > 0) memScoped {
            val t = alloc<utimbuf>()
            t.actime = mtime.convert()
            t.modtime = mtime.convert()
            utime("$dir/$name", t.ptr)
        }
        received.add(name)
    }

    override fun abandon(name: String) {
        unlink("$dir/$name")
    }
}
