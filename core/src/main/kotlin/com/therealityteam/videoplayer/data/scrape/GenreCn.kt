package com.therealityteam.videoplayer.data.scrape

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Genre labels in the metadata language. TMDB is queried in the reader's language, so its genres already come
 * back in it; but the anime path (AniList) only ever returns English genres (Action / Mecha / Slice of Life…), and
 * a stray English TMDB answer can slip in. The English vocabulary of both is mapped to the reader's language from
 * one table a language (resources /genres/<lang>.txt: `English = name` lines); anything else passes through.
 *
 * It was a Chinese table only — a German reader saw "Slice of Life" beside TMDB's "Komödie", and a reader of
 * Traditional Chinese the Simplified 动作. The name stays: the desktop's library page and the app call it.
 */
object GenreCn {

    /** A genre as the metadata language names it, or as stored when the table has no word for it. */
    fun zh(genre: String): String = table().names[genre.trim()] ?: genre

    /**
     * The genres a stored one stands for, each as [zh] names it. TMDB's series genres are pairs —
     * "Sci-Fi & Fantasy", "Action & Adventure" (动作冒险 in Chinese), "War & Politics" — and stood in
     * the 筛选 as genres of their own: 科幻 did not find a series tagged only "Sci-Fi & Fantasy", and
     * a page read 科幻奇幻 where every other said 科幻. They are their parts now, in every language.
     */
    fun zhAll(genre: String): List<String> = genre.trim().let { g -> PAIRS[g] ?: table().pairs[g] ?: listOf(genre) }.map(::zh)

    /** The table file a language tag reads: zh-Hans / zh-Hant, nb for any Norwegian, else the bare language. */
    fun tableFor(tag: String): String {
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        return when (val lang = locale.language.lowercase()) {
            "zh" -> if (locale.script.equals("Hant", true) || (locale.script.isEmpty() && locale.country.uppercase() in setOf("TW", "HK", "MO"))) "zh-Hant" else "zh-Hans"
            "no", "nn" -> "nb"
            else -> lang.ifEmpty { "en" }
        }
    }

    /** A language's names for the English vocabulary, and its TMDB paired genres ("НФ и Фэнтези") by English parts. */
    private class Table(val names: Map<String, String>, val pairs: Map<String, List<String>>)

    private val tables = ConcurrentHashMap<String, Table>()

    private fun table(): Table = tableFor(TmdbConfig.language).let { tables.getOrPut(it) { load(it) } }

    private fun load(name: String): Table {
        val stream = GenreCn::class.java.getResourceAsStream("/genres/$name.txt") ?: return Table(emptyMap(), emptyMap())
        val lines = stream.bufferedReader(Charsets.UTF_8).useLines { all ->
            all.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }.toList()
        }
        val (paired, named) = lines.partition { it.startsWith("@") }
        return Table(
            names = named.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotEmpty() },
            pairs = paired.associate { line ->
                line.removePrefix("@").substringBefore('=').trim() to line.substringAfter('=').split(',').map { it.trim() }.filter { it.isNotEmpty() }
            },
        )
    }

    /** TMDB's paired series genres, in English and as the Chinese site wrote them, by their parts in English. */
    private val PAIRS: Map<String, List<String>> = mapOf(
        "Sci-Fi & Fantasy" to listOf("Sci-Fi", "Fantasy"),
        "科幻奇幻" to listOf("Sci-Fi", "Fantasy"),
        "科幻与奇幻" to listOf("Sci-Fi", "Fantasy"),
        "Action & Adventure" to listOf("Action", "Adventure"),
        "动作冒险" to listOf("Action", "Adventure"),
        "动作与冒险" to listOf("Action", "Adventure"),
        "War & Politics" to listOf("War", "Politics"),
        "战争政治" to listOf("War", "Politics"),
        "战争与政治" to listOf("War", "Politics"),
    )
}
