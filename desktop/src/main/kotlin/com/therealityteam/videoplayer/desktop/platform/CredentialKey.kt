package com.therealityteam.videoplayer.desktop.platform

import com.therealityteam.videoplayer.util.PRODUCT_NAME
import com.sun.jna.platform.win32.Crypt32Util
import com.therealityteam.videoplayer.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The AES key that encrypts saved passwords and tokens (see CredentialCipher), kept where the
 * operating system keeps secrets rather than next to what it protects:
 *
 *  - macOS: the login keychain.
 *  - Windows: a file sealed with DPAPI, which only this Windows account can open.
 *  - Elsewhere: a file only this user can read.
 *
 * A copy of the app's data folder — a backup, a sync — then carries ciphertext without its key.
 */
object CredentialKey {
    private const val TAG = "AuraKey"
    private const val SERVICE = "Aura"
    private const val ACCOUNT = "credentials"

    fun load(): SecretKey? = runCatching {
        val raw = when {
            Host.isMac -> keychain()
            Host.isWindows -> dpapiFile()
            else -> plainFile()
        }
        raw?.let { SecretKeySpec(it, "AES") }
    }.onFailure { Log.w(TAG, "no credential key: ${it.message}", it) }.getOrNull()

    private fun fresh(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun keychain(): ByteArray? {
        val found = run("security", "find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w")
        if (found.code == 0) return Base64.getDecoder().decode(found.out.trim())
        // 44 is "not in the keychain". Anything else — a locked keychain, a denied prompt — must not
        // mint a second key over the first: what it encrypted would be lost. Nothing is encrypted
        // this session instead, and what was stays readable next time.
        if (found.code != SEC_ITEM_NOT_FOUND) return null
        val key = fresh()
        val added = run(
            "security", "add-generic-password", "-s", SERVICE, "-a", ACCOUNT,
            "-l", "$PRODUCT_NAME saved passwords", "-w", Base64.getEncoder().encodeToString(key),
        )
        return if (added.code == 0) key else null
    }

    private fun dpapiFile(): ByteArray? {
        val f = File(Host.dataDir, "aura/credential.key")
        if (f.isFile) return Crypt32Util.cryptUnprotectData(f.readBytes())
        val key = fresh()
        f.parentFile.mkdirs()
        f.writeBytes(Crypt32Util.cryptProtectData(key))
        return key
    }

    private fun plainFile(): ByteArray? {
        val f = File(Host.dataDir, "aura/credential.key")
        if (f.isFile) return Base64.getDecoder().decode(f.readText().trim())
        val key = fresh()
        f.parentFile.mkdirs()
        f.writeText(Base64.getEncoder().encodeToString(key))
        f.setReadable(false, false); f.setReadable(true, true)
        f.setWritable(false, false); f.setWritable(true, true)
        return key
    }

    private class Result(val code: Int, val out: String)

    /** Waited for with a deadline, then read: a keychain prompt nobody answers must not hang startup. */
    private fun run(vararg cmd: String): Result {
        val p = ProcessBuilder(*cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.outputStream.close()
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return Result(-1, "")
        }
        return Result(p.exitValue(), p.inputStream.bufferedReader().readText())
    }

    private const val SEC_ITEM_NOT_FOUND = 44
}
