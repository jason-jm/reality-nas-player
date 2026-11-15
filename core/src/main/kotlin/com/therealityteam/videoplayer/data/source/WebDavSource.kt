package com.therealityteam.videoplayer.data.source

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Base64

/**
 * WebDAV provider using PROPFIND (Depth:1, BFS over collections). Dependency-free
 * (HttpURLConnection + lightweight XML parse). Handles servers that disallow PROPFIND on
 * HttpURLConnection via a reflective method override.
 */
class WebDavSource : MediaSourceProvider {

    override val type = SourceType.WEBDAV

    override suspend fun list(config: MediaSourceConfig): List<DiscoveredVideo> = listing(config).videos

    override suspend fun listing(config: MediaSourceConfig): SourceListing =
        withContext(Dispatchers.IO) {
            val base = normalizeDir(config.uri)
            val origin = originOf(base) ?: return@withContext SourceListing(emptyList())
            val auth = authHeader(config.username, config.password)

            // Each video with the folder it is in, written without its trailing slash so the
            // folder above is the part before the last one.
            val videos = mutableListOf<Pair<String, Entry>>()
            // The nfo files and named pictures beside them, for Sidecars to choose from, and their
            // addresses (a name in a URL is escaped; the listing already has it right).
            val candidates = HashMap<String, HashMap<String, Long?>>()
            val addresses = HashMap<String, String>()
            val queue = ArrayDeque<String>().apply { add(base) }
            val seen = HashSet<String>()
            var dirsVisited = 0
            // Folders the server would not list: what was in them is not known to be gone (a whole
            // share that would not answer is one of them, and keeps everything).
            val unread = mutableListOf<String>()

            while (queue.isNotEmpty() && dirsVisited < MAX_DIRS && videos.size < MAX_FILES) {
                val dir = queue.removeFirst()
                if (!seen.add(dir)) continue
                dirsVisited++
                val body = propfind(dir, auth)
                if (body == null) {
                    unread += dir
                    continue
                }
                val folder = dir.trimEnd('/')
                for (entry in parseEntries(body, origin, dir)) {
                    when {
                        entry.isCollection -> if (entry.url != dir && !SkippedFolders.skip(entry.name)) queue.add(entry.url)
                        entry.name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS -> videos += folder to entry
                        Sidecars.isCandidate(entry.name) -> {
                            candidates.getOrPut(folder) { HashMap() }[entry.name] = null
                            addresses["$folder|${entry.name}"] = entry.url
                        }
                    }
                }
            }
            val local = Sidecars.pickAll(videos.map { it.first to it.second.name }, candidates, '/') { folder, name ->
                addresses["$folder|$name"] ?: "$folder/$name"
            }
            SourceListing(
                videos = videos.mapIndexed { i, (_, entry) -> DiscoveredVideo(path = entry.url, fileName = entry.name, local = local[i]) },
                unread = unread,
                // Stopped at a limit with folders still to list: what they hold is unknown.
                cut = queue.isNotEmpty(),
            )
        }

    /**
     * The files in one folder — name and address — for finding what lies beside a video (its
     * subtitles). One PROPFIND, no recursion; empty when the folder cannot be read.
     */
    suspend fun listFiles(dirUrl: String, username: String?, password: String?): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val dir = normalizeDir(dirUrl)
            val origin = originOf(dir) ?: return@withContext emptyList()
            val body = propfind(dir, authHeader(username, password)) ?: return@withContext emptyList()
            parseEntries(body, origin, dir).filter { !it.isCollection }.map { it.name to it.url }
        }

    internal data class Entry(val url: String, val name: String, val isCollection: Boolean)

    /** XML character references and the five named entities, as text. */
    private fun xmlText(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> runCatching {
                    val code = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                    String(Character.toChars(code))
                }.getOrDefault(m.value)
            }
        }
    }

    internal fun parseEntries(xml: String, origin: String, currentDir: String): List<Entry> {
        val hrefRegex = Regex("<[a-zA-Z0-9]*:?href>([^<]+)</[a-zA-Z0-9]*:?href>", RegexOption.IGNORE_CASE)
        return xml.split(Regex("</[a-zA-Z0-9]*:?response>", RegexOption.IGNORE_CASE)).mapNotNull { block ->
            // XML text: `Movie.chs&amp;eng.ass` is `Movie.chs&eng.ass`. Read as it stood, every file
            // with an ampersand in its name got an address the server answered 404.
            val rawHref = hrefRegex.find(block)?.groupValues?.get(1)?.trim()?.let(::xmlText) ?: return@mapNotNull null
            val url = resolveUrl(rawHref, origin)
            val isCollection = block.contains("<", true) &&
                Regex("collection\\s*/?>", RegexOption.IGNORE_CASE).containsMatchIn(block)
            // A path's own decoding: URLDecoder is a form's, and read Apache's bare + ("Tom & Jerry + Friends") as a
            // space — the file named, and scraped, as "Tom & Jerry   Friends".
            val name = URLDecoder.decode(url.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
            Entry(url = url, name = name, isCollection = isCollection)
        }.filter { it.name.isNotEmpty() }
    }

    private fun propfind(dir: String, auth: String?): String? = runCatching {
        sendPropfind(dir, buildMap {
            put("Depth", "1")
            put("Content-Type", "application/xml")
            auth?.let { put("Authorization", it) }
        }, PROPFIND_BODY)
    }.getOrNull()

    private fun authHeader(user: String?, pass: String?): String? =
        if (user.isNullOrEmpty()) null
        else "Basic " + Base64.getEncoder().encodeToString("$user:${pass ?: ""}".toByteArray())

    private fun normalizeDir(uri: String): String = if (uri.endsWith("/")) uri else "$uri/"

    private fun originOf(url: String): String? = runCatching {
        val u = URL(url)
        val port = if (u.port == -1) "" else ":${u.port}"
        "${u.protocol}://${u.host}$port"
    }.getOrNull()

    private fun resolveUrl(href: String, origin: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("/") -> origin + href
        else -> "$origin/$href"
    }

    companion object {
        /**
         * How a PROPFIND is sent: the body of a 2xx answer, else null. HttpURLConnection will not
         * take the method, and the reflective way around that is closed on Java 16+ — where it
         * quietly sent a POST, which a WebDAV server answers 404, so a desktop found nothing on any
         * WebDAV share. A platform with a client that sends any method hands it in here (the
         * desktop: java.net.http); Android keeps the old way, which works there.
         */
        @Volatile
        var sendPropfind: (url: String, headers: Map<String, String>, body: String) -> String? =
            { url, headers, body -> legacyPropfind(url, headers, body) }

        private fun legacyPropfind(url: String, headers: Map<String, String>, body: String): String? {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            return try {
                setMethod(conn, "PROPFIND")
                conn.connectTimeout = 8_000
                conn.readTimeout = 12_000
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
            } catch (e: Exception) {
                null
            } finally {
                conn.disconnect()
            }
        }

        private fun setMethod(conn: HttpURLConnection, method: String) {
            try {
                conn.requestMethod = method
            } catch (e: Exception) {
                runCatching {
                    val field = HttpURLConnection::class.java.getDeclaredField("method")
                    field.isAccessible = true
                    field.set(conn, method)
                }
            }
        }

        private const val MAX_DIRS = 200
        private const val MAX_FILES = 2000
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:resourcetype/><d:getcontenttype/></d:prop></d:propfind>"
    }
}
