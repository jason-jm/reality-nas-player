package com.therealityteam.videoplayer.desktop.player

import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.StereoMode
import com.therealityteam.videoplayer.data.playback.ProjectionGuess
import java.io.File
import java.util.Properties

/**
 * What is kept of each panorama between plays, as the headset keeps it (aura_pano_guess, aura_pano_level):
 * what its picture was found to look like — so a film is looked at, and asked about, once — and the level
 * its horizon was set to.
 */
class PanoMemory(private val file: File) {

    /** A remembered look: [guess], or null when the film was found flat or the viewer said 不是. */
    class Known(val guess: ProjectionGuess.Guess?)

    private val props: Properties by lazy {
        Properties().also { p -> runCatching { file.inputStream().use(p::load) } }
    }

    @Synchronized
    fun guess(id: String): Known? {
        val v = props.getProperty(GUESS + id) ?: return null
        if (v == NONE) return Known(null)
        val (p, st) = v.split('/').takeIf { it.size == 2 } ?: return null
        return runCatching { Known(ProjectionGuess.Guess(ProjectionMode.valueOf(p), StereoMode.valueOf(st))) }.getOrNull()
    }

    @Synchronized
    fun keepGuess(id: String, guess: ProjectionGuess.Guess?) {
        props.setProperty(GUESS + id, guess?.let { "${it.projection}/${it.stereo}" } ?: NONE)
        save()
    }

    /** The horizon's level for the film: degrees its front is lifted, and turned clockwise; 0, 0 as filmed. */
    @Synchronized
    fun level(id: String): Pair<Float, Float> =
        props.getProperty(LEVEL + id)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (0f to 0f)

    @Synchronized
    fun keepLevel(id: String, tilt: Float, roll: Float) {
        if (tilt == 0f && roll == 0f) props.remove(LEVEL + id) else props.setProperty(LEVEL + id, "$tilt,$roll")
        save()
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use { props.store(it, "Aura: what is kept of each panorama") }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    private companion object {
        const val GUESS = "guess."
        const val LEVEL = "level."
        const val NONE = "none"
    }
}
