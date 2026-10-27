// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ssh

import com.neodesktop.dmodem.terminal.TerminalLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * An SSH connection's interactive shell: host key checked against
 * [KnownHosts], the user authenticated by a key, or by password or the
 * server's questions (keyboard-interactive) asked of the user, a pty of
 * [term] in the terminal's size; its output (stdout and stderr) to the
 * listeners, the window's size to the server as it changes. [connect]
 * starts it on a thread of its own.
 */
class SshLink(
    val host: String,
    val port: Int,
    val user: String,
    /** a key to log in with, or null for passwords */
    private val key: KeyProvider?,
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
    private val keepAliveSec: Int = 30,
    /** port forwards started once logged in */
    private val forwardSpecs: List<ForwardSpec> = emptyList(),
    /** the keys offered to the server as an agent (ssh -A); null: no agent forwarding */
    private val agent: AgentKeys? = null,
) : TerminalLink {

    private val _state = MutableStateFlow<TerminalLink.State>(TerminalLink.State.Connecting)
    override val state: StateFlow<TerminalLink.State> = _state.asStateFlow()
    private val listeners = CopyOnWriteArrayList<TerminalLink.Listener>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ssh-$host-io").apply { isDaemon = true } }

    @Volatile private var client: SSHClient? = null
    @Volatile private var shell: Session.Shell? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closing = false
    @Volatile private var fwd: Forwards? = null

    private val _forwards = MutableStateFlow<List<ForwardState>>(emptyList())
    /** the port forwards, as they run */
    val forwards: StateFlow<List<ForwardState>> = _forwards.asStateFlow()

    private val _events = MutableStateFlow<List<String>>(emptyList())
    /** what the forwards and the agent did (the latest last, at most [MAX_EVENTS]) */
    val events: StateFlow<List<String>> = _events.asStateFlow()

    /** the agent was forwarded: the server took the request */
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
        Thread({ run() }, "ssh-$host").apply { isDaemon = true }.start()
    }

    /** the connection's life on its thread: connect, log in, start forwards and the agent, open the shell and read it until it ends */
    private fun run() {
        try {
            SshSecurity.ensure()
            val c = SSHClient(DefaultConfig())
            client = c
            // (the host key's question asked as this link's: cancelled if it closes)
            c.addHostKeyVerifier(object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
                /** the host's key checked against [knownHosts], the user asked of a new or changed one */
                override fun verify(hostname: String, port: Int, key: java.security.PublicKey) =
                    knownHosts.verify(hostname, port, key, this@SshLink)

                /** the key algorithms [knownHosts] already has for the host, preferred in the handshake */
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                    knownHosts.findExistingAlgorithms(hostname, port)
            })
            c.connectTimeout = 20_000
            c.connect(host, port)
            c.connection.keepAlive.keepAliveInterval = keepAliveSec
            authenticate(c)
            val f = Forwards(c, _forwards, ::event)
            fwd = f
            for (spec in forwardSpecs) f.start(spec)
            val session = AgentSession(c.connection)
            session.open()
            if (agent != null) {
                c.connection.attach(AgentForwarder(c.connection, AgentServer(agent, this@SshLink, ::event)))
                agentForwarded = session.requestAgentForwarding()
                event(if (agentForwarded) "agent forwarded" else "the server refused agent forwarding")
            }
            session.allocatePTY(term, cols, rows, 0, 0, emptyMap())
            val sh = session.startShell()
            shell = sh
            out = sh.outputStream
            _state.value = TerminalLink.State.Open
            pump(sh.errorStream, "stderr")
            pump(sh.inputStream, null)
            closed(if (closing) null else "connection closed by $host")
        } catch (e: UserAuthException) {
            closed("authentication failed" + (e.message?.let { ": $it" } ?: ""))
        } catch (e: TransportException) {
            closed(
                if (e.message?.contains("HostKey", ignoreCase = true) == true || e.message?.contains("verify", ignoreCase = true) == true) {
                    "the host key wasn't accepted"
                } else {
                    e.message ?: "transport error"
                },
            )
        } catch (e: IOException) {
            closed(if (closing) null else (e.message ?: e.javaClass.simpleName))
        } catch (e: RuntimeException) {
            closed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** the user logged in: by [key] if there is one, else by password (the saved one first) or the server's questions */
    private fun authenticate(c: SSHClient) {
        if (key != null) {
            c.authPublickey(user, key)
            return
        }
        var tries = 0
        var saved = savedPassword
        val finder = object : PasswordFinder {
            /** the password: the saved one first, then the user's, kept if they asked; null if they cancelled */
            override fun reqPassword(resource: Resource<*>?): CharArray? {
                tries++
                saved?.let { saved = null; return it.toCharArray() }
                val a = ask(Prompt.Password("Password", "$user@$host", canRemember = true), this@SshLink)
                val p = a.text ?: return null
                if (a.remember) keepPassword(p)
                return p.toCharArray()
            }

            /** another password asked for while under [MAX_TRIES] tries */
            override fun shouldRetry(resource: Resource<*>?) = tries < MAX_TRIES
        }
        val questions = object : ChallengeResponseProvider {
            private var name = ""
            private var instruction = ""
            private var answered = false
            /** no particular submethods asked for */
            override fun getSubmethods(): List<String> = emptyList()
            /** a new round of questions: its name and instruction kept for the dialogs */
            override fun init(resource: Resource<*>?, name: String?, instruction: String?) {
                this.name = name ?: ""
                this.instruction = instruction ?: ""
                answered = false
            }

            /** the user's answer to the server's one question, or null if they cancelled */
            override fun getResponse(prompt: String?, echo: Boolean): CharArray? {
                // (one question at a time from sshj: each its own dialog)
                val a = ask(
                    Prompt.Questions(
                        name.ifEmpty { "$user@$host" }, instruction, listOf((prompt ?: "") to echo),
                    ),
                    this@SshLink,
                )
                answered = true
                return a.texts?.firstOrNull()?.toCharArray()
            }

            /** asked again only if the user didn't answer, and under [MAX_TRIES] tries */
            override fun shouldRetry(): Boolean = !answered && tries++ < MAX_TRIES
        }
        val methods: List<AuthMethod> = listOf(AuthPassword(finder), AuthKeyboardInteractive(questions))
        c.auth(user, methods)
    }

    /** [s] read to the listeners: on a thread of its own when [name]d (stderr), else on this one */
    private fun pump(s: InputStream, name: String?) {
        if (name != null) {
            Thread({ read(s) }, "ssh-$host-$name").apply { isDaemon = true }.start()
        } else {
            read(s)
        }
    }

    /** [s] to the listeners until it ends or fails */
    private fun read(s: InputStream) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                if (n > 0) for (l in listeners) l.onData(buf, n)
            }
        } catch (_: IOException) {
        }
    }

    /** a forward started on the open connection; false if it couldn't be (its error in [forwards]) */
    fun addForward(spec: ForwardSpec): Boolean = fwd?.start(spec) ?: false

    /** the forward for [spec] stopped */
    fun removeForward(spec: ForwardSpec) {
        fwd?.stop(spec)
    }

    /**
     * a command run on the server (beside the shell): its exit status and
     * output (stdout then stderr), or null with no connection
     */
    fun exec(command: String, timeoutSec: Long = 30): Pair<Int?, String>? {
        val c = client ?: return null
        if (_state.value !is TerminalLink.State.Open) return null
        AgentSession(c.connection).use { s ->
            s.open()
            // (the agent is asked for per session: this one's too)
            if (agentForwarded) s.requestAgentForwarding()
            val cmd = s.exec(command)
            val out = cmd.inputStream.readBytes()
            val err = cmd.errorStream.readBytes()
            cmd.join(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            return cmd.exitStatus to (String(out) + String(err))
        }
    }

    /** the link closed, with [reason] (null: the user's doing): its questions cancelled, forwards and connection closed; once */
    private fun closed(reason: String?) {
        if (_state.value is TerminalLink.State.Closed) return
        _state.value = TerminalLink.State.Closed(reason)
        cancelAsks(this)
        fwd?.close()
        try {
            client?.disconnect()
        } catch (_: IOException) {
        }
        io.shutdown()
    }

    /** [length] bytes of [data] queued to the shell, off the caller's thread; false if it isn't open */
    override fun write(data: ByteArray, offset: Int, length: Int): Boolean {
        val o = out ?: return false
        if (_state.value !is TerminalLink.State.Open) return false
        // (off the caller's thread: the UI's; the channel's window may make it wait)
        val copy = data.copyOfRange(offset, offset + length)
        return try {
            io.execute {
                try {
                    synchronized(o) {
                        o.write(copy)
                        o.flush()
                    }
                } catch (_: IOException) {
                }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            false
        }
    }

    /** the new size kept for the pty and sent to the server's window if the shell is open */
    override fun resize(cols: Int, rows: Int) {
        this.cols = cols
        this.rows = rows
        val sh = shell ?: return
        try {
            io.execute {
                try {
                    sh.changeWindowDimensions(cols, rows, 0, 0)
                } catch (_: Exception) {
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /** the shell closed and the link with it, on a thread of its own; no reason given */
    override fun close() {
        closing = true
        cancelAsks(this)
        Thread({
            try {
                shell?.close()
            } catch (_: Exception) {
            }
            closed(null)
        }, "ssh-$host-close").apply { isDaemon = true }.start()
    }

    companion object {
        private const val MAX_TRIES = 3
        private const val MAX_EVENTS = 50
    }
}
