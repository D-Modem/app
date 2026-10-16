// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
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
enum class KeyKind(val label: String, val algorithm: String) {
    ED25519("Ed25519", "Ed25519"),
    ECDSA_P256("ECDSA P-256", "EC"),
    RSA_3072("RSA 3072", "RSA"),
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
    fun blob(k: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(k).compactData

    fun type(k: PublicKey): String = KeyType.fromKey(k).toString()

    fun openSsh(k: PublicKey, comment: String = ""): String =
        type(k) + " " + Base64.getEncoder().encodeToString(blob(k)) + if (comment.isNotEmpty()) " $comment" else ""

    fun fingerprint(k: PublicKey): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob(k)))
}

/**
 * The app's SSH keys: made here (Ed25519, ECDSA P-256, RSA 3072) or
 * imported (OpenSSH's or PKCS#8 PEM, with their passphrase); each private
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

    /** a new key of a kind, kept */
    fun generate(kind: KeyKind, name: String): SshKey {
        SshSecurity.ensure()
        val g = KeyPairGenerator.getInstance(kind.algorithm, "BC")
        when (kind) {
            KeyKind.RSA_3072 -> g.initialize(RSAKeyGenParameterSpec(3072, RSAKeyGenParameterSpec.F4))
            KeyKind.ECDSA_P256 -> g.initialize(ECGenParameterSpec("secp256r1"))
            KeyKind.ED25519 -> {}
        }
        return keep(g.generateKeyPair(), name)
    }

    /**
     * A private key's text (OpenSSH's "BEGIN OPENSSH PRIVATE KEY", or a PEM
     * PKCS#8 / PKCS#1 one) imported, opened with [passphrase] if it has one.
     * Throws if it can't be read.
     */
    fun import(text: String, passphrase: String?, name: String): SshKey {
        SshSecurity.ensure()
        val finder = object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray = (passphrase ?: "").toCharArray()
            override fun shouldRetry(resource: Resource<*>?) = false
        }
        val provider: KeyProvider = when {
            text.contains("BEGIN OPENSSH PRIVATE KEY") -> OpenSSHKeyV1KeyFile().apply { init(text, null, finder) }
            text.contains("BEGIN") && text.contains("PRIVATE KEY") -> PKCS8KeyFile().apply { init(text, null, finder) }
            else -> OpenSSHKeyFile().apply { init(text, null, finder) }
        }
        return keep(KeyPair(provider.public, provider.private), name)
    }

    private fun keep(pair: KeyPair, name: String): SshKey {
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
            KeyFormat.openSsh(pair.public, name.replace(' ', '-')), KeyFormat.fingerprint(pair.public),
            System.currentTimeMillis(),
        )
        saveIndex(_keys.value + k)
        return k
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

    fun provider(id: String): KeyProvider = KeyPairWrapper(keyPair(id))

    fun rename(id: String, name: String) {
        saveIndex(_keys.value.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun delete(id: String) {
        File(dir, "$id.key").delete()
        saveIndex(_keys.value.filter { it.id != id })
    }

    fun byId(id: String): SshKey? = _keys.value.firstOrNull { it.id == id }
}
