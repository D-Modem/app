// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ssh

import java.io.File
import java.util.Base64

/**
 * The hosts' keys, as the user accepted them: "[host]:port type base64"
 * lines (OpenSSH's known_hosts form). A host not in it, or whose key isn't
 * the one kept, is put to the user ([ask]): accepted for this connection
 * only, or kept (in place of the old key, for a changed one).
 */
class KnownHosts(private val file: File, private val ask: (Prompt.HostKey, Any?) -> Answer) {

    /** the host as known_hosts names it: bare on port 22, else "[host]:port" */
    private fun hostPart(host: String, port: Int) = if (port == 22) host else "[$host]:$port"

    /** the file's lines, blank ones left out (none if there's no file) */
    @Synchronized
    private fun lines(): List<String> = if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    /** the keys kept for the host: (type, key in base64) */
    @Synchronized
    fun keysFor(host: String, port: Int): List<Pair<String, String>> {
        val hp = hostPart(host, port)
        return lines().mapNotNull { l ->
            val f = l.trim().split(Regex("\\s+"))
            if (f.size >= 3 && f[0].split(',').contains(hp)) f[1] to f[2] else null
        }
    }

    /** a key ([type], in base64) kept for the host, in place of a key of its type kept before */
    @Synchronized
    fun keep(host: String, port: Int, type: String, b64: String) {
        val hp = hostPart(host, port)
        val others = lines().filterNot { l ->
            val f = l.trim().split(Regex("\\s+"))
            f.size >= 2 && f[0].split(',').contains(hp) && f[1] == type
        }
        file.parentFile?.mkdirs()
        file.writeText((others + "$hp $type $b64").joinToString("\n", postfix = "\n"))
    }

    /** every key kept: (host part as the file has it, type, fingerprint) */
    @Synchronized
    fun all(): List<Triple<String, String, String>> = lines().mapNotNull { l ->
        val f = l.trim().split(Regex("\\s+"))
        if (f.size >= 3) Triple(f[0], f[1], fingerprintOf(f[2])) else null
    }

    /** one key gone: the host part and type as [all] gives them */
    @Synchronized
    fun forget(hostPart: String, type: String) {
        file.writeText(lines().filterNot { l ->
            val f = l.trim().split(Regex("\\s+"))
            f.size >= 2 && f[0] == hostPart && f[1] == type
        }.joinToString("\n", postfix = "\n"))
    }

    /** every key kept for the host gone */
    @Synchronized
    fun forget(host: String, port: Int) {
        val hp = hostPart(host, port)
        file.writeText(lines().filterNot { it.trim().split(Regex("\\s+")).firstOrNull()?.split(',')?.contains(hp) == true }
            .joinToString("\n", postfix = "\n"))
    }

    /**
     * the server's key (its OpenSSH line, "ssh-ed25519 AAAA...") checked:
     * true if it's the one kept, else as the user answers (asked as
     * [owner]'s question: the connection's), kept if they ask
     */
    fun verify(hostname: String, port: Int, keyLine: String, owner: Any? = null): Boolean {
        val f = keyLine.trim().split(Regex("\\s+"))
        if (f.size < 2) return false
        val (type, b64) = f
        val kept = keysFor(hostname, port)
        if (kept.any { it.first == type && it.second == b64 }) return true
        val old = kept.firstOrNull { it.first == type }?.second?.let { fingerprintOf(it) }
        val a = ask(
            Prompt.HostKey(
                if (old != null) "The host key has CHANGED" else "Unknown host",
                hostname, port, type, fingerprintOf(b64), old,
            ),
            owner,
        )
        if (a.accepted && a.remember) keep(hostname, port, type, b64)
        return a.accepted
    }

    /**
     * the host key algorithms to ask the server for, those of the keys kept
     * for the host first (so it shows the key we know); empty when none are
     * kept (libssh's own order)
     */
    fun hostKeyAlgorithms(hostname: String, port: Int): List<String> {
        val kept = keysFor(hostname, port).map { it.first }.distinct()
        if (kept.isEmpty()) return emptyList()
        /** a key type's signature algorithms (an RSA key's SHA-2 ones) */
        fun algs(t: String) = if (t == "ssh-rsa") listOf("rsa-sha2-512", "rsa-sha2-256") else listOf(t)
        return (kept.flatMap { algs(it) } + ALL).distinct()
    }

    /** the SHA256 fingerprint of a key kept in base64, or "(unreadable)" */
    private fun fingerprintOf(b64: String): String = try {
        KeyFormat.fingerprint(Base64.getDecoder().decode(b64))
    } catch (_: Exception) {
        "(unreadable)"
    }

    companion object {
        /** the host key algorithms libdmodemssh takes, in libssh's order */
        private val ALL = listOf(
            "ssh-ed25519", "ecdsa-sha2-nistp521", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp256",
            "rsa-sha2-512", "rsa-sha2-256",
        )
    }
}
