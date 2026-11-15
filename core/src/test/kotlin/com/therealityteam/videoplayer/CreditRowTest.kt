package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.ui.library.CreditRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** 演职人员: the director, the leads, the writers, the rest, the producers last — each person once. */
class CreditRowTest {

    private fun p(name: String, role: String?, url: String? = null) = Person(name, role, profileUrl = url)

    @Test fun `producers follow the leads, and a person with two parts is shown once with both`() {
        val crew = listOf(
            p("Damien Chazelle", "导演"), p("Michael Beugg", "监制"), p("John Legend", "监制"),
            p("Justin Hurwitz", "配乐"), p("Damien Chazelle", "编剧"),
        )
        val cast = listOf(p("Ryan Gosling", "Sebastian"), p("Emma Stone", "Mia"), p("John Legend", "Keith", url = "u"))
        val row = CreditRow.of(crew, cast)
        assertEquals(
            listOf("Damien Chazelle", "Ryan Gosling", "Emma Stone", "John Legend", "Justin Hurwitz", "Michael Beugg"),
            row.map { it.person.name },
        )
        assertEquals(listOf("导演", "编剧"), row[0].roles)
        assertEquals(listOf("Keith", "监制"), row[3].roles)
        assertEquals("u", row[3].person.profileUrl)
    }

    @Test fun `the writers and the source come after six leads, ahead of the rest of the cast`() {
        val crew = listOf(p("平野宏樹", "导演"), p("理不尽な孫の手", "原作"), p("嶋田真恵", "角色设计"))
        val cast = (1..8).map { p("声优$it", "角色$it") }
        val names = CreditRow.of(crew, cast).map { it.person.name }
        assertEquals("平野宏樹", names[0])
        assertEquals("理不尽な孫の手", names[7])
        assertEquals(listOf("声优7", "声优8", "嶋田真恵"), names.takeLast(3))
    }

    @Test fun `a headshot from a later credit is kept when the first has none`() {
        val row = CreditRow.of(listOf(p("A", "导演")), listOf(p("A", "Self", url = "face")))
        assertEquals(1, row.size)
        assertEquals("face", row[0].person.profileUrl)
        assertEquals("导演", row[0].person.role)
    }
}
