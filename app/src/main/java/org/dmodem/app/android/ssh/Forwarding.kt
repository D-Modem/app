// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    /** the spec as its line, the one [parse] reads ("L 8080:db:5432") */
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
            /** [p] as a port, 0..65535, or null */
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

/** A channel's events, from its connection's thread (each to return quickly). */
interface ChannelHandler {
    /** opened, or refused ([why]) */
    fun opened(ok: Boolean, why: String?) {}

    /** its data ([stderr]: the command's error output) */
    fun data(b: ByteArray, stderr: Boolean) {}

    /** its end of data */
    fun eof() {}

    /** its command's exit status */
    fun exit(status: Int) {}

    /** closed: its last event */
    fun closed() {}
}

/** What forwards need of a connection: its channels, each told to its handler. */
interface SshChannels {
    /** a TCP connection from the server to host:port, for one from [from]:[fromPort], told to [x]: its channel, or -1 */
    fun direct(host: String, port: Int, from: String, fromPort: Int, x: ChannelHandler): Int

    /** the server asked to listen ([address] null: its default): [done] told the port bound, or -1 and why; false if it can't be asked */
    fun listen(address: String?, port: Int, done: (Int, String?) -> Unit): Boolean

    /** that listen stopped */
    fun unlisten(address: String?, port: Int)

    /** data to [x]'s channel: the bytes still waiting for the server, or -1 if it's gone */
    fun write(channel: Int, x: ChannelHandler, data: ByteArray, offset: Int, length: Int): Int

    /** [x]'s channel's end of data sent */
    fun eof(channel: Int, x: ChannelHandler)

    /** [x]'s channel closed */
    fun close(channel: Int, x: ChannelHandler)
}

/**
 * A socket and a channel spliced, both ways: the channel's data written to
 * the socket by a thread of its own (queued until [start]), the socket's
 * read to the channel by another (held back while the server's window is
 * full); each one's end passed on as the other's. [done] once the channel
 * has closed.
 */
class Splice(private val link: SshChannels) : ChannelHandler {
    @Volatile var channel = -1
    @Volatile private var socket: Socket? = null
    private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    /** whether the channel opened, once it's known */
    val open = java.util.concurrent.CompletableFuture<Boolean>()
    /** the channel closed */
    val done = java.util.concurrent.CountDownLatch(1)

    /** opened: the socket started, if there's one ([start] does it when it comes) */
    override fun opened(ok: Boolean, why: String?) {
        open.complete(ok)
    }

    /** the channel's data, queued for the socket */
    override fun data(b: ByteArray, stderr: Boolean) {
        queue.put(b)
    }

    /** the channel's end, the socket's output shut once what's queued is written */
    override fun eof() {
        queue.put(END)
    }

    /** the channel gone: the socket closed */
    override fun closed() {
        open.complete(false)
        queue.put(END)
        runCatching { socket?.close() }
        done.countDown()
    }

    /** [s] spliced to the channel: both ways, each on a thread of its own */
    fun start(s: Socket) {
        socket = s
        if (done.count == 0L) {
            runCatching { s.close() }
            return
        }
        Forwards.thread("splice-down") {
            try {
                val o = s.getOutputStream()
                while (true) {
                    val b = queue.take()
                    if (b === END) break
                    o.write(b)
                    o.flush()
                }
                runCatching { s.shutdownOutput() }
            } catch (_: Exception) {
                link.close(channel, this)
            }
        }
        Forwards.thread("splice-up") {
            val buf = ByteArray(32 * 1024)
            try {
                val i = s.getInputStream()
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    var waiting = link.write(channel, this, buf, 0, n)
                    // (the server's window full: held back until it takes more)
                    while (waiting > HIGH_WATER) {
                        Thread.sleep(5)
                        waiting = link.write(channel, this, buf, 0, 0)
                    }
                    if (waiting < 0) break
                }
                link.eof(channel, this)
            } catch (_: Exception) {
                link.close(channel, this)
            }
        }
    }

    /** until the channel has closed (or [ms] have passed) */
    fun await(ms: Long = Long.MAX_VALUE) {
        done.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    companion object {
        private val END = ByteArray(0)
        private const val HIGH_WATER = 256 * 1024
    }
}

/**
 * An SSH connection's port forwards: local (a socket here, each connection
 * a direct-tcpip channel to host:hostport from the server), remote (the
 * server listens, each connection to host:hostport from here) and dynamic
 * (a SOCKS 4/4a/5 proxy here, each connection to where its client asks).
 */
class Forwards(
    private val link: SshChannels,
    private val _states: MutableStateFlow<List<ForwardState>> = MutableStateFlow(emptyList()),
    private val log: (String) -> Unit = {},
) : Closeable {

    val states: StateFlow<List<ForwardState>> = _states.asStateFlow()

    private val running = HashMap<ForwardSpec, Closeable>()
    /** the remote forwards by the port the server bound */
    private val remotes = java.util.concurrent.ConcurrentHashMap<Int, ForwardSpec>()

    @Volatile private var closed = false

    /** the state of [spec]'s forward changed by [f] */
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
            failed(spec, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** [spec] not listening, for [why] */
    private fun failed(spec: ForwardSpec, why: String) {
        set(spec) { it.copy(listening = false, error = why) }
        log("forward ${spec}: $why")
    }

    /** a forward stopped and gone from [states] */
    @Synchronized
    fun stop(spec: ForwardSpec) {
        running.remove(spec)?.let { runCatching { it.close() } }
        _states.update { l -> l.filter { it.spec != spec } }
    }

    /** every forward stopped, their states kept as not listening; no more started */
    @Synchronized
    override fun close() {
        closed = true
        for (c in running.values) runCatching { c.close() }
        running.clear()
        _states.update { l -> l.map { it.copy(listening = false) } }
    }

    /** a local or SOCKS forward's socket bound (loopback unless [ForwardSpec.bind] says, "*" every address), each connection served on a thread of its own: the socket, to close it */
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

    /** a channel from the server to [host]:[port] for [s], once it's open (null if it's refused) */
    private fun open(host: String, port: Int, s: Socket): Splice? {
        val sp = Splice(link)
        sp.channel = link.direct(host, port, s.inetAddress.hostAddress ?: "127.0.0.1", s.port, sp)
        if (sp.channel < 0) return null
        val ok = try {
            sp.open.get(30, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            link.close(sp.channel, sp)
            log("forward to $host:$port: refused by the server")
            return null
        }
        return sp
    }

    /** a connection to a local or SOCKS forward: its channel from the server (SOCKS's to where its client asks), spliced to it, counted while open */
    private fun serve(spec: ForwardSpec, s: Socket) {
        counted(spec) {
            s.use {
                s.tcpNoDelay = true
                val sp = try {
                    if (spec.type == ForwardSpec.Type.DYNAMIC) {
                        Socks.handshake(s.getInputStream(), s.getOutputStream()) { h, p -> open(h, p, s) }
                    } else {
                        open(spec.host, spec.hostPort, s)
                    }
                } catch (e: IOException) {
                    log("forward to ${spec.host}:${spec.hostPort}: ${e.message}")
                    null
                } ?: return@counted
                sp.start(s)
                sp.await()
            }
        }
    }

    /** the server asked to listen for a remote forward, each connection to host:hostport from here: its cancel */
    private fun remote(spec: ForwardSpec): Closeable {
        var bound = -1
        val asked = link.listen(spec.bind, spec.port) { port, why ->
            if (port < 0) {
                failed(spec, why ?: "the server refused to listen")
            } else {
                bound = port
                remotes[port] = spec
                set(spec) { it.copy(listening = true, error = null, boundPort = port) }
            }
        }
        if (!asked) throw IOException("not connected")
        return Closeable {
            if (bound >= 0) {
                remotes.remove(bound)
                link.unlisten(spec.bind, bound)
            }
        }
    }

    /**
     * a connection the server took on a remote forward's [port] (on the
     * connection's thread): its handler, the socket to host:hostport made
     * on a thread of its own; null if it isn't one of ours
     */
    fun incoming(channel: Int, port: Int): ChannelHandler? {
        val spec = remotes[port] ?: return null
        val sp = Splice(link)
        sp.channel = channel
        thread("fwd-R$port") {
            counted(spec) {
                val s = Socket()
                try {
                    s.connect(InetSocketAddress(spec.host, spec.hostPort), 10_000)
                    s.tcpNoDelay = true
                } catch (e: IOException) {
                    runCatching { s.close() }
                    log("forward from the server to ${spec.host}:${spec.hostPort}: ${e.message}")
                    link.close(channel, sp)
                    return@counted
                }
                sp.start(s)
                sp.await()
            }
        }
        return sp
    }

    /** [body] run as one of [spec]'s connections: counted open while it runs, and in the total */
    private inline fun counted(spec: ForwardSpec, body: () -> Unit) {
        set(spec) { it.copy(open = it.open + 1, total = it.total + 1) }
        try {
            body()
        } finally {
            set(spec) { it.copy(open = it.open - 1) }
        }
    }

    companion object {
        /** [body] run on a daemon thread named [name] */
        fun thread(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true }.start()
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

    /** a SOCKS 4/4a request read (the version read already) and, a CONNECT, [open]ed; granted or refused: what was opened */
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

    /** a NUL-terminated string (255 characters at most) */
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

    /** a SOCKS 5 greeting (no authentication only) and request read (the version read already), a CONNECT [open]ed: what was opened, or null */
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

    /** SOCKS 5 reply [code] written (an IPv4 bound address of zeros): [ch], passed back */
    private fun <T : Any> reply5(o: OutputStream, code: Int, ch: T?): T? {
        o.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        o.flush()
        return ch
    }
}
