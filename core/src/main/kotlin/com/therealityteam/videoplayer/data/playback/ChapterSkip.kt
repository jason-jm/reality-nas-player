package com.therealityteam.videoplayer.data.playback

/**
 * 跳过片头 / 跳过片尾 from the file's own chapter marks: a chapter NAMED as the opening or the
 * credits — "OP", "Opening", "オープニング", "片头"; "ED", "Ending", "End Credits", "片尾" — is offered
 * as a skip while it plays. Release groups name these for exactly this; a film's Blu-ray names its
 * credits. A name alone is not trusted with a scene: an opening runs 15 s – 200 s (a song, a title
 * sequence), credits 15 s – 15 min (a film's roll; the scene after them stays, as the next chapter).
 * Pure, so the reading is unit-tested.
 */
object ChapterSkip {

    enum class Kind { INTRO, OUTRO }

    /** The skippable chapter playing: what it is, and where it ends. */
    data class Offer(val kind: Kind, val chapterIndex: Int, val startMs: Long, val endMs: Long)

    /** What a chapter's name says it is, or null for anything else (the story, a preview, a number). */
    fun kindOf(title: String): Kind? {
        val t = title.trim()
        if (t.isEmpty()) return null
        if (CJK_INTRO.any { it in t }) return Kind.INTRO
        if (CJK_OUTRO.any { it in t }) return Kind.OUTRO
        val words = t.lowercase().split(NON_WORD).filter { it.isNotEmpty() }
        val first = words.firstOrNull() ?: return null
        // "Opening Scene", "Intro Sequence of the war": the story, named for where it stands.
        if (words.any { it in STORY_WORDS }) return null
        // "OP" and "ED" are also how a name starts ("Ed and Al"): taken only alone, numbered, or
        // before the song's name ("OP - Blue Bird", "ED「…」").
        val short = first in SHORT_WORDS || NUMBERED_OP.matches(first) || NUMBERED_ED.matches(first)
        if (short && !standsAlone(t)) return null
        return when {
            first in INTRO_WORDS || NUMBERED_OP.matches(first) -> Kind.INTRO
            words.take(2) == listOf("main", "title") || words.take(2) == listOf("main", "titles") -> Kind.INTRO
            first in OUTRO_WORDS || NUMBERED_ED.matches(first) -> Kind.OUTRO
            words.size >= 2 && words[1] == "credits" && words[0] in CREDIT_LEADS -> Kind.OUTRO
            else -> null
        }
    }

    /** Whether the name's first word is all of it, or what follows is a number or a song's name set apart. */
    private fun standsAlone(title: String): Boolean {
        val rest = title.dropWhile { it.isLetterOrDigit() }.trimStart()
        return rest.isEmpty() || rest[0].isDigit() || rest[0] in SONG_MARKS
    }

    /** The offer at [positionMs]: the skippable chapter playing, unless its last moments are all that is left. */
    fun offerAt(chapters: List<Chapter>, positionMs: Long, durationMs: Long): Offer? {
        val i = chapters.indexAt(positionMs)
        if (i < 0) return null
        val chapter = chapters[i]
        val end = chapters.getOrNull(i + 1)?.startMs ?: durationMs
        if (end <= chapter.startMs) return null
        val kind = kindOf(chapter.title) ?: return null
        val length = end - chapter.startMs
        val fits = when (kind) {
            Kind.INTRO -> length in MIN_LENGTH_MS..MAX_INTRO_MS
            Kind.OUTRO -> length in MIN_LENGTH_MS..MAX_OUTRO_MS
        }
        if (!fits || end - positionMs < LAST_MOMENTS_MS) return null
        return Offer(kind, i, chapter.startMs, end)
    }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val NUMBERED_OP = Regex("op\\d{1,2}")
    private val NUMBERED_ED = Regex("ed\\d{1,2}")
    private val SHORT_WORDS = setOf("op", "ed")
    private const val SONG_MARKS = "-:–—「『\"'“(（[【·|/~～"
    private val INTRO_WORDS = setOf("op", "opening", "intro")
    private val OUTRO_WORDS = setOf("ed", "ending", "credits", "outro")
    private val CREDIT_LEADS = setOf("end", "closing", "ending")
    private val STORY_WORDS = setOf("scene", "sequence", "act")
    private val CJK_INTRO = listOf("オープニング", "片头", "片頭", "OP曲")
    private val CJK_OUTRO = listOf("エンディング", "片尾", "ED曲")

    private const val MIN_LENGTH_MS = 15_000L
    private const val MAX_INTRO_MS = 200_000L
    private const val MAX_OUTRO_MS = 900_000L

    /** Inside a chapter's last three seconds there is nothing left worth a button. */
    private const val LAST_MOMENTS_MS = 3_000L
}
