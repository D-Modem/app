// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.io

import java.io.InputStream
import java.io.OutputStream
import java.util.TimeZone

actual typealias IOException = java.io.IOException

actual fun localOffsetSeconds(epochSec: Long): Int = TimeZone.getDefault().getOffset(epochSec * 1000) / 1000

/** a JVM stream as a [Source] */
fun InputStream.asSource(): Source = object : Source {
    override fun read(b: ByteArray, off: Int, len: Int): Int = this@asSource.read(b, off, len)
    override fun close() = this@asSource.close()
}

/** a JVM stream as a [Sink] */
fun OutputStream.asSink(): Sink = object : Sink {
    override fun write(b: ByteArray, off: Int, len: Int) = this@asSink.write(b, off, len)
    override fun write(b: Int) = this@asSink.write(b)
    override fun flush() = this@asSink.flush()
    override fun close() = this@asSink.close()
}

/** a [Sink] as a JVM stream */
fun Sink.asOutputStream(): OutputStream = object : OutputStream() {
    override fun write(b: Int) = this@asOutputStream.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = this@asOutputStream.write(b, off, len)
    override fun flush() = this@asOutputStream.flush()
    override fun close() = this@asOutputStream.close()
}

private val secure = java.security.SecureRandom()

actual fun secureRandomBytes(n: Int): ByteArray = ByteArray(n).also { secure.nextBytes(it) }
