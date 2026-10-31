// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

/** An SSH key's type, as generated here. */
enum class KeyKind(val label: String, val algorithm: String, val bits: Int) {
    ED25519("Ed25519", "Ed25519", 256),
    ECDSA_P256("ECDSA P-256", "EC", 256),
    ECDSA_P384("ECDSA P-384", "EC", 384),
    ECDSA_P521("ECDSA P-521", "EC", 521),
    RSA_2048("RSA 2048", "RSA", 2048),
    RSA_3072("RSA 3072", "RSA", 3072),
    RSA_4096("RSA 4096", "RSA", 4096),
}

/** A key: its name and public half (the private half sealed in its own file). */
data class SshKey(
    val id: String,
    val name: String,
    /** the JCA algorithm (Ed25519, EC, RSA) */
    val algorithm: String,
    /** in authorized_keys's form: "ssh-ed25519 AAAA... name" */
    val publicKey: String,
    val fingerprint: String,
    val created: Long,
)

/** The public key's SSH form and its fingerprint (SHA256:..., as ssh-keygen -l). */
object KeyFormat {
    /** the key in SSH's wire form (its type, then its fields) */
    fun blob(k: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(k).compactData

    /** the key's SSH type: ssh-ed25519, ecdsa-sha2-nistp256, ssh-rsa... */
    fun type(k: PublicKey): String = KeyType.fromKey(k).toString()

    /** the key's authorized_keys line: its type, its blob in base64, and [comment] if any */
    fun openSsh(k: PublicKey, comment: String = ""): String =
        type(k) + " " + Base64.getEncoder().encodeToString(blob(k)) + if (comment.isNotEmpty()) " $comment" else ""

    /** the key's fingerprint, SHA256: and its blob's digest in unpadded base64 */
    fun fingerprint(k: PublicKey): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob(k)))
}

/**
 * The app's SSH keys: made here or imported (OpenSSH's, PuTTY's .ppk or
 * PKCS#8 PEM, with their passphrase); each private
 * key sealed by the [SecretBox] in dir/<id>.key, the list in dir/keys.json.
 */
class SshKeys(private val dir: File, private val box: SecretBox) {

    private val _keys = MutableStateFlow<List<SshKey>>(emptyList())
    val keys: StateFlow<List<SshKey>> = _keys.asStateFlow()

    private val index get() = File(dir, "keys.json")

    init {
        dir.mkdirs()
        _keys.value = try {
            val a = JSONArray(index.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                SshKey(
                    o.getString("id"), o.getString("name"), o.getString("algorithm"),
                    o.getString("publicKey"), o.getString("fingerprint"), o.optLong("created"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** [list] written to keys.json and the keys now */
    @Synchronized
    private fun saveIndex(list: List<SshKey>) {
        val a = JSONArray()
        for (k in list) {
            a.put(
                JSONObject().put("id", k.id).put("name", k.name).put("algorithm", k.algorithm)
                    .put("publicKey", k.publicKey).put("fingerprint", k.fingerprint).put("created", k.created),
            )
        }
        index.writeText(a.toString(2))
        _keys.value = list
    }

    /** a new key of a kind, kept (ssh-keygen -t -b -C): [comment] for its public line, the name's by default */
    fun generate(kind: KeyKind, name: String, comment: String? = null): SshKey {
        SshSecurity.ensure()
        val g = KeyPairGenerator.getInstance(kind.algorithm, "BC")
        when (kind.algorithm) {
            "RSA" -> g.initialize(RSAKeyGenParameterSpec(kind.bits, RSAKeyGenParameterSpec.F4))
            "EC" -> g.initialize(ECGenParameterSpec(if (kind.bits == 521) "secp521r1" else "secp${kind.bits}r1"))
        }
        return keep(g.generateKeyPair(), name, comment)
    }

    /**
     * A private key's text (OpenSSH's "BEGIN OPENSSH PRIVATE KEY", or a PEM
     * PKCS#8 / PKCS#1 one) imported, opened with [passphrase] if it has one.
     * Throws if it can't be read.
     */
    fun import(text: String, passphrase: String?, name: String): SshKey {
        SshSecurity.ensure()
        val finder = object : PasswordFinder {
            /** [passphrase] for the key (none: empty) */
            override fun reqPassword(resource: Resource<*>?): CharArray = (passphrase ?: "").toCharArray()
            /** asked once: a wrong passphrase isn't asked again */
            override fun shouldRetry(resource: Resource<*>?) = false
        }
        val provider: KeyProvider = when {
            Ppk.isPpk(text) -> PuTTYKeyFile().apply { init(text, null, finder) }
            text.contains("BEGIN OPENSSH PRIVATE KEY") -> OpenSSHKeyV1KeyFile().apply { init(text, null, finder) }
            text.contains("BEGIN") && text.contains("PRIVATE KEY") -> PKCS8KeyFile().apply { init(text, null, finder) }
            else -> OpenSSHKeyFile().apply { init(text, null, finder) }
        }
        // (a .ppk's comment kept as the key's)
        return keep(KeyPair(provider.public, provider.private), name, if (Ppk.isPpk(text)) Ppk.comment(text)?.takeIf { it.isNotBlank() } else null)
    }

    /** [given] kept: its private half sealed in its own file (an RSA one made whole first), its entry added */
    private fun keep(given: KeyPair, name: String, comment: String? = null): SshKey {
        val pair = withCrt(given)
        val id = UUID.randomUUID().toString()
        val sealed = box.seal(
            JSONObject()
                .put("algorithm", pair.private.algorithm)
                .put("private", Base64.getEncoder().encodeToString(pair.private.encoded))
                .put("public", Base64.getEncoder().encodeToString(pair.public.encoded))
                .toString().toByteArray(),
        )
        File(dir, "$id.key").writeBytes(sealed)
        val k = SshKey(
            id, name.ifBlank { KeyFormat.type(pair.public) }, pair.private.algorithm,
            KeyFormat.openSsh(pair.public, comment ?: name.replace(' ', '-')), KeyFormat.fingerprint(pair.public),
            System.currentTimeMillis(),
        )
        saveIndex(_keys.value + k)
        return k
    }

    /**
     * an RSA key given as n and d only (sshj reads PuTTY's that way) made
     * whole: its primes found again from n, e and d, so that it can be
     * encoded (Android's provider won't encode one without them)
     */
    private fun withCrt(kp: KeyPair): KeyPair {
        val priv = kp.private as? java.security.interfaces.RSAPrivateKey ?: return kp
        if (priv is java.security.interfaces.RSAPrivateCrtKey) return kp
        val pub = kp.public as java.security.interfaces.RSAPublicKey
        val n = pub.modulus
        val e = pub.publicExponent
        val d = priv.privateExponent
        val one = java.math.BigInteger.ONE
        // k = de - 1 = 2^s t; for random g, a square root of 1 other than ±1 among g^t, g^2t, ... splits n
        val k = d.multiply(e).subtract(one)
        val s = k.lowestSetBit
        val t = k.shiftRight(s)
        val rnd = java.security.SecureRandom()
        var p: java.math.BigInteger? = null
        while (p == null) {
            val g = java.math.BigInteger(n.bitLength() - 1, rnd).add(java.math.BigInteger.valueOf(2))
            var x = g.modPow(t, n)
            for (i in 0 until s) {
                val y = x.multiply(x).mod(n)
                if (y == one && x != one && x != n.subtract(one)) {
                    p = x.subtract(one).gcd(n)
                    break
                }
                x = y
            }
        }
        val q = n.divide(p)
        val spec = java.security.spec.RSAPrivateCrtKeySpec(
            n, e, d, p, q, d.mod(p.subtract(one)), d.mod(q.subtract(one)), q.modInverse(p),
        )
        return KeyPair(pub, KeyFactory.getInstance("RSA", "BC").generatePrivate(spec))
    }

    /** the key's pair, opened: for sshj's authentication */
    fun keyPair(id: String): KeyPair {
        SshSecurity.ensure()
        val o = JSONObject(String(box.open(File(dir, "$id.key").readBytes())))
        val alg = o.getString("algorithm")
        val kf = KeyFactory.getInstance(alg, "BC")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(o.getString("private"))))
        val pub = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(o.getString("public"))))
        return KeyPair(pub, priv)
    }

    /** the key's comment: its public line's third field */
    fun comment(k: SshKey): String = k.publicKey.trim().split(Regex("\\s+"), limit = 3).getOrElse(2) { "" }

    /** the private key in OpenSSH's form (ssh-keygen's), encrypted with [passphrase] unless it's empty */
    fun exportPrivate(id: String, passphrase: String): String {
        val k = byId(id) ?: throw IllegalArgumentException("no key $id")
        return KeyGen.openSshPrivate(keyPair(id), comment(k), passphrase)
    }

    /** the private key as PuTTY's .ppk ([version] 3, or 2 for older PuTTY), encrypted with [passphrase] unless it's empty */
    fun exportPpk(id: String, passphrase: String, version: Int = 3): String {
        val k = byId(id) ?: throw IllegalArgumentException("no key $id")
        return Ppk.write(keyPair(id), comment(k), passphrase, version)
    }

    /** a new comment on the key's public line (ssh-keygen -c) */
    fun setComment(id: String, comment: String) {
        val clean = comment.replace(Regex("[\\r\\n]"), " ").trim()
        saveIndex(
            _keys.value.map {
                if (it.id != id) it else {
                    val f = it.publicKey.trim().split(Regex("\\s+"), limit = 3)
                    it.copy(publicKey = f[0] + " " + f[1] + if (clean.isNotEmpty()) " $clean" else "")
                }
            },
        )
    }

    /** the key as sshj's key provider, for authentication */
    fun provider(id: String): KeyProvider = KeyPairWrapper(keyPair(id))

    /** the key's name changed to [name] */
    fun rename(id: String, name: String) {
        saveIndex(_keys.value.map { if (it.id == id) it.copy(name = name) else it })
    }

    /** the key's sealed file deleted and its entry gone */
    fun delete(id: String) {
        File(dir, "$id.key").delete()
        saveIndex(_keys.value.filter { it.id != id })
    }

    /** the key with [id], or null */
    fun byId(id: String): SshKey? = _keys.value.firstOrNull { it.id == id }
}
