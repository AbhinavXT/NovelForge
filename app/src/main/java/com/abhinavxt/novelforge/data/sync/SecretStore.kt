package com.abhinavxt.novelforge.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretStore {
    /** A null [value] removes the entry. */
    fun put(name: String, value: String?)
    fun get(name: String): String?

    /** True when a value is stored. Must not decrypt anything. */
    fun has(name: String): Boolean = get(name) != null
}

/**
 * AES-256-GCM with a key held in the Android Keystore. Ciphertext and IV are stored
 * Base64 in the `sync` preferences file, which is excluded from backups.
 */
class KeystoreSecretStore(context: Context) : SecretStore {

    private val prefs = context.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)

    @Synchronized
    override fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(PREFIX + name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(PREFIX + name, packed).apply()
    }

    override fun has(name: String): Boolean = prefs.contains(PREFIX + name)

    @Synchronized
    override fun get(name: String): String? {
        val packed = prefs.getString(PREFIX + name, null) ?: return null
        return try {
            val (iv, ct) = packed.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null // key lost (restore, lock-screen reset) or data corrupt
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "novelforge_sync_secret_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val PREFIX = "secret_"
    }
}
