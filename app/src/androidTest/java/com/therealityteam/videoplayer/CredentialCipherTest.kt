package com.therealityteam.videoplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.therealityteam.videoplayer.data.repository.CredentialCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device/emulator because the key lives in the real Android Keystore. */
@RunWith(AndroidJUnit4::class)
class CredentialCipherTest {

    @Test
    fun aPasswordSurvivesTheRoundTripAndIsUnreadableOnDisk() {
        val secret = "Lina1208@—パス"
        val stored = CredentialCipher.protect(secret)
        assertTrue(CredentialCipher.isProtected(stored))
        assertFalse("ciphertext must not contain the secret", stored.contains("Lina1208"))
        assertEquals(secret, CredentialCipher.reveal(stored))
    }

    @Test
    fun protectingTwiceDoesNotDoubleEncrypt() {
        val once = CredentialCipher.protect("hunter2")
        assertEquals(once, CredentialCipher.protect(once))
    }

    @Test
    fun everyEncryptionUsesAFreshIv() {
        assertNotEquals(CredentialCipher.protect("same"), CredentialCipher.protect("same"))
    }

    @Test
    fun legacyPlainTextPassesThroughUnchanged() {
        assertEquals("old-plain-password", CredentialCipher.reveal("old-plain-password"))
        assertFalse(CredentialCipher.isProtected("old-plain-password"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", CredentialCipher.protect(""))
        assertEquals("", CredentialCipher.reveal(""))
    }

    @Test
    fun garbageCiphertextRevealsEmptyNotGarbage() {
        // e.g. sources.json restored onto a different device whose Keystore lacks the key.
        assertEquals("", CredentialCipher.reveal("enc1:AAAA:BBBB"))
    }
}
