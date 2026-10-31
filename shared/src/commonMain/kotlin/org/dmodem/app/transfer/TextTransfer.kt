// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.transfer

import org.dmodem.app.io.IOException

/**
 * A file as plain text: no protocol at all. Sending: its bytes as they
 * are, nothing changed (its line endings its own), as if typed: to a
 * remote's editor or `cat > file`, a BBS's message editor, a monitor that
 * takes text. Receiving: what comes, as it comes, until the line goes
 * quiet ([QUIET_MS] once something has come), saved as it is.
 */
class TextTransfer(
    private val io: TransferIo,
    private val progress: (Progress) -> Unit,
    /** the quiet that ends a receive (the tests' shorter) */
    private val quietMs: Long = QUIET_MS,
) {
    /** [file] sent verbatim */
    fun send(file: Outgoing) {
        val buf = ByteArray(CHUNK)
        var sent = 0L
        file.open().use { src ->
            while (true) {
                if (io.isCancelled) throw TransferCancelled("cancelled")
                val n = src.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                io.write(buf.copyOf(n))
                sent += n
                progress(Progress(file.name, sent, file.size))
            }
        }
        progress(Progress(file.name, sent, file.size, files = 1))
    }

    /** what comes until it stops, into [into] as [name]: its name there */
    fun receive(into: Incoming, name: String): List<String> {
        val first = io.read(FIRST_MS)
        if (first < 0) throw IOException("nothing came")
        val (sink, got) = into.create(name, -1)
        var bytes = 0L
        try {
            val buf = ByteArray(CHUNK)
            var c = first
            while (c >= 0) {
                // (what's waiting taken at once, a chunk at a time)
                var n = 0
                buf[n++] = c.toByte()
                while (n < buf.size && io.available()) {
                    val b = io.read(0)
                    if (b < 0) break
                    buf[n++] = b.toByte()
                }
                sink.write(buf, 0, n)
                bytes += n
                progress(Progress(got, bytes))
                c = io.read(quietMs)
            }
            sink.close()
        } catch (e: IOException) {
            runCatching { sink.close() }
            // (a cancel keeps what came: it's the capture's end)
            if (e !is TransferCancelled) {
                into.abandon(got)
                throw e
            }
        }
        into.done(got, 0)
        progress(Progress(got, bytes, bytes, files = 1))
        return listOf(got)
    }

    companion object {
        const val CHUNK = 1024
        /** how long the first byte may take to come, and the quiet that ends it */
        const val FIRST_MS = 60_000L
        const val QUIET_MS = 10_000L
    }
}
