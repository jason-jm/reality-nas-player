package com.therealityteam.videoplayer.desktop.ui.library

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.ArrowDownAZ
import com.composables.icons.lucide.Baby
import com.composables.icons.lucide.CalendarDays
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.Gamepad2
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.History
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Music
import com.composables.icons.lucide.Shapes
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Telescope
import com.composables.icons.lucide.Tv
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.ui.library.DetailPlay
import com.therealityteam.videoplayer.ui.library.EpisodeNaming
import com.therealityteam.videoplayer.ui.library.LibSection
import com.therealityteam.videoplayer.ui.library.LibSort

// The shared engine decides what a shelf, a sort or a file IS; the words and icons are the
// desktop's own, under the headset's string names (as its LibraryLabels.kt does with R.string).

val LibSection.labelKey: String
    get() = when (this) {
        LibSection.ALL -> "nav_all"
        LibSection.MOVIE -> "nav_movies"
        LibSection.TV -> "nav_tv"
        LibSection.ANIME -> "nav_anime"
        LibSection.DOC -> "nav_documentary"
        LibSection.MUSIC -> "nav_music"
        LibSection.VARIETY -> "nav_variety"
        LibSection.PERSONAL -> "nav_personal"
        LibSection.GAME -> "nav_game"
        LibSection.KIDS -> "nav_kids"
        LibSection.ADULT -> "nav_adult"
        LibSection.OTHER -> "nav_other"
        LibSection.FAVORITES -> "nav_favorites"
        LibSection.HISTORY -> "nav_history"
    }

val LibSection.icon: ImageVector
    get() = when (this) {
        LibSection.ALL -> Lucide.LayoutGrid
        LibSection.MOVIE -> Lucide.Film
        LibSection.TV -> Lucide.Tv
        LibSection.ANIME -> Lucide.Sparkles
        LibSection.DOC -> Lucide.Telescope
        LibSection.MUSIC -> Lucide.Music
        LibSection.VARIETY -> Lucide.Mic
        LibSection.PERSONAL -> Lucide.Camera
        LibSection.GAME -> Lucide.Gamepad2
        LibSection.KIDS -> Lucide.Baby
        LibSection.ADULT -> Lucide.EyeOff
        LibSection.OTHER -> Lucide.Shapes
        LibSection.FAVORITES -> Lucide.Heart
        LibSection.HISTORY -> Lucide.History
    }

val LibSort.labelKey: String get() = "sort_" + name.lowercase()
val LibSort.hintKey: String get() = "sort_" + name.lowercase() + "_hint"

val LibSort.icon: ImageVector
    get() = when (this) {
        LibSort.RECENT -> Lucide.Clock
        LibSort.TITLE -> Lucide.ArrowDownAZ
        LibSort.RATING -> Lucide.Star
        LibSort.YEAR -> Lucide.CalendarDays
        LibSort.WATCHED -> Lucide.History
    }

/** The name shown for one file — the headset's EpisodeNaming.label, in the desktop's words. */
fun episodeLabel(s: Strings, item: MediaItem, numberedRun: Boolean = false): String {
    val i = EpisodeNaming.parse(item, numberedRun)
    return when (i.cat) {
        EpisodeNaming.Cat.EPISODE -> {
            val base = s["ep_episode", EpisodeNaming.fmt(i.episode ?: 0.0)]
            if (i.version.isNullOrBlank()) base else s["ep_version", base, i.version]
        }
        EpisodeNaming.Cat.OVA -> {
            val kind = if (i.extraType == "OAV") "OVA" else i.extraType ?: "OVA"
            if (i.extraNum == null) kind else s["ep_numbered", kind, i.extraNum]
        }
        EpisodeNaming.Cat.EXTRA -> {
            val type = i.extraType
            val lbl = when {
                type != null && type in EpisodeNaming.NAMED_TYPES -> s["ep_t_" + type.lowercase()]
                !type.isNullOrBlank() -> type
                else -> s["ep_extra"]
            }
            when {
                !i.name.isNullOrBlank() -> s["ep_named", lbl, i.name]
                i.extraNum != null -> s["ep_numbered", lbl, i.extraNum]
                else -> lbl
            }
        }
        EpisodeNaming.Cat.FEATURE -> i.name?.takeIf { it.isNotBlank() } ?: s["ep_feature"]
    }
}

/**
 * A file's name in the player — its queue, top bar and up-next card: season and episode first
 * when it is one, else its title, else the file (the headset's queueTitle). An extra keeps the
 * detail page's words for it ("OVA 2", "Creditless OP"); a film is never just "Feature".
 */
fun queueTitle(s: Strings, item: MediaItem): String {
    val season = item.season
    val episode = item.episode
    return when {
        episode != null && season != null -> s["pc_season_episode", season, episode, item.title]
        episode != null -> s["pc_episode", episode, item.title]
        EpisodeNaming.parse(item, false).cat == EpisodeNaming.Cat.FEATURE -> item.title.ifBlank { item.fileName }
        else -> episodeLabel(s, item)
    }
}

/** What the detail page's big button says. */
fun playButtonText(s: Strings, choice: DetailPlay.Choice, numberedRun: Boolean): String {
    val what = { episodeLabel(s, choice.item, numberedRun) }
    return when (choice.kind) {
        DetailPlay.Kind.RESUME -> s["det_resume_item", what()]
        DetailPlay.Kind.NEXT -> s["det_play_item", what()]
        DetailPlay.Kind.RESTART -> s["det_watch_again"]
        DetailPlay.Kind.START -> s["det_play"]
    }
}

fun rowStateText(s: Strings, state: DetailPlay.RowState): String = when (state) {
    DetailPlay.RowState.Watched -> s["det_watched"]
    is DetailPlay.RowState.Left -> s.plural("det_left_min", state.minutes)
    DetailPlay.RowState.Started -> s["det_resume"]
}
