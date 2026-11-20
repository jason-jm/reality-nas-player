package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.MediaKind
import java.util.zip.GZIPInputStream

/** Exact multilingual titles from bangumi-data. Only factual fields are distributed with the player. */
internal object AnimeTitleIndex {
    data class Hit(val record: MetaResult, val aniListId: Int?) {
        /** A work's own exact name can displace an unrelated fuzzy search result. */
        fun identifies(own: String?, other: MetaResult?): Boolean {
            if (other == null) return true
            if (aniListId == other.providerId || own.isNullOrBlank()) return false
            // The box for a cycle of films often has the same name as its TV adaptation.
            if (record.kind == MediaKind.SHOW && other.kind == MediaKind.MOVIE) return false
            val query = normalized(own)
            val others = listOfNotNull(other.title, other.originalTitle, other.romajiTitle, other.englishTitle) + other.synonyms
            return record.synonyms.any { normalized(it) == query } && others.none {
                val title = normalized(it)
                title == query || query.length >= 3 && title.startsWith(query)
            }
        }
    }

    private class Entry(val fields: List<String>) {
        val id = fields[0]
        val form = fields[1]
        val year = fields[2].toIntOrNull()
        val names = fields.drop(4).filter(String::isNotBlank).distinct()
        fun record(): MetaResult {
            val label = when {
                TmdbConfig.language.startsWith("zh-TW") || TmdbConfig.language.startsWith("zh-Hant") ->
                    fields[6].ifBlank { fields[5] }
                TmdbConfig.isChinese -> fields[5].ifBlank { fields[6] }
                TmdbConfig.language.startsWith("ja") -> fields[4]
                else -> fields[7]
            }
            return MetaResult(0, if (form == "movie") MediaKind.MOVIE else MediaKind.SHOW,
                label.ifBlank { fields[4] }, originalTitle = fields[4], englishTitle = fields[7].ifBlank { null },
                synonyms = names, year = year, genres = listOf("Animation"), publicMovieKey = "bangumi:$id")
        }
    }

    private fun normalized(s: String) = StringSimilarity.normalizeTitle(s).replace(" ", "")
    private val byName: Map<String, List<Entry>> by lazy {
        val index = HashMap<String, MutableList<Entry>>()
        val input = checkNotNull(javaClass.getResourceAsStream("/reality-nas-player/anime-titles.tsv.gz"))
        GZIPInputStream(input).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val entry = Entry(line.split('\t'))
                for (name in entry.names.map(::normalized).distinct().filter { it.length >= 2 }) {
                    index.getOrPut(name) { mutableListOf() }.add(entry)
                }
            }
        }
        index
    }

    fun match(names: List<String>, year: Int?, season: Int?, movie: Boolean, form: String?,
              episode: Int?, filmNumber: Int?): Hit? {
        val own = names.firstOrNull() ?: return null
        val expectedForm = when (form) { "OVA" -> "ova"; "ONA" -> "web"; "TV" -> "tv"; else -> null }
        for (query in names.distinct().take(2)) {
            val choices = byName[normalized(query)].orEmpty().filter { e ->
                (year == null || e.year != null && kotlin.math.abs(year - e.year) <= 1) &&
                    (!movie || e.form == "movie") && (movie || episode == null || e.form != "movie") &&
                    (movie || expectedForm == null || e.form == expectedForm)
            }.map { Hit(it.record(), it.fields[3].toIntOrNull()) }.filter { hit ->
                val r = hit.record
                (query == own || !AnimeMatch.namesAnotherWork(own, r)) &&
                    (season == null || season == 1 || AnimeMatch.seasonFit(season, r) > 0 || year != null && year == r.year) &&
                    (r.kind != MediaKind.MOVIE || filmNumber == null || AnimeMatch.filmOrdinal(r) == filmNumber)
            }.distinctBy { it.record.publicMovieKey }
            if (choices.size > 1) return null
            choices.singleOrNull()?.let { return it }
        }
        return null
    }
}
