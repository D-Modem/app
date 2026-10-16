// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
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

    @JvmStatic external fun resize(fd: Int, rows: Int, cols: Int)

    /** bytes read, or -1 at the end */
    @JvmStatic external fun read(fd: Int, b: ByteArray, off: Int, len: Int): Int

    @JvmStatic external fun write(fd: Int, b: ByteArray, off: Int, len: Int): Boolean

    /** the exit status (128 + the signal for one that was killed) */
    @JvmStatic external fun waitFor(pid: Int): Int

    @JvmStatic external fun signal(pid: Int, sig: Int)

    /** [sig] to every process in session [sid] (the shell's pid): its jobs too; how many */
    @JvmStatic external fun signalSession(sid: Int, sig: Int): Int

    @JvmStatic external fun close(fd: Int)

    const val SIGHUP = 1
    const val SIGKILL = 9
}
