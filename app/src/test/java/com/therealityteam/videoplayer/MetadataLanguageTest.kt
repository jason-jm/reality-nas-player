package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.repository.AppSettings
import com.therealityteam.videoplayer.data.repository.METADATA_LANGUAGES
import com.therealityteam.videoplayer.data.repository.resolvedMetadataLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 跟随系统 is a choice of its own. It used to reach the metadata clients as an empty tag — TMDB
 * then answered in its default English, and the Chinese naming rules switched off — until a restart
 * replaced it with whatever the headset's tag was at that moment.
 */
class MetadataLanguageTest {

    @Test fun `following the headset fetches in the headset's language`() {
        assertEquals("de-DE", resolvedMetadataLanguage("", Locale.GERMANY))
        assertEquals("zh-CN", resolvedMetadataLanguage("", Locale.SIMPLIFIED_CHINESE))
    }

    @Test fun `a chosen language wins over the headset's`() {
        assertEquals("ja-JP", resolvedMetadataLanguage("ja-JP", Locale.GERMANY))
    }

    @Test fun `a fresh install follows the headset, and the list offers that first`() {
        assertEquals("", AppSettings().language)
        assertEquals("", METADATA_LANGUAGES.first())
        assertTrue(METADATA_LANGUAGES.drop(1).none { it.isBlank() })
    }
}
