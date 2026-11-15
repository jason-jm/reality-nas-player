package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.Person

/**
 * The detail page's row of faces, in the order a viewer looks for them: whoever directed it, the
 * leads, the writers and the source it came from, the rest of the cast, the other crafts, and the
 * producers last. Each person once, with every part they had ("Keith · 监制").
 *
 * The row used to be the first six crew credits and then the cast, as stored — so a film opened on
 * four executive producers ahead of its two leads, and a singer who produced and acted in it was
 * shown twice. The full lists, crew and cast apart, are still the 更多 sheet's.
 */
object CreditRow {

    class Credit(val person: Person, val roles: List<String>)

    private val DIRECTING = setOf("导演", "总导演", "主创", "Director")
    private val WRITING = setOf("编剧", "原著", "原作", "原案", "系列构成", "脚本", "Writer")
    private val PRODUCING = setOf("制片", "监制")

    /** How many of the cast count as the leads, ahead of the writers. */
    private const val LEADS = 6

    fun of(crew: List<Person>, cast: List<Person>, max: Int = 20): List<Credit> {
        fun crewWhere(take: (String) -> Boolean) = crew.filter { take(it.role?.trim().orEmpty()) }
        val ordered = crewWhere { it in DIRECTING } +
            cast.take(LEADS) +
            crewWhere { it in WRITING } +
            cast.drop(LEADS) +
            crewWhere { it !in DIRECTING && it !in WRITING && it !in PRODUCING } +
            crewWhere { it in PRODUCING }

        val byName = LinkedHashMap<String, Pair<Person, MutableList<String>>>()
        for (p in ordered) {
            val name = p.name.trim().takeIf { it.isNotEmpty() } ?: continue
            val had = byName[name]
            val roles = had?.second ?: mutableListOf()
            p.role?.trim()?.takeIf { it.isNotEmpty() && it !in roles }?.let(roles::add)
            // The first credit places the person; a later one may still bring the only headshot.
            val face = when {
                had == null -> p
                had.first.profilePath == null && had.first.profileUrl == null &&
                    (p.profilePath != null || p.profileUrl != null) -> had.first.copy(profilePath = p.profilePath, profileUrl = p.profileUrl)
                else -> had.first
            }
            byName[name] = face to roles
        }
        return byName.values.take(max).map { (p, roles) -> Credit(p, roles) }
    }
}
