package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.CrewRoles
import com.therealityteam.videoplayer.data.scrape.AniListClient
import com.therealityteam.videoplayer.data.scrape.Credit
import com.therealityteam.videoplayer.data.scrape.rankCrew
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CrewRolesTest {
    @Test fun `every job a scraper stores has a key, and a character's name has none`() {
        (AniListClient.STAFF_ROLES.values + listOf("导演", "主创", "编剧", "原著", "监制", "制片", "配乐", "Director", "Writer"))
            .forEach { assertNotNull("no key for $it", CrewRoles.keyOf(it)) }
        assertEquals("crew_director", CrewRoles.keyOf("导演"))
        assertNull(CrewRoles.keyOf("Paul Atreides"))
        assertNull(CrewRoles.keyOf(null))
    }

    @Test fun `a director who also wrote is filed as the director, whatever order TMDB lists them in`() {
        // Dunkirk's credits as TMDB lists them: Nolan writing before directing.
        val ranked = rankCrew(listOf(
            Credit("Christopher Nolan", "编剧", "nolan.jpg"),
            Credit("Emma Thomas", "制片", null),
            Credit("Christopher Nolan", "导演", null),
            Credit("Hans Zimmer", "配乐", null),
        ))
        assertEquals(listOf("Christopher Nolan" to "导演", "Emma Thomas" to "制片", "Hans Zimmer" to "配乐"), ranked.map { it.name to it.role })
        assertEquals("nolan.jpg", ranked.first().imageUrl)
    }
}
