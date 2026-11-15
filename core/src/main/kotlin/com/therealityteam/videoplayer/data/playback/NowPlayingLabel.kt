package com.therealityteam.videoplayer.data.playback

/**
 * The line under the title in the system's "now playing": `剧名 · S01E02` for an episode,
 * the year for a film, nothing when neither is known. Pure, so the shape is unit-tested.
 */
object NowPlayingLabel {
    fun subtitle(showTitle: String?, season: Int?, episode: Int?, year: Int?): String? {
        val code = if (season != null && episode != null) "S%02dE%02d".format(season, episode) else null
        val episodeLine = listOfNotNull(showTitle?.takeIf { it.isNotBlank() }, code).joinToString(" · ")
        return episodeLine.ifBlank { year?.toString() }
    }
}
