package com.therealityteam.videoplayer.ui.library

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.model.MediaItem

// The words and icons of the shared library's choices, in this app's resources. The shared core
// decides what a sort, a shelf or a file IS; how the reader sees it named is each app's own.

@get:StringRes
val LibSort.label: Int
    get() = when (this) {
        LibSort.RECENT -> R.string.sort_recent
        LibSort.TITLE -> R.string.sort_title
        LibSort.RATING -> R.string.sort_rating
        LibSort.YEAR -> R.string.sort_year
        LibSort.WATCHED -> R.string.sort_watched
    }

@get:StringRes
val LibSort.hint: Int
    get() = when (this) {
        LibSort.RECENT -> R.string.sort_recent_hint
        LibSort.TITLE -> R.string.sort_title_hint
        LibSort.RATING -> R.string.sort_rating_hint
        LibSort.YEAR -> R.string.sort_year_hint
        LibSort.WATCHED -> R.string.sort_watched_hint
    }

@get:DrawableRes
val LibSort.icon: Int
    get() = when (this) {
        LibSort.RECENT -> R.drawable.ic_sort_recent
        LibSort.TITLE -> R.drawable.ic_sort_title
        LibSort.RATING -> R.drawable.ic_sort_rating
        LibSort.YEAR -> R.drawable.ic_sort_year
        LibSort.WATCHED -> R.drawable.ic_history
    }

@get:StringRes
val LibSection.label: Int
    get() = when (this) {
        LibSection.ALL -> R.string.nav_all
        LibSection.MOVIE -> R.string.nav_movies
        LibSection.TV -> R.string.nav_tv
        LibSection.ANIME -> R.string.nav_anime
        LibSection.DOC -> R.string.nav_documentary
        LibSection.MUSIC -> R.string.nav_music
        LibSection.VARIETY -> R.string.nav_variety
        LibSection.PERSONAL -> R.string.nav_personal
        LibSection.GAME -> R.string.nav_game
        LibSection.KIDS -> R.string.nav_kids
        LibSection.ADULT -> R.string.nav_adult
        LibSection.OTHER -> R.string.nav_other
        LibSection.FAVORITES -> R.string.nav_favorites
        LibSection.HISTORY -> R.string.nav_history
    }

@get:DrawableRes
val LibSection.icon: Int
    get() = when (this) {
        LibSection.ALL -> R.drawable.ic_cat_all
        LibSection.MOVIE -> R.drawable.ic_cat_movie
        LibSection.TV -> R.drawable.ic_cat_tv
        LibSection.ANIME -> R.drawable.ic_cat_anime
        LibSection.DOC -> R.drawable.ic_cat_doc
        LibSection.MUSIC -> R.drawable.ic_cat_music
        LibSection.VARIETY -> R.drawable.ic_cat_variety
        LibSection.PERSONAL -> R.drawable.ic_cat_personal
        LibSection.GAME -> R.drawable.ic_cat_game
        LibSection.KIDS -> R.drawable.ic_cat_kids
        LibSection.ADULT -> R.drawable.ic_cat_adult
        // Its own glyph: the shelf used to wear 更多's "…", so with 更多 open two rows said the same thing.
        LibSection.OTHER -> R.drawable.ic_cat_other
        LibSection.FAVORITES -> R.drawable.ic_fav
        LibSection.HISTORY -> R.drawable.ic_history
    }

@get:StringRes
val TaskPhase.label: Int
    get() = when (this) {
        TaskPhase.SCANNING -> R.string.task_scan
        TaskPhase.SCRAPING -> R.string.task_scrape
    }

@get:StringRes
val TaskPhase.busy: Int
    get() = when (this) {
        TaskPhase.SCANNING -> R.string.task_notif_scan
        TaskPhase.SCRAPING -> R.string.task_notif_scrape
    }

/** The name shown for one file. [ctx] supplies the reader's language; the parsing is language-free. */
fun EpisodeNaming.label(ctx: Context, item: MediaItem, numberedRun: Boolean = false): String {
    val i = parse(item, numberedRun)
    return when (i.cat) {
        EpisodeNaming.Cat.EPISODE -> {
            val base = ctx.getString(R.string.ep_episode, fmt(i.episode ?: 0.0))
            if (i.version.isNullOrBlank()) base else ctx.getString(R.string.ep_version, base, i.version)
        }
        // OVA / OAV / OAD read the same in every language; an OAD says OAD, not OVA.
        EpisodeNaming.Cat.OVA -> {
            val kind = if (i.extraType == "OAV") "OVA" else i.extraType ?: "OVA"
            if (i.extraNum == null) kind else ctx.getString(R.string.ep_numbered, kind, i.extraNum)
        }
        EpisodeNaming.Cat.EXTRA -> {
            val lbl = typeName(ctx, i.extraType)
            when {
                !i.name.isNullOrBlank() -> ctx.getString(R.string.ep_named, lbl, i.name)
                i.extraNum != null -> ctx.getString(R.string.ep_numbered, lbl, i.extraNum)
                else -> lbl
            }
        }
        EpisodeNaming.Cat.FEATURE -> i.name?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.ep_feature)
    }
}

/** A named extra type in the reader's language; an acronym (PV, CM, SP…) verbatim. */
private fun typeName(ctx: Context, type: String?): String {
    @StringRes val res = TYPE_LABEL[type]
    return when {
        res != null -> ctx.getString(res)
        !type.isNullOrBlank() -> type
        else -> ctx.getString(R.string.ep_extra)
    }
}

/** How each of [EpisodeNaming.NAMED_TYPES] is named for the reader. */
private val TYPE_LABEL: Map<String, Int> = mapOf(
    "NCOP" to R.string.ep_t_ncop, "NCED" to R.string.ep_t_nced, "OPED" to R.string.ep_t_oped,
    "NC" to R.string.ep_t_nc, "MENU" to R.string.ep_t_menu, "COLLECTION" to R.string.ep_t_collection,
    "TRAILER" to R.string.ep_t_trailer, "TEASER" to R.string.ep_t_teaser,
    "PREVIEW" to R.string.ep_t_preview, "PROGRAM" to R.string.ep_t_program,
    "ANNOUNCEMENT" to R.string.ep_t_announcement, "FEATURETTE" to R.string.ep_t_featurette,
    "INTERVIEW" to R.string.ep_t_interview, "MAKING" to R.string.ep_t_making,
    "DOCUMENTARY" to R.string.ep_t_documentary, "TALK" to R.string.ep_t_talk,
    "GREETING" to R.string.ep_t_greeting, "CONCERT" to R.string.ep_t_concert,
    "BOOKLET" to R.string.ep_t_booklet, "SPECIAL" to R.string.ep_t_special,
    "BONUS" to R.string.ep_t_bonus, "SAMPLE" to R.string.ep_t_sample,
    "DIGEST" to R.string.ep_t_digest, "MANUAL" to R.string.ep_t_manual, "AD" to R.string.ep_t_ad,
    "DELETED" to R.string.ep_t_deleted,
)

/**
 * What the detail page's big button says. [what] names the file the way the page lists it — with its
 * season when the show has several, since "第 1 集" alone is every season's first. The button elides
 * a long name itself; it is not truncated here.
 */
fun DetailPlay.buttonText(ctx: Context, choice: DetailPlay.Choice, what: String): String = when (choice.kind) {
    DetailPlay.Kind.RESUME -> ctx.getString(R.string.det_resume_item, what)
    DetailPlay.Kind.NEXT -> ctx.getString(R.string.det_play_item, what)
    DetailPlay.Kind.RESTART -> ctx.getString(R.string.det_watch_again)
    DetailPlay.Kind.START -> ctx.getString(R.string.det_play)
}

/** A file's state in words: "42 min left", "Watched", "Resume". */
fun DetailPlay.rowStateText(ctx: Context, state: DetailPlay.RowState): String = when (state) {
    DetailPlay.RowState.Watched -> ctx.getString(R.string.det_watched)
    is DetailPlay.RowState.Left -> ctx.resources.getQuantityString(R.plurals.det_left_min, state.minutes, state.minutes)
    DetailPlay.RowState.Started -> ctx.getString(R.string.det_resume)
}

/**
 * A site's score as the reader writes numbers: "7.5" is "7,5" in German or Russian; "91%" and "74" stay as the
 * site gives them. Stored scores always carry a point (RatingScore).
 */
fun scoreText(value: String, locale: java.util.Locale = java.util.Locale.getDefault()): String =
    if (POINT_DECIMAL.matches(value)) {
        java.text.NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(value.toDouble())
    } else {
        value
    }

private val POINT_DECIMAL = Regex("""\d+\.\d""")
