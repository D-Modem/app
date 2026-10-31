// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.transfer

import org.dmodem.app.io.Sink
import org.dmodem.app.io.asSink
import org.dmodem.app.io.asSource
import java.io.File

/** a file to send, from a JVM file */
fun Outgoing.Companion.of(f: File) = Outgoing(f.name, f.length(), f.lastModified() / 1000) { f.inputStream().asSource() }

/** received files into a directory */
class FolderIncoming(private val dir: File) : Incoming {
    val received = ArrayList<File>()

    /** a new file in [dir] for [name], made safe and unique: its stream and the name it got */
    override fun create(name: String, size: Long): Pair<Sink, String> {
        dir.mkdirs()
        val f = unique(dir, safeName(name))
        return f.outputStream().asSink() to f.name
    }

    /** the file finished: its time set (if known) and it added to [received] */
    override fun done(name: String, mtime: Long) {
        val f = File(dir, name)
        if (mtime > 0) f.setLastModified(mtime * 1000)
        received.add(f)
    }

    /** the unfinished file deleted */
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

