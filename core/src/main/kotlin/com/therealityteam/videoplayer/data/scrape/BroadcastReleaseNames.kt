package com.therealityteam.videoplayer.data.scrape

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/** Published release notes can supply the original broadcast name for a translated filename. */
internal class BroadcastReleaseNames(private val read: suspend (String, String) -> JSONObject?) {
    data class Broadcast(val original: String, val year: Int)

    private val lock = Mutex()
    private var byTitle: Map<String, List<Int>>? = null

    suspend fun find(file: String): Broadcast? {
        if (TmdbConfig.offline) return null
        val local = LABELLED_FILE.matchEntire(file)?.groupValues?.get(1) ?: return null
        val key = normalized(local)
        if (key.length !in 4..120) return null
        val ids = lock.withLock {
            val titles = byTitle ?: titleIndex()?.also { byTitle = it }
            titles?.get(key)
        }
        val id = ids?.singleOrNull() ?: return null
        val post = read("$API/$id?fields=ID,title,content", "ja") ?: return null
        if (post.optInt("ID") != id || normalized(post.str("title").orEmpty()) != key) return null
        val html = post.str("content")?.takeIf { it.length <= 64_000 } ?: return null
        val lines = Synopsis.unescape(html.replace(BREAK, "\n").replace(TAG, " ")).lineSequence()
            .map { it.trim() }.filter(String::isNotEmpty).toList()
        val releases = lines.flatMap { line -> RELEASE.findAll(line).map { normalized(it.value) }.toList() }.distinct()
        if (releases.singleOrNull() != normalized(file)) return null
        val header = lines.take(5).filter { it.length <= 280 }
        val original = header.firstNotNullOfOrNull { line ->
            if (!normalized(line).startsWith(key)) return@firstNotNullOfOrNull null
            val end = line.indices.firstOrNull { normalized(line.substring(0, it + 1)) == key } ?: return@firstNotNullOfOrNull null
            line.substring(end + 1).trim { it.isWhitespace() || it in "：:–—-～~。.‘’“”" }
                .takeIf { it.length in 3..120 && it.any { c -> c in 'ぁ'..'ヺ' } && "://" !in it }
        } ?: return null
        val date = header.firstNotNullOfOrNull { DATE.find(it) } ?: return null
        val year = date.groupValues[1].toInt()
        if (runCatching { LocalDate.of(year, date.groupValues[2].toInt(), date.groupValues[3].toInt()) }.isFailure) return null
        if (header.none { MINUTES.containsMatchIn(it) }) return null
        return Broadcast(original, year)
    }

    private suspend fun titleIndex(): Map<String, List<Int>>? {
        val records = mutableMapOf<Int, String>()
        for (page in 1..6) {
            val result = read("$API/?number=100&page=$page&fields=ID,title", "ja") ?: return null
            val found = result.optInt("found", -1)
            if (found !in 1..600) return null
            val posts = result.objects("posts")
            for (post in posts) {
                val id = post.optInt("ID")
                if (id > 0) post.str("title")?.let { records[id] = normalized(it) }
            }
            if (records.size == found) return records.entries.groupBy({ it.value }, { it.key })
            if (posts.size < 100) return null
        }
        return null
    }

    companion object {
        private const val API = "https://public-api.wordpress.com/rest/v1.1/sites/kamonka.wordpress.com/posts"
        private val LABELLED_FILE = Regex("""(?i)^\[NHK]\[(?:纪录片|紀錄片|記錄片)]\s*(.+)\.(?:avi|mkv|mp4|rmvb|rm|wmv|mpg|mpeg)$""")
        private val RELEASE = Regex("""(?i)\[NHK]\[[^\]]{1,25}][^/<>|\r\n]{2,180}?\.(?:avi|mkv|mp4|rmvb|rm|wmv|mpg|mpeg)\b""")
        private val BREAK = Regex("""(?i)<(?:br\s*/?|/p|/div|/li|/h[1-6])\s*>""")
        private val TAG = Regex("""<[^>]{0,1024}>""")
        private val DATE = Regex("""(?<!\d)((?:19|20)\d{2})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日""")
        private val MINUTES = Regex("""(?i)(?<!\d)[1-9]\d{0,2}\s*(?:mins?\b|minutes?\b|分钟|分鐘|分間)""")
        private fun normalized(value: String): String = Normalizer.normalize(Synopsis.unescape(value), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    }
}
