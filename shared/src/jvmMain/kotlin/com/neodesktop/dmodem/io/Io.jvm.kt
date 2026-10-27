// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.io

import java.io.InputStream
import java.io.OutputStream
import java.util.TimeZone

actual typealias IOException = java.io.IOException

/** the default time zone's offset from UTC at [epochSec], in seconds */
actual fun localOffsetSeconds(epochSec: Long): Int = TimeZone.getDefault().getOffset(epochSec * 1000) / 1000

/** a JVM stream as a [Source] */
fun InputStream.asSource(): Source = object : Source {
    /** the stream's read */
    override fun read(b: ByteArray, off: Int, len: Int): Int = this@asSource.read(b, off, len)
    /** the stream closed */
    override fun close() = this@asSource.close()
}

/** a JVM stream as a [Sink] */
fun OutputStream.asSink(): Sink = object : Sink {
    /** [len] bytes of [b] from [off] to the stream */
    override fun write(b: ByteArray, off: Int, len: Int) = this@asSink.write(b, off, len)
    /** a byte to the stream */
    override fun write(b: Int) = this@asSink.write(b)
    /** the stream flushed */
    override fun flush() = this@asSink.flush()
    /** the stream closed */
    override fun close() = this@asSink.close()
}

/** a [Sink] as a JVM stream */
fun Sink.asOutputStream(): OutputStream = object : OutputStream() {
    /** a byte to the sink */
    override fun write(b: Int) = this@asOutputStream.write(b)
    /** [len] bytes of [b] from [off] to the sink */
    override fun write(b: ByteArray, off: Int, len: Int) = this@asOutputStream.write(b, off, len)
    /** the sink flushed */
    override fun flush() = this@asOutputStream.flush()
    /** the sink closed */
    override fun close() = this@asOutputStream.close()
}

private val secure = java.security.SecureRandom()

/** [n] bytes from SecureRandom */
actual fun secureRandomBytes(n: Int): ByteArray = ByteArray(n).also { secure.nextBytes(it) }
