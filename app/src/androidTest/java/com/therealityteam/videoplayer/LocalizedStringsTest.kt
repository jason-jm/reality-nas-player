package com.therealityteam.videoplayer

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.EpisodeNaming
import java.util.Locale
import com.therealityteam.videoplayer.ui.library.label

/**
 * The app ships in more than one language, so the strings a reader sees have to come from theirs.
 *
 * Until recently every one of them was a Chinese literal in the Compose tree, and the few that did
 * live in resources had Chinese as the DEFAULT — which Android falls back to for every locale
 * without a translation, so a reader in Munich got Chinese either way.
 */
@RunWith(AndroidJUnit4::class)
class LocalizedStringsTest {

    private fun contextIn(locale: Locale): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    private fun inLocale(locale: Locale, id: Int): String = contextIn(locale).getString(id)

    private fun file(name: String) = MediaItem(
        id = name, sourceId = "s", path = "smb://nas/video/Show/$name", fileName = name,
        title = name.substringBeforeLast('.'), kind = MediaKind.EPISODE, scrapeStatus = ScrapeStatus.UNMATCHED,
    )

    @Test fun theDefaultLanguageIsNotChinese() {
        // What every locale without its own translation falls back to.
        listOf(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, Locale("pt", "BR")).forEach { l ->
            listOf(R.string.nav_all, R.string.nav_movies, R.string.sort_title, R.string.search_hint)
                .forEach { id ->
                    val s = inLocale(l, id)
                    assertTrue("$l -> $s", s.none { it.code in 0x4E00..0x9FFF })
                }
        }
        assertEquals("All", inLocale(Locale.ENGLISH, R.string.nav_all))
        assertEquals("Title", inLocale(Locale.ENGLISH, R.string.sort_title))
    }

    @Test fun aChineseReaderStillReadsChinese() {
        listOf(Locale.SIMPLIFIED_CHINESE, Locale("zh")).forEach { l ->
            assertEquals(l.toString(), "全部", inLocale(l, R.string.nav_all))
            assertEquals(l.toString(), "标题", inLocale(l, R.string.sort_title))
        }
    }

    @Test fun traditionalChineseIsItsOwnLanguage() {
        // Android does not fall back from a locale carrying a script to plain values-zh, which it
        // reads as simplified: without values-b+zh+Hant a reader in Taipei got English.
        listOf(Locale.TRADITIONAL_CHINESE, Locale.forLanguageTag("zh-Hant-HK")).forEach { l ->
            assertEquals(l.toString(), "電影", inLocale(l, R.string.nav_movies))
            assertEquals(l.toString(), "設定", inLocale(l, R.string.nav_settings))
        }
    }

    @Test fun everyStringHasBothLanguages() {
        // A name missing from values-zh silently shows English to a Chinese reader; one missing from
        // values shows Chinese to everyone else. Neither is visible without looking.
        val ids = R.string::class.java.fields.filter { it.type == Int::class.java }
        val onlyOne = ids.mapNotNull { f ->
            val id = f.getInt(null)
            val en = runCatching { inLocale(Locale.ENGLISH, id) }.getOrNull() ?: return@mapNotNull null
            val zh = runCatching { inLocale(Locale.SIMPLIFIED_CHINESE, id) }.getOrNull() ?: return@mapNotNull null
            // Names, numbers and marks are the same in both; a Chinese string shown to an English
            // reader is not.
            if (en == zh && en.any { it.code in 0x4E00..0x9FFF }) f.name else null
        }
        assertEquals("only Chinese, for every reader: $onlyOne", emptyList<String>(), onlyOne)
    }

    @Test fun everyPluralHasBothLanguages() {
        // Counts are their own resource type and are invisible to the string sweep above.
        val ids = R.plurals::class.java.fields.filter { it.type == Int::class.java }
        assertTrue("no plurals found", ids.isNotEmpty())
        val onlyOne = ids.mapNotNull { f ->
            val id = f.getInt(null)
            val en = contextIn(Locale.ENGLISH).resources.getQuantityString(id, 2, 2)
            val zh = contextIn(Locale.SIMPLIFIED_CHINESE).resources.getQuantityString(id, 2, 2)
            if (en == zh && en.any { it.code in 0x4E00..0x9FFF }) f.name else null
        }
        assertEquals("only Chinese, for every reader: $onlyOne", emptyList<String>(), onlyOne)
        assertEquals("1 episode", contextIn(Locale.ENGLISH).resources.getQuantityString(R.plurals.det_n_episodes, 1, 1))
        assertEquals("26 episodes", contextIn(Locale.ENGLISH).resources.getQuantityString(R.plurals.det_n_episodes, 26, 26))
    }

    @Test fun aFilesNameIsReadInTheReadersLanguage() {
        // The parse is language-free (it reads the FILE's language); only the naming follows the
        // reader. The same file, in three languages — and the same episode number in all of them.
        val ep = file("[Sub] Show - [05][1080p].mkv")
        assertEquals("Episode 5", EpisodeNaming.label(contextIn(Locale.ENGLISH), ep))
        assertEquals("第 5 集", EpisodeNaming.label(contextIn(Locale.SIMPLIFIED_CHINESE), ep))
        assertEquals("第 5 集", EpisodeNaming.label(contextIn(Locale.TRADITIONAL_CHINESE), ep))
        assertEquals(5.0, EpisodeNaming.parse(ep).episode)

        // A Japanese file name is read by a German reader exactly as by a Japanese one.
        val cn = file("[字幕组] 某番組 第07話 [BDRip].mkv")
        assertEquals(7.0, EpisodeNaming.parse(cn).episode)
        assertEquals("Episode 7", EpisodeNaming.label(contextIn(Locale.GERMAN), cn))

        // Extras: a named type is translated, an acronym is not.
        val ncop = file("[Sub] Show [NCOP01][1080p].mkv")
        assertEquals("Creditless OP 01", EpisodeNaming.label(contextIn(Locale.ENGLISH), ncop))
        assertEquals("无字幕 OP 01", EpisodeNaming.label(contextIn(Locale.SIMPLIFIED_CHINESE), ncop))
        val pv = file("[Sub] Show [PV02][1080p].mkv")
        listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.JAPANESE).forEach {
            assertEquals(it.toString(), "PV 02", EpisodeNaming.label(contextIn(it), pv))
        }
        // An OAD says OAD, not OVA.
        assertEquals("OAD", EpisodeNaming.label(contextIn(Locale.ENGLISH), file("[Sub] Show OAD [1080p].mkv")))
    }
}
