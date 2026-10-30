// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.io

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.TimeSource

/**
 * What the shared code needs of I/O, the same on the JVM and on iOS
 * (and Linux native, its tests): bytes in and out, a buffer of them, a
 * queue of them read with a timeout, the time.
 */

/** an I/O error: on the JVM, java.io.IOException itself (the apps' catches as they were) */
expect open class IOException(message: String?) : Exception

/** bytes coming in: up to [len] of them into [b] at [off], how many (-1 at the end) */
interface Source : AutoCloseable {
    /** up to [len] bytes into [b] at [off]: how many, -1 at the end */
    fun read(b: ByteArray, off: Int = 0, len: Int = b.size - off): Int
    /** the source let go of (nothing, unless it holds something) */
    override fun close() {}
}

/** bytes going out */
interface Sink : AutoCloseable {
    /** [len] bytes of [b] from [off] sent on */
    fun write(b: ByteArray, off: Int = 0, len: Int = b.size - off)
    /** the one byte [b] sent on */
    fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    /** what is held back sent on (nothing, unless it holds some) */
    fun flush() {}
    /** the sink let go of (nothing, unless it holds something) */
    override fun close() {}
}

/** a growing buffer of bytes (the JVM's ByteArrayOutputStream) */
class Bytes(capacity: Int = 64) : Sink {
    private var buf = ByteArray(maxOf(capacity, 16))
    var size = 0
        private set

    /** the buffer grown, doubling, to fit [n] more */
    private fun room(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    /** the byte [b] appended */
    override fun write(b: Int) {
        room(1)
        buf[size++] = b.toByte()
    }

    /** [len] bytes of [b] from [off] appended */
    override fun write(b: ByteArray, off: Int, len: Int) {
        room(len)
        b.copyInto(buf, size, off, off + len)
        size += len
    }

    /** a copy of the bytes so far */
    fun toByteArray(): ByteArray = buf.copyOf(size)

    /** emptied, keeping its room */
    fun reset() {
        size = 0
    }
}

/**
 * Chunks of bytes from one thread to another, read with a timeout (the
 * JVM's LinkedBlockingQueue, on coroutines' channels).
 */
class ByteQueue {
    private val ch = Channel<ByteArray>(Channel.UNLIMITED)
    /** a chunk looked at (isNotEmpty()) and not taken yet: the reader's alone */
    private var head: ByteArray? = null

    /** (any thread) */
    fun put(b: ByteArray) {
        ch.trySend(b)
    }

    /**
     * the next chunk, waiting up to [ms]; null if none came (the reader's
     * thread). (A select, not receive() in withTimeoutOrNull: a chunk sent
     * just as that timed out was taken by the cancelled receive and lost.)
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun poll(ms: Long): ByteArray? {
        poll()?.let { return it }
        if (ms <= 0) return null
        return runBlocking {
            select {
                ch.onReceive { it }
                onTimeout(ms) { null }
            }
        }
    }

    /** the next chunk if one waits, else null */
    fun poll(): ByteArray? {
        head?.let { head = null; return it }
        return ch.tryReceive().getOrNull()
    }

    /** whether a chunk waits (without taking it) */
    fun isNotEmpty(): Boolean {
        if (head == null) head = ch.tryReceive().getOrNull()
        return head != null
    }

    /** every waiting chunk dropped */
    fun clear() {
        head = null
        while (ch.tryReceive().isSuccess) Unit
    }
}

private val start = TimeSource.Monotonic.markNow()

/** a monotonic clock's milliseconds (from any origin) */
fun monotonicMs(): Long = start.elapsedNow().inWholeMilliseconds

/** this thread asleep for [ms] */
fun sleepMs(ms: Long) {
    if (ms > 0) runBlocking { delay(ms) }
}

/** the local time zone's offset from UTC at [epochSec] (seconds) */
expect fun localOffsetSeconds(epochSec: Long): Int

/** [n] bytes from the system's secure random source */
expect fun secureRandomBytes(n: Int): ByteArray

/** a byte from the source (0-255), -1 at its end */
fun Source.read(): Int {
    val b = ByteArray(1)
    while (true) {
        val n = read(b, 0, 1)
        if (n < 0) return -1
        if (n == 1) return b[0].toInt() and 0xff
    }
}

/** ISO 8859-1's text of [len] bytes at [off] */
fun latin1String(b: ByteArray, off: Int, len: Int): String =
    CharArray(len) { (b[off + it].toInt() and 0xff).toChar() }.concatToString()

/** up to [n] bytes read and dropped: how many (fewer at the end) */
fun Source.skip(n: Long): Long {
    val scratch = ByteArray(minOf(n, 8192L).toInt().coerceAtLeast(1))
    var done = 0L
    while (done < n) {
        val k = read(scratch, 0, minOf(n - done, scratch.size.toLong()).toInt())
        if (k < 0) break
        done += k
    }
    return done
}

/** writes gathered [size] bytes at a time (flushed, and passed on, when closed) */
fun Sink.buffered(size: Int = 8192): Sink {
    val to = this
    return object : Sink {
        private val buf = ByteArray(size)
        private var n = 0

        /** [len] bytes of [b] gathered, or written straight through when as big as the buffer */
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len >= size) {
                flushBuf()
                to.write(b, off, len)
                return
            }
            if (n + len > size) flushBuf()
            b.copyInto(buf, n, off, off + len)
            n += len
        }

        /** the byte [b] gathered */
        override fun write(b: Int) {
            if (n == size) flushBuf()
            buf[n++] = b.toByte()
        }

        /** what is gathered written to the sink underneath */
        private fun flushBuf() {
            if (n > 0) to.write(buf, 0, n)
            n = 0
        }

        /** what is gathered written, and the sink underneath flushed */
        override fun flush() {
            flushBuf()
            to.flush()
        }

        /** flushed, and the sink underneath closed */
        override fun close() {
            flush()
            to.close()
        }
    }
}
