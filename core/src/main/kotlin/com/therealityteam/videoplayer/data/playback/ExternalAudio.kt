package com.therealityteam.videoplayer.data.playback

/**
 * A sound track in a file of its own beside the video — a release's Mandarin dub as `Film.国语.mka`, a
 * director's commentary as `Film.commentary.ac3` — as the engine opens it: [uri] (a file, or the stream
 * server's URL for one on a share), [label] what the menus call it (its name's tag: 国语, else the file's
 * name), [language] the code its tag gives (zh, yue, en, ja, ko), when it gives one.
 */
data class SidecarAudio(val uri: String, val label: String, val language: String? = null)

/** Which files beside a video are its sound tracks, found by name as its subtitle files are. */
object ExternalAudio {

    /** A match by name only: the caller makes it a [SidecarAudio] with the URI it can be read at. */
    data class Candidate(val fileName: String, val label: String, val language: String?)

    /**
     * Sound containers and raw streams a release ships a dub in. Not .mp4/.mkv, which are videos of
     * their own, and not .mp3/.wav beside a video of another name (a soundtrack album) — only files
     * named after the video are taken.
     */
    private val EXTENSIONS = setOf(
        "mka", "ac3", "eac3", "ec3", "dts", "dtshd", "thd", "truehd", "mlp",
        "flac", "aac", "m4a", "mp3", "opus", "ogg", "oga", "wav", "wv",
    )

    /**
     * Whether Media3 merges [uriOrName] in beside the film — a container it reads and can seek in: a
     * Matroska, an MP4 sound, FLAC, Ogg, WAV, and MP3 and ADTS AAC by their bitrate. Raw AC-3 / E-AC-3
     * (unseekable there), DTS, TrueHD and WavPack (no reader) are mpv's: chosen, the film goes on in mpv.
     */
    fun media3Merges(uriOrName: String): Boolean = extensionOf(uriOrName) in MEDIA3

    /** A dub only mpv plays, named by its codec as the menus name codecs (the file holds nothing else). */
    fun codecOf(uriOrName: String): String? = when (extensionOf(uriOrName)) {
        "ac3" -> "Dolby AC-3"
        "eac3", "ec3" -> "Dolby E-AC-3"
        "dts", "dtshd" -> "DTS"
        "thd", "truehd", "mlp" -> "Dolby TrueHD"
        "wv" -> "WavPack"
        else -> null
    }

    private fun extensionOf(uriOrName: String): String =
        uriOrName.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    private val MEDIA3 = setOf("mka", "m4a", "flac", "ogg", "oga", "opus", "wav", "mp3", "aac")

    /** The id both engines give the dub [index] of [SidecarAudio]s — the same in each, so a pick is kept across them. */
    fun trackId(index: Int): String = "$DUB$index"

    /** The dub an id [trackId] made names, or null for any other track's. */
    fun indexOf(trackId: String): Int? = trackId.takeIf { it.startsWith(DUB) }?.removePrefix(DUB)?.toIntOrNull()

    private const val DUB = "dub:"

    /** Whether [name] is a sound file (by its extension) — for walks that keep candidates. */
    fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /**
     * The sound files among [siblings] that belong to [videoFileName]: `Film.mka` and `Film.国语.mka`
     * belong to `Film.mkv` — the name's own extra segment ("国语") is the track's label. In name order.
     */
    fun match(videoFileName: String, siblings: List<String>): List<Candidate> {
        val stem = videoFileName.substringBeforeLast('.')
        if (stem.isEmpty()) return emptyList()
        return siblings.filter { name ->
            isAudio(name) && name.substringBeforeLast('.').let { it.equals(stem, ignoreCase = true) || it.startsWith("$stem.", ignoreCase = true) }
        }.sortedBy { it.lowercase() }.map { name ->
            val tag = name.substringBeforeLast('.').drop(stem.length).trim('.', ' ').ifBlank { null }
            Candidate(fileName = name, label = tag ?: name, language = tag?.let(::languageOf))
        }
    }

    /**
     * The language a dub's tag names, as a code: 国语 / 普通话 / Mandarin → zh, 粤语 / Cantonese → yue,
     * 台配 → zh-TW, English → en, 日语 → ja, 韩语 → ko. Null for a tag that names none ("commentary", "5.1").
     */
    fun languageOf(tag: String): String? {
        val t = tag.lowercase()
        val tokens = t.split(SEPARATORS).filter { it.isNotEmpty() }.toSet()
        // (国 alone is no tag: 英国 and 韩国 hold it too.)
        return when {
            "粤" in t || "粵" in t || tokens.any { it in CANTONESE } -> "yue"
            "台配" in t || "台湾" in t || "台灣" in t -> "zh-TW"
            // (韩国语 holds 国语.)
            "韩" in t || "韓" in t || tokens.any { it in KOREAN } -> "ko"
            MANDARIN_WORDS.any { it in t } || tokens.any { it in MANDARIN } -> "zh"
            "英" in t || tokens.any { it in ENGLISH } -> "en"
            "日" in t || tokens.any { it in JAPANESE } -> "ja"
            else -> null
        }
    }

    private val SEPARATORS = Regex("[\\s._\\-\\[\\]()&+,/@]+")
    private val MANDARIN_WORDS = listOf("国语", "國語", "国配", "國配", "普通话", "普通話", "中文", "华语", "華語", "汉语", "漢語")
    private val CANTONESE = setOf("yue", "cantonese", "can")
    private val MANDARIN = setOf("zh", "zho", "chi", "chn", "cmn", "mandarin", "chinese", "gy", "guoyu")
    private val ENGLISH = setOf("en", "eng", "english")
    private val JAPANESE = setOf("ja", "jp", "jpn", "japanese")
    private val KOREAN = setOf("ko", "kor", "korean")
}
