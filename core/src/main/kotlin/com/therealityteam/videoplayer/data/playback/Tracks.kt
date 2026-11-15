package com.therealityteam.videoplayer.data.playback

/**
 * One selectable stream inside the playing file — an audio track or a subtitle track.
 * [id] is an engine-specific selection key; [label] is ready for display ("日语 · DTS 5.1").
 */
data class MediaTrack(
    val id: String,
    val label: String,
    val language: String? = null,
    /** Channel count for sound tracks (0 when unknown, and for text tracks). */
    val channels: Int = 0,
    val selected: Boolean = false,
    /** What names the track: a sidecar's file name, a titled track's title, else its language. */
    val name: String = label,
    /** Where it comes from and how it is coded: "外挂 · SRT", "Dolby TrueHD 7.1 · 日本語". */
    val detail: String = "",
    /** A same-folder subtitle file rather than a stream inside the video. */
    val external: Boolean = false,
    /** The engine's own name for the stream (an MKV track's number), where it has one. */
    val streamId: String? = null,
    /** ASS / SSA: subtitles typeset by their author, which libass can draw as set. */
    val typeset: Boolean = false,
    /** Bitmap subtitles authored in video-frame coordinates can occupy the letterbox bars. */
    val bitmapSubtitle: Boolean = false,
)

/** An external subtitle file to load alongside the video (same-folder .srt/.ass/.vtt…). */
data class SidecarSubtitle(
    val uri: String,
    val mimeType: String,
    val label: String,
    /** The language code the engine matches the reader's language against ("zh-Hans", "en"). */
    val language: String? = null,
    /** The languages as the app reads them, a name tag ("chs&eng") — from the file's name, or its lines. */
    val tag: String? = null,
)

/**
 * A read-once snapshot of the playing file's streams for the info panel (Movist's ⌘I).
 * Each field is a display-ready line, or null when the engine can't tell.
 */
data class MediaInfo(
    val video: String? = null,
    val audio: String? = null,
    /** Decoders in use (硬解/软解 + names), once initialised. */
    val decoder: String? = null,
    /** Frame tally (丢帧 / 已渲染) — live, so the panel reads it again while open. */
    val frames: String? = null,
)

/** What a stream's own title is worth showing. */
object TrackTitle {

    /**
     * [title], or null where it is a muxer's default rather than a name — `Main Subtitle`, `Subtitle`,
     * `Track 2`, `Stereo`, `Audio`: Mushoku Tensei's one subtitle track headed its row in English as
     * "Main Subtitle" while saying nothing the language and the format beside it did not. The row then
     * goes by its language. A title that tells tracks apart — `Commentary`, `Forced`, `Signs & Songs`,
     * `简体中文`, `国语` — stays. So does a codec's name with more in it than the stream says (`Atmos`).
     */
    fun named(title: String?): String? = title?.trim()?.takeIf { it.isNotEmpty() && !STAND_IN.matches(it) }

    private val STAND_IN = Regex(
        """(?i)(?:main|default|primary)?\s*(?:subtitles?|subs?|audio|sound|track)?\s*(?:track)?\s*#?\d{0,2}|""" +
            """(?:stereo|mono|surround|\d\.\d)(?:\s*(?:audio|track))?|und(?:efined)?|unknown|none|""" +
            // A codec and its channels — `FLAC 2.0`, `AAC 5.1`, `DTS-HD MA 7.1` — is what the row's
            // detail line already reads off the stream, or, where a release re-encoded the track and
            // kept the old title, a wrong word: Reconguista's AAC track headed its row "FLAC 2.0".
            // (`TrueHD Atmos`, `DTS:X` say more than the stream line does, and stay.)
            """(?:\d\.\d[\s_.-]*)?(?:flac|aac(?:-lc)?|he-aac|ac-?3|e-?ac-?3|dd\+?|ddp|dolby digital(?: plus)?|dts(?:-hd(?: ?ma| ?hra)?)?|""" +
            """lpcm|pcm|mp3|mp2|opus|vorbis|alac|truehd)(?:[\s_.-]*(?:\d\.\d|\dch|stereo|mono))?""",
    )
}
