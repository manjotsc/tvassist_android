package com.tvassist.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for passphrase encryption of backup secrets. */
class BackupCryptoTest {

    @Test fun roundTripsWithCorrectPassphrase() {
        val secret = "llat_abcDEF1234567890"
        val enc = BackupCrypto.encrypt(secret, "correct horse battery")
        assertTrue(BackupCrypto.isEncrypted(enc))
        assertNotEquals(secret, enc)
        assertFalse(enc.contains(secret))
        assertEquals(secret, BackupCrypto.decrypt(enc, "correct horse battery"))
    }

    @Test fun wrongPassphraseReturnsNull() {
        val enc = BackupCrypto.encrypt("token", "right")
        assertNull(BackupCrypto.decrypt(enc, "wrong"))
    }

    @Test fun blankPassphraseOnEncryptedReturnsNull() {
        val enc = BackupCrypto.encrypt("token", "pw")
        assertNull(BackupCrypto.decrypt(enc, ""))
    }

    @Test fun plaintextValuePassesThroughDecryptUnchanged() {
        // Older/plaintext backups (no prefix) restore as-is regardless of passphrase.
        assertEquals("plain-token", BackupCrypto.decrypt("plain-token", "anything"))
        assertEquals("plain-token", BackupCrypto.decrypt("plain-token", ""))
    }

    @Test fun emptySecretStaysEmpty() {
        assertEquals("", BackupCrypto.encrypt("", "pw"))
    }

    @Test fun sameInputEncryptsDifferentlyEachTime() {
        // Random salt+IV ⇒ ciphertext isn't reused, so identical secrets don't produce identical blobs.
        assertNotEquals(BackupCrypto.encrypt("x", "pw"), BackupCrypto.encrypt("x", "pw"))
    }
}
