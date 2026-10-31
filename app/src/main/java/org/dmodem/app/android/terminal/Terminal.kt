// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.terminal

import org.dmodem.app.terminal.BackspaceMode
import org.dmodem.app.terminal.TermCharset
import android.content.Context
import org.dmodem.app.android.NativeModem
import org.dmodem.app.android.modem.ModemService
import org.dmodem.app.android.modem.ModemState
import org.dmodem.app.android.settings.TerminalSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.dmodem.app.android.local.Linux
import org.dmodem.app.android.local.LocalShell
import org.dmodem.app.android.ssh.AgentKeys
import org.dmodem.app.android.ssh.ForwardSpec
import org.dmodem.app.android.ssh.KeystoreBox
import org.dmodem.app.android.ssh.Prompt
import org.dmodem.app.android.ssh.KnownHosts
import org.dmodem.app.android.ssh.PasswordStore
import org.dmodem.app.android.ssh.PromptBroker
import org.dmodem.app.android.ssh.SecretBox
import org.dmodem.app.android.ssh.SshKeys
import org.dmodem.app.android.ssh.SshLink
import org.dmodem.app.android.telnet.TelnetLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's terminal: its sessions (the modem's, always there, and the
 * SSH and Telnet connections opened from the directory), the one on the
 * screen, the dialing directory, the snippets, the SSH keys and known
 * hosts, and the questions the connections ask the user.
 */
@android.annotation.SuppressLint("StaticFieldLeak")
object Terminal {
    /** the modem's session (on its DTE) */
    lateinit var session: TerminalSession
        private set
    lateinit var directory: JsonListStore<DialEntry>
        private set
    lateinit var snippets: JsonListStore<Snippet>
        private set
    lateinit var keys: SshKeys
        private set
    lateinit var knownHosts: KnownHosts
        private set
    lateinit var passwords: PasswordStore
        private set
    val prompts = PromptBroker()

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    /** the sessions, the modem's first */
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()
    private val _active = MutableStateFlow<TerminalSession?>(null)
    /** the session on the screen */
    val active: StateFlow<TerminalSession?> = _active.asStateFlow()

    // (the application's context: it lives as long as the process does)
    @android.annotation.SuppressLint("StaticFieldLeak")
    private lateinit var app: Context

    /** the stores and the modem's session set up, from [context]'s application; once, at start */
    fun init(context: Context, box: SecretBox = KeystoreBox()) {
        app = context.applicationContext
        session = TerminalSession(ModemState.dte) { app.getExternalFilesDir("captures") }
        session.incoming = ::downloads
        _sessions.value = listOf(session)
        _active.value = session
        directory = JsonListStore(
            File(app.filesDir, "directory.json"), DialEntry::toJson, DialEntry::fromJson, DialEntry::id,
        )
        snippets = JsonListStore(
            File(app.filesDir, "snippets.json"), Snippet::toJson, Snippet::fromJson, Snippet::id, Snippet.DEFAULTS,
        )
        keys = SshKeys(File(app.filesDir, "ssh"), box)
        knownHosts = KnownHosts(File(app.filesDir, "ssh/known_hosts")) { p, owner -> prompts.ask(p, owner) }
        passwords = PasswordStore(File(app.filesDir, "ssh/passwords.json"), box)
    }

    /** where files a remote sends go */
    fun downloads(): org.dmodem.app.transfer.Incoming = org.dmodem.app.android.transfer.DownloadsIncoming(app)

    /** [s] put on the screen, if it is one of the sessions */
    fun select(s: TerminalSession) {
        if (s in _sessions.value) _active.value = s
    }

    /**
     * An SSH or Telnet entry opened in a new session (on the screen from
     * now): null when it is on its way, else why it can't be.
     */
    suspend fun open(context: Context, e: DialEntry): String? {
        if (!e.complete) return "the entry is missing its host, user or key"
        val t = TerminalSettingsStore(context).current()
        val (cols, rows) = synchronized(session.emulator) { session.emulator.cols to session.emulator.rows }
        val link: TerminalLink = when (e.kind) {
            EntryKind.SSH -> {
                val key = if (e.auth == SshAuth.KEY) {
                    try {
                        keys.provider(e.keyId)
                    } catch (x: Exception) {
                        return "the key can't be opened: ${x.message}"
                    }
                } else {
                    null
                }
                SshLink(
                    e.host.trim(), e.portOrDefault, e.user.trim(), key, knownHosts,
                    ask = { p, owner -> prompts.ask(p, owner) },
                    cancelAsks = { prompts.cancel(it) },
                    savedPassword = if (e.auth == SshAuth.PASSWORD) passwords.get(e.id) else null,
                    // (a password that can't be kept is still used for this login)
                    keepPassword = { runCatching { passwords.put(e.id, it) } },
                    cols = cols, rows = rows,
                    forwardSpecs = e.forwards.mapNotNull { ForwardSpec.parse(it) },
                    agent = if (e.agentForwarding) agentKeys(e) else null,
                ).also { it.connect() }
            }
            EntryKind.TELNET -> TelnetLink(
                e.host.trim(), e.portOrDefault,
                termType = if ((e.charset ?: t.charset) == TermCharset.CP437) "ANSI" else "XTERM-256COLOR",
                cols = cols, rows = rows,
            ).also { it.connect() }
            EntryKind.MODEM -> return "a modem entry is dialled, not opened"
        }
        val s = TerminalSession(
            link,
            label = e.name.ifBlank { e.address },
            kind = if (e.kind == EntryKind.SSH) TerminalSession.Kind.SSH else TerminalSession.Kind.TELNET,
            entry = e,
        ) { app.getExternalFilesDir("captures") }
        s.charset = e.charset ?: (if (e.kind == EntryKind.SSH) TermCharset.UTF8 else t.charset)
        s.keys.backspace = e.backspace ?: (if (e.kind == EntryKind.SSH) BackspaceMode.DEL else t.backspace)
        s.keys.enter = t.enter
        s.incoming = ::downloads
        _sessions.value = _sessions.value + s
        _active.value = s
        directory.put(e.copy(lastDialed = System.currentTimeMillis(), dialCount = e.dialCount + 1))
        ConnectionService.update(app)
        return null
    }

    /** the app's keys as an agent for [e]'s connection: each use asked of the user unless the entry says not to */
    private fun agentKeys(e: DialEntry) = AgentKeys(
        list = { keys.keys.value.mapNotNull { k -> runCatching { k.name to keys.keyPair(k.id) }.getOrNull() } },
        confirm = if (!e.agentConfirm) null else { name, owner ->
            prompts.ask(
                Prompt.Confirm(
                    "Sign with \"$name\"?",
                    "${e.address} asks to use the key \"$name\" through the forwarded agent: " +
                        "to log in from there to another host, or for git. Allow it only if you started that.",
                ),
                owner,
            ).accepted
        },
    )

    /** the local shell (Android's; the tests' a host's) */
    var localShell: (Context) -> LocalShell = { LocalShell(it.applicationContext.filesDir, it.applicationContext.cacheDir) }

    /** a local shell opened in a new session (on the screen from now): null, else why it can't be */
    suspend fun openLocal(context: Context): String? {
        val (cols, rows) = synchronized(session.emulator) { session.emulator.cols to session.emulator.rows }
        val link = withContext(Dispatchers.IO) { localShell(context).link(cols, rows) }
        val n = _sessions.value.count { it.kind == TerminalSession.Kind.LOCAL }
        // (the session listening before the shell starts: its first prompt not lost)
        val s = TerminalSession(link, label = if (n == 0) "Shell" else "Shell ${n + 1}", kind = TerminalSession.Kind.LOCAL) {
            app.getExternalFilesDir("captures")
        }
        if (!withContext(Dispatchers.IO) { link.start() }) {
            s.close()
            return (link.state.value as? TerminalLink.State.Closed)?.reason ?: "the shell didn't start"
        }
        s.charset = TermCharset.UTF8
        s.keys.backspace = BackspaceMode.DEL
        s.keys.enter = org.dmodem.app.terminal.EnterMode.CR
        s.incoming = ::downloads
        _sessions.value = _sessions.value + s
        _active.value = s
        ConnectionService.update(app)
        return null
    }

    /** a shell in Linux system [id] opened in a new session: null, else why it can't be */
    suspend fun openLinux(context: Context, id: String): String? {
        val (cols, rows) = synchronized(session.emulator) { session.emulator.cols to session.emulator.rows }
        val sys = Linux.system(context, id)
        val link = try {
            withContext(Dispatchers.IO) { sys.link(cols, rows) }
        } catch (e: java.io.IOException) {
            return e.message
        }
        // (the tab: the distribution's name without its version, numbered when there are more)
        val short = sys.name.replace(Regex("\\s+[0-9(].*$"), "").removeSuffix(" Linux").ifEmpty { sys.name }
        val n = _sessions.value.count { it.linuxId == id }
        // (listening before the shell starts: its first prompt not lost)
        val s = TerminalSession(
            link, label = if (n == 0) short else "$short ${n + 1}", kind = TerminalSession.Kind.LINUX, linuxId = id,
        ) {
            app.getExternalFilesDir("captures")
        }
        if (!withContext(Dispatchers.IO) { link.start() }) {
            s.close()
            return (link.state.value as? TerminalLink.State.Closed)?.reason ?: "Linux didn't start"
        }
        s.charset = TermCharset.UTF8
        s.keys.backspace = BackspaceMode.DEL
        s.keys.enter = org.dmodem.app.terminal.EnterMode.CR
        s.incoming = ::downloads
        _sessions.value = _sessions.value + s
        _active.value = s
        ConnectionService.update(app)
        return null
    }

    /** a session closed and gone (the modem's stays) */
    fun close(s: TerminalSession) {
        if (s === session) return
        s.close()
        val left = _sessions.value - s
        _sessions.value = left
        if (_active.value === s) _active.value = left.lastOrNull() ?: session
        ConnectionService.update(app)
    }

    /** a closed session's entry opened again in its place (a shell: a new one) */
    suspend fun reconnect(context: Context, s: TerminalSession): String? {
        if (s.kind == TerminalSession.Kind.LOCAL) {
            close(s)
            return openLocal(context)
        }
        if (s.kind == TerminalSession.Kind.LINUX) {
            close(s)
            return openLinux(context, s.linuxId ?: return "no Linux system")
        }
        val e = s.entry ?: return "nothing to reconnect to"
        close(s)
        return open(context, directory.items.value.firstOrNull { it.id == e.id } ?: e)
    }

    /** the sessions (network ones, shells) still open or opening: the service keeps them going */
    fun openNetworkSessions(): Int =
        _sessions.value.count { it.kind != TerminalSession.Kind.MODEM && it.link.state.value !is TerminalLink.State.Closed }

    /** the captures' folder */
    fun capturesDir(context: Context): File? = context.applicationContext.getExternalFilesDir("captures")

    /**
     * An entry dialled: the modem started if it is off, the entry's
     * character set and backspace on the session, its init commands, then
     * ATD. Null when the dial went out, else why it didn't.
     */
    suspend fun dial(context: Context, e: DialEntry): String? {
        if (e.kind != EntryKind.MODEM) return open(context, e)
        if (e.number.isBlank()) return "no number to dial"
        if (ModemState.run.value != ModemState.Run.RUNNING) {
            // (an error of an earlier start isn't this one's)
            ModemState.setError(null)
            ModemService.start(context)
            val up = withTimeoutOrNull(START_TIMEOUT_MS) {
                ModemState.run.first { it == ModemState.Run.RUNNING || (it == ModemState.Run.STOPPED && ModemState.error.value != null) }
            }
            if (up != ModemState.Run.RUNNING) return ModemState.error.value ?: "the modem didn't start"
            // (its init commands go first)
            withContext(Dispatchers.IO) { sendAndWait(null, 800) }
        }
        if (ModemState.status.value.link != NativeModem.Link.IDLE || ModemState.status.value.offhook) {
            return "the modem is in a call: hang up first"
        }
        val t = TerminalSettingsStore(context).current()
        session.charset = e.charset ?: t.charset
        session.keys.backspace = e.backspace ?: t.backspace
        for (cmd in e.initCommands.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            withContext(Dispatchers.IO) { sendAndWait(cmd + "\r", 2000) }
        }
        _active.value = session
        // (the line's dial tone first, a second of it, as a phone off hook has: the country's)
        withContext(Dispatchers.IO) { ModemState.tones.playAndWait(org.dmodem.app.android.modem.CallTones.Kind.DIAL, DIAL_TONE_MS) }
        session.send("ATD" + e.number.trim() + "\r")
        directory.put(e.copy(lastDialed = System.currentTimeMillis(), dialCount = e.dialCount + 1))
        return null
    }

    /** text sent (if any), then until the modem says OK (or ERROR), or ms go by */
    private fun sendAndWait(text: String?, ms: Long) {
        val got = CountDownLatch(1)
        val seen = StringBuilder()
        val l = TerminalLink.Listener { d, n ->
            synchronized(seen) {
                seen.append(String(d, 0, n, Charsets.ISO_8859_1))
                if (seen.contains("OK") || seen.contains("ERROR")) got.countDown()
            }
        }
        ModemState.dte.addListener(l)
        try {
            if (text != null) session.send(text)
            got.await(ms, TimeUnit.MILLISECONDS)
        } finally {
            ModemState.dte.removeListener(l)
        }
    }

    private const val START_TIMEOUT_MS = 20_000L
    /** the dial tone before an entry is dialled (ms) */
    private const val DIAL_TONE_MS = 1000
}
