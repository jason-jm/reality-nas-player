package com.therealityteam.videoplayer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.desktop.player.Chapter
import com.therealityteam.videoplayer.desktop.player.SeekBar
import com.therealityteam.videoplayer.desktop.ui.theme.AuraTheme
import kotlinx.coroutines.delay
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The seek bar under a hovering pointer, with its picture, rendered to a PNG to look at:
 *
 *   ./gradlew -p desktop test --tests '*SeekPreviewShot*' -Daura.snapshots=/tmp/shots \
 *       [-Daura.frame=/tmp/preview/preview_50.png]
 */
@OptIn(ExperimentalTestApi::class)
class SeekPreviewShot {

    @Test
    fun hover() {
        val out = System.getProperty("aura.snapshots")?.let(::File)
        assumeTrue("set -Daura.snapshots", out != null)
        val frame = System.getProperty("aura.frame")?.let { ImageIO.read(File(it)) }?.toComposeImageBitmap()
        runDesktopComposeUiTest(900, 320) {
            setContent {
                CompositionLocalProvider(LocalStrings provides Strings.forLanguage("zh-CN")) {
                    AuraTheme(true) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0E)).padding(horizontal = 24.dp, vertical = 24.dp), contentAlignment = Alignment.BottomCenter) {
                            SeekBar(
                                positionMs = 1_800_000, durationMs = 6_398_272, bufferedMs = 1_950_000,
                                chapters = listOf(Chapter("开场", 0), Chapter("防波堤", 1_500_000), Chapter("海上", 3_300_000), Chapter("天空", 4_700_000)),
                                onSeek = {},
                                modifier = Modifier.testTag("seek"),
                                chapterTitle = { ms -> if (ms >= 3_300_000) "海上" else "防波堤" },
                                preview = { _, _, _ -> delay(30); frame },
                                previewAspect = 2.39f,
                            )
                        }
                    }
                }
            }
            onNodeWithTag("seek").performMouseInput { moveTo(Offset(width * 0.55f, height / 2f)) }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out!!.apply { mkdirs() }, "seek_preview_zh-CN.png").writeBytes(png)
        }
    }
}
