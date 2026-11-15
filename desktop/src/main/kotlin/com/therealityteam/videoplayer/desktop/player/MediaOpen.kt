package com.therealityteam.videoplayer.desktop.player

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.playback.ExternalAudio
import com.therealityteam.videoplayer.data.playback.SidecarAudio
import com.therealityteam.videoplayer.data.playback.ProjectionDetector
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.playback.LocalStreamServer
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.data.source.JellyfinClient
import com.therealityteam.videoplayer.data.source.PlexClient
import com.therealityteam.videoplayer.data.source.WebDavSource
import com.therealityteam.videoplayer.data.source.SmbSource
import com.therealityteam.videoplayer.data.subtitle.SubtitleEngine
import com.therealityteam.videoplayer.data.subtitle.SubtitleTranscoder
import com.therealityteam.videoplayer.data.subtitle.TtmlToSrt
import com.therealityteam.videoplayer.desktop.platform.Desktop
import com.therealityteam.videoplayer.desktop.platform.Host
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.Subtitle
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.net.URLEncoder
import java.util.Base64

/**
 * How a library entry becomes something mpv can open — the desktop's counterpart of the headset's
 * startSource: local files as they are, SMB through the loopback proxy (with the subtitles beside
 * the video), media servers signed with their token, WebDAV with its password. And the subtitle
 * files that belong to a video, wherever it lives.
 */
class MediaOpen(private val sources: SourceRepository, private val proxy: LocalStreamServer) {

    /** What the last open registered with the proxy, released when the next one opens. */
    private val proxied = mutableListOf<String>()

    /**
     * The 外挂音轨 of the last file opened — sound files named after it beside it (a dub's .mka), as mpv
     * opens them — found by the same folder listing as its subtitles ([ExternalAudio]), each with what
     * the menus call it (国语) and the language its name gives.
     */
    var audioFiles: List<SidecarAudio> = emptyList()
        private set

    /** Where the last file opened has its chapters, when it is a disc's film (its playlist's marks): mpv finds none in its streams. */
    var discChaptersMs: List<Long> = emptyList()
        private set

    /** The last file's folder — its address and the names in it, folders ending in "/" — where a fansub's fonts are looked for. */
    var beside: Pair<String, List<String>>? = null
        private set

    /** The login of the source [item] is on (none for a file of no source). */
    fun loginOf(item: MediaItem): Pair<String?, String?> = sourceFor(item).let { it?.username to it?.password }

    fun release() {
        proxied.forEach { proxy.unregister(it) }
        proxied.clear()
    }

    suspend fun open(item: MediaItem): UriMediaData {
        release()
        audioFiles = emptyList()
        discChaptersMs = emptyList()
        beside = null
        val path = item.path
        val src = sourceFor(item)
        // A data disc's video (a tutorial's .mov inside its ISO): served from where it lies in the image.
        com.therealityteam.videoplayer.data.disc.DiscImages.innerOf(path)?.let { (image, inner) ->
            val at = if (Desktop.isLocal(image)) File(image).absolutePath else image
            val url = withContext(Dispatchers.IO) { proxy.registerDiscFile(at, src?.username, src?.password, inner, key = path) }
                ?: throw DiscProblem(DiscProblem.Kind.UNREADABLE)
            proxied += path
            return UriMediaData(url)
        }
        // 原盘: a disc image's film, read out of it and streamed as one .m2ts / .vob (DiscTitle).
        if (com.therealityteam.videoplayer.data.disc.DiscImages.isImage(path)) {
            val open = withContext(Dispatchers.IO) {
                proxy.registerDisc(if (Desktop.isLocal(path)) File(path).absolutePath else path, src?.username, src?.password)
            }
            proxied += if (Desktop.isLocal(path)) File(path).absolutePath else path
            return when (open) {
                is com.therealityteam.videoplayer.data.playback.DiscOpen.Playing -> UriMediaData(open.url).also { discChaptersMs = open.title.chaptersMs }
                is com.therealityteam.videoplayer.data.playback.DiscOpen.Encrypted -> throw DiscProblem(DiscProblem.Kind.ENCRYPTED)
                com.therealityteam.videoplayer.data.playback.DiscOpen.NoFilm -> throw DiscProblem(DiscProblem.Kind.NO_FILM)
                com.therealityteam.videoplayer.data.playback.DiscOpen.Unreadable -> throw DiscProblem(DiscProblem.Kind.UNREADABLE)
                // A data disc of videos: the player plays them one after another (DataDisc).
                is com.therealityteam.videoplayer.data.playback.DiscOpen.Videos -> throw DataDisc(open.paths)
            }
        }
        // A disc ripped as its files (BDMV / VIDEO_TS on a share): its playlist's clips as one stream, as
        // an image's film is — not the largest clip alone. One with no playlist plays its file, as before.
        if (com.therealityteam.videoplayer.data.disc.DiscImages.ripFolderOf(path, src?.type) != null) {
            val at = if (Desktop.isLocal(path)) File(path).absolutePath else path
            val open = withContext(Dispatchers.IO) { proxy.registerDiscFolder(at, src?.username, src?.password) }
            when (open) {
                is com.therealityteam.videoplayer.data.playback.DiscOpen.Playing -> {
                    proxied += at
                    discChaptersMs = open.title.chaptersMs
                    return UriMediaData(open.url)
                }
                is com.therealityteam.videoplayer.data.playback.DiscOpen.Encrypted -> throw DiscProblem(DiscProblem.Kind.ENCRYPTED)
                else -> Unit
            }
        }
        return when {
            path.startsWith("smb://") -> {
                val url = proxy.register(path, src?.username, src?.password)
                    ?: error("the SMB proxy could not take $path")
                proxied += path
                UriMediaData(url, extraFiles = MediaExtraFiles(smbSidecars(path, src)))
            }
            com.therealityteam.videoplayer.data.source.RemoteAddress.parse(path) != null -> {
                val url = proxy.register(path, src?.username, src?.password)
                    ?: error("the proxy could not take $path")
                proxied += path
                UriMediaData(url, extraFiles = MediaExtraFiles(remoteSidecars(path, src)))
            }
            path.startsWith("http") && src != null && !src.token.isNullOrBlank() &&
                (src.type == SourceType.JELLYFIN || src.type == SourceType.EMBY) ->
                UriMediaData(path + "&api_key=" + URLEncoder.encode(src.token, "UTF-8"), extraFiles = MediaExtraFiles(serverSidecars(item, src)))
            path.startsWith("http") && src != null && !src.token.isNullOrBlank() && src.type == SourceType.PLEX ->
                UriMediaData(PlexClient.signed(path, src.token.orEmpty()))
            path.startsWith("http") && src?.type == SourceType.WEBDAV ->
                // The headset never sent the password when playing; mpv takes the header — for the
                // subtitles beside the video too, which the headset never looked for on WebDAV.
                UriMediaData(
                    path,
                    headers = if (src.username.isNullOrBlank()) emptyMap() else mapOf("Authorization" to basic(src.username, src.password)),
                    extraFiles = MediaExtraFiles(webDavSidecars(path, src)),
                )
            Desktop.isLocal(path) -> {
                val file = File(path)
                UriMediaData(file.absolutePath, extraFiles = MediaExtraFiles(localSidecars(file)))
            }
            path.startsWith("file:") -> UriMediaData(path)
            else -> UriMediaData(path)
        }
    }

    /**
     * A Jellyfin / Emby film's subtitle files, as its server keeps them beside it — the direct-play stream is the
     * video file alone, so they never came with it (as on the headset: PlaybackViewModel.playMediaServer). Each
     * as the server serves it, in UTF-8, named by its file as a share's sidecar is.
     */
    private suspend fun serverSidecars(item: MediaItem, src: MediaSourceConfig): List<Subtitle> {
        val itemId = JellyfinClient.itemIdOf(item.path) ?: return emptyList()
        val user = src.serverUserId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val external = withTimeoutOrNull(3_000) {
            try {
                JellyfinClient().externalSubtitles(item.path.substringBeforeLast("/Videos/"), user, src.token.orEmpty(), itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }.orEmpty()
        return external.map { sub ->
            val name = sub.fileName ?: "${item.fileName.substringBeforeLast('.')}.${sub.language ?: "sub"}.${sub.format}"
            val tag = SubtitleEngine.matchSidecars(item.fileName, listOf(name)).firstOrNull()?.language ?: sub.language
            Subtitle(uri = readable(sub.url, name), language = tag, label = name)
        }
    }

    /** A disc image that has no film to play — encrypted, without one, or out of reach. */
    class DiscProblem(val kind: Kind) : Exception("disc image: $kind") {
        enum class Kind { ENCRYPTED, NO_FILM, UNREADABLE }
    }

    /** An image with no film but videos ([paths] in it): what plays is them, one after another. */
    class DataDisc(val paths: List<String>) : Exception("a data disc of ${paths.size} videos")

    /** An ad-hoc file or address the user opened, with the subtitles next to it. */
    suspend fun openAdHoc(pathOrUrl: String): UriMediaData = open(adHocItem(pathOrUrl))

    private fun sourceFor(item: MediaItem): MediaSourceConfig? =
        sources.get(item.sourceId)


            ?: sources.all()
                .filter { item.path.startsWith(it.uri.trimEnd('/') + "/") || item.path == it.uri }
                .maxByOrNull { it.uri.length }

    /**
     * Subtitle files for a local video: beside it, and in the Subs / Subtitles folder release
     * groups put them in (by name, or all of them in a folder of the video's own name). Text
     * subtitles in another encoding are copied to UTF-8 first; picture ones (VobSub, PGS) as is.
     */
    private suspend fun localSidecars(video: File): List<Subtitle> = withContext(Dispatchers.IO) {
        val dir = video.parentFile ?: return@withContext emptyList()
        val found = mutableListOf<Pair<File, String?>>()
        fun match(folder: File, all: Boolean) {
            val names = folder.listFiles()?.filter { it.isFile && it.length() > 0 }?.map { it.name }.orEmpty()
            if (all) {
                // A Subs/<video stem>/ folder: everything in it is for this video.
                names.filter { it.substringAfterLast('.', "").lowercase() in SUBTITLE_EXTENSIONS }
                    .sorted().forEach { found += File(folder, it) to it.substringBeforeLast('.') }
            } else {
                SubtitleEngine.matchSidecars(video.name, names, imageFormats = true)
                    .forEach { found += File(folder, it.fileName) to it.language }
            }
        }
        match(dir, all = false)
        beside = dir.absolutePath to dir.listFiles()?.map { if (it.isDirectory) "${it.name}/" else it.name }.orEmpty()
        audioFiles = ExternalAudio.match(video.name, dir.list()?.toList().orEmpty()).map { SidecarAudio(File(dir, it.fileName).absolutePath, it.label, it.language) }
        dir.listFiles()?.filter { it.isDirectory && it.name.lowercase() in SUBTITLE_FOLDERS }?.forEach { subs ->
            match(subs, all = false)
            val own = File(subs, video.nameWithoutExtension)
            if (own.isDirectory) match(own, all = true)
        }
        val cache = File(Host.dataDir, "aura/cache") // the transcoder files copies under cache/subtitles
        found.distinctBy { it.first.canonicalPath }.map { (file, lang) ->
            val playable = when (file.extension.lowercase()) {
                in TEXT_EXTENSIONS -> utf8Copy(file, cache).absolutePath
                in TTML_EXTENSIONS -> runCatching { srtCopy(file.readBytes(), file.name, file.absolutePath + ":" + file.lastModified()) }.getOrNull()
                    ?: file.absolutePath
                else -> file.absolutePath
            }
            Subtitle(uri = playable, language = lang, label = file.name)
        }
    }

    /**
     * Subtitle files next to an SMB video: one bounded folder listing, each hit served through
     * the proxy. A slow or failing NAS must not delay the video itself.
     */
    private suspend fun smbSidecars(path: String, src: MediaSourceConfig?): List<Subtitle> {
        val rest = path.removePrefix("smb://")
        val host = rest.substringBefore('/')
        val share = rest.substringAfter('/').substringBefore('/')
        val full = rest.substringAfter('/').substringAfter('/', "")
        val dir = full.substringBeforeLast('/', "")
        val fileName = full.substringAfterLast('/')
        if (fileName.isEmpty()) return emptyList()
        val names = withTimeoutOrNull(4_000) {
            try {
                withContext(Dispatchers.IO) { SmbSource().listFileNames(host, share, dir, src?.username, src?.password, withFolders = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        } ?: return emptyList()
        beside = ("smb://$host/$share" + (if (dir.isEmpty()) "" else "/$dir")) to names
        // …and the release's Subs/ (a fansub's sub/), chosen as the headset chooses: by name, by the
        // episode's number, or for a film its unnumbered ones.
        val folder = com.therealityteam.videoplayer.data.subtitle.SubtitleFolders.folderIn(names)
        val inFolder = folder?.let { f ->
            withTimeoutOrNull(3_000) {
                try {
                    withContext(Dispatchers.IO) {
                        SmbSource().listFileNames(host, share, listOf(dir, f).filter { it.isNotEmpty() }.joinToString("/"), src?.username, src?.password)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.orEmpty()
        val isFeature = com.therealityteam.videoplayer.ui.library.EpisodeNaming.parse(
            com.therealityteam.videoplayer.data.model.MediaItem(path, "", path, fileName, title = fileName),
        ).cat == com.therealityteam.videoplayer.ui.library.EpisodeNaming.Cat.FEATURE
        val found = (
            SubtitleEngine.matchSidecars(fileName, names, imageFormats = true).map { it to it.fileName } +
                folder?.let { f ->
                    com.therealityteam.videoplayer.data.subtitle.SubtitleFolders.match(fileName, inFolder, isFeature).map { it to "$f/${it.fileName}" }
                }.orEmpty()
            ).distinctBy { it.first.fileName }
        audioFiles = ExternalAudio.match(fileName, names).mapNotNull { a ->
            val smbUri = "smb://$host/$share" + (if (dir.isEmpty()) "" else "/$dir") + "/${a.fileName}"
            proxy.register(smbUri, src?.username, src?.password)?.let { url -> proxied += smbUri; SidecarAudio(url, a.label, a.language) }
        }
        return found.mapNotNull { (c, relative) ->
            val smbUri = "smb://$host/$share" + (if (dir.isEmpty()) "" else "/$dir") + "/$relative"
            proxy.register(smbUri, src?.username, src?.password)?.let { url ->
                proxied += smbUri
                Subtitle(uri = readable(url, c.fileName), language = c.language, label = c.label)
            }
        }
    }

    /**
     * Subtitle files and dubs next to a video on an FTP or SFTP server: the folder listed over the
     * stream's own connections (the proxy's), within the same bounds as a share's, each hit served
     * through the proxy.
     */
    private suspend fun remoteSidecars(path: String, src: MediaSourceConfig?): List<Subtitle> {
        val folderUri = path.substringBeforeLast('/')
        val fileName = path.substringAfterLast('/')
        if (fileName.isEmpty()) return emptyList()
        suspend fun listed(ms: Long, block: () -> List<String>): List<String>? = withTimeoutOrNull(ms) {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
        val names = listed(4_000) { proxy.siblings(path) } ?: return emptyList()
        beside = folderUri to names
        val folder = com.therealityteam.videoplayer.data.subtitle.SubtitleFolders.folderIn(names)
        val inFolder = folder?.let { f -> listed(3_000) { proxy.listBeside(path, f) } }.orEmpty()
        val isFeature = com.therealityteam.videoplayer.ui.library.EpisodeNaming.parse(
            com.therealityteam.videoplayer.data.model.MediaItem(path, "", path, fileName, title = fileName),
        ).cat == com.therealityteam.videoplayer.ui.library.EpisodeNaming.Cat.FEATURE
        val found = (
            SubtitleEngine.matchSidecars(fileName, names, imageFormats = true).map { it to it.fileName } +
                folder?.let { f ->
                    com.therealityteam.videoplayer.data.subtitle.SubtitleFolders.match(fileName, inFolder, isFeature).map { it to "$f/${it.fileName}" }
                }.orEmpty()
            ).distinctBy { it.first.fileName }
        audioFiles = ExternalAudio.match(fileName, names).mapNotNull { a ->
            val uri = "$folderUri/${a.fileName}"
            proxy.register(uri, src?.username, src?.password)?.let { url -> proxied += uri; SidecarAudio(url, a.label, a.language) }
        }
        return found.mapNotNull { (c, relative) ->
            val uri = "$folderUri/$relative"
            proxy.register(uri, src?.username, src?.password)?.let { url ->
                proxied += uri
                Subtitle(uri = readable(url, c.fileName), language = c.language, label = c.label)
            }
        }
    }

    /**
     * A text subtitle on a share or server, as mpv can read it. mediamp's libmpv is built without
     * charset guessing, so a GBK or Big5 file — still the common case for Chinese subtitles —
     * came out as mojibake from a NAS (local files were already converted). Text sidecars are
     * small: fetched here, and when not UTF-8, a UTF-8 copy under the cache takes their place —
     * under their own name, which mpv shows. Anything that fails keeps the address.
     */
    private suspend fun readable(url: String, name: String, headers: Map<String, String> = emptyMap()): String {
        val ext = name.substringAfterLast('.').lowercase()
        if (ext !in TEXT_EXTENSIONS && ext !in TTML_EXTENSIONS) return url
        val bytes = withTimeoutOrNull(4_000) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url.replace(" ", "%20")))
                        .timeout(java.time.Duration.ofSeconds(4)).apply { headers.forEach { (k, v) -> header(k, v) } }.GET().build()
                    val response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
                    if (response.statusCode() !in 200..299) return@runCatching null
                    response.body().use { it.readNBytes((SubtitleTranscoder.MAX_BYTES + 1).toInt()) }.takeIf { it.size <= SubtitleTranscoder.MAX_BYTES }
                }.getOrNull()
            }
        } ?: return url
        if (ext in TTML_EXTENSIONS) return srtCopy(bytes, name, url.substringBefore('?')) ?: url
        val from = SubtitleTranscoder.charsetToConvert(bytes) ?: return url
        return runCatching {
            val key = Integer.toHexString((url.substringBefore('?') + ":" + bytes.size + ":" + bytes.contentHashCode()).hashCode())
            val copy = File(File(Host.dataDir, "aura/cache/subtitles/$key"), name)
            if (!copy.isFile) {
                copy.parentFile.mkdirs()
                copy.writeBytes(SubtitleTranscoder.toUtf8(bytes, from))
            }
            copy.absolutePath
        }.getOrDefault(url)
    }

    /** Subtitle files next to a WebDAV video: one bounded folder listing, played from the server. */
    private suspend fun webDavSidecars(path: String, src: MediaSourceConfig): List<Subtitle> {
        val fileName = runCatching { java.net.URLDecoder.decode(path.substringAfterLast('/'), "UTF-8") }.getOrNull() ?: return emptyList()
        val dir = path.substringBeforeLast('/') + "/"
        val entries = withTimeoutOrNull(4_000) {
            try {
                WebDavSource().listFiles(dir, src.username, src.password)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        } ?: return emptyList()
        val byName = entries.toMap()
        // (mpv sends the main file's Authorization with them too: its header fields are the player's.)
        audioFiles = ExternalAudio.match(fileName, entries.map { it.first }).mapNotNull { a -> byName[a.fileName]?.let { SidecarAudio(it, a.label, a.language) } }
        val auth = if (src.username.isNullOrBlank()) emptyMap() else mapOf("Authorization" to basic(src.username, src.password))
        return SubtitleEngine.matchSidecars(fileName, entries.map { it.first }, imageFormats = true).mapNotNull { c ->
            byName[c.fileName]?.let { url -> Subtitle(uri = readable(url, c.fileName, auth), language = c.language, label = c.label) }
        }
    }

    /**
     * TTML (or DFXP) as SubRip, which mpv reads: FFmpeg writes TTML but cannot read it, so the
     * file was a track with nothing in it. The copy lives in the cache under the original's name
     * with .srt for its extension; null when the file would not convert.
     */
    private fun srtCopy(bytes: ByteArray, name: String, key: String): String? {
        val srt = TtmlToSrt.convert(bytes) ?: return null
        val dir = File(Host.dataDir, "aura/cache/subtitles/" + Integer.toHexString((key + ":" + bytes.contentHashCode()).hashCode()))
        val copy = File(dir, name.substringBeforeLast('.') + ".srt")
        if (!copy.isFile) {
            dir.mkdirs()
            copy.writeText(srt)
        }
        return copy.absolutePath
    }

    /**
     * A text subtitle mpv can read: the file itself when it is UTF-8 already, else a UTF-8 copy —
     * kept under its own name (in a folder named for the original) because mpv names the track
     * after the file, and "e5369ad2.ass" tells nobody which subtitle it is.
     */
    private fun utf8Copy(file: File, cache: File): File {
        val converted = SubtitleTranscoder.utf8Path(file, cache)
        if (converted == file) return file
        val named = File(File(converted.parentFile, converted.nameWithoutExtension), file.name)
        if (!named.isFile || named.length() != converted.length()) {
            named.parentFile.mkdirs()
            runCatching { converted.copyTo(named, overwrite = true) }.onFailure { return converted }
        }
        return named
    }

    private fun basic(user: String?, password: String?): String =
        "Basic " + Base64.getEncoder().encodeToString("${user.orEmpty()}:${password.orEmpty()}".toByteArray())

    companion object {
        /** For fetching subtitle files from the proxy or a server. */
        private val http: java.net.http.HttpClient by lazy {
            java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(4))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build()
        }

        private val OWNERS = setOf(SourceType.SMB, SourceType.WEBDAV, SourceType.FTP, SourceType.SFTP, SourceType.NFS)

        /** The configured share or file server an address lies under (the deepest, if several). */
        fun owner(address: String, sources: List<MediaSourceConfig>): MediaSourceConfig? =
            sources
                .filter { it.type in OWNERS && it.uri.isNotBlank() }
                .filter { address.startsWith(it.uri.trimEnd('/') + "/", ignoreCase = true) }
                .maxByOrNull { it.uri.length }

        /**
         * What an opened file or address is called: its last part — for an address without its
         * query and with its %-escapes read ("My%20Film.mkv?token=…" is "My Film.mkv"), and the
         * server's name when the address ends at it.
         */
        internal fun displayName(pathOrUrl: String): String {
            val url = "://" in pathOrUrl
            val bare = if (url) pathOrUrl.substringBefore('?').substringBefore('#') else pathOrUrl
            val last = bare.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
            if (!url) return last
            if (last.isEmpty() || bare.trimEnd('/').endsWith("//$last")) return bare.substringAfter("://").substringBefore('/').ifEmpty { pathOrUrl }
            return runCatching { java.net.URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
        }

        private val SUBTITLE_FOLDERS = setOf("subs", "subtitles", "sub", "字幕")
        private val TEXT_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")
        private val TTML_EXTENSIONS = setOf("ttml", "dfxp")
        private val SUBTITLE_EXTENSIONS = TEXT_EXTENSIONS + TTML_EXTENSIONS + setOf("idx", "sup")

        /**
         * A file or address opened by hand: not in the library, played as itself — with the
         * credentials of [owner], a source it lies under, if there is one.
         */
        fun adHocItem(pathOrUrl: String, owner: MediaSourceConfig? = null): MediaItem {
            val name = displayName(pathOrUrl)
            return MediaItem(
                id = "adhoc:" + pathOrUrl.hashCode().toUInt().toString(16),
                sourceId = owner?.id ?: "adhoc",
                path = pathOrUrl,
                fileName = name,
                title = name.substringBeforeLast('.'),
                // 3D and spherical files say so in their names, as the scraper reads them — not by
                // a 2:1 frame, which is also the shape of many a flat series.
                video = ProjectionDetector.detect(name),
            )
        }
    }
}
