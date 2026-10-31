// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ssh

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import net.schmizz.sshj.common.Buffer
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
     * it isn't empty.
     */
    fun openSshPrivate(kp: KeyPair, comment: String, passphrase: String = "", rounds: Int = 16): String {
        val pub = KeyFormat.blob(kp.public)
        val encrypted = passphrase.isNotEmpty()
        val block = if (encrypted) 16 else 8
        val check = SecureRandom().nextInt().toLong() and 0xffffffffL
        val priv = Buffer.PlainBuffer().putUInt32(check).putUInt32(check)
        privateFields(kp, priv)
        priv.putString(comment)
        var pad = 1
        while (priv.available() % block != 0) priv.putByte((pad++).toByte())
        var secret = priv.compactData

        val kdfOptions: ByteArray
        if (encrypted) {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            kdfOptions = Buffer.PlainBuffer().putBytes(salt).putUInt32(rounds.toLong()).compactData
            val keyIv = ByteArray(48)
            BCrypt().pbkdf(passphrase.toByteArray(), salt, rounds, keyIv)
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyIv.copyOfRange(0, 32), "AES"), IvParameterSpec(keyIv.copyOfRange(32, 48)))
            secret = c.doFinal(secret)
        } else {
            kdfOptions = ByteArray(0)
        }
        val out = Buffer.PlainBuffer()
        out.putRawBytes("openssh-key-v1\u0000".toByteArray())
        out.putString(if (encrypted) "aes256-ctr" else "none")
        out.putString(if (encrypted) "bcrypt" else "none")
        out.putBytes(kdfOptions)
        out.putUInt32(1)
        out.putBytes(pub)
        out.putBytes(secret)
        val b64 = Base64.getEncoder().encodeToString(out.compactData).chunked(70).joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    /** the key's type and private fields into [b], as OpenSSH's private key section has them (RSA, ECDSA or Ed25519) */
    private fun privateFields(kp: KeyPair, b: Buffer.PlainBuffer) {
        val type = KeyFormat.type(kp.public)
        b.putString(type)
        when (val p = kp.private) {
            is RSAPrivateCrtKey -> {
                b.putMPInt(p.modulus).putMPInt(p.publicExponent).putMPInt(p.privateExponent)
                b.putMPInt(p.crtCoefficient).putMPInt(p.primeP).putMPInt(p.primeQ)
            }
            is ECPrivateKey -> {
                // the curve's name and the public point, as in the public blob; then d
                val pubBlob = Buffer.PlainBuffer(KeyFormat.blob(kp.public))
                pubBlob.readString()
                b.putString(pubBlob.readString())
                b.putBytes(pubBlob.readBytes())
                b.putMPInt(p.s)
            }
            else -> {
                // Ed25519: the 32-byte public key, then seed and public key together
                val seed = (PrivateKeyFactory.createKey(p.encoded) as Ed25519PrivateKeyParameters).encoded
                val pubBlob = Buffer.PlainBuffer(KeyFormat.blob(kp.public))
                pubBlob.readString()
                val pk = pubBlob.readBytes()
                b.putBytes(pk)
                b.putBytes(seed + pk)
            }
        }
    }

    /** the command that adds [publicKey] to the server's authorized_keys once (ssh-copy-id) */
    fun copyIdCommand(publicKey: String): String {
        val k = publicKey.trim().replace("'", "")
        return "umask 077; mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "(grep -qxF '$k' ~/.ssh/authorized_keys || echo '$k' >> ~/.ssh/authorized_keys) && echo added"
    }
}
