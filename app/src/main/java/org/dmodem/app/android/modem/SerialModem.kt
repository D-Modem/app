// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.modem

import org.dmodem.app.android.bridge.SerialLine
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A modem of its own on a USB serial adapter (the line LINE_SERIAL_MODEM): a US Robotics Courier, a
 * Zoom, any Hayes modem on a cable to the adapter (a Prolific PL2303's, an FTDI's, ...). It takes the app's
 * AT commands and data in place of D-Modem's own modem, as a Bluetooth modem does: the terminal, the
 * internet, fax and the local Linux's /dev/ttyDM0 talk to it. Its DTR and RTS are held up while it's
 * open (closing it drops DTR: a modem set to &D2 hangs up), and its DCD, where the adapter reads it,
 * says whether it's online.
 */
class SerialModem(private val line: SerialLine) : Closeable {
    @Volatile private var open = true
    private var watch: Thread? = null

    val name: String get() = line.name

    /** its octets: -1 once closed or the adapter's gone */
    val input: InputStream = object : InputStream() {
        private val buf = ByteArray(4096)
        private var have = 0
        private var at = 0

        /** one octet, or -1 at the end */
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        /** what the modem sent into [b], waiting for some; -1 once closed or gone */
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (open) {
                if (at < have) {
                    val n = minOf(len, have - at)
                    System.arraycopy(buf, at, b, off, n)
                    at += n
                    return n
                }
                // (a timeout: polled again, so a close is seen)
                have = try { line.read(buf, 200) } catch (_: IOException) { return -1 }
                at = 0
            }
            return -1
        }
    }

    /** octets to it: an IOException once closed or gone */
    val output: OutputStream = object : OutputStream() {
        /** one octet to the modem */
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        /** [len] octets of [b] to the modem */
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (!open) throw IOException("${line.name} is closed")
            line.write(b, off, len)
        }
    }

    /**
     * DTR and RTS up, RTS/CTS flow control as [rtsCts] has it (said to [log] when the adapter can't),
     * and its DCD watched: each change to [onCarrier]
     */
    fun start(rtsCts: Boolean, log: (String) -> Unit = {}, onCarrier: (Boolean) -> Unit = {}) {
        runCatching { line.setDtr(true) }
        runCatching { line.setRts(true) }
        if (rtsCts && !line.setRtsCts(true)) log("${line.name} has no RTS/CTS flow control of its own: none")
        watch = Thread({
            var last: Boolean? = null
            try {
                while (open) {
                    val cd = runCatching { line.carrier() }.getOrNull()
                    if (cd != null && cd != last) {
                        // (the first look only says so if it's on: a modem idle isn't news)
                        if (last != null || cd) onCarrier(cd)
                        last = cd
                    }
                    Thread.sleep(250)
                }
            } catch (_: InterruptedException) {
            }
        }, "serial-modem-dcd").apply { isDaemon = true; start() }
    }

    /** closed: DTR down (the modem hangs up), the adapter let go */
    override fun close() {
        if (!open) return
        open = false
        watch?.interrupt()
        runCatching { line.setDtr(false) }
        line.close()
    }
}
