// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CompletableFuture

/** A question a connection asks the user. */
sealed class Prompt {
    abstract val title: String

    /** the server's host key: unknown (first time), or not the one kept (changed) */
    data class HostKey(
        override val title: String,
        val host: String,
        val port: Int,
        val keyType: String,
        val fingerprint: String,
        /** the key kept for the host, when this one differs from it */
        val oldFingerprint: String?,
    ) : Prompt()

    /** a password (for the user, or a key's passphrase) */
    data class Password(
        override val title: String,
        val message: String,
        /** offer to keep it for the entry */
        val canRemember: Boolean,
    ) : Prompt()

    /** yes or no (accepted): an agent's key to be used, say */
    data class Confirm(
        override val title: String,
        val message: String,
        val yes: String = "Allow",
    ) : Prompt()

    /** keyboard-interactive: the server's own questions */
    data class Questions(
        override val title: String,
        val instruction: String,
        /** each question, and whether its answer may be shown */
        val questions: List<Pair<String, Boolean>>,
    ) : Prompt()
}

/** The user's answer: null text (or accepted false) when cancelled. */
data class Answer(
    val accepted: Boolean = false,
    val text: String? = null,
    val texts: List<String>? = null,
    /** keep it: the host key in known_hosts, the password for the entry */
    val remember: Boolean = false,
)

/**
 * Questions from the connections' threads to the UI, one at a time: a
 * connection blocks in [ask] until the UI [answer]s, or until the question
 * is [cancel]led (its connection closed). No timeout: a question may stay
 * open as long as the user leaves it (the server ends a login that takes
 * too long by itself).
 */
class PromptBroker {
    private class Pending(val prompt: Prompt, val owner: Any?, val reply: CompletableFuture<Answer>)

    private val lock = Object()
    private val _current = MutableStateFlow<Prompt?>(null)
    /** the question waiting for the user, if any */
    val current: StateFlow<Prompt?> = _current.asStateFlow()
    private var pending: Pending? = null
    /** owners whose questions are cancelled (their connections closed) */
    private val cancelled = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

    /** asked and waited for (on a connection's thread, never the UI's); [owner]: the connection asking */
    fun ask(p: Prompt, owner: Any? = null): Answer {
        val reply = CompletableFuture<Answer>()
        synchronized(lock) {
            while (pending != null) {
                if (owner != null && owner in cancelled) return Answer()
                lock.wait()
            }
            if (owner != null && owner in cancelled) return Answer()
            pending = Pending(p, owner, reply)
            _current.value = p
        }
        return try {
            reply.get()
        } catch (_: Exception) {
            Answer()
        } finally {
            synchronized(lock) {
                if (pending?.reply === reply) {
                    pending = null
                    _current.value = null
                }
                lock.notifyAll()
            }
        }
    }

    /**
     * the UI's answer to question [p]: only while it is the one asked (a
     * dialog's late dismissal can't answer the question after it)
     */
    fun answer(p: Prompt, a: Answer) {
        synchronized(lock) {
            val q = pending ?: return
            if (q.prompt === p) q.reply.complete(a)
        }
    }

    /** [owner]'s questions cancelled, the one asked now and those to come */
    fun cancel(owner: Any) {
        synchronized(lock) {
            cancelled.add(owner)
            pending?.takeIf { it.owner === owner }?.reply?.complete(Answer())
            lock.notifyAll()
        }
    }
}
