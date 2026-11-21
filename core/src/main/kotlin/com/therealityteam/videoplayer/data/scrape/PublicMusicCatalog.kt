package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.MediaKind
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/** A named concert or music documentary can have a public record of the actual moving image. */
internal class PublicMusicCatalog(private val read: suspend (String, String) -> JSONObject?) {
    suspend fun match(path: String, file: String, parsed: ParsedName): MetaResult? {
        if (TmdbConfig.offline) return null
        val title = releaseTitle(path,file,parsed) ?: return null
        val q = URLEncoder.encode("mediatype:movies AND title:\"$title\"", "UTF-8")
        val response = read("https://archive.org/advancedsearch.php?q=$q&rows=8&output=json&fl%5B%5D=identifier&fl%5B%5D=title",
            TmdbConfig.language)?.optJSONObject("response") ?: return null
        if (response.optInt("numFound") > 8) return null
        val ids = response.objects("docs").filter { it.str("title")?.let { s -> same(s,title) } == true }
            .mapNotNull { it.str("identifier")?.takeIf(ID::matches) }.distinct()
        val id = ids.singleOrNull() ?: return null
        return detail(id)?.takeIf { same(it.title,title) &&
            (parsed.year == null || it.year != null && abs(parsed.year-it.year) <= 1) }
    }

    suspend fun detail(id: String): MetaResult? {
        if (TmdbConfig.offline || !ID.matches(id)) return null
        val body = read("https://archive.org/metadata/$id", TmdbConfig.language) ?: return null
        val item = body.optJSONObject("metadata") ?: return null
        if (item.str("identifier") != id || item.str("mediatype") != "movies") return null
        fun values(field: String): List<String> = if (item.optJSONArray(field) != null) item.strings(field) else listOfNotNull(item.str(field))
        val title = item.str("title") ?: return null
        val creators = values("creator")
        if (creators.isEmpty() || values("subject").none { MUSIC_TOPIC.containsMatchIn(it) }) return null
        val hasVideo = body.objects("files").any { f -> f.str("source") == "original" &&
            f.str("format")?.lowercase() in VIDEO_FORMATS && longEnough(f.str("length")) }
        if (!hasVideo) return null
        return MetaResult(0, MediaKind.MOVIE, title, year = item.str("date")?.take(4)?.toIntOrNull(),
            genres = listOf("Music"), crew = creators.take(12).map { Credit(it, "Creator") }, publicMovieKey = "archive:$id")
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
        private val CONCERT_LABEL = Regex("""(?i)\bconcert\b|\blive (?:at|in|from)\b|演唱[会會]|音乐会|音樂會""")
        private val MUSIC_TOPIC = Regex("""(?i)\bmusic\b|\bconcert\b|\brecital\b|演唱[会會]|音[乐樂]""")
        private val VIDEO_FORMATS = setOf("matroska","mpeg4","mpeg2","mpeg2-ts","h.264","quicktime","avi","ogg video")
        private val CLOCK = Regex("""\d{1,4}(?::[0-5]\d){1,2}""")
        private fun longEnough(length: String?): Boolean {
            if (length == null) return false
            val seconds = length.toDoubleOrNull() ?: length.takeIf(CLOCK::matches)?.split(':')
                ?.fold(0.0) { total, unit -> total * 60 + unit.toInt() } ?: return false
            return seconds.isFinite() && seconds >= 60
        }
        fun releaseTitle(path: String, file: String, parsed: ParsedName): String? {
            if (!FilenameParser.isUnderMusicRoot(path) || FilenameParser.nothingToLookUp(path,file,parsed.title)) return null
            val folder = FilenameParser.folderName(path) ?: return null
            if (FilenameParser.isShelfFolder(folder) || FilenameParser.isSeasonFolder(folder) ||
                FilenameParser.inMusicCategoryFolder(path) && !CONCERT_LABEL.containsMatchIn(folder)) return null
            val name = FilenameParser.parse(folder.replace('_',' ')).title
            return name.takeIf { it.length <= 120 && it.count(Char::isLetter) >= 5 && !FilenameParser.isJunkTitle(it) &&
                it.none { c -> c == '"' || c == '@' || c == '/' || c == '\\' || c.isISOControl() } }
        }
        private fun same(a: String,b: String) = StringSimilarity.normalizeTitle(a).replace(" ","") ==
            StringSimilarity.normalizeTitle(b).replace(" ","")
        fun credit(key: String): Pair<String,String>? = key.removePrefix("archive:").takeIf { key.startsWith("archive:") && ID.matches(it) }
            ?.let { "Internet Archive" to "https://archive.org/details/$it" }
    }
}
