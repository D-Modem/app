// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.local

import org.dmodem.app.android.terminal.TerminalLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * A program here, on a pseudo-terminal: the local shell. Its output to the
 * listeners; what is typed to it; the window's size as it changes. Closed
 * when it exits (with its status), or hung up by [close].
 */
class LocalLink(
    private val command: List<String>,
    private val environment: Map<String, String>,
    private val cwd: String?,
    @Volatile private var cols: Int = 80,
    @Volatile private var rows: Int = 24,
) : TerminalLink {

    private val _state = MutableStateFlow<TerminalLink.State>(TerminalLink.State.Connecting)
    override val state: StateFlow<TerminalLink.State> = _state.asStateFlow()
    private val listeners = CopyOnWriteArrayList<TerminalLink.Listener>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "pty-io").apply { isDaemon = true } }

    @Volatile private var fd = -1
    @Volatile var pid = -1
        private set
    @Volatile private var closing = false

    /** the exit status, once it has exited */
    @Volatile var exitStatus: Int? = null
        private set

    /** [l] given the program's output from now */
    override fun addListener(l: TerminalLink.Listener) {
        listeners.add(l)
    }

    /** [l] given no more of it */
    override fun removeListener(l: TerminalLink.Listener) {
        listeners.remove(l)
    }

    /** started (here: fork and exec are quick); false, closed with the reason, if it can't be */
    fun start(): Boolean {
        val pidOut = IntArray(1)
        try {
            Pty.load()
            fd = Pty.start(
                command[0], command.toTypedArray(), environment.map { (k, v) -> "$k=$v" }.toTypedArray(),
                cwd, rows, cols, pidOut,
            )
        } catch (e: IOException) {
            closed(e.message)
            return false
        } catch (e: UnsatisfiedLinkError) {
            closed("no pseudo-terminals here: ${e.message}")
            return false
        }
        pid = pidOut[0]
        _state.value = TerminalLink.State.Open
        Thread({ read() }, "pty-read-$pid").apply { isDaemon = true }.start()
        Thread({
            val status = Pty.waitFor(pid)
            exitStatus = status
            // (what it wrote last read before the end is told)
            Thread.sleep(50)
            closed(if (closing) null else "exited" + if (status != 0) " ($status)" else "")
        }, "pty-wait-$pid").apply { isDaemon = true }.start()
        return true
    }

    /** the program's output to the listeners, until the pty's end */
    private fun read() {
        val buf = ByteArray(16384)
        while (true) {
            val n = Pty.read(fd, buf, 0, buf.size)
            if (n < 0) break
            for (l in listeners) l.onData(buf, n)
        }
    }

    /** closed, saying [reason] (once): the writer stopped, the pty's master closed */
    private fun closed(reason: String?) {
        synchronized(this) {
            if (_state.value is TerminalLink.State.Closed) return
            _state.value = TerminalLink.State.Closed(reason)
        }
        io.shutdown()
        if (fd >= 0) {
            val f = fd
            fd = -1
            Pty.close(f)
        }
    }

    /** [length] octets of [data] to the program, on the writer's thread; false when it isn't open */
    override fun write(data: ByteArray, offset: Int, length: Int): Boolean {
        val f = fd
        if (f < 0 || _state.value !is TerminalLink.State.Open) return false
        val copy = data.copyOfRange(offset, offset + length)
        return try {
            io.execute { Pty.write(f, copy, 0, copy.size) }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    /** the window's size kept and given the pty */
    override fun resize(cols: Int, rows: Int) {
        this.cols = cols
        this.rows = rows
        val f = fd
        if (f >= 0) Pty.resize(f, rows, cols)
    }

    /**
     * hung up, as a terminal closing does: SIGHUP to the shell and every
     * job it started (nohup'd ones stay); the shell killed if it hasn't
     * gone after that
     */
    override fun close() {
        if (_state.value is TerminalLink.State.Closed) return
        closing = true
        val p = pid
        if (p > 0) {
            Pty.signalSession(p, Pty.SIGHUP)
            Thread({
                // (again, for a job the shell was forking as the first went: not there to be found then)
                for (wait in listOf(100L, 400L)) {
                    Thread.sleep(wait)
                    Pty.signalSession(p, Pty.SIGHUP)
                }
                Thread.sleep(1500)
                if (exitStatus == null) Pty.signal(p, Pty.SIGKILL)
            }, "pty-kill-$p").apply { isDaemon = true }.start()
        } else {
            closed(null)
        }
    }
}
