// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** How an entry is reached. */
enum class EntryKind(val label: String) { MODEM("Modem (dial)"), SSH("SSH"), TELNET("Telnet") }

/** How an SSH entry logs in. */
enum class SshAuth(val label: String) { PASSWORD("Password"), KEY("Key") }

/** A system to reach: a BBS, a host, an ISP; by the modem, SSH or Telnet. */
data class DialEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val kind: EntryKind = EntryKind.MODEM,
    /** what ATD dials: a number, or a SIP user (MODEM) */
    val number: String = "",
    /** the host, its port and the user (SSH, TELNET) */
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
    val auth: SshAuth = SshAuth.PASSWORD,
    /** the key (SshKeys' id) with [SshAuth.KEY] */
    val keyId: String = "",
    /** AT commands before the dial (AT+MS=..., AT&K...), empty: none */
    val initCommands: String = "",
    /** the entry's character set; null: the terminal's */
    val charset: TermCharset? = null,
    /** the entry's backspace; null: the terminal's */
    val backspace: BackspaceMode? = null,
    val notes: String = "",
    val lastDialed: Long = 0,
    val dialCount: Int = 0,
    /** port forwards started with the connection (SSH): ForwardSpec's lines, "L 8080:localhost:80" */
    val forwards: List<String> = emptyList(),
    /** the app's keys offered to the server as an agent (ssh -A) */
    val agentForwarding: Boolean = false,
    /** each use of the agent asked of the user first */
    val agentConfirm: Boolean = true,
) {
    /** the port to connect to: the entry's, or its kind's */
    val portOrDefault: Int get() = if (port > 0) port else if (kind == EntryKind.TELNET) 23 else 22

    /** what it is reached at, as one line */
    val address: String get() = when (kind) {
        EntryKind.MODEM -> number
        EntryKind.SSH -> (if (user.isNotEmpty()) "$user@" else "") + host + (if (port > 0 && port != 22) ":$port" else "")
        EntryKind.TELNET -> host + (if (port > 0 && port != 23) ":$port" else "")
    }

    /** enough to reach it */
    val complete: Boolean get() = when (kind) {
        EntryKind.MODEM -> number.isNotBlank()
        EntryKind.SSH -> host.isNotBlank() && user.isNotBlank() && (auth == SshAuth.PASSWORD || keyId.isNotEmpty())
        EntryKind.TELNET -> host.isNotBlank()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", kind.name).put("number", number)
        .put("host", host).put("port", port).put("user", user).put("auth", auth.name).put("keyId", keyId)
        .put("init", initCommands).put("charset", charset?.name ?: "")
        .put("backspace", backspace?.name ?: "").put("notes", notes)
        .put("lastDialed", lastDialed).put("dialCount", dialCount)
        .put("forwards", org.json.JSONArray(forwards)).put("agent", agentForwarding).put("agentConfirm", agentConfirm)

    companion object {
        fun fromJson(o: JSONObject) = DialEntry(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            name = o.optString("name"),
            kind = runCatching { EntryKind.valueOf(o.optString("kind")) }.getOrDefault(EntryKind.MODEM),
            number = o.optString("number"),
            host = o.optString("host"),
            port = o.optInt("port"),
            user = o.optString("user"),
            auth = runCatching { SshAuth.valueOf(o.optString("auth")) }.getOrDefault(SshAuth.PASSWORD),
            keyId = o.optString("keyId"),
            initCommands = o.optString("init"),
            charset = o.optString("charset").takeIf { it.isNotEmpty() }
                ?.let { runCatching { TermCharset.valueOf(it) }.getOrNull() },
            backspace = o.optString("backspace").takeIf { it.isNotEmpty() }
                ?.let { runCatching { BackspaceMode.valueOf(it) }.getOrNull() },
            notes = o.optString("notes"),
            lastDialed = o.optLong("lastDialed"),
            dialCount = o.optInt("dialCount"),
            forwards = o.optJSONArray("forwards")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            agentForwarding = o.optBoolean("agent"),
            agentConfirm = o.optBoolean("agentConfirm", true),
        )
    }
}

/** Text to send with a tap: commands, logins, menu keys. */
data class Snippet(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** with escapes: \r \n \t \e \\ \xHH, ^A..^_ as ^A */
    val text: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("text", text)

    companion object {
        fun fromJson(o: JSONObject) = Snippet(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            name = o.optString("name"),
            text = o.optString("text"),
        )

        /** the text with its escapes made the characters they stand for */
        fun unescape(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    val n = s[i + 1]
                    i += 2
                    when (n) {
                        'r' -> sb.append('\r')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'e' -> sb.append('\u001b')
                        '\\' -> sb.append('\\')
                        '^' -> sb.append('^')
                        'x' -> {
                            val hex = s.substring(i, minOf(i + 2, s.length))
                            val v = hex.toIntOrNull(16)
                            if (v != null && hex.length == 2) {
                                sb.append(v.toChar()); i += 2
                            } else {
                                sb.append("\\x")
                            }
                        }
                        else -> sb.append('\\').append(n)
                    }
                    continue
                }
                if (c == '^' && i + 1 < s.length && s[i + 1] in '@'..'_') {
                    sb.append((s[i + 1].code - '@'.code).toChar())
                    i += 2
                    continue
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        val DEFAULTS = listOf(
            Snippet(name = "Hang up", text = "ATH\\r"),
            Snippet(name = "Last call's details", text = "ATI11\\r"),
            Snippet(name = "Modem info", text = "ATI\\r"),
            Snippet(name = "V.92 and below (default)", text = "AT+MS=92\\r"),
            Snippet(name = "V.34 only", text = "AT+MS=34\\r"),
            Snippet(name = "V.32bis, 4800-14400", text = "AT+MS=132,0,4800,14400\\r"),
            Snippet(name = "Ctrl-C", text = "^C"),
        )
    }
}

/** A list kept in a JSON file. */
class JsonListStore<T>(
    private val file: File,
    private val toJson: (T) -> JSONObject,
    private val fromJson: (JSONObject) -> T,
    private val id: (T) -> String,
    defaults: List<T> = emptyList(),
) {
    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    init {
        _items.value = load() ?: defaults.also { save(it) }
    }

    private fun load(): List<T>? = try {
        if (!file.exists()) {
            null
        } else {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }
    } catch (_: Exception) {
        null
    }

    @Synchronized
    private fun save(list: List<T>) {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(a.toString(2))
        tmp.renameTo(file)
        _items.value = list
    }

    /** item added, or put in place of the one with its id */
    fun put(item: T) {
        val l = _items.value.toMutableList()
        val i = l.indexOfFirst { id(it) == id(item) }
        if (i >= 0) l[i] = item else l.add(item)
        save(l)
    }

    fun remove(item: T) {
        save(_items.value.filter { id(it) != id(item) })
    }

    fun move(from: Int, to: Int) {
        val l = _items.value.toMutableList()
        if (from !in l.indices || to !in l.indices) return
        l.add(to, l.removeAt(from))
        save(l)
    }
}
