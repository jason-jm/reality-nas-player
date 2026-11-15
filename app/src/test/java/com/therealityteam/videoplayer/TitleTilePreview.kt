package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.ui.library.titleGradient
import com.therealityteam.videoplayer.ui.library.titleSize
import org.junit.Test

/** Prints the real covers for real titles, so the design can be looked at without a headset. */
class TitleTilePreview {
    @Test fun print() {
        val titles = System.getProperty("aura.titles")?.split("\n")?.filter { it.isNotBlank() } ?: return
        titles.forEach { t ->
            val hex = titleGradient(t).joinToString(",") { c ->
                "%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
            }
            println("TILE\t$t\t$hex\t${titleSize(t).value.toInt()}")
        }
    }
}
