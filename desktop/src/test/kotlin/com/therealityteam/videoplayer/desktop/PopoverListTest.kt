package com.therealityteam.videoplayer.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.desktop.ui.components.Popover
import com.therealityteam.videoplayer.desktop.ui.components.PopoverRow
import com.therealityteam.videoplayer.desktop.ui.theme.AuraTheme
import kotlin.test.Test

/** A long list in a popover — the queue, the subtitle tracks — opens with its chosen row in sight. */
@OptIn(ExperimentalTestApi::class)
class PopoverListTest {

    @Test
    fun `the chosen row of a long list is in sight when it opens`() = runDesktopComposeUiTest(900, 900) {
        setContent {
            CompositionLocalProvider(LocalStrings provides Strings.forLanguage("en")) {
                AuraTheme(true) {
                    Box(Modifier.size(40.dp)) {
                        Popover(expanded = true, onDismiss = {}, above = false) {
                            for (i in 1..80) PopoverRow("Episode $i", checked = i == 63, onClick = {})
                        }
                    }
                }
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        onNodeWithText("Episode 63").assertIsDisplayed()
        onNodeWithText("Episode 1").assertIsNotDisplayed()
    }
}
