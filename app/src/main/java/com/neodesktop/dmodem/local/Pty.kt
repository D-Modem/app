// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.local

/** A program on a pseudo-terminal (libdmodem_pty: pty.c). */
object Pty {
    @Volatile private var loaded = false

    /** the library loaded: the app's own, or [path] (the unit tests' host build) */
    @Synchronized
    fun load(path: String? = null) {
        if (loaded) return
        if (path != null) System.load(path) else System.loadLibrary("dmodem_pty")
        loaded = true
    }

    /** [path] run with [argv] and [envp] in [cwd], on a new pty of [rows]x[cols]: the master's fd; its pid in pidOut[0] */
    @JvmStatic external fun start(
        path: String, argv: Array<String>, envp: Array<String>, cwd: String?, rows: Int, cols: Int, pidOut: IntArray,
    ): Int

    /** the pty of master [fd] told it is [rows]x[cols] now */
    @JvmStatic external fun resize(fd: Int, rows: Int, cols: Int)

    /** bytes read, or -1 at the end */
    @JvmStatic external fun read(fd: Int, b: ByteArray, off: Int, len: Int): Int

    /** all of [b] from [off] for [len] written; false if it can't be */
    @JvmStatic external fun write(fd: Int, b: ByteArray, off: Int, len: Int): Boolean

    /** the exit status (128 + the signal for one that was killed) */
    @JvmStatic external fun waitFor(pid: Int): Int

    /** [sig] to the process group of [pid] (to [pid] alone if that fails) */
    @JvmStatic external fun signal(pid: Int, sig: Int)

    /** [sig] to every process in session [sid] (the shell's pid): its jobs too; how many */
    @JvmStatic external fun signalSession(sid: Int, sig: Int): Int

    /** master [fd] closed */
    @JvmStatic external fun close(fd: Int)

    /** a pty with no program on it, its slave raw (the modem's /dev/ttyDM0): the master's fd */
    @JvmStatic external fun openPort(): Int

    /** the slave's path of master [fd] (/dev/pts/N) */
    @JvmStatic external fun ptsName(fd: Int): String?

    /** poll() for reading, [ms] at most: [POLLIN] and [POLLHUP] (the slave closed by all), 0 for nothing */
    @JvmStatic external fun poll(fd: Int, ms: Int): Int

    const val POLLIN = 0x1
    const val POLLHUP = 0x10

    const val SIGHUP = 1
    const val SIGKILL = 9
}
