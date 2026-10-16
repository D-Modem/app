// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Octets sealed for keeping on the device, and opened again. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-GCM under a key in the Android Keystore (in the phone's secure
 * hardware where it has some): what it seals can't be opened off the
 * device, nor by another app. Sealed: the 12-octet IV, then the
 * ciphertext with its tag.
 */
class KeystoreBox(private val alias: String = "dmodem-secrets") : SecretBox {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}

/** No sealing at all: the JVM tests' (there is no Android Keystore there). */
class PlainBox : SecretBox {
    override fun seal(plain: ByteArray) = plain.copyOf()
    override fun open(sealed: ByteArray) = sealed.copyOf()
}

/** Passwords kept for directory entries, sealed, by entry id. */
class PasswordStore(private val file: File, private val box: SecretBox) {

    @Synchronized
    private fun load(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    @Synchronized
    fun get(id: String): String? = try {
        load().optString(id).takeIf { it.isNotEmpty() }?.let {
            String(box.open(Base64.getDecoder().decode(it)), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun put(id: String, password: String) {
        val o = load()
        o.put(id, Base64.getEncoder().encodeToString(box.seal(password.toByteArray(Charsets.UTF_8))))
        file.writeText(o.toString())
    }

    @Synchronized
    fun remove(id: String) {
        val o = load()
        o.remove(id)
        file.writeText(o.toString())
    }
}

/**
 * The full BouncyCastle as "BC": Android's own is cut down (no Ed25519,
 * no X25519 for sshj), so it goes, and sshj uses this one.
 */
object SshSecurity {
    @Volatile
    private var done = false

    @Synchronized
    fun ensure() {
        if (done) return
        val bc = Security.getProvider("BC")
        if (bc == null || bc.javaClass.name != org.bouncycastle.jce.provider.BouncyCastleProvider::class.java.name) {
            Security.removeProvider("BC")
            Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
        net.schmizz.sshj.common.SecurityUtils.setRegisterBouncyCastle(false)
        net.schmizz.sshj.common.SecurityUtils.setSecurityProvider("BC")
        done = true
    }
}
