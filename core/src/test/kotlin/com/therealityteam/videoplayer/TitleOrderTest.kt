package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.ui.library.TitleIndex
import com.therealityteam.videoplayer.ui.library.TitleOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** A→Z and the rail's marks in the reader's language. */
class TitleOrderTest {

    private fun sorted(locale: Locale, vararg titles: String): List<String> {
        val c = TitleOrder.collator(locale)
        return titles.sortedWith { a, b -> c.compare(TitleOrder.sortName(a), TitleOrder.sortName(b)) }
    }

    private fun marks(locale: Locale, vararg titles: String) = titles.map { TitleOrder.mark(it, locale) }

    @Test fun `Swedish puts Å Ä Ö after Z, and they are marks of their own`() {
        val sv = Locale.forLanguageTag("sv-SE")
        assertEquals(listOf("Alien", "Zorro", "Ångest", "Älskade", "Ödet"), sorted(sv, "Ödet", "Ångest", "Zorro", "Älskade", "Alien"))
        assertEquals(listOf('A', 'Z', 'Å', 'Ä', 'Ö'), marks(sv, "Alien", "Zorro", "Ångest", "Älskade", "Ödet"))
    }

    @Test fun `German files Ä under A, Polish Ł after L`() {
        assertEquals(listOf('A', 'U'), marks(Locale.GERMAN, "Ärger", "Über den Wolken"))
        val pl = Locale.forLanguageTag("pl-PL")
        assertEquals(listOf("Lalka", "Łódź", "Most"), sorted(pl, "Most", "Łódź", "Lalka"))
        assertEquals(listOf('L', 'Ł', 'M'), marks(pl, "Lalka", "Łódź", "Most"))
        assertEquals(listOf('L'), marks(Locale.GERMAN, "Łódź"))
    }

    @Test fun `Cyrillic, Greek, Hangul, kana and Thai have marks of their script`() {
        assertEquals(listOf('Н', 'Е', 'Б'), marks(Locale.forLanguageTag("ru"), "Начало", "Ёлки", "«Брат»"))
        assertEquals(listOf('Ψ', 'Ο'), marks(Locale.forLanguageTag("el"), "Ψυχώ", "Όνειρα"))
        assertEquals(listOf('ㄱ', 'ㄱ', 'ㅅ'), marks(Locale.KOREAN, "기생충", "꽃잎", "살인의 추억"))
        assertEquals(listOf('か', 'か', 'た', 'は'), marks(Locale.JAPANESE, "かぐや姫", "ガンダム", "トトロ", "パプリカ"))
        // A leading vowel is written first and read after its consonant: เพื่อน is under พ.
        assertEquals(listOf('พ', 'ฟ'), marks(Locale.forLanguageTag("th"), "เพื่อน", "ฟ้าทะลายโจร"))
    }

    @Test fun `digits are #, another script is the rest`() {
        assertEquals(listOf(TitleIndex.OTHER, TitleOrder.REST, 'A'), marks(Locale.GERMAN, "2001: Odyssee", "千と千尋", "„Amélie“"))
    }

    @Test fun `a Chinese reader's wall stays in pinyin`() {
        assertEquals(true, TitleOrder.byPinyin(Locale.SIMPLIFIED_CHINESE))
        assertEquals(true, TitleOrder.byPinyin(Locale.TRADITIONAL_CHINESE))
        assertEquals(false, TitleOrder.byPinyin(Locale.JAPANESE))
    }
}
