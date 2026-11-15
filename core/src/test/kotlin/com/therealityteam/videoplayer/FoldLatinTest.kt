package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.StringSimilarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.text.Normalizer

/** Accents come off Latin letters when names are compared — and only off Latin letters. */
class FoldLatinTest {
    private fun n(s: String) = StringSimilarity.normalizeTitle(s)

    @Test fun `a name typed without its accents is the same name`() {
        assertEquals(n("Shogun"), n("Shōgun"))
        assertEquals("amelie", n("Amélie"))
        assertEquals("pokemon the first movie", n("Pokémon: The First Movie"))
        assertEquals("leon the professional", n("Léon: The Professional"))
        // Decomposed, as a Mac's SMB server hands a name over: the same.
        assertEquals(n("Amélie"), n(Normalizer.normalize("Amélie", Normalizer.Form.NFD)))
        // Letters with no decomposition, as a keyboard spells them.
        assertEquals("smorgasbord", n("Smørgåsbord"))
        assertEquals("strasse", n("Straße"))
        assertEquals("lodz", n("Łódź"))
    }

    @Test fun `kana keep their voicing and Hangul stays whole`() {
        assertEquals("スペシャル", n("スペシャル"))
        assertEquals("スペシャル", n(Normalizer.normalize("スペシャル", Normalizer.Form.NFD)))
        assertNotEquals(n("バカ"), n("ハカ"))
        assertEquals("기생충", n("기생충"))
        assertEquals("進撃の巨人", n("進撃の巨人"))
    }
}
