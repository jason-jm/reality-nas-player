package com.therealityteam.videoplayer.platform

import android.content.Context
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.therealityteam.videoplayer.data.repository.CredentialCipher
import com.therealityteam.videoplayer.ui.library.LibraryPlatform
import com.therealityteam.videoplayer.ui.library.Romanizer
import com.therealityteam.videoplayer.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * What the shared core (the `:core` module) needs from Android, handed over once at startup —
 * before anything reads the library, groups the wall or opens a source.
 */
object CorePlatform {

    fun install() {
        Log.sink = Log.Sink { priority, tag, message, error ->
            android.util.Log.println(
                priority, tag,
                if (error == null) message else message + "\n" + android.util.Log.getStackTraceString(error),
            )
        }
        Romanizer.transliterators = { id ->
            android.icu.text.Transliterator.getInstance(id)?.let { t -> { text: String -> t.transliterate(text) } }
        }
        CredentialCipher.keySource = ::keystoreKey
    }

    /** The credentials' AES key, in the Android Keystore: readable by no other app, gone with an uninstall. */
    private fun keystoreKey(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private const val ALIAS = "aura-credentials"
}

/** The library's view of the headset. */
class AndroidLibraryPlatform(private val context: Context) : LibraryPlatform {

    override val filesDir: File get() = context.filesDir

    override val installedAt: Long
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(-1L)

    /**
     * 扫描本地: the shared storage's Movies and Download, what the page says it reads — listed to Aura only
     * once the system's 「视频」 permission is given (SourcesScreen asks for it). Each once: /sdcard and
     * /storage/emulated/0 are one folder, and naming both put every film on the wall twice. Not Aura's own
     * Android/data folder: only adb can put films there, and its downloads/ holds the copies 下载到本机
     * made of films the wall already has.
     */
    override fun localScanDirs(): List<String> =
        listOf(Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_DOWNLOADS)
            .map { File(Environment.getExternalStorageDirectory(), it) }
            .map { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .distinct()

    /** Hours of scraping must not share the window's lifetime: the foreground service keeps the process alive. */
    override fun keepWorking() = LibraryTaskService.start(context)
}
