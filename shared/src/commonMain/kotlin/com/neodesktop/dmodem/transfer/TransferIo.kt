// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.transfer

import com.neodesktop.dmodem.io.*


/** The transfer was stopped: by the user, or the other side. */
class TransferCancelled(msg: String) : IOException(msg)

/**
 * A transfer's line: what comes from the remote (fed by the session,
 * instead of to the terminal), read with timeouts; what it sends, to the
 * remote. [cancel] makes the next read throw.
 */
class TransferIo(private val send: (ByteArray) -> Unit) {
    /** each way's bytes as they go (true: from the remote), for finding faults */
    @kotlin.concurrent.Volatile var trace: ((Boolean, ByteArray) -> Unit)? = null

    private val queue = ByteQueue()
    private var cur: ByteArray? = null
    private var pos = 0
    @kotlin.concurrent.Volatile private var cancelled: String? = null

    /** bytes from the remote */
    fun feed(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        if (len > 0) queue.put(data.copyOfRange(off, off + len).also { b -> trace?.invoke(true, b) })
    }

    fun write(b: ByteArray) {
        if (cancelled != null) throw TransferCancelled(cancelled!!)
        trace?.invoke(false, b)
        send(b)
    }

    fun write(vararg b: Int) = write(ByteArray(b.size) { b[it].toByte() })

    /** a byte (0-255), or -1 when none came within [timeoutMs] (the cancel looked at every quarter second) */
    fun read(timeoutMs: Long): Int {
        val deadline = monotonicMs() + timeoutMs
        while (true) {
            cancelled?.let { throw TransferCancelled(it) }
            val c = cur
            if (c != null && pos < c.size) return c[pos++].toInt() and 0xff
            val left = deadline - monotonicMs()
            if (left <= 0) return -1
            val next = queue.poll(minOf(left, 250L)) ?: continue
            cur = next
            pos = 0
        }
    }

    /** the next byte if one is waiting (left there), else -1: without waiting */
    fun peek(): Int {
        while (true) {
            val c = cur
            if (c != null && pos < c.size) return c[pos].toInt() and 0xff
            cur = queue.poll() ?: return -1
            pos = 0
        }
    }

    /** whether bytes are waiting (without waiting for any) */
    fun available(): Boolean = (cur?.let { pos < it.size } == true) || queue.isNotEmpty()

    /** everything waiting dropped (to resynchronise) */
    fun purge() {
        cur = null
        queue.clear()
    }

    /** what came but wasn't read: back to the terminal when the transfer ends */
    fun leftover(): ByteArray {
        val out = Bytes()
        cur?.let { if (pos < it.size) out.write(it, pos, it.size - pos) }
        cur = null
        while (true) out.write(queue.poll() ?: break)
        return out.toByteArray()
    }

    fun cancel(why: String) {
        cancelled = why
    }

    val isCancelled: Boolean get() = cancelled != null
}
