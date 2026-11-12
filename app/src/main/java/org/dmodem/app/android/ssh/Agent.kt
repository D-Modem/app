// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ssh

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature
import java.security.interfaces.ECPublicKey

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
                o.write(frame(handle(SshReader(msg))))
                o.flush()
            }
        } catch (_: EOFException) {
        } catch (_: IOException) {
        }
    }

    /**
     * bytes from an agent channel: each whole request in them answered, the
     * replies (length-prefixed) to send back; a part of one kept for the
     * next; null when a request is too long (the channel to close)
     */
    fun feed(data: ByteArray): ByteArray? {
        pending.write(data)
        val out = ByteArrayOutputStream()
        var buf = pending.toByteArray()
        while (buf.size >= 4) {
            val len = SshReader(buf).int()
            if (len <= 0 || len > MAX_MESSAGE) return null
            if (buf.size < 4 + len) break
            out.write(frame(handle(SshReader(buf.copyOfRange(4, 4 + len)))))
            buf = buf.copyOfRange(4 + len, buf.size)
        }
        pending.reset()
        pending.write(buf)
        return out.toByteArray()
    }

    private val pending = ByteArrayOutputStream()

    /** one request's reply */
    fun handle(b: SshReader): ByteArray = try {
        when (b.byte()) {
            REQUEST_IDENTITIES -> identities()
            SIGN_REQUEST -> sign(b.bytes(), b.bytes(), if (b.available() >= 4) b.int() else 0)
            else -> FAILURE
        }
    } catch (e: Exception) {
        log("agent: ${e.message ?: e.javaClass.simpleName}")
        FAILURE
    }

    /** the keys' list: each key's blob and name */
    private fun identities(): ByteArray {
        val ks = keys.list()
        val r = SshWriter().byte(IDENTITIES_ANSWER).uint32(ks.size.toLong())
        for ((name, kp) in ks) r.bytes(KeyFormat.blob(kp.public)).string(name)
        return r.bytes()
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
        val encoded = signature(alg, kp, data)
        log("agent: signed with $name ($alg)")
        val inner = SshWriter().string(alg).bytes(encoded).bytes()
        return SshWriter().byte(SIGN_RESPONSE).bytes(inner).bytes()
    }

    /** [data] signed by [kp] as SSH's [alg] has it: Ed25519's 64 bytes, RSA's PKCS#1, ECDSA's r and s as mpints */
    private fun signature(alg: String, kp: KeyPair, data: ByteArray): ByteArray {
        SshSecurity.ensure()
        val jca = when (alg) {
            "ssh-ed25519" -> "Ed25519"
            "ssh-rsa" -> "SHA1withRSA"
            "rsa-sha2-256" -> "SHA256withRSA"
            "rsa-sha2-512" -> "SHA512withRSA"
            else -> when ((kp.public as? ECPublicKey)?.params?.curve?.field?.fieldSize) {
                256 -> "SHA256withECDSA"
                384 -> "SHA384withECDSA"
                521 -> "SHA512withECDSA"
                else -> throw IOException("no $alg signatures")
            }
        }
        val sig = (if (jca == "Ed25519") Signature.getInstance(jca, "BC") else Signature.getInstance(jca)).run {
            initSign(kp.private)
            update(data)
            sign()
        }
        if (kp.public !is ECPublicKey) return sig
        // (DER's SEQUENCE of two INTEGERs, as SSH's two mpints)
        val r = SshReader(sig)
        /** a DER length */
        fun len(): Int {
            val b = r.byte()
            return if (b < 0x80) b else (0 until (b and 0x7f)).fold(0) { v, _ -> (v shl 8) or r.byte() }
        }
        /** a DER INTEGER */
        fun integer(): BigInteger {
            if (r.byte() != 0x02) throw IOException("not an ECDSA signature")
            return BigInteger(r.raw(len()))
        }
        if (r.byte() != 0x30) throw IOException("not an ECDSA signature")
        len()
        return SshWriter().mpint(integer()).mpint(integer()).bytes()
    }

    companion object {
        /** [msg] with its length before it */
        fun frame(msg: ByteArray): ByteArray = SshWriter().bytes(msg).bytes()

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
