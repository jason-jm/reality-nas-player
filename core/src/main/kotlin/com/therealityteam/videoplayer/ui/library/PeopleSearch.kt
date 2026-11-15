package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.Person

/**
 * 人物 in a search: the people whose names hold the query, from the library's own credits
 * ([PeopleIndex]). Typing an actor's name puts their face above the answer and opens every card
 * that credits them — a card is searchable only by the first eight of its cast and four of its crew.
 */
object PeopleSearch {

    class Hit(val person: Person, val works: List<MediaGroup>)

    /**
     * Each name as a search compares it (see [SearchKeys.normalize]), in simplified Han ([HanFold]) so
     * 「宫崎骏」 finds 宮崎駿 — worked out once per index.
     */
    fun keys(index: Map<String, List<MediaGroup>>): List<Pair<String, String>> =
        index.keys.map { compared(it) to it }.filter { it.first.isNotEmpty() }

    private fun compared(name: String): String = HanFold.fold(SearchKeys.normalize(name))

    /**
     * The people [query] finds: the name itself first, then a name with a part that is the query
     * (斯通 → 艾玛·斯通, before 埃里克·斯通斯特里特), then names that start with it, then those that
     * hold it; more cards before fewer. Nothing for a single CJK character or under three letters —
     * those name half the library's credits.
     */
    fun match(index: Map<String, List<MediaGroup>>, keys: List<Pair<String, String>>, query: String, limit: Int = 12): List<Hit> {
        val q = compared(query)
        if (q.length < (if (SearchKeys.hasCjk(q)) 2 else 3)) return emptyList()
        return keys.asSequence()
            .filter { (key, _) -> key.contains(q) }
            .mapNotNull { (key, name) -> index[name]?.takeIf { it.isNotEmpty() }?.let { Triple(key, name, it) } }
            // One person credited in two scripts — 宮崎駿 by AniList, 宫崎骏 by TMDB — is one face, by
            // the name more of their cards give, holding the cards of both.
            .groupBy { it.first }.values
            .map { same ->
                val first = same.maxWith(compareBy<Triple<String, String, List<MediaGroup>>> { it.third.size }.thenBy { it.second })
                if (same.size == 1) first else Triple(first.first, first.second, same.flatMap { it.third }.distinctBy { it.key })
            }
            .asSequence()
            .sortedWith(
                compareBy<Triple<String, String, List<MediaGroup>>> { (key, name, _) ->
                    when {
                        key == q -> 0
                        name.split(NAME_PARTS).any { compared(it) == q } -> 1
                        key.startsWith(q) -> 2
                        else -> 3
                    }
                }
                    .thenByDescending { it.third.size }
                    .thenBy { it.second },
            )
            .take(limit)
            .map { (_, name, works) -> Hit(faceOf(name, works), works) }
            .toList()
    }

    /** Where a name breaks into its parts: 艾玛·斯通, Emma Stone, Jean-Luc, 宮崎・駿. */
    private val NAME_PARTS = Regex("""[\s·・.\-]+""")

    /** The person as their cards credit them: the first credit with a headshot, if any has one. */
    private fun faceOf(name: String, works: List<MediaGroup>): Person {
        var plain: Person? = null
        for (group in works) for (item in group.items) {
            for (p in item.cast) {
                if (p.name.trim() != name) continue
                if (p.profilePath != null || p.profileUrl != null) return p.copy(role = null)
                if (plain == null) plain = p.copy(role = null)
            }
            for (p in item.crew) {
                if (p.name.trim() != name) continue
                if (p.profilePath != null || p.profileUrl != null) return p.copy(role = null)
                if (plain == null) plain = p.copy(role = null)
            }
        }
        return plain ?: Person(name)
    }
}
