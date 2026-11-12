// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ssh

import org.dmodem.app.android.terminal.TerminalLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * An SSH connection's interactive shell, on libdmodemssh (the apps' own
 * client, on libssh): host key checked against [KnownHosts], the user
 * authenticated by a key, or by password or the server's questions
 * (keyboard-interactive) asked of the user, a pty of [term] in the
 * terminal's size; its output (stdout and stderr) to the listeners, the
 * window's size to the server as it changes. [connect] starts it on a
 * thread of its own, which polls the connection for its events.
 */
class SshLink(
    val host: String,
    val port: Int,
    val user: String,
    /** a private key's text (OpenSSH's, unencrypted) to log in with, or null for passwords */
    private val key: String?,
    private val knownHosts: KnownHosts,
    /** the user asked (from the connection's thread), with the link as the asker */
    private val ask: (Prompt, Any) -> Answer,
    /** the link's questions cancelled (it has closed) */
    private val cancelAsks: (Any) -> Unit = {},
    /** a password kept for the entry, tried first */
    private val savedPassword: String? = null,
    /** a password the user typed and asked to keep */
    private val keepPassword: (String) -> Unit = {},
    private val term: String = "xterm-256color",
    @Volatile private var cols: Int = 80,
    @Volatile private var rows: Int = 24,
    /** port forwards started once logged in */
    private val forwardSpecs: List<ForwardSpec> = emptyList(),
    /** the keys offered to the server as an agent (ssh -A); null: no agent forwarding */
    private val agent: AgentKeys? = null,
) : TerminalLink, SshChannels {

    private val _state = MutableStateFlow<TerminalLink.State>(TerminalLink.State.Connecting)
    override val state: StateFlow<TerminalLink.State> = _state.asStateFlow()
    private val listeners = CopyOnWriteArrayList<TerminalLink.Listener>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ssh-$host-io").apply { isDaemon = true } }
    private val agentIo = Executors.newSingleThreadExecutor { r -> Thread(r, "ssh-$host-agent").apply { isDaemon = true } }

    /** the connection (0 before it's made and once it's freed), its channels' handlers: all under [lock] */
    private val lock = Any()
    private var h = 0L
    private var ready = false
    private val handlers = HashMap<Int, ChannelHandler>()
    /** channels whose number was taken again before their close was handed over: those closes to ignore */
    private val staleCloses = HashMap<Int, Int>()
    private val listens = HashMap<Int, (Int, String?) -> Unit>()

    // (the connection's thread's own)
    @Volatile private var shell = -1
    @Volatile private var shellHandler: ChannelHandler? = null
    @Volatile private var started = false
    private var triedKey = false
    private var tries = 0
    private var saved = savedPassword
    private var authStarted = false
    private var why: String? = null
    @Volatile private var closing = false
    @Volatile private var fwd: Forwards? = null

    private val _forwards = MutableStateFlow<List<ForwardState>>(emptyList())
    /** the port forwards, as they run */
    val forwards: StateFlow<List<ForwardState>> = _forwards.asStateFlow()

    private val _events = MutableStateFlow<List<String>>(emptyList())
    /** what the forwards and the agent did (the latest last, at most [MAX_EVENTS]) */
    val events: StateFlow<List<String>> = _events.asStateFlow()

    /** the agent was forwarded: asked of the server with the shell */
    @Volatile var agentForwarded = false
        private set

    /** [s] added to [events], the oldest dropped past [MAX_EVENTS] */
    private fun event(s: String) {
        _events.value = (_events.value + s).takeLast(MAX_EVENTS)
    }

    /** [l] told the shell's output from now on */
    override fun addListener(l: TerminalLink.Listener) {
        listeners.add(l)
    }

    /** [l] told no more */
    override fun removeListener(l: TerminalLink.Listener) {
        listeners.remove(l)
    }

    /** the connection made, in a thread of its own */
    fun connect() {
        started = true
        Thread({ run() }, "ssh-$host").apply { isDaemon = true }.start()
    }

    /** the connection's life on its thread: each event acted on until it's over, then it's freed */
    private fun run() {
        val c = try {
            NativeSsh.create(host, port, user)
        } catch (e: Throwable) {
            // (no native library: nothing to connect with)
            return closed("SSH isn't available: ${e.message ?: e.javaClass.simpleName}")
        }
        if (c == 0L) return closed("could not start SSH")
        NativeSsh.option(c, "timeout", "20")
        knownHosts.hostKeyAlgorithms(host, port).takeIf { it.isNotEmpty() }?.let {
            NativeSsh.option(c, "hostkeys", it.joinToString(","))
        }
        synchronized(lock) {
            if (closing) {
                NativeSsh.free(c)
                return closed(null)
            }
            h = c
        }
        val ev = IntArray(4)
        var reason: String? = null
        while (true) {
            val data = NativeSsh.poll(c, 1000, ev)
            if (ev[0] < 0) break
            if (ev[0] == 0) continue
            if (ev[1] == NativeSsh.EV_CLOSED) {
                reason = data?.let { String(it) }
                break
            }
            try {
                event(c, ev[1], ev[2], ev[3], data)
            } catch (e: RuntimeException) {
                why = why ?: (e.message ?: e.javaClass.simpleName)
                NativeSsh.disconnect(c)
            }
        }
        // (no more calls from other threads; then the channels told, then it's freed)
        val left: Collection<ChannelHandler>
        synchronized(lock) {
            h = 0L
            ready = false
            left = handlers.values.toList()
            handlers.clear()
            listens.clear()
        }
        for (x in left) runCatching { x.closed() }
        NativeSsh.free(c)
        closed(
            when {
                closing -> null
                why != null -> why
                authStarted && shell < 0 && reason != null -> "authentication failed: $reason"
                reason != null && shell < 0 -> reason
                else -> "connection closed by $host"
            },
        )
    }

    /** one event of the connection's (not its last) acted on */
    private fun event(c: Long, type: Int, channel: Int, value: Int, data: ByteArray?) {
        when (type) {
            NativeSsh.EV_HOSTKEY -> {
                val ok = knownHosts.verify(host, port, data?.let { String(it) } ?: "", this)
                if (!ok) why = "the host key wasn't accepted"
                NativeSsh.trust(c, ok, false)
            }
            NativeSsh.EV_AUTH -> login(c, value, data?.let { String(it) } ?: "")
            NativeSsh.EV_PROMPTS -> questions(c, value, data?.let { String(it) } ?: "")
            NativeSsh.EV_READY -> ready(c, data)
            NativeSsh.EV_INCOMING -> incoming(c, channel, value)
            NativeSsh.EV_LISTEN -> synchronized(lock) { listens.remove(channel) }
                ?.invoke(value, data?.let { String(it) })
            else -> {
                val x = synchronized(lock) {
                    if (type == NativeSsh.EV_CLOSE) {
                        val stale = staleCloses[channel]
                        if (stale != null) {
                            if (stale <= 1) staleCloses.remove(channel) else staleCloses[channel] = stale - 1
                            null
                        } else {
                            handlers.remove(channel)
                        }
                    } else {
                        handlers[channel]
                    }
                } ?: return
                when (type) {
                    NativeSsh.EV_OPEN -> x.opened(value != 0, data?.let { String(it) })
                    NativeSsh.EV_DATA -> if (data != null) x.data(data, value != 0)
                    NativeSsh.EV_EOF -> x.eof()
                    NativeSsh.EV_EXIT -> x.exit(value)
                    NativeSsh.EV_CLOSE -> x.closed()
                }
            }
        }
    }

    /**
     * the next login: by [key] if there is one (and only by it), else by
     * password (the saved one first) or the server's questions, [MAX_TRIES]
     * times at most
     */
    private fun login(c: Long, methods: Int, failed: String) {
        authStarted = true
        if (key != null) {
            if (!triedKey && methods and NativeSsh.AUTH_PUBLICKEY != 0) {
                triedKey = true
                if (NativeSsh.authKey(c, key, null) == 0) return
                why = "authentication failed: the key can't be read"
            }
            return NativeSsh.authCancel(c)
        }
        val password = methods and NativeSsh.AUTH_PASSWORD != 0
        val interactive = methods and NativeSsh.AUTH_INTERACTIVE != 0
        if (password && saved != null) {
            val p = saved!!
            saved = null
            return NativeSsh.authPassword(c, p)
        }
        if (++tries > MAX_TRIES) return NativeSsh.authCancel(c)
        when {
            password -> {
                val a = ask(Prompt.Password("Password", "$user@$host", canRemember = true), this)
                val p = a.text ?: return NativeSsh.authCancel(c)
                if (a.remember) keepPassword(p)
                NativeSsh.authPassword(c, p)
            }
            interactive -> NativeSsh.authInteractive(c)
            else -> NativeSsh.authCancel(c)
        }
    }

    /** a round of the server's questions put to the user ([name] the round's title), or answered by the saved password */
    private fun questions(c: Long, count: Int, name: String) {
        if (count == 0) return NativeSsh.answered(c)
        val echo = BooleanArray(1)
        val qs = (0 until count).map { NativeSsh.prompt(c, it, echo) to echo[0] }
        val instruction = NativeSsh.instruction(c)
        // (a password asked as a question: the saved one, if there is one)
        val s = saved
        if (s != null && count == 1 && !qs[0].second) {
            saved = null
            NativeSsh.answer(c, 0, s)
            return NativeSsh.answered(c)
        }
        val a = ask(Prompt.Questions(name.ifEmpty { "$user@$host" }, instruction, qs), this)
        val texts = a.texts ?: return NativeSsh.authCancel(c)
        texts.forEachIndexed { i, t -> NativeSsh.answer(c, i, t) }
        NativeSsh.answered(c)
    }

    /** logged in: the server's banner shown, the forwards started, the shell asked for (with the agent, when it's forwarded) */
    private fun ready(c: Long, banner: ByteArray?) {
        synchronized(lock) { ready = true }
        if (banner != null && banner.isNotEmpty()) {
            val b = String(banner).replace("\r\n", "\n").replace("\n", "\r\n").toByteArray()
            for (l in listeners) l.onData(b, b.size)
        }
        val f = Forwards(this, _forwards, ::event)
        fwd = f
        for (spec in forwardSpecs) f.start(spec)
        val sh = object : ChannelHandler {
            /** the shell open, or refused: the link with it */
            override fun opened(ok: Boolean, why: String?) {
                if (ok) {
                    _state.value = TerminalLink.State.Open
                } else {
                    this@SshLink.why = "the server refused the shell" + (why?.let { ": $it" } ?: "")
                    NativeSsh.disconnect(c)
                }
            }

            /** the shell's output (stderr too), to the listeners */
            override fun data(b: ByteArray, stderr: Boolean) {
                for (l in listeners) l.onData(b, b.size)
            }

            /** the shell over: so is the connection */
            override fun closed() {
                synchronized(lock) { if (h != 0L) NativeSsh.disconnect(h) }
            }
        }
        shellHandler = sh
        shell = register(sh) { NativeSsh.shell(it, term, cols, rows, agent != null) }
        if (shell < 0) {
            why = "the shell couldn't be opened"
            return NativeSsh.disconnect(c)
        }
        if (agent != null) {
            agentForwarded = true
            event("agent forwarded")
        }
    }

    /** a channel the server opened: the agent's, or a remote forward's connection; closed if it's neither */
    private fun incoming(c: Long, channel: Int, port: Int) {
        val x: ChannelHandler? = if (port == NativeSsh.IN_AGENT) {
            agent?.let { agentChannel(channel, it) }
        } else {
            fwd?.incoming(channel, port)
        }
        if (x == null) return NativeSsh.close(c, channel)
        synchronized(lock) { handlers[channel] = x }
    }

    /** an agent channel: its requests answered by an [AgentServer] of its own, off the connection's thread (a signature may ask the user) */
    private fun agentChannel(channel: Int, keys: AgentKeys): ChannelHandler {
        val server = AgentServer(keys, this, ::event)
        return object : ChannelHandler {
            /** requests, answered in order */
            override fun data(b: ByteArray, stderr: Boolean) {
                val me = this
                runCatching {
                    agentIo.execute {
                        val reply = server.feed(b)
                        if (reply == null) {
                            close(channel, me)
                        } else if (reply.isNotEmpty()) {
                            write(channel, me, reply, 0, reply.size)
                        }
                    }
                }
            }

            /** no more requests: the channel closed */
            override fun eof() {
                close(channel, this)
            }
        }
    }

    /** a channel made by [make] (given the connection) and its handler kept, under the lock: its number, or -1 */
    private fun register(x: ChannelHandler, make: (Long) -> Int): Int = synchronized(lock) {
        if (h == 0L || !ready) return -1
        val n = make(h)
        if (n > 0) {
            // (a number whose close is on its way: that close isn't this one's)
            handlers.put(n, x)?.let { old ->
                staleCloses[n] = (staleCloses[n] ?: 0) + 1
                runCatching { old.closed() }
            }
        }
        n
    }

    /** a TCP connection from the server, for a forward */
    override fun direct(host: String, port: Int, from: String, fromPort: Int, x: ChannelHandler): Int =
        register(x) { NativeSsh.direct(it, host, port, from, fromPort) }

    /** the server asked to listen, for a remote forward */
    override fun listen(address: String?, port: Int, done: (Int, String?) -> Unit): Boolean = synchronized(lock) {
        if (h == 0L || !ready) return false
        val id = NativeSsh.listen(h, address, port)
        if (id < 0) return false
        listens[id] = done
        true
    }

    /** a remote forward's listen stopped */
    override fun unlisten(address: String?, port: Int) {
        synchronized(lock) { if (h != 0L) NativeSsh.unlisten(h, address, port) }
    }

    /** data to a channel, if it's still [x]'s */
    override fun write(channel: Int, x: ChannelHandler, data: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        if (h == 0L || handlers[channel] !== x) -1 else NativeSsh.write(h, channel, data, offset, length)
    }

    /** a channel's end of data sent, if it's still [x]'s */
    override fun eof(channel: Int, x: ChannelHandler) {
        synchronized(lock) { if (h != 0L && handlers[channel] === x) NativeSsh.eof(h, channel) }
    }

    /** a channel closed, if it's still [x]'s */
    override fun close(channel: Int, x: ChannelHandler) {
        synchronized(lock) { if (h != 0L && handlers[channel] === x) NativeSsh.close(h, channel) }
    }

    /** a forward started on the open connection; false if it couldn't be (its error in [forwards]) */
    fun addForward(spec: ForwardSpec): Boolean = fwd?.start(spec) ?: false

    /** the forward for [spec] stopped */
    fun removeForward(spec: ForwardSpec) {
        fwd?.stop(spec)
    }

    /**
     * a command run on the server (beside the shell, with the agent if
     * it's forwarded): its exit status and output (stdout then stderr), or
     * null with no connection
     */
    fun exec(command: String, timeoutSec: Long = 30): Pair<Int?, String>? {
        if (_state.value !is TerminalLink.State.Open) return null
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var code: Int? = null
        val x = object : ChannelHandler {
            /** refused: done */
            override fun opened(ok: Boolean, why: String?) {
                if (!ok) err.write((why ?: "refused").toByteArray())
            }

            /** its output, kept */
            override fun data(b: ByteArray, stderr: Boolean) {
                synchronized(out) { (if (stderr) err else out).write(b) }
            }

            /** its exit status, kept */
            override fun exit(status: Int) {
                synchronized(out) { code = status }
            }

            /** over */
            override fun closed() {
                done.countDown()
            }
        }
        val ch = register(x) { NativeSsh.exec(it, command, agentForwarded) }
        if (ch < 0) return null
        if (!done.await(timeoutSec, TimeUnit.SECONDS)) close(ch, x)
        return synchronized(out) { code to (String(out.toByteArray()) + String(err.toByteArray())) }
    }

    /** the link closed, with [reason] (null: the user's doing): its questions cancelled, forwards closed; once */
    private fun closed(reason: String?) {
        if (_state.value is TerminalLink.State.Closed) return
        _state.value = TerminalLink.State.Closed(reason)
        cancelAsks(this)
        fwd?.close()
        io.shutdown()
        agentIo.shutdown()
    }

    /** [length] bytes of [data] queued to the shell, off the caller's thread; false if it isn't open */
    override fun write(data: ByteArray, offset: Int, length: Int): Boolean {
        val x = shellHandler ?: return false
        if (_state.value !is TerminalLink.State.Open) return false
        val copy = data.copyOfRange(offset, offset + length)
        return try {
            io.execute { write(shell, x, copy, 0, copy.size) }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            false
        }
    }

    /** the new size kept for the pty and sent to the server's window if the shell is open */
    override fun resize(cols: Int, rows: Int) {
        this.cols = cols
        this.rows = rows
        val x = shellHandler ?: return
        try {
            io.execute {
                synchronized(lock) { if (h != 0L && handlers[shell] === x) NativeSsh.resize(h, shell, cols, rows) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /** the connection closed and the link with it; no reason given */
    override fun close() {
        closing = true
        cancelAsks(this)
        synchronized(lock) { if (h != 0L) NativeSsh.disconnect(h) }
        if (!started) closed(null)
    }

    companion object {
        private const val MAX_TRIES = 3
        private const val MAX_EVENTS = 50
    }
}
