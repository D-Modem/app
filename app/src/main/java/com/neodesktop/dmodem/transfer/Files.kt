// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.transfer

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** A file to send: its name, size (-1 unknown), time (seconds, 0 unknown), contents. */
class Outgoing(val name: String, val size: Long, val mtime: Long, val open: () -> InputStream) {
    companion object {
        fun of(f: File) = Outgoing(f.name, f.length(), f.lastModified() / 1000, f::inputStream)
    }
}

/** Where received files go. */
interface Incoming {
    /** a new file named [name] (made safe, and unique there) to write: its stream and the name it got */
    fun create(name: String, size: Long): Pair<OutputStream, String>

    /** a file finished: [mtime] its time, if known (seconds) */
    fun done(name: String, mtime: Long) {}

    /** a file not finished (an error, a cancel): what was written, removed */
    fun abandon(name: String) {}
}

/** received files into a directory */
class FolderIncoming(private val dir: File) : Incoming {
    val received = ArrayList<File>()

    override fun create(name: String, size: Long): Pair<OutputStream, String> {
        dir.mkdirs()
        val f = unique(dir, safeName(name))
        return f.outputStream() to f.name
    }

    override fun done(name: String, mtime: Long) {
        val f = File(dir, name)
        if (mtime > 0) f.setLastModified(mtime * 1000)
        received.add(f)
    }

    override fun abandon(name: String) {
        File(dir, name).delete()
    }

    companion object {
        /** a name with no path in it, nothing a file system minds */
        fun safeName(name: String): String {
            val base = name.replace('\\', '/').substringAfterLast('/')
                .replace(Regex("[\\x00-\\x1f:*?\"<>|]"), "_").trim().trimStart('.')
            return base.ifEmpty { "received" }.take(200)
        }

        /** [name] in [dir], or "name (2).ext" when it's taken */
        fun unique(dir: File, name: String): File {
            var f = File(dir, name)
            if (!f.exists()) return f
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            var n = 2
            while (f.exists()) {
                f = File(dir, name.substring(0, dot) + " ($n)" + name.substring(dot))
                n++
            }
            return f
        }
    }
}

/** How far a transfer has got. */
data class Progress(
    val file: String = "",
    val bytes: Long = 0,
    /** the file's size, -1 unknown */
    val size: Long = -1,
    /** files finished so far */
    val files: Int = 0,
    /** errors corrected on the line (blocks repeated) */
    val errors: Int = 0,
)
