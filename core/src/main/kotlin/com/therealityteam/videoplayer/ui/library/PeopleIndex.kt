package com.therealityteam.videoplayer.ui.library

/**
 * Who worked on what, across the library: a person's name → the cards that credit them, in front of
 * or behind the camera, newest first.
 *
 * The name is the only key there is: the scraper keeps no person id. Names agree within a library
 * scraped in one language, which is the library the detail page is showing, so "the director's other
 * films" comes out right; across languages (萨姆·门德斯 on one card, Sam Mendes on another) the
 * two never meet, and the page simply offers fewer titles rather than wrong ones.
 */
object PeopleIndex {

    fun of(groups: List<MediaGroup>): Map<String, List<MediaGroup>> {
        val byName = HashMap<String, MutableList<MediaGroup>>()
        for (group in groups) {
            // A show credits the same people on every episode: count each card once per name.
            val names = HashSet<String>()
            for (item in group.items) {
                for (person in item.cast) keyOf(person.name)?.let(names::add)
                for (person in item.crew) keyOf(person.name)?.let(names::add)
            }
            for (name in names) byName.getOrPut(name) { mutableListOf() }.add(group)
        }
        return byName.mapValues { (_, cards) ->
            cards.sortedWith(compareByDescending<MediaGroup> { it.year ?: Int.MIN_VALUE }.thenBy { it.title })
        }
    }

    /** The cards [name] is credited on, other than [except] — what "另有 N 部" counts. */
    fun othersOf(index: Map<String, List<MediaGroup>>, name: String, except: String): List<MediaGroup> =
        keyOf(name)?.let { key -> index[key].orEmpty().filter { it.key != except } }.orEmpty()

    private fun keyOf(name: String): String? = name.trim().takeIf { it.isNotEmpty() }
}
