// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.modem

import android.os.ParcelFileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The modem's DTE side, shared: what the modem sends goes to every
 * listener (the app's console, the terminal, the TCP bridge), and any of
 * them can write to the modem. The modem behind it can change (the
 * service restarts it when the network changes): [attach] the new one's
 * streams, and the listeners stay.
 */
class DteHub {

    fun interface Listener {
        /** The modem's octets: [data] up to [length], valid only during the call. */
        fun onData(data: ByteArray, length: Int)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val outLock = Any()
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var reader: Thread? = null

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** The modem's end of its DTE socket: the hub's from now (closed by [detach]). */
    fun attach(pfd: ParcelFileDescriptor) {
        attach(
            ParcelFileDescriptor.AutoCloseInputStream(pfd),
            ParcelFileDescriptor.AutoCloseOutputStream(pfd.dup()),
        )
    }

    /** The same with streams (the tests' pipes). */
    @Synchronized
    fun attach(inStream: InputStream, outStream: OutputStream) {
        detach()
        input = inStream
        synchronized(outLock) { output = outStream }
        reader = Thread({ read(inStream) }, "dte-reader").apply {
            isDaemon = true
            start()
        }
    }

    /** The streams closed (the modem sees its DTE gone), the reader ended. */
    @Synchronized
    fun detach() {
        val r = reader
        synchronized(outLock) {
            try {
                output?.close()
            } catch (_: IOException) {
            }
            output = null
        }
        try {
            input?.close()
        } catch (_: IOException) {
        }
        input = null
        reader = null
        if (r != null && r != Thread.currentThread()) r.join(2000)
    }

    val attached: Boolean get() = synchronized(outLock) { output != null }

    /** Octets to the modem: false if there is no modem, or it has gone. */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean =
        synchronized(outLock) {
            val o = output ?: return false
            try {
                o.write(data, offset, length)
                o.flush()
                true
            } catch (_: IOException) {
                false
            }
        }

    fun write(text: String): Boolean = write(text.toByteArray(Charsets.ISO_8859_1))

    private fun read(s: InputStream) {
        val buf = ByteArray(4096)
        while (true) {
            val n = try {
                s.read(buf)
            } catch (_: IOException) {
                -1
            }
            if (n < 0) break
            if (n == 0) continue
            for (l in listeners) l.onData(buf, n)
        }
    }
}
