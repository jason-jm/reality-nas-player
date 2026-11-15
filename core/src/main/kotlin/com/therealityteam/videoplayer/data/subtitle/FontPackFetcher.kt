package com.therealityteam.videoplayer.data.subtitle

import com.therealityteam.videoplayer.data.playback.LocalStreamServer
import com.therealityteam.videoplayer.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A video's fansub fonts: the pack beside it ([FontPacks]) — or, for a special in its release's SPs/, a
 * folder up — fetched once through the stream server (as the video itself is: a share's, an FTP / SFTP /
 * NFS server's, a WebDAV server's, or a file on the device), taken out into [FontCache], and picked from by
 * the faces a script names. The headset and the desktop both ask it.
 */
class FontPackFetcher(private val server: LocalStreamServer, cacheRoot: File, private val tmpDir: File) {

    private val cache = FontCache(cacheRoot)

    /**
     * The fonts answering to [wanted] for the video in [folder] (its address — `smb://nas/share/dir`, a
     * path), whose folder holds [names] (folders ending in "/"), at most [maxBytes] of them. Blocking:
     * the first time a show's pack is fetched whole.
     */
    fun pick(folder: String, names: List<String>, wanted: Set<String>, username: String?, password: String?, maxBytes: Long): List<File> {
        if (wanted.isEmpty()) return emptyList()
        val (here, there) = packsFor(folder, names, username, password) ?: return emptyList()
        for (pack in there) {
            val key = "$here/${pack.name}"
            val started = System.nanoTime()
            val kept = cache.cached(key)
            val fonts = kept ?: runCatching { fetch(here, pack, key, username, password) }
                .onFailure { Log.w(TAG, "font pack ${pack.name} not read: ${it.message}") }.getOrNull() ?: continue
            val picked = FontCache.pick(fonts, wanted, maxBytes)
            val found = picked.flatMapTo(HashSet()) { f -> f.names.map { it.lowercase() } }
            Log.i(
                TAG,
                "font pack ${pack.name.ifEmpty { "(loose)" }}: ${fonts.size} fonts${if (kept != null) " kept" else " taken out"} in " +
                    "${(System.nanoTime() - started) / 1_000_000} ms, ${picked.size} for the script " +
                    "(${picked.sumOf { it.file.length() } / 1024} KB); not in it: ${wanted.filter { it.lowercase() !in found }}",
            )
            if (picked.isNotEmpty()) return picked.map { it.file }
        }
        return emptyList()
    }

    /** The folder whose packs serve the video, and them: its own, else — from a specials folder — the one up. */
    private fun packsFor(folder: String, names: List<String>, username: String?, password: String?): Pair<String, List<FontPacks.Pack>>? {
        FontPacks.packsIn(names).takeIf { it.isNotEmpty() }?.let { return folder to it }
        if (!FontPacks.isExtrasFolder(folder.trimEnd('/').substringAfterLast('/'))) return null
        val up = folder.trimEnd('/').substringBeforeLast('/').takeIf { it.isNotEmpty() && (it.contains('/') || it.startsWith("/")) } ?: return null
        val listed = server.listFolder(up, username, password) ?: return null
        return FontPacks.packsIn(listed).takeIf { it.isNotEmpty() }?.let { up to it }
    }

    private fun fetch(folder: String, pack: FontPacks.Pack, key: String, username: String?, password: String?): List<FontCache.Font> =
        when (pack.kind) {
            FontPacks.Kind.SEVEN_ZIP, FontPacks.Kind.ZIP -> {
                tmpDir.mkdirs()
                val tmp = File.createTempFile("fontpack", ".part", tmpDir)
                try {
                    fetchTo("$folder/${pack.name}", username, password, tmp, MAX_PACK_BYTES)
                    cache.fromArchive(key, tmp, pack.kind)
                } finally {
                    tmp.delete()
                }
            }
            FontPacks.Kind.FOLDER -> {
                val inside = server.listFolder("$folder/${pack.name}", username, password).orEmpty()
                cache.fromFiles(key, inside.filter { !it.endsWith("/") && FontPacks.isFontFile(it) }.mapNotNull { n -> font("$folder/${pack.name}/$n", username, password)?.let { n to it } })
            }
            FontPacks.Kind.LOOSE -> {
                val inside = server.listFolder(folder, username, password).orEmpty()
                cache.fromFiles(key, inside.filter { !it.endsWith("/") && FontPacks.isFontFile(it) }.mapNotNull { n -> font("$folder/$n", username, password)?.let { n to it } })
            }
        }

    private fun font(uri: String, username: String?, password: String?): ByteArray? {
        tmpDir.mkdirs()
        val tmp = File.createTempFile("font", ".part", tmpDir)
        return try {
            fetchTo(uri, username, password, tmp, FontCache.MAX_FONT)
            tmp.readBytes()
        } catch (_: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    /** [uri] into [to], at most [max] bytes: a file as it is, a share's through the stream server, a WebDAV server's with its login. */
    private fun fetchTo(uri: String, username: String?, password: String?, to: File, max: Long) {
        if (!uri.contains("://") || uri.startsWith("file://")) {
            val f = File(uri.removePrefix("file://"))
            require(f.length() <= max) { "${f.length()} bytes" }
            f.copyTo(to, overwrite = true)
            return
        }
        val key = "fontpack:$uri"
        val served = LocalStreamServer.serves(uri)
        val url = if (served) server.register(uri, username, password, key) ?: error("not served: $uri") else uri
        try {
            val conn = URL(url.replace(" ", "%20")).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 20_000
            if (!served && !username.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString("$username:${password.orEmpty()}".toByteArray()))
            }
            require(conn.contentLengthLong <= max) { "${conn.contentLengthLong} bytes" }
            conn.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        require(total <= max) { "past $max bytes" }
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            if (served) server.unregister(key)
        }
    }

    companion object {
        private const val TAG = "AuraAss"

        /** A fansub's font archive fetched whole, once a show: past this it is someone's font collection, not a show's. */
        const val MAX_PACK_BYTES = 600L * 1024 * 1024
    }
}
