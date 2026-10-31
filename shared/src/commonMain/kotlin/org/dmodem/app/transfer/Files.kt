// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.transfer

import org.dmodem.app.io.*


/** A file to send: its name, size (-1 unknown), time (seconds, 0 unknown), contents. */
class Outgoing(val name: String, val size: Long, val mtime: Long, val open: () -> Source) {
    /** (the platforms' own: Outgoing.of(a file)) */
    companion object
}

/** Where received files go. */
interface Incoming {
    /** a new file named [name] (made safe, and unique there) to write: its stream and the name it got */
    fun create(name: String, size: Long): Pair<Sink, String>

    /** a file finished: [mtime] its time, if known (seconds) */
    fun done(name: String, mtime: Long) {}

    /** a file not finished (an error, a cancel): what was written, removed */
    fun abandon(name: String) {}
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
