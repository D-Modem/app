// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * The modem as a serial port on TCP (as ser2net or tcpser have it): a
 * client that connects has the modem's DTE, raw or in Telnet (with RFC
 * 2217's port control) as [telnet] says. Only this phone's own programs
 * can connect unless [anyAddress]; a [password], if set, is asked for
 * first.
 */
class TcpBridge(
    private val bridge: SerialBridge,
    val port: Int,
    val anyAddress: Boolean = false,
    val telnet: Boolean = false,
    private val password: String = "",
) {
    sealed interface State {
        data object Stopped : State
        data class Listening(val port: Int) : State
        data class Failed(val why: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var server: ServerSocket? = null
    private var thread: Thread? = null

    /** listening from now (false: the port couldn't be had; [state] says why) */
    fun start(): Boolean {
        if (server != null) return true
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(if (anyAddress) null else InetAddress.getLoopbackAddress(), port), 4)
            }
        } catch (e: IOException) {
            _state.value = State.Failed("port $port: ${e.message}")
            return false
        }
        server = s
        _state.value = State.Listening(s.localPort)
        thread = Thread({ accept(s) }, "tcp-bridge-$port").apply { isDaemon = true; start() }
        return true
    }

    /** the port the server got (when [port] was 0: any) */
    val localPort: Int get() = server?.localPort ?: -1

    /** the server closed, its thread joined */
    fun stop() {
        val s = server ?: return
        server = null
        runCatching { s.close() }
        thread?.join(2000)
        _state.value = State.Stopped
    }

    /** each connection to [s] served on a thread of its own, until it's closed */
    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (_: IOException) { break }
            Thread({ client(c) }, "tcp-bridge-client").apply { isDaemon = true; start() }
        }
    }

    /** [c] served: Telnet started, the password asked for, then the modem if no other client has it (else BUSY) */
    private fun client(c: Socket) {
        c.tcpNoDelay = true
        val name = c.inetAddress.hostAddress ?: "?"
        val input = c.getInputStream()
        val output = c.getOutputStream()
        val t = if (telnet) Telnet() else null
        try {
            if (t != null) output.write(t.start())
            if (password.isNotEmpty() && !login(input, output, t)) {
                c.close()
                return
            }
            val p = Port("tcp", name, input, output) { c.close() }
            if (!bridge.tryClaim(p)) {
                output.write("\r\nBUSY: the modem is in use\r\n".toByteArray())
                output.flush()
                c.close()
                return
            }
            // (Telnet's replies from now go out through the bridge's writer)
            bridge.serve(p, t)
        } catch (_: IOException) {
            runCatching { c.close() }
        }
    }

    /** the password asked for, up to three times */
    private fun login(input: InputStream, output: OutputStream, t: Telnet?): Boolean {
        // (Telnet's answers while it's asked: straight out)
        t?.reply = { b -> synchronized(output) { output.write(b); output.flush() } }
        repeat(3) {
            output.write("Password: ".toByteArray())
            output.flush()
            val line = readLine(input, t) ?: return false
            if (same(line, password)) {
                output.write("\r\n".toByteArray())
                return true
            }
            Thread.sleep(2000)
            output.write("\r\nWrong password\r\n".toByteArray())
        }
        return false
    }

    /** a line typed (no echo: the client was told we echo, and we don't) */
    private fun readLine(input: InputStream, t: Telnet?): String? {
        val sb = StringBuilder()
        val one = ByteArray(1)
        while (sb.length < 256) {
            val n = input.read(one)
            if (n < 0) return null
            val d = t?.decode(one, 1) ?: one
            for (b in d) {
                val c = b.toInt() and 0xff
                when (c) {
                    13, 10 -> if (sb.isNotEmpty() || c == 13) return sb.toString()
                    8, 127 -> if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
                    0 -> {}
                    else -> sb.append(c.toChar())
                }
            }
        }
        return null
    }

    /** whether [a] and [b] are the same, compared in constant time (by their SHA-256) */
    private fun same(a: String, b: String): Boolean {
        val d = MessageDigest.getInstance("SHA-256")
        return MessageDigest.isEqual(d.digest(a.toByteArray()), d.digest(b.toByteArray()))
    }
}
