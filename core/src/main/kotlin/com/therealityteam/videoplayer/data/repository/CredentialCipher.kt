package com.therealityteam.videoplayer.data.repository

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts source credentials (SMB/WebDAV passwords, Jellyfin tokens) at rest.
 *
 * sources.json used to hold them in plain text. The AES key lives in the platform's own key store —
 * the Android Keystore on the headset (readable by no other app, gone with an uninstall), the
 * system keychain on a desktop — and protected values carry an "enc1:" prefix on disk. A value
 * without the prefix is legacy plain text: it still loads, and the repository re-persists it
 * encrypted. If the key store itself is unavailable the value is kept as-is — a plaintext password
 * that plays videos beats an encrypted one nobody can decrypt.
 */
object CredentialCipher {

    private const val PREFIX = "enc1:"

    /**
     * Where the AES key comes from. The app sets this once at startup, before the first
     * [protect] / [reveal]; left unset, nothing is encrypted.
     */
    @Volatile
    var keySource: () -> SecretKey? = { null }

    private val key: SecretKey? by lazy { runCatching { keySource() }.getOrNull() }

    /** The value as it should be written to disk: encrypted when possible, unchanged otherwise. */
    fun protect(value: String): String {
        if (value.isEmpty() || value.startsWith(PREFIX)) return value
        val k = key ?: return value
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val b64 = Base64.getEncoder()
            PREFIX + b64.encodeToString(cipher.iv) + ":" + b64.encodeToString(ct)
        }.getOrDefault(value)
    }

    /**
     * The usable value of what was read from disk: decrypted if it carries the prefix, returned
     * unchanged if legacy plain text. An undecryptable value (restored onto another device, whose
     * Keystore lacks this key) becomes empty — the connection then fails visibly and the user
     * re-enters the password, instead of ciphertext being sent to their NAS as one.
     */
    fun reveal(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        val k = key ?: return ""
        return runCatching {
            val (ivB64, ctB64) = value.removePrefix(PREFIX).split(":", limit = 2).let { it[0] to it[1] }
            val b64 = Base64.getDecoder()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, b64.decode(ivB64)))
            }
            String(cipher.doFinal(b64.decode(ctB64)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    /** True when [value] is stored encrypted. */
    fun isProtected(value: String): Boolean = value.startsWith(PREFIX)
}
