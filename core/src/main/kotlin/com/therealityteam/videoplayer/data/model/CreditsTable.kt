package com.therealityteam.videoplayer.data.model

import org.json.JSONObject

/**
 * The cast and crew a library's files share, written once instead of once per file.
 *
 * Credits belong to a WORK, and the library stores a FILE. Friends' 227 episodes each carried the
 * same list headed by 詹妮弗·安妮斯顿; 進撃の巨人's 158 each carried 梶裕貴. Measured on a real
 * 9,597-item library: 14.92 MB of cast and crew, 1.36 MB of it distinct — eleven copies of
 * everything, and 58% of the whole file for a section only the detail page ever reads. Reading it
 * took 5.2 of the 17.7 seconds the poster wall needed to appear.
 *
 * So the distinct lists are written once, at the head of the same array, and each item names the
 * one it uses. One array, one atomic rename, nothing to keep in sync. A library written before this
 * existed carries its credits inline and is read exactly as it always was; an item that carries
 * both is read from its own copy, so a half-migrated file cannot lose anybody.
 */
class CreditsTable private constructor(
    private val byKey: Map<String, Pair<List<Person>, List<Person>>>,
    /**
     * The overviews more than one file shares — a show's, written once rather than once per episode:
     * on a real NAS library 6,930 overviews were 601 texts, 4.7 MB of a 17 MB file. An item names
     * its own by index ("ov"). A build from before this reads the header without them, and those
     * items without an overview, until a scrape writes them again.
     */
    private val texts: List<String> = emptyList(),
) {
    val size: Int get() = byKey.size

    /** Whether there is anything to write at the head of the array. */
    val isEmpty: Boolean get() = byKey.isEmpty() && texts.isEmpty()

    private val textIndex: Map<String, Int> by lazy { texts.withIndex().associate { (i, t) -> t to i } }

    /** The index [overview] is written under, when it is one of the shared ones. */
    fun textRef(overview: String?): Int? = overview?.let { textIndex[it] }

    /** The shared overview at [ref]. */
    fun text(ref: Int): String? = texts.getOrNull(ref)

    fun credits(key: String?): Pair<List<Person>, List<Person>>? {
        return byKey[key ?: return null]
    }

    /** The header object to write at the head of the array. */
    fun header(): JSONObject = JSONObject().apply {
        put(MARKER, VERSION)
        put(
            "table",
            JSONObject().apply {
                byKey.forEach { (k, v) ->
                    put(
                        k,
                        JSONObject()
                            .put("cast", MediaJson.personArray(v.first))
                            .put("crew", MediaJson.personArray(v.second)),
                    )
                }
            },
        )
        if (texts.isNotEmpty()) put("texts", org.json.JSONArray(texts))
    }

    companion object {
        /** Names the header object, and says which shape it is. A plain item never has this key. */
        const val MARKER = "auraCredits"
        const val VERSION = 1

        /** True when [o] is the credits header rather than an item. */
        fun isHeader(o: JSONObject): Boolean = o.has(MARKER)

        /** Reads the header back. A version this build does not know reads as no table at all. */
        fun read(o: JSONObject): CreditsTable {
            if (o.optInt(MARKER) != VERSION) return CreditsTable(emptyMap())
            val texts = o.optJSONArray("texts")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
            val t = o.optJSONObject("table") ?: return CreditsTable(emptyMap(), texts)
            val out = HashMap<String, Pair<List<Person>, List<Person>>>(t.length() * 2)
            t.keys().forEach { k ->
                val e = t.optJSONObject(k) ?: return@forEach
                out[k] = MediaJson.personList(e.optJSONArray("cast")) to
                    MediaJson.personList(e.optJSONArray("crew"))
            }
            return CreditsTable(out, texts)
        }

        /**
         * The distinct credit lists in [items], and the key each item that has credits should carry.
         *
         * Keys are handed out in the order the lists are first met, so the same library writes the
         * same file twice running — a diff of two dumps shows what changed, not a reshuffle.
         */
        fun of(items: List<MediaItem>): Pair<CreditsTable, Map<String, String>> {
            // Keyed by the lists themselves: Person is a data class, so a List pair hashes and
            // compares element by element, exactly and with no key to build. (An earlier draft
            // concatenated the fields into a string per item — 15 MB of transient text on every
            // write of a library this size, to answer a question List.equals already answers.)
            val keyOf = HashMap<Pair<List<Person>, List<Person>>, String>()
            val table = LinkedHashMap<String, Pair<List<Person>, List<Person>>>()
            val keyOfItem = HashMap<String, String>(items.size * 2)
            items.forEach { item ->
                if (item.cast.isEmpty() && item.crew.isEmpty()) return@forEach
                val credits = item.cast to item.crew
                keyOfItem[item.id] = keyOf.getOrPut(credits) {
                    "c${keyOf.size}".also { table[it] = credits }
                }
            }
            // The overviews used more than once, in the order first met (the same library, the same file).
            val seen = HashMap<String, Int>()
            items.forEach { it.overview?.let { o -> seen[o] = (seen[o] ?: 0) + 1 } }
            val texts = LinkedHashSet<String>()
            items.forEach { it.overview?.let { o -> if ((seen[o] ?: 0) > 1) texts += o } }
            return CreditsTable(table, texts.toList()) to keyOfItem
        }
    }
}
