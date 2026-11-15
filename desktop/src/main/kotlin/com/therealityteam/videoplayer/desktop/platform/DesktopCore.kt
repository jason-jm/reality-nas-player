package com.therealityteam.videoplayer.desktop.platform

import com.ibm.icu.text.Transliterator
import com.therealityteam.videoplayer.data.source.WebDavSource
import com.therealityteam.videoplayer.data.repository.CredentialCipher
import com.therealityteam.videoplayer.data.scrape.ArtworkCache
import com.therealityteam.videoplayer.ui.library.LibraryPlatform
import com.therealityteam.videoplayer.ui.library.Romanizer
import com.therealityteam.videoplayer.util.Log
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * What the shared engine (core/) needs from a desktop, handed over once at startup — before
 * anything reads the library, groups the wall or opens a source. The headset's counterpart is the
 * PICO app's CorePlatform.
 */
object DesktopCore {

    fun install() {
        LogFile.install()
        CrashLog.install()
        Romanizer.transliterators = { id ->
            Transliterator.getInstance(id).let { t -> { text: String -> synchronized(t) { t.transliterate(text) } } }
        }
        CredentialCipher.keySource = CredentialKey::load
        WebDavSource.sendPropfind = ::propfind
        // SFTP servers' keys as first seen, kept with the app's data (trust on first use).
        com.therealityteam.videoplayer.data.source.PinnedHostKeys.file = java.io.File(Host.dataDir, "aura/sftp_known_hosts")
        com.therealityteam.videoplayer.util.UnreachableHosts.file = java.io.File(Host.dataDir, "aura/unreachable_hosts.tsv")
        // AI matching is gone: the model it downloaded into the app's own folder goes too (a GGUF the user had
        // pointed it at, elsewhere on the computer, is theirs and stays).
        Thread({ runCatching { java.io.File(Host.dataDir, "llm").takeIf { it.isDirectory }?.deleteRecursively() } }, "aura-old-model").start()
    }

    private val dav: java.net.http.HttpClient by lazy {
        java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(8))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build()
    }

    /** A PROPFIND the JDK's HttpURLConnection will not send (see WebDavSource.sendPropfind). */
    private fun propfind(url: String, headers: Map<String, String>, body: String): String? = runCatching {
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI(url))
            .timeout(java.time.Duration.ofSeconds(12))
            .method("PROPFIND", java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val response = dav.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        response.body().takeIf { response.statusCode() in 200..299 }
    }.getOrNull()
}

/** The library's view of this computer. */
class DesktopLibraryPlatform(override val filesDir: File = Host.dataDir) : LibraryPlatform {

    /** When this build was made: the jar the code runs from changes with every build and install. */
    override val installedAt: Long = runCatching {
        File(DesktopLibraryPlatform::class.java.protectionDomain.codeSource.location.toURI()).lastModified()
    }.getOrDefault(-1L)

    override fun localScanDirs(): List<String> = Host.videoFolders.map { it.absolutePath }

    /** A desktop app outlives its window by itself; nothing to hold. */
    override fun keepWorking() = Unit
}

/** Posters read small with Skia for the colour pick — the same grid the headset samples. */
object SkiaSampler : ArtworkCache.ImageSampler {
    override fun sample(path: String, maxWidth: Int, maxHeight: Int): ArtworkCache.Pixels? {
        val image = Image.makeFromEncoded(File(path).readBytes())
        try {
            // The headset decodes at a quarter and then shrinks to the grid; so does this.
            val w = min(max(image.width / 4, 1), maxWidth)
            val h = min(max(image.height / 4, 1), maxHeight)
            val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            val surface = Surface.makeRaster(info)
            val bitmap = Bitmap()
            try {
                surface.canvas.drawImageRect(
                    image,
                    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeWH(w.toFloat(), h.toFloat()),
                )
                bitmap.allocPixels(info)
                if (!surface.readPixels(bitmap, 0, 0)) return null
                val bytes = bitmap.readPixels() ?: return null
                val argb = IntArray(w * h) { i ->
                    val o = i * 4
                    (0xFF shl 24) or
                        ((bytes[o].toInt() and 0xFF) shl 16) or
                        ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                        (bytes[o + 2].toInt() and 0xFF)
                }
                return ArtworkCache.Pixels(w, h, argb)
            } finally {
                bitmap.close()
                surface.close()
            }
        } finally {
            image.close()
        }
    }
}

/**
 * The log, in a file where a user can find it and attach it to a bug report (Help › Show Logs),
 * mirrored to standard error when run from a terminal. One file, rolled at 4 MB, one old copy kept.
 */
object LogFile {
    private const val MAX_BYTES = 4L * 1024 * 1024
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val levels = mapOf(Log.DEBUG to 'D', Log.INFO to 'I', Log.WARN to 'W', Log.ERROR to 'E')

    val file: File get() = File(Host.logDir, "aura.log")

    fun install() {
        Log.sink = Log.Sink { priority, tag, message, error ->
            val line = buildString {
                append(LocalDateTime.now().format(stamp)).append(' ')
                append(levels[priority] ?: '?').append('/').append(tag).append(": ").append(message)
                if (error != null) append('\n').append(StringWriter().also { error.printStackTrace(PrintWriter(it)) })
            }
            write(line)
        }
    }

    @Synchronized
    private fun write(line: String) {
        System.err.println(line)
        runCatching {
            val f = file
            if (f.length() > MAX_BYTES) {
                val old = File(f.parentFile, "aura.1.log")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(line + "\n")
        }
    }
}
