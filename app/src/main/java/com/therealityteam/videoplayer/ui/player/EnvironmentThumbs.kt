package com.therealityteam.videoplayer.ui.player

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.therealityteam.videoplayer.player.Environments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The places' pictures on 虚拟环境's tiles, decoded once for the process. painterResource decoded all twelve JPEGs
 * on the main thread each time the menu opened, in its first frame: on the B3110 (matched pairs, the menu opened from
 * 播放 12 times each) the worst frame of the opening went 50.9 → 38.9 ms with them read once.
 * [preload] reads them off the main thread as the player opens; until one is in, its tile reads the resource as before.
 */
internal object EnvironmentThumbs {

    private val decoded = mutableStateMapOf<Int, ImageBitmap>()
    private val started = AtomicBoolean(false)

    suspend fun preload(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val res = context.applicationContext.resources
        try {
            for (env in Environments.all) {
                if (env.thumb in decoded) continue
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeResource(res, env.thumb)?.also { it.prepareToDraw() } }.getOrNull()
                } ?: continue
                decoded[env.thumb] = bitmap.asImageBitmap()
            }
        } catch (e: CancellationException) {
            // The player closed first: the next one reads the rest.
            started.set(false)
            throw e
        }
    }

    @Composable
    fun painter(@DrawableRes res: Int): Painter =
        decoded[res]?.let { remember(it) { BitmapPainter(it) } } ?: painterResource(res)
}
