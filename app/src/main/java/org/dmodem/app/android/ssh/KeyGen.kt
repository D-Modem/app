// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ssh

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECPrivateKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.util.Base64

/**
 * What ssh-keygen tells of a key and writes of it: its size, its
 * fingerprints (SHA256 and the old MD5), its randomart (-lv), and the
 * private key in OpenSSH's own form (-o), encrypted with a passphrase as
 * ssh-keygen does it (bcrypt KDF, aes256-ctr).
 */
object KeyGen {

    /** the key's size in bits, as ssh-keygen -l gives it */
    fun bits(k: PublicKey): Int = when (k) {
        is RSAPublicKey -> k.modulus.bitLength()
        is ECPublicKey -> k.params.curve.field.fieldSize
        else -> 256
    }

    /** ssh-keygen's name for the key's type: ED25519, ECDSA, RSA */
    fun typeName(k: PublicKey): String = when (k) {
        is RSAPublicKey -> "RSA"
        is ECPublicKey -> "ECDSA"
        else -> "ED25519"
    }

    /** the SHA-256 of the key's blob (its fingerprint's digest) */
    fun sha256(k: PublicKey): ByteArray = MessageDigest.getInstance("SHA-256").digest(KeyFormat.blob(k))

    /** MD5:xx:xx:... (as ssh-keygen -E md5) */
    fun md5(k: PublicKey): String =
        "MD5:" + MessageDigest.getInstance("MD5").digest(KeyFormat.blob(k)).joinToString(":") { "%02x".format(it) }

    /** the "drunken bishop" picture of the SHA256 fingerprint, as ssh-keygen -lv draws it */
    fun randomart(k: PublicKey): String = randomart(sha256(k), "[${typeName(k)} ${bits(k)}]", "[SHA256]")

    /** the "drunken bishop" picture of [digest], [title] on its top border and [hashName] on its bottom */
    fun randomart(digest: ByteArray, title: String, hashName: String): String {
        val w = 17
        val h = 9
        val chars = " .o+=*BOX@%&#/^SE"
        val len = chars.length - 1
        val field = Array(w) { IntArray(h) }
        var x = w / 2
        var y = h / 2
        for (byte in digest) {
            var input = byte.toInt() and 0xff
            repeat(4) {
                x += if (input and 1 != 0) 1 else -1
                y += if (input and 2 != 0) 1 else -1
                x = x.coerceIn(0, w - 1)
                y = y.coerceIn(0, h - 1)
                if (field[x][y] < len - 2) field[x][y]++
                input = input shr 2
            }
        }
        field[w / 2][h / 2] = len - 1
        field[x][y] = len
        /** a border line of the picture's width, [label] in its middle */
        fun border(label: String): String {
            val sb = StringBuilder("+")
            val left = (w - label.length) / 2
            repeat(left) { sb.append('-') }
            sb.append(label)
            repeat(w - left - label.length) { sb.append('-') }
            return sb.append('+').toString()
        }
        val sb = StringBuilder(border(title)).append('\n')
        for (row in 0 until h) {
            sb.append('|')
            for (col in 0 until w) sb.append(chars[minOf(field[col][row], len)])
            sb.append("|\n")
        }
        return sb.append(border(hashName)).toString()
    }

    /**
     * The private key as "-----BEGIN OPENSSH PRIVATE KEY-----", for ssh and
     * ssh-keygen (and this app's import); encrypted with [passphrase] when
     * it isn't empty, as ssh-keygen does it (by libdmodemssh: bcrypt KDF,
     * aes256-ctr).
     */
    fun openSshPrivate(kp: KeyPair, comment: String, passphrase: String = ""): String {
        val plain = openSshPlain(kp, comment)
        if (passphrase.isEmpty()) return plain
        return NativeSsh.keyExport(plain, null, passphrase) ?: throw IOException("the key couldn't be encrypted")
    }

    /** the private key in OpenSSH's form, unencrypted */
    private fun openSshPlain(kp: KeyPair, comment: String): String {
        val check = SecureRandom().nextInt().toLong() and 0xffffffffL
        val priv = SshWriter().uint32(check).uint32(check)
        privateFields(kp, priv)
        priv.string(comment)
        var pad = 1
        while (priv.size() % 8 != 0) priv.byte(pad++)
        val out = SshWriter()
            .raw("openssh-key-v1\u0000".toByteArray())
            .string("none").string("none").bytes(ByteArray(0))
            .uint32(1)
            .bytes(KeyFormat.blob(kp.public))
            .bytes(priv.bytes())
        val b64 = Base64.getEncoder().encodeToString(out.bytes()).chunked(70).joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    /** the key's type and private fields into [b], as OpenSSH's private key section has them (RSA, ECDSA or Ed25519) */
    private fun privateFields(kp: KeyPair, b: SshWriter) {
        b.string(KeyFormat.type(kp.public))
        when (val p = kp.private) {
            is RSAPrivateCrtKey -> {
                b.mpint(p.modulus).mpint(p.publicExponent).mpint(p.privateExponent)
                b.mpint(p.crtCoefficient).mpint(p.primeP).mpint(p.primeQ)
            }
            is ECPrivateKey -> {
                // the curve's name and the public point, as in the public blob; then d
                val pub = kp.public as ECPublicKey
                b.string(KeyFormat.curve(pub.params.curve.field.fieldSize).first)
                b.bytes(KeyFormat.point(pub))
                b.mpint(p.s)
            }
            else -> {
                // Ed25519: the 32-byte public key, then seed and public key together
                val pk = KeyFormat.ed25519(kp.public)
                b.bytes(pk)
                b.bytes(ed25519Seed(p) + pk)
            }
        }
    }

    /** an Ed25519 private key's 32-byte seed */
    fun ed25519Seed(k: PrivateKey): ByteArray =
        (PrivateKeyFactory.createKey(k.encoded) as Ed25519PrivateKeyParameters).encoded

    /** an Ed25519 private key from its 32-byte seed */
    fun ed25519Private(seed: ByteArray): PrivateKey {
        if (seed.size != 32) throw IOException("an Ed25519 seed is 32 bytes")
        SshSecurity.ensure()
        return KeyFactory.getInstance("Ed25519", "BC").generatePrivate(PKCS8EncodedKeySpec(ED25519_PKCS8 + seed))
    }

    /** PKCS#8's prefix of an Ed25519 key, before its seed */
    private val ED25519_PKCS8 = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20,
    )

    /** an RSA private key made whole from n, e, d and its primes */
    fun rsaPrivate(n: BigInteger, e: BigInteger, d: BigInteger, p: BigInteger, q: BigInteger): PrivateKey {
        val one = BigInteger.ONE
        return KeyFactory.getInstance("RSA").generatePrivate(
            RSAPrivateCrtKeySpec(n, e, d, p, q, d.mod(p.subtract(one)), d.mod(q.subtract(one)), q.modInverse(p)),
        )
    }

    /** an ECDSA private key: d on the curve of the SSH name [curveName] */
    fun ecPrivate(curveName: String, d: BigInteger): PrivateKey {
        val bits = when (curveName) {
            "nistp256" -> 256
            "nistp384" -> 384
            "nistp521" -> 521
            else -> throw IOException("unknown curve $curveName")
        }
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, KeyFormat.ecParams(KeyFormat.curve(bits).second)))
    }

    /**
     * An unencrypted OpenSSH private key ("BEGIN OPENSSH PRIVATE KEY") read:
     * its pair and its comment. An encrypted one is opened first by
     * [NativeSsh.keyExport].
     */
    fun readOpenSsh(text: String): Pair<KeyPair, String> {
        val body = text.substringAfter("-----BEGIN OPENSSH PRIVATE KEY-----").substringBefore("-----END")
        val r = SshReader(Base64.getMimeDecoder().decode(body.trim()))
        if (!r.raw(15).contentEquals("openssh-key-v1\u0000".toByteArray())) throw IOException("not an OpenSSH key")
        if (r.string() != "none" || r.string() != "none") throw IOException("the key is encrypted")
        r.bytes() // the KDF's options
        if (r.int() != 1) throw IOException("one key per file only")
        val pub = KeyFormat.fromBlob(r.bytes())
        val p = SshReader(r.bytes())
        if (p.uint32() != p.uint32()) throw IOException("the key's check numbers differ")
        val type = p.string()
        val priv: PrivateKey = when {
            type == "ssh-rsa" -> {
                val n = p.mpint()
                val e = p.mpint()
                val d = p.mpint()
                p.mpint() // iqmp
                rsaPrivate(n, e, d, p.mpint(), p.mpint())
            }
            type.startsWith("ecdsa-sha2-") -> {
                val curve = p.string()
                p.bytes() // the public point
                ecPrivate(curve, p.mpint())
            }
            type == "ssh-ed25519" -> {
                p.bytes() // the public key
                ed25519Private(p.bytes().copyOf(32))
            }
            else -> throw IOException("unknown key type $type")
        }
        return KeyPair(pub, priv) to p.string()
    }

    /** the command that adds [publicKey] to the server's authorized_keys once (ssh-copy-id) */
    fun copyIdCommand(publicKey: String): String {
        val k = publicKey.trim().replace("'", "")
        return "umask 077; mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "(grep -qxF '$k' ~/.ssh/authorized_keys || echo '$k' >> ~/.ssh/authorized_keys) && echo added"
    }
}
