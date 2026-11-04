// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.bridge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A serial adapter for the tests: what's written to it kept, what it "receives" queued, its settings and lines noted. */
class FakeSerialLine(override val name: String = "a test adapter") : SerialLine {
    /** octets the far end sends (the app reads them) */
    val incoming = LinkedBlockingQueue<ByteArray>()
    private val got = ByteArrayOutputStream()
    @Volatile var format: SerialFormat? = null
        private set
    @Volatile var rtsCts: Boolean? = null
        private set
    @Volatile var dtr: Boolean? = null
        private set
    @Volatile var rts: Boolean? = null
        private set
    /** its DCD (null: it can't say) */
    @Volatile var cd: Boolean? = null
    @Volatile var closed = false
    /** unplugged: reads and writes fail */
    @Volatile var gone = false

    /** what was written to it, as text */
    fun sent(): String = synchronized(got) { String(got.toByteArray(), Charsets.ISO_8859_1) }

    /** the next queued octets (0 after [timeoutMs] with none); an IOException once closed or gone */
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        if (closed || gone) throw IOException("closed")
        val b = incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
        System.arraycopy(b, 0, buf, 0, b.size)
        return b.size
    }

    /** kept; an IOException once closed or gone */
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed || gone) throw IOException("closed")
        synchronized(got) { got.write(b, off, len) }
    }

    /** noted */
    override fun setFormat(f: SerialFormat) { format = f }
    /** noted */
    override fun setRtsCts(on: Boolean): Boolean { rtsCts = on; return true }
    /** noted */
    override fun setDtr(on: Boolean) { dtr = on }
    /** noted */
    override fun setRts(on: Boolean) { rts = on }
    /** as set */
    override fun carrier(): Boolean? = cd
    /** noted */
    override fun close() { closed = true }
}
