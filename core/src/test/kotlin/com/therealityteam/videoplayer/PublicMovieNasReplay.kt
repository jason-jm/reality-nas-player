package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import com.therealityteam.videoplayer.data.scrape.*
import com.therealityteam.videoplayer.data.source.SidecarReader
import com.therealityteam.videoplayer.data.source.Sidecars
import com.therealityteam.videoplayer.data.source.VIDEO_EXTENSIONS
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Read the actual mounted media folders. All library and artwork writes stay in the private test output. */
class PublicMovieNasReplay {
    @Test fun existingLocalMetadata() {
        val root = System.getProperty("aura.publicMovies.live")?.let(::File) ?: return
        val cases = JSONArray(File(root, "cohort.json").readText())
        val data = File(root, "nas-test-data")
        val readLocal = SidecarReader { _, path, limit -> File(path).takeIf { it.isFile && it.length() <= limit }?.readBytes() }
        val originals = Triple(TmdbConfig.offline, TmdbConfig.readLocal, TmdbConfig.language)
        val online = System.getProperty("aura.publicMovies.online") == "true"
        val requests = linkedSetOf<String>()
        val catalog = if (System.getProperty("aura.publicMovies.liveWeb") == "true") PublicMovieCatalog(data) else PublicMovieCatalog { url, _ ->
            val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val body = File(root, "bodies/$hash.json")
            if (body.exists()) JSONObject(body.readText()) else { requests += url; null }
        }
        val disk = HashMap<File, List<File>>()
        fun files(dir: File) = disk.getOrPut(dir) { dir.listFiles()?.toList().orEmpty() }
        fun mountedFile(path: String): File {
            val direct = File(path)
            if (direct.isFile) return direct
            val normal = java.text.Normalizer.Form.NFC
            var parent = File("/")
            for (name in path.trimStart('/').split('/')) {
                val wanted = java.text.Normalizer.normalize(name, normal)
                val literal = File(parent, name)
                parent = if (literal.exists()) literal else files(parent).first {
                    java.text.Normalizer.normalize(it.name, normal) == wanted
                }
            }
            return parent
        }
        fun candidates(list: List<File>) = list.filter { Sidecars.isCandidate(it.name) }.associate { it.name to it.length() }
        try {
            TmdbConfig.offline = !online
            TmdbConfig.language = "zh-CN"
            check(TmdbConfig.effectiveKey.isBlank())
            TmdbConfig.readLocal = true
            val engine = ScrapeEngine(TmdbMetadataClient(data), ArtworkCache(data) { _, _, _ -> null },
                JsonMediaRepository(data), AniListClient(data), BangumiClient(data), OmdbClient(data), JikanClient(data),
                ImdbDatasetClient(data), readLocal, if (online) catalog else null)
            File(root, if (online) "nas-combined.jsonl" else "nas-local.jsonl").bufferedWriter().use { out ->
                runBlocking {
                    for (i in 0 until cases.length()) {
                        val row = cases.getJSONObject(i)
                        val file = mountedFile(row.getString("path"))
                        check(file.isFile && file.length() > 0) { "NAS inventory entry ${row.getString("id")} changed" }
                        val siblings = files(file.parentFile)
                        val parent = file.parentFile.parentFile
                        val local = if (System.getProperty("aura.publicMovies.noSidecars") == "true") null else Sidecars.pick(file.name, file.parent, candidates(siblings),
                            parent.path, candidates(files(parent)), siblings.count { it.extension.lowercase() in VIDEO_EXTENSIONS }
                        ) { dir, name -> File(dir, name).path }
                        val item = MediaItem(row.getString("id"), "nas", file.path, file.name, title = file.name, local = local)
                        val result = if (local != null || online) engine.scrape(item) else item
                        val art = result.posterPath?.let(::File)
                        val magic = art?.takeIf { it.isFile }?.inputStream()?.use { input -> ByteArray(12).also { input.read(it) } }
                        val poster = magic != null && (magic[0] == 0xff.toByte() && magic[1] == 0xd8.toByte() ||
                            magic.copyOfRange(1, 4).decodeToString() == "PNG" || magic.copyOfRange(8, 12).decodeToString() == "WEBP")
                        val nfo = local?.nfo?.let { path -> readLocal.read(item, path, 1024 * 1024) }?.let { KodiNfo.parse(KodiNfo.text(it)) }
                        out.appendLine(JSONObject().put("id", item.id).put("matched", result.scrapeStatus == ScrapeStatus.MATCHED).put("fromNfo", result.fromNfo)
                            .put("key", result.collectionKey).put("nfoCredit", result.nfoCredit).put("kind", result.kind.name)
                            .put("title", result.title).put("original", result.originalTitle).put("aliases", JSONArray(result.aliases))
                            .put("year", result.year).put("tmdb", result.tmdbId).put("imdb", nfo?.imdbId)
                            .put("overview", result.overview?.take(140)).put("poster", poster).toString())
                    }
                }
            }
        } finally {
            TmdbConfig.offline = originals.first
            TmdbConfig.readLocal = originals.second
            TmdbConfig.language = originals.third
            if (online) File(root, "requested-nas.txt").writeText(requests.joinToString("\n"))
        }
    }
}
