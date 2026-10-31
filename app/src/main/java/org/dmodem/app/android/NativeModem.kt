// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android

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
 * Or on a line to another phone ([Config.peer]): off hook, the modem
 * hands its line to the listener ([Listener.onLine]), whose events go
 * back with [peerEvent].
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
        /** the line another phone's modem through the app (the SIP account unused) */
        val peer: Boolean = false,
        /** a peer line's silence ahead of our samples (ms): their room on the way */
        val peerLeadMs: Int = 100,
        /**
         * The line a USB modem's (a SmartUSB56 or a pico-serial Line stick): its descriptor from
         * the device's UsbDeviceConnection, open while the modem runs (-1: none); its rate (Hz)
         */
        val usbFd: Int = -1,
        val usbRate: Int = 9600,
    )

    interface Listener {
        /** a line of the modem's or SIP's log */
        fun onLog(line: String) {}
        /** the call's state, as it changes */
        fun onStatus(status: Status) {}
        /** The modem stopped: 0 by [stop] or the DTE closed, -1 on an error. */
        fun onExit(result: Int) {}
        /** A peer line's modem off hook: dialling (caller) [dial], or answering; [line] its samples (ours to close). */
        fun onLine(caller: Boolean, dial: String, line: ParcelFileDescriptor) { line.close() }
        /** The modem's speaker (ATM, ATL): 0 off, 1..3 its volume. */
        fun onSpeaker(level: Int) {}
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
            config.peer, config.peerLeadMs, config.usbFd, config.usbRate,
        )
        return if (fd >= 0) ParcelFileDescriptor.adoptFd(fd) else null
    }

    /** The modem stopped (its call hung up, SIP gone); blocks until it has. */
    fun stop() = nativeStop()

    val running: Boolean get() = nativeRunning()

    /**
     * The modem speaker's sound while it is on (the line, 8000 Hz 16 bit
     * mono, in blocks): its socket, for the process's one reader (null
     * after the first call).
     */
    fun speakerSound(): ParcelFileDescriptor? = nativeSpeakerFd().takeIf { it >= 0 }?.let { ParcelFileDescriptor.adoptFd(it) }

    /** A peer line's event to the modem: RING, CANCEL, ANSWERED, FAILED <SIP status>, DROPPED. */
    fun peerEvent(line: String) = nativePeerEvent(line)

    /** the modem started (jni.cpp): the app's end of its DTE's socket, or -1 */
    @JvmStatic
    private external fun nativeStart(
        user: String, password: String, domain: String, sipPort: Int,
        listen: Boolean, t38: Boolean, dns: String, country: String,
        dataFile: String, logLevel: Int, sipLogLevel: Int,
        peer: Boolean, peerLeadMs: Int, usbFd: Int, usbRate: Int,
    ): Int

    /** a peer line's event to the engine */
    @JvmStatic
    private external fun nativePeerEvent(line: String)

    /** the speaker sound's socket, -1 after the first call */
    @JvmStatic
    private external fun nativeSpeakerFd(): Int

    /** the modem stopped, waiting for it */
    @JvmStatic
    private external fun nativeStop()

    /** whether the modem runs */
    @JvmStatic
    private external fun nativeRunning(): Boolean

    // the native library's callbacks (jni.cpp)

    /** a log line, to the listener */
    @JvmStatic
    private fun onLog(line: String) {
        listener?.onLog(line)
    }

    /** the call's state, to the listener as a [Status] */
    @JvmStatic
    private fun onStatus(link: Int, offhook: Boolean, txRate: Int, rxRate: Int) {
        val l = Link.entries.getOrElse(link) { Link.IDLE }
        listener?.onStatus(Status(l, offhook, txRate, rxRate))
    }

    /** a peer line's call to the listener (its descriptor closed if there is none) */
    @JvmStatic
    private fun onLine(caller: Boolean, dial: String, fd: Int) {
        val pfd = ParcelFileDescriptor.adoptFd(fd)
        val l = listener
        if (l == null) pfd.close() else l.onLine(caller, dial, pfd)
    }

    /** the speaker's level, to the listener */
    @JvmStatic
    private fun onSpeaker(level: Int) {
        listener?.onSpeaker(level)
    }

    /** the modem's end, to the listener */
    @JvmStatic
    private fun onExit(result: Int) {
        listener?.onExit(result)
    }
}
