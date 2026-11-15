package com.therealityteam.videoplayer.data.subtitle

import com.therealityteam.videoplayer.data.playback.SidecarSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A sidecar match by file name only — the caller turns it into a playable [SidecarSubtitle]. */
data class SidecarCandidate(
    val fileName: String,
    val mimeType: String,
    val label: String,
    val language: String?,
)

/**
 * Finds and loads subtitles that live next to a video. Two consumers:
 * - [discoverSidecars] (local) / [matchSidecars] (any file list, incl. SMB) hand sidecar
 *   files to the ExoPlayer engine, which parses every format itself (SRT/ASS/SSA/VTT/TTML);
 * - [loadSidecar] is the legacy self-parsed path kept for the Cypress engine (SRT only).
 */
object SubtitleEngine {

    private val MIMES = mapOf(
        "srt" to "application/x-subrip",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
        "vtt" to "text/vtt",
        "ttml" to "application/ttml+xml",
        // TTML's older name, as some releases still call their files.
        "dfxp" to "application/ttml+xml",
    )

    /**
     * Picture subtitles in files of their own: VobSub (the .idx names its .sub) and Blu-ray PGS.
     * Only for an engine that reads them — mpv does; the headset's ExoPlayer does not.
     */
    private val IMAGE_MIMES = mapOf(
        "idx" to "application/x-vobsub",
        "sup" to "application/pgs",
    )

    /**
     * Which of [siblings] are subtitles for [videoFileName], matched loosely the way desktop
     * players do: `Movie.srt` and `Movie.chs.srt` both belong to `Movie.mkv`; the extra name
     * segment ("chs") becomes the track label. Pure — works on any directory listing.
     */
    fun matchSidecars(videoFileName: String, siblings: List<String>, imageFormats: Boolean = false): List<SidecarCandidate> {
        val stem = videoFileName.substringBeforeLast('.')
        if (stem.isEmpty()) return emptyList()
        val mimes = if (imageFormats) MIMES + IMAGE_MIMES else MIMES
        return siblings.filter { name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            val nameStem = name.substringBeforeLast('.')
            mimes.containsKey(ext) && (nameStem == stem || nameStem.startsWith("$stem.")) && complete(name, siblings)
        }.sorted().map { name ->
            val tag = name.substringBeforeLast('.').removePrefix(stem).trim('.')
            SidecarCandidate(
                fileName = name,
                mimeType = mimes.getValue(name.substringAfterLast('.').lowercase()),
                // The file name itself: with release names the language tag rides at the end
                // ("…x265-GROUP.chs&eng.srt"), so the whole name is what identifies the track.
                label = name,
                language = tag.takeIf { it.isNotBlank() },
            )
        }
    }

    /** The mime type a subtitle file named [name] is read as, or null for no subtitle (text ones only). */
    fun mimeOf(name: String): String? = MIMES[name.substringAfterLast('.', "").lowercase()]

    /** As [mimeOf], and with [imageFormats] the picture ones too (a VobSub's .idx, a Blu-ray's .sup). */
    fun mimeOf(name: String, imageFormats: Boolean): String? =
        mimeOf(name) ?: if (imageFormats) IMAGE_MIMES[name.substringAfterLast('.', "").lowercase()] else null

    /** Whether [mimeType] is a subtitle of pictures — read from a file of its own by mpv only. */
    fun isPicture(mimeType: String?): Boolean = mimeType != null && mimeType in IMAGE_MIMES.values

    /**
     * Whether [name] has all it needs among [siblings]: a VobSub's .idx is only the index, its
     * pictures are the .sub of the same name — an .idx without one shows nothing.
     */
    fun complete(name: String, siblings: List<String>): Boolean {
        if (!name.endsWith(".idx", ignoreCase = true)) return true
        val pictures = name.dropLast(4) + ".sub"
        return siblings.any { it.equals(pictures, ignoreCase = true) }
    }

    /** Sidecar files next to a local video, as playable configurations — with [imageFormats], a VobSub too. */
    fun discoverSidecars(videoPath: String, imageFormats: Boolean = false): List<SidecarSubtitle> {
        if (!videoPath.startsWith("/")) return emptyList()
        val video = File(videoPath)
        val dir = video.parentFile ?: return emptyList()
        val names = runCatching { dir.listFiles() }.getOrNull().orEmpty()
            .filter { it.isFile && it.length() > 0 }.map { it.name }
        return matchSidecars(video.name, names, imageFormats).map {
            SidecarSubtitle(
                uri = "file://${File(dir, it.fileName).absolutePath}",
                mimeType = it.mimeType,
                label = it.label,
                language = it.language,
            )
        }
    }

    /** Legacy self-parsed sidecar (SRT/ASS text only) for the Cypress engine's overlay. */
    suspend fun loadSidecar(videoPath: String): SubtitleTrack? = withContext(Dispatchers.IO) {
        if (!videoPath.startsWith("/")) return@withContext null // local files only
        val base = File(videoPath)
        val dir = base.parentFile ?: return@withContext null
        val stem = base.nameWithoutExtension
        val file = listOf("srt", "ass", "ssa").map { File(dir, "$stem.$it") }
            .firstOrNull { it.exists() && it.length() > 0 } ?: return@withContext null
        runCatching { SubtitleParser.parse(file.readBytes(), file.name) }.getOrNull()
    }
}
