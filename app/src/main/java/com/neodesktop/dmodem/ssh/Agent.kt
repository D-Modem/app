// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ssh

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannel
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannelOpener
import net.schmizz.sshj.connection.channel.forwarded.ConnectListener
import net.schmizz.sshj.signature.Signature
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.util.concurrent.TimeUnit

/**
 * An agent's keys: those the server (and the hosts reached from it) may
 * use to sign with, by name. [confirm] asks the user before each
 * signature, when the entry wants that (the key's name, and the connection
 * asking: its questions cancelled when it closes); false refuses.
 */
class AgentKeys(
    val list: () -> List<Pair<String, KeyPair>>,
    val confirm: ((keyName: String, owner: Any) -> Boolean)? = null,
)

/**
 * The SSH agent protocol's server side (draft-miller-ssh-agent), for the
 * channels the server opens back ("auth-agent@openssh.com"): the keys
 * listed, and data signed with one of them. Nothing else (no adding,
 * removing or locking): the keys are the app's.
 */
class AgentServer(private val keys: AgentKeys, private val owner: Any = Unit, private val log: (String) -> Unit = {}) {

    /** requests read from [i] and answered on [o], each length-prefixed, until the stream ends (or a message is too long) */
    fun serve(i: InputStream, o: OutputStream) {
        val d = DataInputStream(i)
        try {
            while (true) {
                val len = d.readInt()
                if (len <= 0 || len > MAX_MESSAGE) return
                val msg = ByteArray(len).also { d.readFully(it) }
                val reply = handle(Buffer.PlainBuffer(msg))
                o.write(Buffer.PlainBuffer().putUInt32(reply.size.toLong()).compactData)
                o.write(reply)
                o.flush()
            }
        } catch (_: EOFException) {
        } catch (_: IOException) {
        }
    }

    /** one request's reply */
    fun handle(b: Buffer.PlainBuffer): ByteArray = try {
        when (b.readByte().toInt()) {
            REQUEST_IDENTITIES -> identities()
            SIGN_REQUEST -> sign(b.readBytes(), b.readBytes(), if (b.available() >= 4) b.readUInt32AsInt() else 0)
            else -> FAILURE
        }
    } catch (e: Exception) {
        log("agent: ${e.message ?: e.javaClass.simpleName}")
        FAILURE
    }

    /** the keys' list: each key's blob and name */
    private fun identities(): ByteArray {
        val ks = keys.list()
        val r = Buffer.PlainBuffer().putByte(IDENTITIES_ANSWER.toByte()).putUInt32(ks.size.toLong())
        for ((name, kp) in ks) r.putBytes(KeyFormat.blob(kp.public)).putString(name)
        return r.compactData
    }

    /** [data] signed with the key whose blob is [blob] (RSA's hash as [flags] ask), once confirmed: the response, or FAILURE */
    private fun sign(blob: ByteArray, data: ByteArray, flags: Int): ByteArray {
        val (name, kp) = keys.list().firstOrNull { KeyFormat.blob(it.second.public).contentEquals(blob) } ?: return FAILURE
        if (keys.confirm?.invoke(name, owner) == false) {
            log("agent: a signature with $name refused")
            return FAILURE
        }
        val type = KeyFormat.type(kp.public)
        // RSA: the hash the server asked for (ssh-rsa's SHA-1 only when it asks for nothing else)
        val alg = when {
            type != "ssh-rsa" -> type
            flags and RSA_SHA2_512 != 0 -> "rsa-sha2-512"
            flags and RSA_SHA2_256 != 0 -> "rsa-sha2-256"
            else -> "ssh-rsa"
        }
        val sig = signature(alg)
        sig.initSign(kp.private)
        sig.update(data)
        val encoded = sig.encode(sig.sign())
        log("agent: signed with $name ($alg)")
        val inner = Buffer.PlainBuffer().putString(alg).putBytes(encoded).compactData
        return Buffer.PlainBuffer().putByte(SIGN_RESPONSE.toByte()).putBytes(inner).compactData
    }

    /** a signature of algorithm [alg], from sshj's key algorithms */
    private fun signature(alg: String): Signature {
        val f = DefaultConfig().keyAlgorithms.firstOrNull { it.name == alg } ?: throw IOException("no $alg signatures")
        return f.create().newSignature()
    }

    companion object {
        const val FAILURE_CODE = 5
        const val REQUEST_IDENTITIES = 11
        const val IDENTITIES_ANSWER = 12
        const val SIGN_REQUEST = 13
        const val SIGN_RESPONSE = 14
        const val RSA_SHA2_256 = 2
        const val RSA_SHA2_512 = 4
        private const val MAX_MESSAGE = 256 * 1024
        private val FAILURE = byteArrayOf(FAILURE_CODE.toByte())
    }
}

/** A session that can ask for the agent to be forwarded (sshj has no call for it). */
class AgentSession(conn: Connection) : SessionChannel(conn) {
    /** "auth-agent-req@openssh.com": the server to open agent channels back to us; false if it won't */
    fun requestAgentForwarding(): Boolean = try {
        sendChannelRequest("auth-agent-req@openssh.com", true, Buffer.PlainBuffer())
            .await(conn.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        true
    } catch (_: IOException) {
        false
    }
}

/** The agent channels the server opens, each served by an [AgentServer]. */
class AgentForwarder(conn: Connection, private val server: AgentServer) :
    AbstractForwardedChannelOpener(TYPE, conn) {

    private class AgentChannel(conn: Connection, recipient: Int, window: Long, maxPacket: Long) :
        AbstractForwardedChannel(conn, TYPE, recipient, window, maxPacket, "", 0)

    /** the server's agent channel opened: confirmed and served, on the listener's thread */
    override fun handleOpen(buf: SSHPacket) {
        val ch = AgentChannel(conn, buf.readUInt32AsInt(), buf.readUInt32(), buf.readUInt32())
        callListener(
            ConnectListener { c ->
                c.confirm()
                c.use { server.serve(it.inputStream, it.outputStream) }
            },
            ch,
        )
    }

    companion object {
        const val TYPE = "auth-agent@openssh.com"
    }
}
