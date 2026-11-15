package com.therealityteam.videoplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.repository.CredentialCipher
import com.therealityteam.videoplayer.data.repository.JsonSourceRepository
import com.therealityteam.videoplayer.data.source.SmbSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI

/**
 * The whole credential chain against a real NAS: sources.json (ciphertext on disk) → repository
 * (decrypts on load) → SMB authentication. Skips silently when the device has no SMB source
 * configured — it proves the chain on a dev machine, not in CI.
 */
@RunWith(AndroidJUnit4::class)
class SmbCredentialRoundTripTest {

    @Test
    fun theDecryptedPasswordStillOpensTheRealServer(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = JsonSourceRepository(ctx.filesDir)
        val smb = repo.all().firstOrNull { it.type == SourceType.SMB && !it.password.isNullOrEmpty() }
            ?: return@runBlocking // nothing configured here — nothing to prove
        assertFalse(
            "the repository must hand out plaintext, not ciphertext",
            CredentialCipher.isProtected(smb.password!!),
        )
        val host = URI(smb.uri).host
        val shares = SmbSource().listShares(host, smb.username, smb.password)
        assertTrue(
            "authenticating against $host with the decrypted password should list shares",
            shares.isNotEmpty(),
        )
    }
}
