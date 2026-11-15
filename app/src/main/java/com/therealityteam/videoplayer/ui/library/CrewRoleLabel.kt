package com.therealityteam.videoplayer.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.model.CrewRoles

/**
 * A credit's role as the reader reads it. A crew job is stored as the scraper wrote it (导演 from
 * TMDB and AniList, Director from a server in English) and is shown in the app's language; a
 * character's name, or a job [CrewRoles] does not list, is shown as stored.
 */
@Composable
fun crewRoleLabel(role: String?): String? {
    val res = CrewRoles.keyOf(role)?.let { ROLE_STRINGS[it] } ?: return role
    return stringResource(res)
}

private val ROLE_STRINGS = mapOf(
    "crew_director" to R.string.crew_director,
    "crew_chief_director" to R.string.crew_chief_director,
    "crew_creator" to R.string.crew_creator,
    "crew_writer" to R.string.crew_writer,
    "crew_original_work" to R.string.crew_original_work,
    "crew_original_creator" to R.string.crew_original_creator,
    "crew_executive_producer" to R.string.crew_executive_producer,
    "crew_producer" to R.string.crew_producer,
    "crew_composer" to R.string.crew_composer,
    "crew_series_composition" to R.string.crew_series_composition,
    "crew_script" to R.string.crew_script,
    "crew_storyboard" to R.string.crew_storyboard,
    "crew_character_design" to R.string.crew_character_design,
    "crew_original_character_design" to R.string.crew_original_character_design,
    "crew_music" to R.string.crew_music,
    "crew_sound_director" to R.string.crew_sound_director,
    "crew_art_director" to R.string.crew_art_director,
    "crew_animation_director" to R.string.crew_animation_director,
)
