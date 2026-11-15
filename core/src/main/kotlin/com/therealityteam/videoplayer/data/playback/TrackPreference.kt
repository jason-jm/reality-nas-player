package com.therealityteam.videoplayer.data.playback

import java.util.Locale

/**
 * 首选字幕: which subtitles a file opens with when nothing was picked for it or its show. Saved by name: the
 * languages added later sit between the ones that were there and OFF.
 */
enum class SubtitleChoice {
    AUTO, SIMPLIFIED, TRADITIONAL, ENGLISH, JAPANESE,
    KOREAN, GERMAN, FRENCH, SPANISH, ITALIAN, PORTUGUESE, RUSSIAN, DUTCH, POLISH, SWEDISH, DANISH, NORWEGIAN,
    FINNISH, CZECH, GREEK, ROMANIAN, TURKISH, THAI, MALAY,
    OFF,
}

/** 首选音轨: which sound a file opens with when nothing was picked for it or its show. */
enum class AudioChoice {
    DEFAULT, JAPANESE, CHINESE, ENGLISH,
    KOREAN, GERMAN, FRENCH, SPANISH, ITALIAN, PORTUGUESE, RUSSIAN, DUTCH, POLISH, SWEDISH, DANISH, NORWEGIAN,
    FINNISH, CZECH, GREEK, ROMANIAN, TURKISH, THAI, MALAY,
}

/**
 * The first choice of tracks, by the reader's preference — after the file's own pick ([TrackMemory])
 * and its show's ([TrackPicks]), before the engine's default. A subtitle file's "language" is
 * whatever its name says (`chs`, `cht`, `sc`, `chs&eng`, `简体`…) and an embedded track's is a
 * code with perhaps a title ("简体中文", "Chinese (Traditional)"), so each track is read for the
 * languages it holds rather than matched by code. Pure, so the reading is unit-tested.
 */
object TrackPreference {

    /** A language a track can be in, by its ISO 639-1 code (Chinese by script). */
    enum class Lang(val code: String) {
        SIMPLIFIED("zh-Hans"), TRADITIONAL("zh-Hant"), CHINESE("zh"), ENGLISH("en"), JAPANESE("ja"),
        KOREAN("ko"), GERMAN("de"), FRENCH("fr"), SPANISH("es"), ITALIAN("it"), PORTUGUESE("pt"), RUSSIAN("ru"),
        DUTCH("nl"), POLISH("pl"), SWEDISH("sv"), DANISH("da"), NORWEGIAN("nb"), FINNISH("fi"), CZECH("cs"),
        GREEK("el"), ROMANIAN("ro"), TURKISH("tr"), THAI("th"), MALAY("ms");

        companion object {
            /** The language of [locale] (its script for Chinese), or null for one no track list names. */
            fun of(locale: Locale): Lang? = when (val l = locale.language) {
                "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) TRADITIONAL else SIMPLIFIED
                "no", "nn" -> NORWEGIAN
                else -> entries.firstOrNull { it.code == l }
            }
        }
    }

    /**
     * The subtitles to open with, or null to leave the engine's own choice (AUTO for a reader who is
     * not Chinese, or nothing fits). [locale] is the system's, which AUTO follows.
     */
    fun subtitle(
        choice: SubtitleChoice,
        tracks: List<MediaTrack>,
        locale: Locale = Locale.getDefault(),
        /** The video's own file name: "[FLsnow]…[GB_BIG5]…" says what its one untagged subtitle is in. */
        videoFileName: String? = null,
        /** The sound tracks, the playing one marked: 自动 gives a German reader of a German dub no German subtitles. */
        sound: List<MediaTrack> = emptyList(),
    ): TrackMemory.Subtitle? {
        if (tracks.isEmpty()) return null
        if (choice == SubtitleChoice.AUTO && locale.language != "zh") {
            val mine = Lang.of(locale)
            if (mine != null && sound.any { it.selected && mine in langsOf(it) }) return null
        }
        val order = when (choice) {
            // 自动 follows the system's language. The engine matches a language code and nothing else,
            // and a disc's Chinese tracks all say zh: it opened The Mandalorian on Chinese (Cantonese)
            // over Chinese (Simplified). A Chinese reader's script is ours to tell; anyone else's
            // language the engine's.
            SubtitleChoice.AUTO -> autoOrder(locale) ?: return null
            SubtitleChoice.OFF -> return TrackMemory.Subtitle.Off
            // A Chinese reader takes the other script before nothing.
            SubtitleChoice.SIMPLIFIED -> listOf(Lang.SIMPLIFIED, Lang.CHINESE, Lang.TRADITIONAL)
            SubtitleChoice.TRADITIONAL -> listOf(Lang.TRADITIONAL, Lang.CHINESE, Lang.SIMPLIFIED)
            // Any other language: itself (the enums share their names).
            else -> listOf(Lang.valueOf(choice.name))
        }
        val stem = videoFileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        return (best(tracks, order, stem) ?: namedByFile(tracks, order, videoFileName))?.let { TrackMemory.Subtitle.Select(it.id) }
    }

    /**
     * The file's one subtitle track when nothing names its language — an old fansub's embedded ASS,
     * untitled and untagged — but the video's own name says what language its subtitles are in
     * ("[GB]", "[CHS]", "[BIG5]", "简繁"), and that is one the reader reads. Only one: of two untagged
     * tracks the name cannot say which is which.
     */
    private fun namedByFile(tracks: List<MediaTrack>, order: List<Lang>, videoFileName: String?): MediaTrack? {
        val name = videoFileName ?: return null
        val unnamed = tracks.filter { langsOf(it).isEmpty() }
        if (unnamed.size != 1 || tracks.size != 1) return null
        val said = langsOfText(name.substringBeforeLast('.'))
        return unnamed.first().takeIf { order.any { it in said } }
    }

    /** The sound to open with, or null to leave the file's default (DEFAULT, or nothing fits). */
    fun audio(choice: AudioChoice, tracks: List<MediaTrack>, locale: Locale = Locale.getDefault()): String? {
        val lang = when (choice) {
            AudioChoice.DEFAULT -> return null
            else -> Lang.valueOf(choice.name)
        }
        // Mandarin and Cantonese tracks are rarely tagged by script; any Chinese answers 中文 — the
        // Mandarin before a Cantonese dub, except for a reader in Hong Kong or Macau.
        val wanted = if (lang == Lang.CHINESE) setOf(Lang.CHINESE, Lang.SIMPLIFIED, Lang.TRADITIONAL) else setOf(lang)
        val cantoneseFirst = locale.country in CANTONESE_REGIONS
        return tracks.filter { t -> langsOf(t).any { it in wanted } }
            .minWithOrNull(compareBy { t -> isCantonese(t) != cantoneseFirst })?.id
    }

    /**
     * 默认's sound for a film whose original title says plainly what language it was made in — Hangul
     * is Korean, kana Japanese — when the file holds a track in that language that is not the one it
     * opens on: a MULTi release of 공작 (The Spy Gone North) opened on its French dub, the Korean
     * second. Null otherwise (a title in Han characters alone may be Chinese or Japanese; one in
     * Latin letters says nothing), and the file's own default stands.
     */
    fun originalSound(tracks: List<MediaTrack>, originalTitle: String?): String? {
        val words = originalLanguageWords(originalTitle ?: return null) ?: return null
        if (tracks.size < 2) return null
        val own = tracks.filter { t ->
            val text = listOfNotNull(t.language, t.name).joinToString(" ")
            text.lowercase().split(SEPARATORS).any { it in words } || words.any { w -> w.any { c -> c.code > 0x2E80 } && w in text }
        }
        if (own.isEmpty() || own.any { it.selected }) return null
        return own.first().id
    }

    /** The words a track of the language an original title is written in goes by, or null. */
    private fun originalLanguageWords(title: String): Set<String>? = when {
        title.any { it in '\uAC00'..'\uD7A3' || it in '\u1100'..'\u11FF' } -> KOREAN
        title.any { it in '\u3040'..'\u30FF' } -> JAPANESE + setOf("日语", "日語", "日文", "日本語")
        else -> null
    }

    private val KOREAN = setOf("ko", "kor", "korean", "韩语", "韓語", "韩文", "韓文", "한국어")

    /**
     * What 自动 reads in: a Chinese reader their own script first, then any Chinese, then the other script; every
     * other reader their language — a German one the German subtitles, where the engine's own choice matched a
     * code and nothing else (a track titled "Deutsch" with no tag was never chosen).
     */
    private fun autoOrder(locale: Locale): List<Lang>? = when {
        locale.language != "zh" -> Lang.of(locale)?.let { listOf(it) }
        locale.script == "Hant" || locale.country in TRADITIONAL_REGIONS -> listOf(Lang.TRADITIONAL, Lang.CHINESE, Lang.SIMPLIFIED)
        else -> listOf(Lang.SIMPLIFIED, Lang.CHINESE, Lang.TRADITIONAL)
    }

    /**
     * The best track for [order]: the earliest language that has one; within it, a track of the
     * whole film before a forced one (only the lines spoken in another language), the written
     * standard before Cantonese, and one language before two.
     */
    private fun best(tracks: List<MediaTrack>, order: List<Lang>, videoStem: String? = null): MediaTrack? {
        for (lang in order) {
            val holding = tracks.map { it to langsOf(it, videoStem) }.filter { (_, langs) -> lang in langs }
            if (holding.isNotEmpty()) {
                return holding.minWith(compareBy({ isForced(it.first) }, { isCantonese(it.first) }, { it.second.size })).first
            }
        }
        return null
    }

    private fun textOf(track: MediaTrack): String = listOfNotNull(track.language, track.name).joinToString(" ").lowercase()

    /** A forced track: only the lines spoken in another language than the film's. */
    private fun isForced(track: MediaTrack): Boolean = textOf(track).let { t ->
        "forced" in t.split(SEPARATORS) || "强制" in t || "強制" in t
    }

    /** Cantonese: a dub, or subtitles written as it is spoken — not the written standard. */
    private fun isCantonese(track: MediaTrack): Boolean = textOf(track).let { t ->
        t.split(SEPARATORS).any { it == "cantonese" || it == "yue" } || "粤" in t || "粵" in t
    }

    private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")
    private val CANTONESE_REGIONS = setOf("HK", "MO")

    /**
     * The languages a track holds, read from its language tag and its name; two for a bilingual one. A code (de,
     * ger, nor) counts in the tag only: in a name, "it", "da", "de" or "may" are as often words.
     */
    fun langsOf(track: MediaTrack): Set<Lang> {
        val out = LinkedHashSet(langsOfText(listOfNotNull(track.language, track.name).joinToString(" ")))
        val tag = track.language?.lowercase()?.split('-', '_')?.firstOrNull()
        if (tag != null) OTHERS.filter { (_, spec) -> tag in spec.codes }.forEach { (lang, _) -> out += lang }
        return out
    }

    /**
     * …a sidecar named after the video read by what it adds to that name: "[WLGO]…[BIG5&GB]…[01].gb.ass"
     * is Simplified, though the video's own name, which both of its files carry, says GB and BIG5.
     */
    private fun langsOf(track: MediaTrack, videoStem: String?): Set<Lang> {
        val own = videoStem?.takeIf { track.name.startsWith(it) && track.name.length > it.length }?.let { track.name.removePrefix(it) }
            ?: return langsOf(track)
        return langsOfText(listOfNotNull(track.language, own).joinToString(" ")).ifEmpty { langsOf(track) }
    }

    /** The languages a tag or a name spells: "chs&eng", "简体中文", "Chinese (Traditional)". */
    fun langsOfText(text: String): Set<Lang> {
        val tokens = text.lowercase().split(SEPARATORS).filter { it.isNotEmpty() }.toSet()
        val out = LinkedHashSet<Lang>()
        if (tokens.any { it in SIMPLIFIED } || "简" in text || "簡" in text || "simplified" in tokens) out += Lang.SIMPLIFIED
        if (tokens.any { it in TRADITIONAL } || "繁" in text || "traditional" in tokens) out += Lang.TRADITIONAL
        if (out.isEmpty() && (tokens.any { it in CHINESE } || "中文" in text || "国语" in text || "國語" in text || "粤语" in text)) out += Lang.CHINESE
        if (tokens.any { it in ENGLISH } || "英" in text) out += Lang.ENGLISH
        if (tokens.any { it in JAPANESE } || "日语" in text || "日語" in text || "日文" in text || "日本語" in text) out += Lang.JAPANESE
        // The rest by their names — in English, in themselves, and as releases tag them (VOSTFR, Castellano).
        for ((lang, spec) in OTHERS) if (tokens.any { it in spec.words } || spec.words.any { w -> w.length > 3 && w.any { it.code > 0x2E80 } && w in text }) out += lang
        return out
    }

    /** A language's codes (ISO 639-1, -2/B, -2/T: in a track's tag) and the words its tracks are named by. */
    private class Spec(val codes: Set<String>, val words: Set<String>)

    private fun spec(codes: String, words: String) = Spec(codes.split(' ').toSet(), words.split(' ').toSet())

    private val OTHERS: Map<Lang, Spec> = linkedMapOf(
        Lang.KOREAN to spec("ko kor", "korean 한국어 韩语 韓語 韩文 韓文"),
        Lang.GERMAN to spec("de ger deu", "german deutsch"),
        Lang.FRENCH to spec("fr fre fra", "french français francais vf vff vfq vostfr truefrench"),
        Lang.SPANISH to spec("es spa", "spanish español espanol castellano castilian latino"),
        Lang.ITALIAN to spec("it ita", "italian italiano"),
        Lang.PORTUGUESE to spec("pt por pob", "portuguese português portugues brazilian"),
        Lang.RUSSIAN to spec("ru rus", "russian русский"),
        Lang.DUTCH to spec("nl dut nld", "dutch nederlands flemish vlaams"),
        Lang.POLISH to spec("pl pol", "polish polski"),
        Lang.SWEDISH to spec("sv swe", "swedish svenska"),
        Lang.DANISH to spec("da dan", "danish dansk"),
        Lang.NORWEGIAN to spec("nb no nn nor nob nno", "norwegian norsk"),
        Lang.FINNISH to spec("fi fin", "finnish suomi"),
        Lang.CZECH to spec("cs cze ces", "czech čeština cestina"),
        Lang.GREEK to spec("el gre ell", "greek ελληνικά"),
        Lang.ROMANIAN to spec("ro rum ron", "romanian română romana"),
        Lang.TURKISH to spec("tr tur", "turkish türkçe turkce"),
        Lang.THAI to spec("th tha", "thai ไทย"),
        Lang.MALAY to spec("ms may msa", "malay melayu"),
    )

    private val SEPARATORS = Regex("[\\s._\\-\\[\\]()&+,/@]+")
    private val SIMPLIFIED = setOf("chs", "sc", "gb", "zhs", "hans", "zh-hans", "zh-cn", "zh-sg", "chi_sim", "简体", "简中")
    private val TRADITIONAL = setOf("cht", "tc", "big5", "zht", "hant", "zh-hant", "zh-tw", "zh-hk", "chi_tra", "繁体", "繁體", "繁中")
    private val CHINESE = setOf("zh", "zho", "chi", "chinese", "cmn", "yue", "mandarin", "cantonese")
    private val ENGLISH = setOf("en", "eng", "english")
    private val JAPANESE = setOf("ja", "jp", "jpn", "japanese")
}
