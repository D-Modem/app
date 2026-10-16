// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ssh

import net.schmizz.sshj.common.Buffer
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
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
 * releases among them). Read back by sshj's PuTTYKeyFile.
 */
object Ppk {

    /** PuTTYgen's Argon2 defaults: 8 MiB, one lane; passes enough for a moment's work */
    data class Argon(val memoryKiB: Int = 8192, val passes: Int = 13, val parallelism: Int = 1)

    fun isPpk(text: String) = text.trimStart().startsWith("PuTTY-User-Key-File-")

    /** the "Comment:" a .ppk carries, if any */
    fun comment(text: String): String? =
        text.lineSequence().firstOrNull { it.startsWith("Comment: ") }?.removePrefix("Comment: ")?.trim()

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
        val cipherKey: ByteArray
        val iv: ByteArray
        val macKey: ByteArray
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        if (version == 3) {
            if (encrypted) {
                val out = ByteArray(80)
                Argon2BytesGenerator().apply {
                    init(
                        Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                            .withMemoryAsKB(argon.memoryKiB).withIterations(argon.passes)
                            .withParallelism(argon.parallelism).withSalt(salt).build(),
                    )
                }.generateBytes(passphrase.toByteArray(), out)
                cipherKey = out.copyOfRange(0, 32)
                iv = out.copyOfRange(32, 48)
                macKey = out.copyOfRange(48, 80)
                headers.append("Key-Derivation: Argon2id\n")
                headers.append("Argon2-Memory: ${argon.memoryKiB}\n")
                headers.append("Argon2-Passes: ${argon.passes}\n")
                headers.append("Argon2-Parallelism: ${argon.parallelism}\n")
                headers.append("Argon2-Salt: ${hex(salt)}\n")
            } else {
                cipherKey = ByteArray(0); iv = ByteArray(0); macKey = ByteArray(0)
            }
        } else {
            fun sha1(vararg parts: ByteArray) = MessageDigest.getInstance("SHA-1").run { parts.forEach { update(it) }; digest() }
            val p = passphrase.toByteArray()
            cipherKey = (sha1(byteArrayOf(0, 0, 0, 0), p) + sha1(byteArrayOf(0, 0, 0, 1), p)).copyOf(32)
            iv = ByteArray(16)
            macKey = sha1("putty-private-key-file-mac-key".toByteArray(), if (encrypted) p else ByteArray(0))
        }

        val macData = Buffer.PlainBuffer().putString(alg).putString(encryption).putString(comment)
            .putBytes(pub).putBytes(priv).compactData
        val macAlg = if (version == 3) "HmacSHA256" else "HmacSHA1"
        val mac = Mac.getInstance(macAlg).run {
            // (an empty key, for an unencrypted v3 file: HMAC's own zero-length key)
            init(if (macKey.isEmpty()) EmptyKey(macAlg) else SecretKeySpec(macKey, macAlg))
            doFinal(macData)
        }
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

    /** an HMAC key of no bytes (SecretKeySpec won't make one) */
    private class EmptyKey(private val alg: String) : javax.crypto.SecretKey {
        override fun getAlgorithm() = alg
        override fun getFormat() = "RAW"
        override fun getEncoded() = ByteArray(0)
    }

    private fun privateBlob(kp: KeyPair): ByteArray {
        val b = Buffer.PlainBuffer()
        when (val p = kp.private) {
            is RSAPrivateCrtKey -> b.putMPInt(p.privateExponent).putMPInt(p.primeP).putMPInt(p.primeQ).putMPInt(p.crtCoefficient)
            is ECPrivateKey -> b.putMPInt(p.s)
            else -> b.putBytes((PrivateKeyFactory.createKey(p.encoded) as Ed25519PrivateKeyParameters).encoded)
        }
        return b.compactData
    }

    private fun lines(data: ByteArray) = Base64.getEncoder().encodeToString(data).chunked(64)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
