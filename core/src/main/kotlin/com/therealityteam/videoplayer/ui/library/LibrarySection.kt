package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.scrape.FilenameParser

/** Accurate content category. 电影/电视剧 are LIVE-action only; 动画 is ALL animation (incl. films/OVA). */
/**
 * The shelves a card can sit on.
 *
 * These are FORMS, not subjects. The databases hand back a genre vocabulary that is almost entirely
 * subject matter — action, sci-fi, romance, war, western — and Alien is a science-fiction FILM, not
 * a science-fiction instead of a film. Those belong to a second axis and are not shelves.
 *
 * [OTHER] is the honest end of the list rather than a bin for whatever was hard: a game recording
 * really is none of the rest, and no database has ever heard of it.
 */
enum class LibCategory { MOVIE, TV, ANIME, DOC, MUSIC, VARIETY, PERSONAL, GAME, KIDS, ADULT, OTHER }

/**
 * Left-sidebar entries: the category filters + the favorites/history lists.
 *
 * The label is each app's own resource, never a string here (LibraryLabels): the apps ship in
 * more than one language, and a name the reader sees has to come from the one they read.
 */
enum class LibSection(val category: LibCategory?) {
    ALL(null),
    MOVIE(LibCategory.MOVIE),
    TV(LibCategory.TV),
    ANIME(LibCategory.ANIME),
    DOC(LibCategory.DOC),
    MUSIC(LibCategory.MUSIC),
    VARIETY(LibCategory.VARIETY),
    PERSONAL(LibCategory.PERSONAL),
    GAME(LibCategory.GAME),
    KIDS(LibCategory.KIDS),
    ADULT(LibCategory.ADULT),
    OTHER(LibCategory.OTHER),
    FAVORITES(null),
    HISTORY(null),
}

/** The shelves always on show, in order. */
val PRIMARY_SECTIONS = listOf(
    LibSection.ALL, LibSection.MOVIE, LibSection.TV, LibSection.ANIME, LibSection.DOC,
)

/**
 * The shelves behind 更多 — the ones a given library may well have nothing on.
 *
 * A fixed list, not one that follows the content: a sidebar that rearranges itself between two
 * people's libraries is a sidebar neither of them can be told how to use. What it does instead is
 * fold, so the cost of a shelf nobody is using is one row rather than seven.
 */
val SECONDARY_SECTIONS = listOf(
    LibSection.MUSIC, LibSection.VARIETY, LibSection.PERSONAL,
    LibSection.GAME, LibSection.KIDS, LibSection.ADULT, LibSection.OTHER,
)

/**
 * Classifies an item by genre + kind + anime-flag. Anime wins first (so an animated film lands in
 * 动画, not 电影), then documentary / variety by genre, else live movie/TV by kind.
 */
object MediaCategory {
    /**
     * A genre arrives in whatever language the metadata was fetched in — "Animation" for one
     * reader, "Dokumentarfilm", "ドキュメンタリー", "다큐멘터리" or "мультфильм" for the next — so the
     * shelves cannot be decided by a list of Chinese and English NAMES. Measured on this library
     * by re-running the evaluator with the genre column translated: category accuracy 0.9959 for a
     * Chinese reader, 0.9834 for a German one, 0.9641 for a Japanese one, on the very same files.
     *
     * Match STEMS against a folded genre (diacritics stripped, lower-cased) instead. The genre
     * vocabulary is a closed set — about 35 names per language across TMDB and AniList — and no
     * other name in it contains any of these stems, so a stem is as safe as an exact name and
     * survives wording the table cannot know ("Dokumentarfilm" vs "Dokumentation", "Reality-TV").
     */
    private val ANIME_G = listOf(
        "anima", "アニメ", "애니", "мультфильм", "мультиплика", "动画", "动漫", "動畫", "動漫",
    )
    private val DOC_G = listOf(
        "document", "dokument", "ドキュメンタリ", "다큐", "документальн", "纪录", "记录", "紀錄", "記錄",
    )
    private val VARIETY_G = listOf(
        "realit", "talk", "リアリティ", "トーク", "버라이어티", "리얼리티", "토크", "реалити", "ток-шоу",
        "真人秀", "脱口秀", "综艺", "綜藝", "实境", "實境",
    )
    // A title is in the FILE's language, not the reader's, so these stay lists of names.
    private val CONCERT = Regex(
        "(?i)演唱会|演唱會|音乐会|音樂會|live concert|\\bconcert\\b|\\bkonzert\\b|ライブ|コンサート|콘서트|" +
            "\\blive at\\b|\\blive in\\b|\\bunplugged\\b|\\bmtv live\\b|\\bsetlist\\b|" +
            "\\bworld tour\\b|巡回演出|巡迴演出|演奏会|演奏會|리사이틀",
    )

    /**
     * The Music genre, which cannot be trusted on its own.
     *
     * TMDB puts it on films ABOUT music — 爆裂鼓手, 波西米亚狂想曲, 海上钢琴师 — which are films, and
     * AniList puts it on anime music videos, which are anime. So it only decides anything for
     * something that is not already a film and not already anime; a recording of a concert is
     * usually in no database at all and is found by its folder or its title instead.
     */
    private val MUSIC_G = listOf(
        "music", "musik", "musique", "музык", "音乐", "音樂", "ミュージック", "음악",
    )

    /** Children's programming, named as such where no genre says so. */
    private val KIDS_TITLE = Regex("(?i)\\bfor kids\\b|儿歌|兒歌|少儿|少兒|动画片大全|キッズ|어린이")

    private val KIDS_G = listOf(
        "kids", "children", "kinder", "enfant", "детск", "儿童", "兒童", "少儿", "少兒",
        "キッズ", "子供", "어린이",
    )

    // A broadcaster's documentary STRAND names itself in the title, which is the only signal left
    // for a programme that matches no database and sits outside a documentary library root:
    // `ＮＨＫスペシャル　選　新・ドキュメント太平洋戦争`, `映像の世紀バタフライエフェクト`, `ＥＴＶ特集`.
    private val DOC_TITLE = Regex(
        "(?i)ＮＨＫスペシャル|NHKスペシャル|映像の世紀|ＢＳ１スペシャル|BS1スペシャル|" +
            "ＥＴＶ特集|ETV特集|ドキュメンタリー|ドキュメント|纪录片|紀錄片|记录片|\\bdocumentary\\b",
    )
    /** …but 特集ドラマ is a drama special, and NHK labels those with the same 特集. */
    private val DOC_TITLE_NOT = Regex("ドラマ|drama")

    private val MARKS = Regex("""\p{Mn}+""")

    /**
     * The forms of a genre a stem may be looked for in: composed and lower-cased, plus — when it
     * differs — one with the marks taken off, so "Réalité" also reads as "realite".
     *
     * Both, not just the bare one: NFD decomposition takes the dakuten off katakana as readily as
     * the accent off an é, and a stripped "ドキュメンタリー" becomes "トキュメンタリー", which matches
     * nothing. (That cost this fix half of its Japanese gain until the evaluator showed the 12
     * documentaries still landing on the wrong shelf.)
     */
    private fun forms(s: String): List<String> {
        val nfc = java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFC).lowercase()
        val bare = MARKS.replace(java.text.Normalizer.normalize(nfc, java.text.Normalizer.Form.NFD), "")
        return if (bare == nfc) listOf(nfc) else listOf(nfc, bare)
    }

    private fun Collection<String>.saysAnyOf(stems: List<String>): Boolean =
        any { g -> stems.any { g.contains(it) } }

    /** Whether a database calls [item] animation, in whichever language its genres came in. */
    fun isAnimation(item: MediaItem): Boolean = item.genres.flatMap(::forms).saysAnyOf(ANIME_G)

    /**
     * Which shelf [item] belongs on.
     *
     * Read in order, because the categories genuinely overlap and something has to win. What decides
     * the order is which answer a person would be most annoyed to see wrong:
     *
     *  1. adult, because it is hidden by default and a thing that leaks onto another shelf defeats
     *     the point of hiding it;
     *  2. personal, because a clip off a camera must never be looked up at all — that is where the
     *     worst matches come from, and it is a judgement the folder makes, not the metadata;
     *  3. children, before animation, because a parent looking for 天线宝宝 wants it under 儿童 and
     *     animation is where it would otherwise disappear;
     *  4. then the forms the databases actually describe, and the kind as a last resort.
     */
    fun of(item: MediaItem): LibCategory {
        val g = item.genres.flatMap(::forms)
        // ── What the person filed, first ──────────────────────────────────────────────────────
        //
        // Except for adult, which is a database's warning and the one thing that must not end up on
        // a shelf somebody's family is scrolling through.
        //
        // After that the folder wins, because a folder is a judgement somebody made about their own
        // files and a genre is a stranger's guess about a work. The existing anime and documentary
        // roots already worked this way; the rest join them, and the order they are asked in only
        // matters for a file filed under two of them at once.
        if (item.certification == "R18+" || g.saysAnyOf(ADULT_G)) return LibCategory.ADULT

        // A clip off a camera is not a work, and the name is as good a signal as the folder. The
        // scraper asks the same question to decide there is nothing to look up.
        if (FilenameParser.isOwnRecording(item.path, item.fileName)) return LibCategory.PERSONAL
        // A file under an Anime/动漫 root is anime by the person's own filing, including the ones
        // still UNMATCHED (no genres at all) and the ones matched to a TMDB entry whose genres do
        // not say 动画. Without this the whole shelf leaks into 电视剧 an episode at a time.
        if (FilenameParser.isUnderAnimeRoot(item.path)) return LibCategory.ANIME
        if (FilenameParser.isUnderKidsRoot(item.path)) return LibCategory.KIDS
        if (FilenameParser.isUnderMusicRoot(item.path)) return LibCategory.MUSIC
        if (FilenameParser.isUnderGameRoot(item.path)) return LibCategory.GAME
        if (FilenameParser.isUnderDocRoot(item.path)) return LibCategory.DOC

        // ── then what the databases say ───────────────────────────────────────────────────────
        //
        // NFC: an SMB filename from a macOS host is decomposed, so ＢＳ１スペシャル arrives as
        // ス + ヘ + U+309A and never matches a composed literal.
        val says = { r: Regex ->
            val t = java.text.Normalizer.normalize(item.title, java.text.Normalizer.Form.NFC)
            val f = java.text.Normalizer.normalize(item.fileName, java.text.Normalizer.Form.NFC)
            r.containsMatchIn(t) || r.containsMatchIn(f)
        }
        // Before anime, so that a parent looking for 天线宝宝 finds it under 儿童 rather than losing
        // it among everything else that happens to be drawn.
        if (g.saysAnyOf(KIDS_G) || says(KIDS_TITLE)) return LibCategory.KIDS

        if (item.collectionKey?.startsWith("anilist:") == true || g.saysAnyOf(ANIME_G)) {
            return LibCategory.ANIME
        }
        // Most of a documentary shelf never matches a database — a Chinese-titled NHK one-off is in
        // neither TMDB nor AniList — so it carries no genres at all and used to fall through to 电影
        // by kind: 158 of 187 documentaries were filed there. A broadcaster's strand names itself.
        if (g.saysAnyOf(DOC_G) || (says(DOC_TITLE) && !says(DOC_TITLE_NOT))) return LibCategory.DOC

        // Music before variety: a concert had nowhere else to go and landed on the variety shelf,
        // and on a real library every card there turned out to be one.
        if (says(CONCERT) || (g.saysAnyOf(MUSIC_G) && item.kind != MediaKind.MOVIE)) {
            return LibCategory.MUSIC
        }
        if (g.saysAnyOf(VARIETY_G)) return LibCategory.VARIETY
        // Last, because it reads the NAME rather than the folder, and a name is the weaker claim:
        // measured on a real shelf only 22 of 156 game files can be told apart this way. Ahead of
        // the kind fallback all the same — a gameplay recording is not a film merely for having no
        // episode number in it.
        if (FilenameParser.isGameFootage(item.path, item.fileName, item.title)) return LibCategory.GAME

        return when (item.kind) {
            MediaKind.MOVIE -> LibCategory.MOVIE
            MediaKind.SHOW, MediaKind.EPISODE -> LibCategory.TV
            // Nothing identified it and its own name names no episode. The folder somebody put it
            // in is the last thing anybody said about it on purpose, so it decides — and only here,
            // where every other rule has already declined to answer.
            MediaKind.UNKNOWN -> when {
                FilenameParser.isUnderTvRoot(item.path) -> LibCategory.TV
                FilenameParser.isUnderMovieRoot(item.path) -> LibCategory.MOVIE
                else -> LibCategory.OTHER
            }
        }
    }

    /** What a database calls content it will not show without being asked. */
    private val ADULT_G = listOf("hentai", "adult", "エロ", "성인")
}
