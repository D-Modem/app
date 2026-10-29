// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.PublicKey
import java.util.Base64

/**
 * The hosts' keys, as the user accepted them: "[host]:port type base64"
 * lines (OpenSSH's known_hosts form). A host not in it, or whose key isn't
 * the one kept, is put to the user ([ask]): accepted for this connection
 * only, or kept (in place of the old key, for a changed one).
 */
class KnownHosts(private val file: File, private val ask: (Prompt.HostKey, Any?) -> Answer) : HostKeyVerifier {


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

    /** [key] kept for the host, in place of a key of its type kept before */
    @Synchronized
    fun keep(host: String, port: Int, key: PublicKey) {
        val hp = hostPart(host, port)
        val type = KeyFormat.type(key)
        val others = lines().filterNot { l ->
            val f = l.trim().split(Regex("\\s+"))
            f.size >= 2 && f[0].split(',').contains(hp) && f[1] == type
        }
        file.parentFile?.mkdirs()
        file.writeText((others + "$hp $type ${Base64.getEncoder().encodeToString(KeyFormat.blob(key))}").joinToString("\n", postfix = "\n"))
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

    /** [verify] with no owner for the question */
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = verify(hostname, port, key, null)

    /** verify(), the question (if any) asked as [owner]'s: the connection's */
    fun verify(hostname: String, port: Int, key: PublicKey, owner: Any?): Boolean {
        val type = KeyFormat.type(key)
        val b64 = Base64.getEncoder().encodeToString(KeyFormat.blob(key))
        val kept = keysFor(hostname, port)
        if (kept.any { it.first == type && it.second == b64 }) return true
        val old = kept.firstOrNull { it.first == type }?.second?.let { fingerprintOf(it) }
        val a = ask(
            Prompt.HostKey(
                if (old != null) "The host key has CHANGED" else "Unknown host",
                hostname, port, type, KeyFormat.fingerprint(key), old,
            ),
            owner,
        )
        if (a.accepted && a.remember) keep(hostname, port, key)
        return a.accepted
    }

    /** the key types kept for the host, first (so the server is asked for the key we know) */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        keysFor(hostname, port).map { it.first }.distinct()

    /** the SHA256 fingerprint of a key kept in base64, or "(unreadable)" */
    private fun fingerprintOf(b64: String): String = try {
        val key = Buffer.PlainBuffer(Base64.getDecoder().decode(b64)).readPublicKey()
        KeyFormat.fingerprint(key)
    } catch (_: Exception) {
        "(unreadable)"
    }
}
