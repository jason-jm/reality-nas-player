package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.CreditsTable
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaJson
import com.therealityteam.videoplayer.data.model.Person
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cast belongs to a work; the library stores files. Writing it per file cost a real 9,597-item
 * library 14.92 MB for 1.36 MB of distinct content.
 */
class CreditsPoolTest {

    // As big as TMDB actually returns — 12 cast and 8 crew, each with a real-length image URL.
    // A one-line cast makes pooling look pointless; on a real shelf it is 1.9 KB per episode.
    private val friends = (1..12).map {
        Person("詹妮弗·安妮斯顿 $it", "Rachel Green $it", profileUrl = "https://image.tmdb.org/t/p/w185/2Nk3vDgCQnU8dOPDTtbEDjTKygp$it.jpg")
    }
    private val makers = (1..8).map {
        Person("David Crane $it", "Executive Producer", profileUrl = "https://image.tmdb.org/t/p/w185/aFjTzJHE6AFbwQifOlnNDzmiwlq$it.jpg")
    }

    private fun episode(n: Int, cast: List<Person> = friends, crew: List<Person> = makers) = MediaItem(
        id = "e$n", sourceId = "src", path = "/tv/friends/s01e$n.mkv", fileName = "s01e$n.mkv",
        title = "Friends", cast = cast, crew = crew,
    )

    /** Write a whole library the way the repository does, then read it back the same way. */
    private fun roundTrip(items: List<MediaItem>): Pair<List<MediaItem>, Int> {
        val (table, keyOf) = CreditsTable.of(items)
        val texts = (if (!table.isEmpty) listOf(table.header().toString()) else emptyList()) +
            items.map { MediaJson.item(it, keyOf[it.id], table.textRef(it.overview)).toString() }
        var credits: CreditsTable? = null
        val out = mutableListOf<MediaItem>()
        texts.forEachIndexed { i, text ->
            val o = JSONObject(text)
            if (i == 0 && CreditsTable.isHeader(o)) credits = CreditsTable.read(o)
            else out += MediaJson.item(o, credits)
        }
        return out to texts.sumOf { it.length }
    }

    @Test fun `a season's worth of episodes carries one copy of the cast`() {
        val season = (1 until 24).map { episode(it) }
        val (back, _) = roundTrip(season)
        assertEquals(season.size, back.size)
        back.forEach {
            assertEquals(friends, it.cast)
            assertEquals(makers, it.crew)
        }
        assertEquals("one distinct list for the whole season", 1, CreditsTable.of(season).first.size)
    }

    @Test fun `pooling is what makes the file smaller`() {
        val season = (1 until 24).map { episode(it) }
        val pooled = roundTrip(season).second
        val inline = season.sumOf { MediaJson.item(it).toString().length }
        // A season shares one cast, so the pooled form should cost about one episode's worth.
        assertTrue("pooled $pooled should be a fraction of inline $inline", pooled * 5 < inline)
    }

    @Test fun `two works keep their own casts apart`() {
        val other = listOf(Person("梶裕貴", "エレン"))
        val mixed = (1 until 4).map { episode(it) } + (4 until 7).map { episode(it, cast = other) }
        val (back, _) = roundTrip(mixed)
        assertEquals(2, CreditsTable.of(mixed).first.size)
        assertEquals(friends, back[0].cast)
        assertEquals(other, back[5].cast)
    }

    @Test fun `a library written before pooling reads exactly as it did`() {
        // No header, credits inline on every item — nothing to look up and nothing lost.
        val o = MediaJson.item(episode(1))
        assertFalse(o.has("creditsKey"))
        assertEquals(friends, MediaJson.item(o, credits = null).cast)
    }

    @Test fun `an item that carries both is read from its own copy`() {
        // A file half-written by two builds must not lose anybody: inline wins over the table.
        val (table, keyOf) = CreditsTable.of(listOf(episode(1)))
        val o = MediaJson.item(episode(1), keyOf["e1"]).put("cast", MediaJson.personArray(listOf(Person("Nobody"))))
        assertEquals(listOf(Person("Nobody")), MediaJson.item(o, table).cast)
    }

    @Test fun `a header this build does not understand costs nothing but the credits`() {
        val o = JSONObject().put(CreditsTable.MARKER, CreditsTable.VERSION + 99)
        assertTrue(CreditsTable.isHeader(o))
        assertEquals(0, CreditsTable.read(o).size)
    }

    @Test fun `an item with no credits names no list`() {
        val bare = episode(1, cast = emptyList(), crew = emptyList())
        val (table, keyOf) = CreditsTable.of(listOf(bare))
        assertEquals(0, table.size)
        assertFalse(MediaJson.item(bare, keyOf[bare.id]).has("creditsKey"))
    }

    @Test fun `a show's overview is written once for all its episodes, and every episode reads it back`() {
        val show = "罗斯与瑞秋的十年。".repeat(40)
        val season = (1 until 24).map { episode(it).copy(overview = show) } +
            episode(99).copy(id = "film", overview = "A film of its own.")
        val (back, _) = roundTrip(season)
        assertEquals(season.map { it.overview }, back.map { it.overview })
        // Once, in the header; each episode names it.
        val table = CreditsTable.of(season).first
        assertEquals(1, Regex(Regex.escape(show)).findAll(table.header().toString()).count())
        val first = MediaJson.item(season.first(), null, table.textRef(show))
        assertTrue(first.has("ov") && !first.has("overview"))
        // A file's own overview stays with it.
        assertTrue(MediaJson.item(season.last(), null, table.textRef(season.last().overview)).has("overview"))
    }
}
