// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ssh

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.IOException
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PuTTY's private key files (.ppk), as PuTTYgen writes them: version 3
 * (Argon2id, AES-256-CBC, HMAC-SHA-256; PuTTY 0.75 and on) or version 2
 * (SHA-1, for older PuTTY and the tools that only read it, WinSCP's older
 * releases among them); and read, of either version.
 */
object Ppk {

    /** PuTTYgen's Argon2 defaults: 8 MiB, one lane; passes enough for a moment's work */
    data class Argon(val memoryKiB: Int = 8192, val passes: Int = 13, val parallelism: Int = 1)

    /** whether [text] is a .ppk (its first line PuTTY's) */
    fun isPpk(text: String) = text.trimStart().startsWith("PuTTY-User-Key-File-")

    /** the "Comment:" a .ppk carries, if any */
    fun comment(text: String): String? =
        text.lineSequence().firstOrNull { it.startsWith("Comment: ") }?.removePrefix("Comment: ")?.trim()

    /** the key as a .ppk of [version] 3 or 2, [comment] in it, encrypted with [passphrase] unless it's empty (v3's key from Argon2id with [argon]) */
    fun write(kp: KeyPair, comment: String, passphrase: String = "", version: Int = 3, argon: Argon = Argon()): String {
        require(version == 2 || version == 3) { "PPK versions 2 and 3 only" }
        val alg = KeyFormat.type(kp.public)
        val encrypted = passphrase.isNotEmpty()
        val encryption = if (encrypted) "aes256-cbc" else "none"
        val pub = KeyFormat.blob(kp.public)
        var priv = privateBlob(kp)
        if (encrypted) {
            // padded to the cipher's block, as PuTTY pads it (with the start of the blob's SHA-1)
            val pad = (16 - priv.size % 16) % 16
            priv += MessageDigest.getInstance("SHA-1").digest(priv).copyOf(pad)
        }

        val headers = StringBuilder()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        if (version == 3 && encrypted) {
            headers.append("Key-Derivation: Argon2id\n")
            headers.append("Argon2-Memory: ${argon.memoryKiB}\n")
            headers.append("Argon2-Passes: ${argon.passes}\n")
            headers.append("Argon2-Parallelism: ${argon.parallelism}\n")
            headers.append("Argon2-Salt: ${hex(salt)}\n")
        }
        val (cipherKey, iv, macKey) = keys(version, passphrase, Argon2Parameters.ARGON2_id, argon, salt)

        val mac = mac(version, macKey, alg, encryption, comment, pub, priv)
        val privOut = if (encrypted) {
            Cipher.getInstance("AES/CBC/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(iv))
                doFinal(priv)
            }
        } else {
            priv
        }

        val sb = StringBuilder()
        sb.append("PuTTY-User-Key-File-$version: $alg\n")
        sb.append("Encryption: $encryption\n")
        sb.append("Comment: $comment\n")
        lines(pub).let { sb.append("Public-Lines: ${it.size}\n"); it.forEach { l -> sb.append(l).append('\n') } }
        sb.append(headers)
        lines(privOut).let { sb.append("Private-Lines: ${it.size}\n"); it.forEach { l -> sb.append(l).append('\n') } }
        sb.append("Private-MAC: ${hex(mac)}\n")
        return sb.toString()
    }

    /**
     * the file's cipher key, IV and MAC key from [passphrase] (empty: none):
     * v3's from Argon2 of [argonType] with [argon] and [salt], v2's by SHA-1
     */
    private fun keys(version: Int, passphrase: String, argonType: Int, argon: Argon, salt: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val p = passphrase.toByteArray()
        if (version == 3) {
            if (passphrase.isEmpty()) return Triple(ByteArray(0), ByteArray(0), ByteArray(0))
            val out = ByteArray(80)
            Argon2BytesGenerator().apply {
                init(
                    Argon2Parameters.Builder(argonType)
                        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                        .withMemoryAsKB(argon.memoryKiB).withIterations(argon.passes)
                        .withParallelism(argon.parallelism).withSalt(salt).build(),
                )
            }.generateBytes(p, out)
            return Triple(out.copyOfRange(0, 32), out.copyOfRange(32, 48), out.copyOfRange(48, 80))
        }
        /** the SHA-1 of [parts] one after another */
        fun sha1(vararg parts: ByteArray) = MessageDigest.getInstance("SHA-1").run { parts.forEach { update(it) }; digest() }
        return Triple(
            (sha1(byteArrayOf(0, 0, 0, 0), p) + sha1(byteArrayOf(0, 0, 0, 1), p)).copyOf(32),
            ByteArray(16),
            sha1("putty-private-key-file-mac-key".toByteArray(), p),
        )
    }

    /** the file's MAC: over its type, encryption, comment and both blobs (the private one decrypted, padded) */
    private fun mac(version: Int, macKey: ByteArray, alg: String, encryption: String, comment: String, pub: ByteArray, priv: ByteArray): ByteArray {
        val data = SshWriter().string(alg).string(encryption).string(comment).bytes(pub).bytes(priv).bytes()
        val macAlg = if (version == 3) "HmacSHA256" else "HmacSHA1"
        return Mac.getInstance(macAlg).run {
            // (an empty key, for an unencrypted v3 file: HMAC's own zero-length key)
            init(if (macKey.isEmpty()) EmptyKey(macAlg) else SecretKeySpec(macKey, macAlg))
            doFinal(data)
        }
    }

    /**
     * A .ppk read (version 2 or 3), opened with [passphrase] if it's
     * encrypted: the key and its comment. Throws if it can't be (a wrong
     * passphrase among the reasons: its MAC then differs).
     */
    fun read(text: String, passphrase: String?): Pair<KeyPair, String> {
        val lines = text.lines().map { it.trimEnd('\r') }
        val head = mutableMapOf<String, String>()
        var pub = ByteArray(0)
        var privData = ByteArray(0)
        var i = 0
        /** the next [n] lines as base64 */
        fun block(n: Int) = Base64.getDecoder().decode(lines.subList(i, i + n).joinToString("")).also { i += n }
        while (i < lines.size) {
            val l = lines[i++]
            val colon = l.indexOf(": ")
            if (colon < 0) continue
            val k = l.substring(0, colon)
            val v = l.substring(colon + 2)
            when (k) {
                "Public-Lines" -> pub = block(v.trim().toInt())
                "Private-Lines" -> privData = block(v.trim().toInt())
                else -> head[k] = v
            }
        }
        val first = head.keys.firstOrNull { it.startsWith("PuTTY-User-Key-File-") } ?: throw IOException("not a PuTTY key")
        val version = first.removePrefix("PuTTY-User-Key-File-").toInt()
        if (version != 2 && version != 3) throw IOException("PuTTY key files of version $version can't be read")
        val alg = head.getValue(first).trim()
        val encryption = head["Encryption"]?.trim() ?: "none"
        val comment = head["Comment"] ?: ""
        val encrypted = when (encryption) {
            "none" -> false
            "aes256-cbc" -> true
            else -> throw IOException("PuTTY's $encryption encryption can't be read")
        }
        val pass = if (encrypted) passphrase?.takeIf { it.isNotEmpty() } ?: throw IOException("the key needs its passphrase") else ""
        val argonType = when (head["Key-Derivation"]?.trim()) {
            "Argon2d" -> Argon2Parameters.ARGON2_d
            "Argon2i" -> Argon2Parameters.ARGON2_i
            else -> Argon2Parameters.ARGON2_id
        }
        val argon = Argon(
            head["Argon2-Memory"]?.trim()?.toInt() ?: 0,
            head["Argon2-Passes"]?.trim()?.toInt() ?: 0,
            head["Argon2-Parallelism"]?.trim()?.toInt() ?: 0,
        )
        val salt = head["Argon2-Salt"]?.trim()?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray() ?: ByteArray(0)
        val (cipherKey, iv, macKey) = keys(version, pass, argonType, argon, salt)
        val priv = if (encrypted) {
            Cipher.getInstance("AES/CBC/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(iv))
                doFinal(privData)
            }
        } else {
            privData
        }
        val want = head["Private-MAC"]?.trim() ?: throw IOException("the key has no MAC")
        if (!MessageDigest.isEqual(hex(mac(version, macKey, alg, encryption, comment, pub, priv)).toByteArray(), want.lowercase().toByteArray())) {
            throw IOException(if (encrypted) "the passphrase is wrong" else "the key's MAC is wrong")
        }
        val public = KeyFormat.fromBlob(pub)
        val r = SshReader(priv)
        val private = when (public) {
            is java.security.interfaces.RSAPublicKey -> {
                val d = r.mpint()
                val p = r.mpint()
                val q = r.mpint()
                KeyGen.rsaPrivate(public.modulus, public.publicExponent, d, p, q)
            }
            is java.security.interfaces.ECPublicKey ->
                KeyGen.ecPrivate(KeyFormat.curve(public.params.curve.field.fieldSize).first, r.mpint())
            else -> KeyGen.ed25519Private(r.bytes())
        }
        return KeyPair(public, private) to comment
    }

    /** an HMAC key of no bytes (SecretKeySpec won't make one) */
    private class EmptyKey(private val alg: String) : javax.crypto.SecretKey {
        /** the key's algorithm, the MAC's */
        override fun getAlgorithm() = alg
        /** raw */
        override fun getFormat() = "RAW"
        /** no bytes */
        override fun getEncoded() = ByteArray(0)
    }

    /** the private half's fields as PuTTY's private blob has them (RSA's d, p, q, iqmp; ECDSA's s; Ed25519's seed) */
    private fun privateBlob(kp: KeyPair): ByteArray {
        val b = SshWriter()
        when (val p = kp.private) {
            is RSAPrivateCrtKey -> b.mpint(p.privateExponent).mpint(p.primeP).mpint(p.primeQ).mpint(p.crtCoefficient)
            is ECPrivateKey -> b.mpint(p.s)
            else -> b.bytes(KeyGen.ed25519Seed(p))
        }
        return b.bytes()
    }

    /** [data] in base64, in lines of 64 */
    private fun lines(data: ByteArray) = Base64.getEncoder().encodeToString(data).chunked(64)

    /** [b] in lower-case hex */
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
