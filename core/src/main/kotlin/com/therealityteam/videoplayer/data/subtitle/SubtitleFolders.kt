package com.therealityteam.videoplayer.data.subtitle

/**
 * Subtitles kept in a folder of their own beside the video — a release's `Subs/`, a fansub's `sub/`
 * (机动警察's 炎之川 SSA, 亡念之扎姆德's Chinese ASS) — which the same-folder lookup never saw: their
 * names seldom start with the video's. Which of them belong to a file:
 *  - one named after the video, as beside it;
 *  - for an episode, the ones that carry its number (`[01]`, `Ep01`, ` - 01`);
 *  - for a film (a folder's feature, not its trailer), the ones that carry no number at all.
 * Text subtitles, and with `imageFormats` the picture ones too (a VobSub's .idx and .sub) for the
 * engine that draws them. Pure, so it is tested.
 */
object SubtitleFolders {

    /** The subtitle folder among a video's [siblings] (folders end in "/"), or null. */
    fun folderIn(siblings: List<String>): String? =
        siblings.firstOrNull { it.endsWith("/") && it.trimEnd('/').lowercase() in NAMES }?.trimEnd('/')

    /**
     * The subtitles in [inside] (the folder's names) for [videoFileName]; [isFeature] says the video is
     * its folder's film rather than an extra beside it. At most [LIMIT], named first.
     */
    fun match(videoFileName: String, inside: List<String>, isFeature: Boolean, imageFormats: Boolean = false): List<SidecarCandidate> {
        val files = inside.filter { !it.endsWith("/") }
        val subs = files.filter { SubtitleEngine.mimeOf(it, imageFormats) != null && SubtitleEngine.complete(it, files) }
        val named = SubtitleEngine.matchSidecars(videoFileName, subs + files.filter { it.endsWith(".sub", ignoreCase = true) }, imageFormats)
        val episode = numberOf(videoFileName.substringBeforeLast('.'))
        val loose = subs.filter { name -> named.none { it.fileName == name } }.filter { name ->
            val n = numberOf(name.substringBeforeLast('.'))
            if (episode != null) n == episode else isFeature && n == null
        }.sorted().map { name ->
            SidecarCandidate(fileName = name, mimeType = SubtitleEngine.mimeOf(name, imageFormats)!!, label = name, language = null)
        }
        return (named + loose).take(LIMIT)
    }

    /** The episode number a name carries — `[06]`, `Ep01`, `E01`, ` - 01 ` — read the same way for video and subtitle. */
    internal fun numberOf(stem: String): Int? {
        BRACKET.find(stem)?.let { return it.groupValues[1].toInt() }
        EP.find(stem)?.let { return it.groupValues[1].toInt() }
        DASH.find(stem)?.let { return it.groupValues[1].toInt() }
        return null
    }

    private val NAMES = setOf("subs", "sub", "subtitles", "subtitle", "字幕")
    private val BRACKET = Regex("""\[(\d{1,3})(?:v\d)?\]""")
    private val EP = Regex("""(?i)(?:^|[^a-z])(?:ep|e)\s*0*(\d{1,3})(?!\d)""")
    private val DASH = Regex(""" - (\d{1,3})(?:v\d)?(?:\s|$)""")
    private const val LIMIT = 8
}
