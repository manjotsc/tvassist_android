package com.tvassist.data.settings

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-based encryption for the secret fields (HA token, notification token, Maps key) inside a
 * backup file. Unlike [SecretCrypto] (device-bound Android Keystore key), this derives the key from a
 * user passphrase so an encrypted backup is portable to another device — while staying useless to
 * anyone who reads the file out of the public Downloads/USB folder without the passphrase.
 *
 * Format: "enc1:" + Base64(salt[16] | iv[12] | ciphertext+GCMtag). PBKDF2WithHmacSHA256 → AES-256-GCM.
 * [decrypt] passes through any value lacking the [PREFIX] unchanged, so plaintext/older backups still load.
 */
object BackupCrypto {
    private const val PREFIX = "enc1:"
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, "AES")
    }

    /** Encrypts [plain] with [passphrase]. Empty [plain] stays empty (nothing to protect). */
    fun encrypt(plain: String, passphrase: String): String {
        if (plain.isEmpty()) return plain
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase.toCharArray(), salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(salt + iv + ct)
    }

    /**
     * Decrypts a [PREFIX]-tagged value with [passphrase]; returns the plaintext, or null if the
     * passphrase is wrong / the data is corrupt. A value without the prefix is returned unchanged
     * (already plaintext). An empty passphrase on an encrypted value yields null.
     */
    fun decrypt(value: String, passphrase: String): String? {
        if (!isEncrypted(value)) return value
        if (passphrase.isEmpty()) return null
        return runCatching {
            val raw = Base64.getDecoder().decode(value.removePrefix(PREFIX))
            val salt = raw.copyOfRange(0, SALT_LEN)
            val iv = raw.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
            val ct = raw.copyOfRange(SALT_LEN + IV_LEN, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase.toCharArray(), salt), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }
}
