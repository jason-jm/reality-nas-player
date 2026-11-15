package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.desktop.i18n.Strings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The desktop reads every language the headset speaks, with the plural forms each one has. */
class StringsLanguagesTest {

    @Test fun `each language reads its own folder`() {
        assertEquals("values", Strings.folderFor(Locale.US))
        assertEquals("values-zh", Strings.folderFor(Locale.forLanguageTag("zh-CN")))
        assertEquals("values-b+zh+Hant", Strings.folderFor(Locale.forLanguageTag("zh-HK")))
        assertEquals("values-de", Strings.folderFor(Locale.forLanguageTag("de-AT")))
        assertEquals("values-pt", Strings.folderFor(Locale.forLanguageTag("pt-PT")))
        assertEquals("values-nb", Strings.folderFor(Locale.forLanguageTag("no-NO")))
        assertEquals("values-th", Strings.folderFor(Locale.forLanguageTag("th-TH")))
        assertEquals("values", Strings.folderFor(Locale.forLanguageTag("ar-EG")))
    }

    @Test fun `plural forms as CLDR counts them`() {
        fun q(lang: String, vararg n: Int) = n.map { Strings.quantity(lang, it) }
        assertEquals(listOf("one", "few", "many", "many", "one", "few", "many"), q("ru", 1, 3, 5, 11, 21, 22, 25))
        assertEquals(listOf("one", "few", "many", "many", "few"), q("pl", 1, 2, 5, 12, 22))
        assertEquals(listOf("one", "few", "other"), q("cs", 1, 4, 5))
        assertEquals(listOf("few", "one", "few", "other", "few"), q("ro", 0, 1, 19, 20, 102))
        assertEquals(listOf("one", "one", "other"), q("fr", 0, 1, 2))
        assertEquals(listOf("other", "one", "other"), q("de", 0, 1, 2))
        assertEquals(listOf("other", "other"), q("ja", 1, 2))
    }

    @Test fun `a German table has German words`() {
        val de = Strings.forLanguage("de-DE")
        assertEquals(true, de["set_nav_about"] != Strings.forLanguage("en")["set_nav_about"])
    }
}
