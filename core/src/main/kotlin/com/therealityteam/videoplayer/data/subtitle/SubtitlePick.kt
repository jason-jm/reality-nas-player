package com.therealityteam.videoplayer.data.subtitle

import java.util.Locale

/**
 * Which subtitle to show when the reader has not chosen one for a file: the one in their
 * language, else English — read from everything a track says about itself, not only its language
 * tag. The subtitles people actually keep beside their films mostly carry none: a fansub's
 * `Movie.chs&eng.ass`, a group's "简日双语", `Subs/2_Chinese.srt`. A tag-only rule (mpv's slang, the
 * headset's language codes) passes them over for an English track that happens to be labelled.
 *
 * Among equals: text over pictures (sharper, restyleable), a full track over a forced one or one
 * for the hard of hearing, the file's default, then the order the file lists them in.
 */
object SubtitlePick {

    /** What a track says about itself. */
    data class Candidate(
        val id: String,
        val language: String?,
        val title: String?,
        val fileName: String?,
        val codec: String?,
        val forced: Boolean = false,
        val hearingImpaired: Boolean = false,
        val default: Boolean = false,
    )

    /**
     * The best track for a reader of [reader], or null when none is in their language or English.
     * [videoFileName] is the video's own file name: a subtitle file named after it is read only for
     * what it adds ("Parasite.2019.KOREAN.chs.srt" is Chinese, whatever the film's name says).
     */
    fun best(tracks: List<Candidate>, reader: Locale, videoFileName: String? = null): Candidate? =
        tracks.withIndex()
            .map { (i, t) -> Triple(t, score(t, reader, videoFileName), i) }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy<Triple<Candidate, Int, Int>> { it.second }.thenByDescending { it.third })
            ?.first

    /** How well [t] suits [reader]: language first (hundreds), then the tie-breakers. 0 = not at all. */
    fun score(t: Candidate, reader: Locale, videoFileName: String? = null): Int {
        val lang = languageOf(t, videoFileName) ?: return 0
        val wanted = when {
            reader.language == "zh" && isTraditionalReader(reader) -> when (lang) {
                Lang.ZH_HANT -> 500; Lang.ZH -> 450; Lang.ZH_HANS -> 400; Lang.EN -> 100; else -> 0
            }
            reader.language == "zh" -> when (lang) {
                Lang.ZH_HANS -> 500; Lang.ZH -> 450; Lang.ZH_HANT -> 400; Lang.EN -> 100; else -> 0
            }
            reader.language == "ja" -> when (lang) { Lang.JA -> 500; Lang.EN -> 100; else -> 0 }
            reader.language == "ko" -> when (lang) { Lang.KO -> 500; Lang.EN -> 100; else -> 0 }
            reader.language == "en" -> if (lang == Lang.EN) 500 else 0
            else -> when {
                lang == Lang.OTHER && matchesCode(t.language, reader) -> 500
                lang == Lang.EN -> 100
                else -> 0
            }
        }
        if (wanted == 0) return 0
        var s = wanted
        if (!isPicture(t.codec)) s += 20
        if (!t.forced) s += 12
        if (!t.hearingImpaired && !(t.title?.let { SDH.containsMatchIn(it) } ?: false)) s += 6
        if (t.default) s += 2
        return s
    }

    private enum class Lang { ZH_HANS, ZH_HANT, ZH, EN, JA, KO, OTHER }

    private val HANS = Regex("""(?i)(^|[^a-z])(chs|sc|gb|zh-?hans|zh-?cn|chi_sim)([^a-z]|$)|简|簡体|简体|简中""")
    private val HANT = Regex("""(?i)(^|[^a-z])(cht|tc|big5|zh-?hant|zh-?tw|zh-?hk|chi_tra)([^a-z]|$)|繁|正體""")
    private val ZH = Regex("""(?i)(^|[^a-z])(chi|zho|zh|chinese|mandarin)([^a-z]|$)|中文|中字|国语|國語|双语|雙語""")
    private val EN = Regex("""(?i)(^|[^a-z])(eng|en|english)([^a-z]|$)|英文|英语|英語""")
    private val JA = Regex("""(?i)(^|[^a-z])(jpn|ja|jp|japanese)([^a-z]|$)|日文|日语|日語""")
    private val KO = Regex("""(?i)(^|[^a-z])(kor|ko|korean)([^a-z]|$)|韩文|韩语|韓文|韓語""")
    private val SDH = Regex("""(?i)(^|[^a-z])(sdh|hi|cc)([^a-z]|$)""")

    /**
     * The track's language from its tag, its title and its file's name, in that order of trust.
     * A bilingual Chinese one (chs&eng, 简英) counts as Chinese — that is what it is read for.
     */
    private fun languageOf(t: Candidate, videoFileName: String?): Lang? {
        val stem = videoFileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        val own = t.fileName?.substringBeforeLast('.')?.let { f -> if (stem != null && f.startsWith(stem)) f.removePrefix(stem) else f }
        val sources = listOfNotNull(t.language, t.title, own)
        for (text in sources) {
            when {
                HANS.containsMatchIn(text) -> return Lang.ZH_HANS
                HANT.containsMatchIn(text) -> return Lang.ZH_HANT
                ZH.containsMatchIn(text) -> return Lang.ZH
            }
        }
        for (text in sources) {
            when {
                JA.containsMatchIn(text) -> return Lang.JA
                KO.containsMatchIn(text) -> return Lang.KO
                EN.containsMatchIn(text) -> return Lang.EN
            }
        }
        return if (t.language.isNullOrBlank()) null else Lang.OTHER
    }

    private fun matchesCode(tag: String?, reader: Locale): Boolean {
        val t = tag?.lowercase() ?: return false
        val iso3 = runCatching { reader.isO3Language }.getOrNull()
        return t == reader.language || t == iso3 || t.startsWith(reader.language + "-")
    }

    private fun isTraditionalReader(reader: Locale): Boolean =
        reader.script.equals("Hant", ignoreCase = true) ||
            (reader.script.isEmpty() && reader.country.uppercase() in setOf("TW", "HK", "MO"))

    private fun isPicture(codec: String?): Boolean =
        codec?.lowercase() in setOf("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "pgs", "vobsub", "xsub")
}
