// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem

import android.os.ParcelFileDescriptor

/**
 * D-Modem's modem on a SIP line, in the native library (libdmodem_jni:
 * app/src/main/cpp). One modem per process: the SIP user agent is the
 * process's.
 *
 * [start] gives the app's end of the modem's DTE, a stream socket: AT
 * commands and data written to it go to the modem, its responses and the
 * received data come from it. Closing it ends the call and stops the
 * modem.
 *
 * The listener's calls come on the modem's and SIP's own threads.
 */
object NativeModem {

    /** The call's state, as the modem reports it. */
    enum class Link { IDLE, CONNECTING, ONLINE, HANGING_UP }

    data class Status(
        val link: Link,
        val offhook: Boolean,
        /** bit/s, while online */
        val txRate: Int,
        val rxRate: Int,
    )

    data class Config(
        /** the SIP account: user, password, registrar or proxy (host[:port]) */
        val user: String,
        val password: String,
        val domain: String,
        /** the local SIP port */
        val sipPort: Int = 5060,
        /** register with the domain and take calls in */
        val listen: Boolean = false,
        /** fax calls may go over to T.38 */
        val t38: Boolean = true,
        /** DNS servers for SRV/NAPTR, from the active network (Android has no resolv.conf) */
        val dns: List<String> = emptyList(),
        /** the modem's country by name (empty: USA) */
        val country: String = "",
        /** where the modem keeps what it learns of the line over calls (empty: nowhere) */
        val dataFile: String = "",
        /** the modem's log level (0: errors only) */
        val logLevel: Int = 0,
        /** SIP's log level (0 none, 1 errors .. 4 the SIP messages) */
        val sipLogLevel: Int = 1,
    )

    interface Listener {
        fun onLog(line: String) {}
        fun onStatus(status: Status) {}
        /** The modem stopped: 0 by [stop] or the DTE closed, -1 on an error. */
        fun onExit(result: Int) {}
    }

    @Volatile
    var listener: Listener? = null

    init {
        System.loadLibrary("dmodem_jni")
    }

    /** The modem started with [config]: the app's end of its DTE, or null if it runs already or can't start. */
    fun start(config: Config): ParcelFileDescriptor? {
        val fd = nativeStart(
            config.user, config.password, config.domain, config.sipPort,
            config.listen, config.t38, config.dns.joinToString(","),
            config.country, config.dataFile, config.logLevel, config.sipLogLevel,
        )
        return if (fd >= 0) ParcelFileDescriptor.adoptFd(fd) else null
    }

    /** The modem stopped (its call hung up, SIP gone); blocks until it has. */
    fun stop() = nativeStop()

    val running: Boolean get() = nativeRunning()

    @JvmStatic
    private external fun nativeStart(
        user: String, password: String, domain: String, sipPort: Int,
        listen: Boolean, t38: Boolean, dns: String, country: String,
        dataFile: String, logLevel: Int, sipLogLevel: Int,
    ): Int

    @JvmStatic
    private external fun nativeStop()

    @JvmStatic
    private external fun nativeRunning(): Boolean

    // the native library's callbacks (jni.cpp)

    @JvmStatic
    private fun onLog(line: String) {
        listener?.onLog(line)
    }

    @JvmStatic
    private fun onStatus(link: Int, offhook: Boolean, txRate: Int, rxRate: Int) {
        val l = Link.entries.getOrElse(link) { Link.IDLE }
        listener?.onStatus(Status(l, offhook, txRate, rxRate))
    }

    @JvmStatic
    private fun onExit(result: Int) {
        listener?.onExit(result)
    }
}
