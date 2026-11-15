package com.therealityteam.videoplayer.data.scrape

/**
 * What a database says of an episode that is not about the episode: the name it fills in for one it
 * has none for — "第 3 集", "Episode 3", "第3話", only the number the row already shows — and the
 * staff it lists where a synopsis would be. Pure.
 */
object EpisodeNames {

    fun isPlaceholder(name: String): Boolean = PLACEHOLDER.matches(name.trim())

    /**
     * A description that is the episode's staff, not what happens in it — bgm.tv fills many with
     * 「脚本：志茂文彦  分镜：石原立也  演出：石原立也  作画监督：池田和美」.
     */
    fun isCredits(text: String): Boolean = CREDIT.findAll(text).count() >= 2

    private val CREDIT = Regex(
        """(?:脚本|劇本|剧本|编剧|編劇|分镜|分鏡|絵コンテ|演出|作画监督|作画監督|総作画監督|总作画监督|原画|美术|美術|シナリオ|监督|監督)\s*[：:]""",
    )

    private val PLACEHOLDER = Regex(
        """(?i)^(?:第\s*[0-9０-９一二三四五六七八九十百零〇]+\s*[集话話回]|episode\s*\d+|ep\.?\s*\d+|エピソード\s*\d+|\d+)$""",
    )
}
