// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.forwarded.ConnectListener
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A port forward, as ssh's -L, -R and -D have them:
 * "L [bind:]port:host:hostport", "R [bind:]port:host:hostport",
 * "D [bind:]port" (a SOCKS 4/5 proxy).
 */
data class ForwardSpec(
    val type: Type,
    /** the address listened on; null: loopback (-L, -D) or the server's default (-R) */
    val bind: String?,
    val port: Int,
    /** where the connections go (L: from the server; R: from here); unused for D */
    val host: String = "",
    val hostPort: Int = 0,
) {
    enum class Type(val flag: Char, val label: String) {
        LOCAL('L', "Local"),
        REMOTE('R', "Remote"),
        DYNAMIC('D', "SOCKS proxy"),
    }

    override fun toString(): String {
        val b = bind?.let { (if (it.contains(':')) "[$it]" else it) + ":" } ?: ""
        val to = if (type == Type.DYNAMIC) "" else ":" + (if (host.contains(':')) "[$host]" else host) + ":$hostPort"
        return "${type.flag} $b$port$to"
    }

    /** a line for people: "localhost:8080 → db:5432 (from the server)" */
    fun describe(): String {
        val listen = (bind ?: if (type == Type.REMOTE) "server" else "localhost") + ":$port"
        return when (type) {
            Type.LOCAL -> "$listen → $host:$hostPort (via the server)"
            Type.REMOTE -> "$listen on the server → $host:$hostPort here"
            Type.DYNAMIC -> "SOCKS 4/5 proxy on $listen"
        }
    }

    companion object {
        /** the spec from its line ("L 8080:localhost:80", or ssh's own "-L 8080:..."); null if it isn't one */
        fun parse(line: String): ForwardSpec? {
            val s = line.trim().removePrefix("-")
            if (s.isEmpty()) return null
            val type = Type.entries.firstOrNull { it.flag == s[0].uppercaseChar() } ?: return null
            val parts = split(s.substring(1).trim()) ?: return null
            fun port(p: String) = p.toIntOrNull()?.takeIf { it in 0..65535 }
            return when (type) {
                Type.DYNAMIC -> when (parts.size) {
                    1 -> port(parts[0])?.let { ForwardSpec(type, null, it) }
                    2 -> port(parts[1])?.let { ForwardSpec(type, parts[0].ifEmpty { null }, it) }
                    else -> null
                }
                else -> when (parts.size) {
                    3 -> {
                        val p = port(parts[0]) ?: return null
                        val hp = port(parts[2]) ?: return null
                        if (parts[1].isEmpty()) null else ForwardSpec(type, null, p, parts[1], hp)
                    }
                    4 -> {
                        val p = port(parts[1]) ?: return null
                        val hp = port(parts[3]) ?: return null
                        if (parts[2].isEmpty()) null else ForwardSpec(type, parts[0].ifEmpty { null }, p, parts[2], hp)
                    }
                    else -> null
                }
            }
        }

        /** "a:b:[::1]:c" → [a, b, ::1, c] */
        private fun split(s: String): List<String>? {
            val out = ArrayList<String>()
            var i = 0
            val cur = StringBuilder()
            while (i < s.length) {
                val ch = s[i]
                when {
                    ch == '[' -> {
                        val end = s.indexOf(']', i)
                        if (end < 0) return null
                        cur.append(s, i + 1, end)
                        i = end
                    }
                    ch == ':' -> { out.add(cur.toString()); cur.setLength(0) }
                    else -> cur.append(ch)
                }
                i++
            }
            out.add(cur.toString())
            return out
        }
    }
}

/** A forward as it runs: listening or not (and why), its connections. */
data class ForwardState(
    val spec: ForwardSpec,
    val listening: Boolean = false,
    val error: String? = null,
    /** the port actually listened on (the spec's, or the one picked for port 0) */
    val boundPort: Int = 0,
    val open: Int = 0,
    val total: Int = 0,
)

/**
 * An SSH connection's port forwards: local (a socket here, each connection
 * a direct-tcpip channel to host:hostport from the server), remote (the
 * server listens, each connection to host:hostport from here) and dynamic
 * (a SOCKS 4/4a/5 proxy here, each connection to where its client asks).
 */
class Forwards(
    private val client: SSHClient,
    private val _states: MutableStateFlow<List<ForwardState>> = MutableStateFlow(emptyList()),
    private val log: (String) -> Unit = {},
) : Closeable {

    val states: StateFlow<List<ForwardState>> = _states.asStateFlow()

    private val running = HashMap<ForwardSpec, Closeable>()

    @Volatile private var closed = false

    private fun set(spec: ForwardSpec, f: (ForwardState) -> ForwardState) =
        _states.update { l -> l.map { if (it.spec == spec) f(it) else it } }

    /** a forward started (in the caller's thread: it binds); false, with its error in [states], if it can't */
    @Synchronized
    fun start(spec: ForwardSpec): Boolean {
        if (closed || running.containsKey(spec)) return false
        _states.update { l -> l.filter { it.spec != spec } + ForwardState(spec) }
        return try {
            running[spec] = when (spec.type) {
                ForwardSpec.Type.LOCAL, ForwardSpec.Type.DYNAMIC -> listen(spec)
                ForwardSpec.Type.REMOTE -> remote(spec)
            }
            true
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            set(spec) { it.copy(listening = false, error = why) }
            log("forward ${spec}: $why")
            false
        }
    }

    /** a forward stopped and gone from [states] */
    @Synchronized
    fun stop(spec: ForwardSpec) {
        running.remove(spec)?.let { runCatching { it.close() } }
        _states.update { l -> l.filter { it.spec != spec } }
    }

    @Synchronized
    override fun close() {
        closed = true
        for (c in running.values) runCatching { c.close() }
        running.clear()
        _states.update { l -> l.map { it.copy(listening = false) } }
    }

    private fun listen(spec: ForwardSpec): Closeable {
        val addr = InetAddress.getByName(spec.bind?.takeIf { it != "*" && it != "localhost" } ?: if (spec.bind == "*") "0.0.0.0" else "127.0.0.1")
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(addr, spec.port), 50)
        set(spec) { it.copy(listening = true, error = null, boundPort = server.localPort) }
        thread("fwd-${spec.type.flag}${server.localPort}") {
            try {
                while (!server.isClosed) {
                    val s = server.accept()
                    thread("fwd-conn-${s.port}") { serve(spec, s) }
                }
            } catch (_: IOException) {
            }
            set(spec) { it.copy(listening = false) }
        }
        return server
    }

    private fun serve(spec: ForwardSpec, s: Socket) {
        counted(spec) {
            s.use {
                s.tcpNoDelay = true
                val ch: Channel = try {
                    if (spec.type == ForwardSpec.Type.DYNAMIC) {
                        Socks.handshake(s.getInputStream(), s.getOutputStream()) { h, p ->
                            try {
                                client.newDirectConnection(h, p)
                            } catch (e: IOException) {
                                log("SOCKS to $h:$p: ${e.message}")
                                null
                            }
                        } ?: return@counted
                    } else {
                        client.newDirectConnection(spec.host, spec.hostPort)
                    }
                } catch (e: IOException) {
                    log("forward to ${spec.host}:${spec.hostPort}: ${e.message}")
                    return@counted
                }
                ch.use { splice(s, it) }
            }
        }
    }

    private fun remote(spec: ForwardSpec): Closeable {
        val rpf = client.remotePortForwarder
        val listener = ConnectListener { ch ->
            // (in a thread sshj starts for the channel; refused if this throws)
            val s = Socket()
            try {
                s.connect(InetSocketAddress(spec.host, spec.hostPort), 10_000)
                s.tcpNoDelay = true
            } catch (e: IOException) {
                runCatching { s.close() }
                log("forward from the server to ${spec.host}:${spec.hostPort}: ${e.message}")
                throw e
            }
            ch.confirm()
            counted(spec) { s.use { ch.use { splice(s, ch) } } }
        }
        val bound = rpf.bind(RemotePortForwarder.Forward(spec.bind ?: "", spec.port), listener)
        set(spec) { it.copy(listening = true, error = null, boundPort = bound.port) }
        return Closeable { runCatching { rpf.cancel(bound) } }
    }

    private inline fun counted(spec: ForwardSpec, body: () -> Unit) {
        set(spec) { it.copy(open = it.open + 1, total = it.total + 1) }
        try {
            body()
        } finally {
            set(spec) { it.copy(open = it.open - 1) }
        }
    }

    companion object {
        fun thread(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true }.start()

        /** both ways between a socket and a channel, until either ends */
        fun splice(s: Socket, ch: Channel) {
            val up = Thread({
                copy(s.getInputStream(), ch.outputStream)
                runCatching { ch.outputStream.close() }
            }, "splice-up").apply { isDaemon = true; start() }
            copy(ch.inputStream, s.getOutputStream())
            runCatching { s.shutdownOutput() }
            up.join(30_000)
        }

        private fun copy(i: InputStream, o: OutputStream) {
            val buf = ByteArray(32 * 1024)
            try {
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    o.flush()
                }
            } catch (_: IOException) {
            }
        }
    }
}

/** A SOCKS 4, 4a and 5 server's handshake (no authentication, CONNECT only). */
object Socks {
    /**
     * The client's request read and [open]ed; the reply written. What was
     * opened back, or null when the request can't be (refused, or not SOCKS).
     */
    fun <T : Any> handshake(i: InputStream, o: OutputStream, open: (String, Int) -> T?): T? {
        val d = java.io.DataInputStream(i)
        return when (d.readUnsignedByte()) {
            4 -> socks4(d, o, open)
            5 -> socks5(d, o, open)
            else -> null
        }
    }

    private fun <T : Any> socks4(d: java.io.DataInputStream, o: OutputStream, open: (String, Int) -> T?): T? {
        val cmd = d.readUnsignedByte()
        val port = d.readUnsignedShort()
        val ip = ByteArray(4).also { d.readFully(it) }
        cstring(d) // the user id
        // 4a: 0.0.0.x (x != 0) and the host's name after the user id
        val host = if (ip[0] == 0.toByte() && ip[1] == 0.toByte() && ip[2] == 0.toByte() && ip[3] != 0.toByte()) {
            cstring(d)
        } else {
            InetAddress.getByAddress(ip).hostAddress!!
        }
        val ch = if (cmd == 1) open(host, port) else null
        o.write(byteArrayOf(0, if (ch != null) 0x5a else 0x5b, 0, 0, 0, 0, 0, 0))
        o.flush()
        return ch
    }

    private fun cstring(d: java.io.DataInputStream): String {
        val b = java.io.ByteArrayOutputStream()
        while (true) {
            val c = d.readUnsignedByte()
            if (c == 0) break
            if (b.size() > 255) throw IOException("SOCKS4 string too long")
            b.write(c)
        }
        return b.toString(Charsets.ISO_8859_1.name())
    }

    private fun <T : Any> socks5(d: java.io.DataInputStream, o: OutputStream, open: (String, Int) -> T?): T? {
        val n = d.readUnsignedByte()
        val methods = ByteArray(n).also { d.readFully(it) }
        if (!methods.contains(0)) {
            o.write(byteArrayOf(5, 0xff.toByte()))
            o.flush()
            return null
        }
        o.write(byteArrayOf(5, 0))
        o.flush()
        if (d.readUnsignedByte() != 5) return null
        val cmd = d.readUnsignedByte()
        d.readUnsignedByte() // reserved
        val host = when (d.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { d.readFully(it) }).hostAddress!!
            3 -> String(ByteArray(d.readUnsignedByte()).also { d.readFully(it) }, Charsets.ISO_8859_1)
            4 -> InetAddress.getByAddress(ByteArray(16).also { d.readFully(it) }).hostAddress!!
            else -> return reply5(o, 8, null)
        }
        val port = d.readUnsignedShort()
        if (cmd != 1) return reply5(o, 7, null)
        val ch = open(host, port)
        return reply5(o, if (ch != null) 0 else 5, ch)
    }

    private fun <T : Any> reply5(o: OutputStream, code: Int, ch: T?): T? {
        o.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        o.flush()
        return ch
    }
}
