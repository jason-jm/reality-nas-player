package com.therealityteam.videoplayer.data.model

/**
 * The jobs a crew credit can carry, as the scrapers store them — TMDB's and AniList's jobs in
 * Chinese (TmdbMetadataClient.crewJobs, AniListClient.STAFF_ROLES), Plex's and Jellyfin's in
 * Chinese or English after the metadata language — each with the string key a screen shows it by.
 * The library keeps what it stored; a reader in another language is shown their own word for it.
 * A cast credit's role is a character's name and is not in here.
 */
object CrewRoles {
    private val KEYS = mapOf(
        "导演" to "crew_director", "Director" to "crew_director",
        "总导演" to "crew_chief_director",
        "主创" to "crew_creator",
        "编剧" to "crew_writer", "Writer" to "crew_writer",
        "原著" to "crew_original_work",
        "原作" to "crew_original_creator",
        "监制" to "crew_executive_producer",
        "制片" to "crew_producer",
        "配乐" to "crew_composer",
        "系列构成" to "crew_series_composition",
        "脚本" to "crew_script",
        "分镜" to "crew_storyboard",
        "角色设计" to "crew_character_design",
        "原案" to "crew_original_character_design",
        "音乐" to "crew_music",
        "音响监督" to "crew_sound_director",
        "美术监督" to "crew_art_director",
        "作画监督" to "crew_animation_director",
    )

    /** The string key for a stored crew job, or null when [role] is not one (a character's name). */
    fun keyOf(role: String?): String? = role?.trim()?.let(KEYS::get)
}
