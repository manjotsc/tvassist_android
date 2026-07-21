package com.tvassist.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for stored secrets (the HA long-lived token and the notification token), backed
 * by a non-exportable AES-256-GCM key held in the Android Keystore — so a filesystem or backup dump
 * of the app's DataStore files can't read them.
 *
 * [encrypt] output is tagged with [PREFIX]; [decrypt] passes anything without that tag through
 * unchanged, so pre-encryption plaintext values keep working and get re-encrypted on their next
 * write. If the Keystore is somehow unavailable, [encrypt] falls back to storing plaintext rather
 * than losing the value; an unreadable ciphertext decrypts to "" (forcing a harmless re-entry).
 */
object SecretCrypto {
    private const val ALIAS = "tvassist_secret_v1"
    private const val PREFIX = "enc1:"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    // Memoize ciphertext → plaintext so the settings flow (which rebuilds on every change and thus
    // calls decrypt repeatedly) does at most ONE Keystore round-trip per distinct token value, not
    // one per emission. Only a handful of ciphertexts ever exist; clear if it somehow grows.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return kg.generateKey()
    }

    /** Encrypt a secret for storage. Blank stays blank; failures fall back to plaintext. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        }.getOrDefault(plain)
    }

    /** Decrypt a stored secret. Untagged values (legacy plaintext / blank) pass through unchanged. */
    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        cache[stored]?.let { return it }
        val plain = runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_LEN)
            val ct = raw.copyOfRange(IV_LEN, raw.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrDefault("")
        if (cache.size > 16) cache.clear()
        cache[stored] = plain
        return plain
    }
}
