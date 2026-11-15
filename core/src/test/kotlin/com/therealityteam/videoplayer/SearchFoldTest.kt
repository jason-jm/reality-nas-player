package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.ui.library.SearchKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A title is found by the letters a keyboard types: accents off Latin, Greek and Cyrillic, on both sides. */
class SearchFoldTest {

    private fun finds(query: String, title: String) =
        assertTrue("'$query' finds '$title'", SearchKeys.normalize(title).contains(SearchKeys.normalize(query)))

    @Test fun `Latin accents and the letters keyboards spell out`() {
        finds("amelie", "Le Fabuleux Destin d'Amélie Poulain")
        finds("strasse", "Die Straße")
        finds("smorgasbord", "Smörgåsbord")
        finds("lodz", "Łódź")
        finds("istanbul", "İstanbul Hatırası")
        finds("haenus", "Hænus")
        finds("pokemon", "Pokémon")
    }

    @Test fun `Cyrillic ё is е, Greek accents and final sigma fold`() {
        finds("елки", "Ёлки")
        finds("ψυχω", "Ψυχώ")
        finds("οδυσσεια", "Οδύσσεια")
        finds("ερως", "Έρως")
    }

    @Test fun `kana keep their voicing, Hangul its syllables`() {
        assertEquals("バカ", SearchKeys.normalize("バカ").uppercase())
        assertTrue(SearchKeys.normalize("ハカ") != SearchKeys.normalize("バカ"))
        assertEquals("기생충", SearchKeys.normalize("기생충"))
    }

    @Test fun `scripts a Latin keyboard cannot type are romanised as well`() {
        assertTrue(SearchKeys.needsRomanizing("Начало"))
        assertTrue(SearchKeys.needsRomanizing("Ψυχώ"))
        assertTrue(SearchKeys.needsRomanizing("ฟ้าทะลายโจร"))
        assertTrue(!SearchKeys.needsRomanizing("Amélie"))
    }
}
