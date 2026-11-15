package com.therealityteam.videoplayer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.therealityteam.videoplayer.data.scrape.Synopsis

/**
 * [text], a synopsis, as the reader reads it ([Synopsis.forReader]): without bgm.tv's source text
 * tacked on after the translation, and none at all where it is Japanese for a reader of Chinese.
 */
@Composable
fun readableSynopsis(text: String?): String? {
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    return remember(text, language) { Synopsis.forReader(text, language) }
}
