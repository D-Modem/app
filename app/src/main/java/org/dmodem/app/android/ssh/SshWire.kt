// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ssh

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** SSH's wire encoding (RFC 4251): what's put, in order; [bytes] once done. */
class SshWriter {
    private val out = ByteArrayOutputStream()

    /** one byte */
    fun byte(b: Int) = apply { out.write(b) }

    /** a uint32, big-endian */
    fun uint32(v: Long) = apply {
        out.write((v ushr 24).toInt() and 0xff)
        out.write((v ushr 16).toInt() and 0xff)
        out.write((v ushr 8).toInt() and 0xff)
        out.write(v.toInt() and 0xff)
    }

    /** bytes as they are, with no length */
    fun raw(b: ByteArray) = apply { out.write(b) }

    /** a string: its length, then its bytes */
    fun bytes(b: ByteArray) = apply { uint32(b.size.toLong()); out.write(b) }

    /** a string of text, in UTF-8 */
    fun string(s: String) = bytes(s.toByteArray())

    /** an mpint: two's complement, big-endian, as short as it goes */
    fun mpint(v: BigInteger) = bytes(if (v.signum() == 0) ByteArray(0) else v.toByteArray())

    /** what's been put so far */
    fun size() = out.size()

    /** what's been put */
    fun bytes(): ByteArray = out.toByteArray()
}

/** SSH's wire encoding read back, from [b]: each read throws at its end. */
class SshReader(private val b: ByteArray) {
    private var at = 0

    /** what's left to read */
    fun available() = b.size - at

    /** [n] bytes, as they are */
    fun raw(n: Int): ByteArray {
        if (n < 0 || n > available()) throw IOException("the SSH data ends early")
        return b.copyOfRange(at, at + n).also { at += n }
    }

    /** one byte, 0 to 255 */
    fun byte(): Int = raw(1)[0].toInt() and 0xff

    /** a uint32 */
    fun uint32(): Long = raw(4).fold(0L) { v, x -> (v shl 8) or (x.toLong() and 0xff) }

    /** a uint32 that fits an Int */
    fun int(): Int = uint32().also { if (it > Int.MAX_VALUE) throw IOException("an SSH length too large") }.toInt()

    /** a string's bytes */
    fun bytes(): ByteArray = raw(int())

    /** a string of text */
    fun string(): String = String(bytes())

    /** an mpint */
    fun mpint(): BigInteger = bytes().let { if (it.isEmpty()) BigInteger.ZERO else BigInteger(it) }
}

/**
 * Public keys in SSH's form (RSA, ECDSA on NIST P-256/384/521, Ed25519),
 * to and from JCA's; their authorized_keys lines and fingerprints
 * (SHA256:..., as ssh-keygen -l).
 */
object KeyFormat {
    /** X.509's prefix of an Ed25519 key, before its 32 bytes */
    private val ED25519_X509 = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    /** the NIST curve of [bits]: its SSH name and JCA's */
    fun curve(bits: Int): Pair<String, String> = when (bits) {
        256 -> "nistp256" to "secp256r1"
        384 -> "nistp384" to "secp384r1"
        521 -> "nistp521" to "secp521r1"
        else -> throw IOException("no ECDSA on a $bits-bit curve")
    }

    /** the curve's parameters, by its JCA name */
    fun ecParams(jcaName: String): ECParameterSpec =
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec(jcaName))
            getParameterSpec(ECParameterSpec::class.java)
        }

    /** the key's SSH type: ssh-ed25519, ecdsa-sha2-nistp256, ssh-rsa... */
    fun type(k: PublicKey): String = when (k) {
        is RSAPublicKey -> "ssh-rsa"
        is ECPublicKey -> "ecdsa-sha2-" + curve(k.params.curve.field.fieldSize).first
        else -> if (k.algorithm.equals("Ed25519", true) || k.algorithm.equals("EdDSA", true)) "ssh-ed25519" else throw IOException("no SSH form for ${k.algorithm} keys")
    }

    /** an Ed25519 key's 32 bytes */
    fun ed25519(k: PublicKey): ByteArray = k.encoded.let { it.copyOfRange(it.size - 32, it.size) }

    /** an EC point, uncompressed, as SSH has it */
    fun point(k: ECPublicKey): ByteArray {
        val n = (k.params.curve.field.fieldSize + 7) / 8
        /** [v] in exactly n bytes */
        fun fixed(v: BigInteger): ByteArray {
            val b = v.toByteArray().let { if (it.size > n) it.copyOfRange(it.size - n, it.size) else it }
            return ByteArray(n - b.size) + b
        }
        return byteArrayOf(4) + fixed(k.w.affineX) + fixed(k.w.affineY)
    }

    /** the key in SSH's wire form (its type, then its fields) */
    fun blob(k: PublicKey): ByteArray {
        val w = SshWriter().string(type(k))
        when (k) {
            is RSAPublicKey -> w.mpint(k.publicExponent).mpint(k.modulus)
            is ECPublicKey -> w.string(curve(k.params.curve.field.fieldSize).first).bytes(point(k))
            else -> w.bytes(ed25519(k))
        }
        return w.bytes()
    }

    /** the key's authorized_keys line: its type, its blob in base64, and [comment] if any */
    fun openSsh(k: PublicKey, comment: String = ""): String =
        type(k) + " " + Base64.getEncoder().encodeToString(blob(k)) + if (comment.isNotEmpty()) " $comment" else ""

    /** the key's fingerprint, SHA256: and its blob's digest in unpadded base64 */
    fun fingerprint(k: PublicKey): String = fingerprint(blob(k))

    /** a blob's fingerprint */
    fun fingerprint(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    /** a public key from its blob */
    fun fromBlob(blob: ByteArray): PublicKey = read(SshReader(blob))

    /** a public key read from [r]: its type, then its fields */
    fun read(r: SshReader): PublicKey {
        val type = r.string()
        return when {
            type == "ssh-rsa" -> {
                val e = r.mpint()
                val n = r.mpint()
                KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
            }
            type.startsWith("ecdsa-sha2-") -> ecPublic(r.string(), r.bytes())
            type == "ssh-ed25519" -> ed25519Public(r.bytes())
            else -> throw IOException("unknown key type $type")
        }
    }

    /** an Ed25519 public key from its 32 bytes */
    fun ed25519Public(raw: ByteArray): PublicKey {
        if (raw.size != 32) throw IOException("an Ed25519 key is 32 bytes")
        SshSecurity.ensure()
        return KeyFactory.getInstance("Ed25519", "BC").generatePublic(X509EncodedKeySpec(ED25519_X509 + raw))
    }

    /** an ECDSA public key from its curve's SSH name and its point (uncompressed) */
    fun ecPublic(curveName: String, q: ByteArray): PublicKey {
        val jca = when (curveName) {
            "nistp256" -> "secp256r1"
            "nistp384" -> "secp384r1"
            "nistp521" -> "secp521r1"
            else -> throw IOException("unknown curve $curveName")
        }
        val params = ecParams(jca)
        val n = (params.curve.field.fieldSize + 7) / 8
        if (q.size != 1 + 2 * n || q[0] != 4.toByte()) throw IOException("not an uncompressed EC point")
        val x = BigInteger(1, q.copyOfRange(1, 1 + n))
        val y = BigInteger(1, q.copyOfRange(1 + n, q.size))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }
}
