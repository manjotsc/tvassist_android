package com.tvassist.data.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * Issue #2: [SecretCrypto.encrypt] used to return its plaintext input when the Keystore failed, so
 * a save "succeeded" while storing the HA token unencrypted. These pin that it now throws instead,
 * never hands the secret back, and that reading old plaintext values still works.
 *
 * The Keystore does not exist on the JVM, so the round-trip is swapped out
 * ([SecretCrypto.keystoreEncrypt]). That every caller encrypts *before* opening its DataStore edit
 * — which is what keeps the previous value on a failure — is structural, in `SettingsStore`.
 */
class SecretCryptoTest {

    private val real = SecretCrypto.keystoreEncrypt

    @After
    fun restore() {
        SecretCrypto.keystoreEncrypt = real
    }

    @Test
    fun aKeystoreFailureThrowsAndNeverReturnsThePlaintext() {
        SecretCrypto.keystoreEncrypt = { throw java.security.KeyStoreException("keystore unavailable") }
        val secret = "eyJhbGciOiJIUzI1NiJ9.super-secret-token"
        try {
            val stored = SecretCrypto.encrypt(secret)
            fail("expected SecretStorageException, but encrypt returned a value of length ${stored.length}")
        } catch (e: SecretStorageException) {
            // The message reaches the UI and the log: it must not carry the secret.
            assertFalse(e.message.orEmpty().contains(secret))
            assertEquals(java.security.KeyStoreException::class.java, e.cause?.javaClass)
        }
    }

    @Test
    fun anyExceptionFromTheKeystoreIsReportedTheSameWay() {
        // AndroidKeyStore throws a zoo of types (ProviderException, InvalidKeyException, …).
        SecretCrypto.keystoreEncrypt = { throw IllegalStateException("boom") }
        try {
            SecretCrypto.encrypt("token")
            fail("expected SecretStorageException")
        } catch (_: SecretStorageException) {
        }
    }

    @Test
    fun blankStaysBlankWithoutTouchingTheKeystore() {
        SecretCrypto.keystoreEncrypt = { error("must not be called for a blank secret") }
        assertEquals("", SecretCrypto.encrypt(""))
    }

    @Test
    fun aWorkingKeystoreIsUsedAsIs() {
        SecretCrypto.keystoreEncrypt = { "enc1:sealed(${it.length})" }
        assertEquals("enc1:sealed(5)", SecretCrypto.encrypt("token"))
    }

    /** Existing installs may still hold an untagged (pre-encryption) value; it must keep reading. */
    @Test
    fun legacyPlaintextStillReads() {
        assertEquals("old-plain-token", SecretCrypto.decrypt("old-plain-token"))
        assertEquals("", SecretCrypto.decrypt(""))
    }
}
