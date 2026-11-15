package com.therealityteam.videoplayer.data.playback

/**
 * The sound tracks a Matroska file declares — read from its `Tracks` element, codec by codec — so a
 * track Media3's reader drops without a word can be told from a file with no sound at all. FLsnow's
 * Clannad TV rips carry their AAC as `A_AAC/MPEG4/LC/SBR`, the old way of naming it; Media3 knows only
 * `A_AAC`, keeps the picture and plays the episode silent; an old fansub's `S_ASS` subtitles go the same
 * way. Pure byte parsing over a [ByteSource].
 */
object MatroskaTracks {

    /** The codec ids of the file's sound tracks, in its order; empty when there are none or it is no MKV. */
    fun audioCodecs(source: ByteSource): List<String> = codecs(source, TYPE_AUDIO)

    /** The codec ids of the file's subtitle tracks, in its order. */
    fun subtitleCodecs(source: ByteSource): List<String> = codecs(source, TYPE_SUBTITLE)

    /**
     * The ids of the tracks Media3's reader would drop: the sound ([sound]) and subtitles ([text]) it
     * has no name for — asked only of the kind it came up without, so a file that plays its main
     * sound is not taken from the hardware decoder for an extra commentary in ALAC.
     */
    fun droppedByMedia3(source: ByteSource, sound: Boolean = true, text: Boolean = true): List<String> =
        all(source).filter { (type, codec) ->
            (sound && type == TYPE_AUDIO && !media3Reads(codec)) || (text && type == TYPE_SUBTITLE && codec !in MEDIA3_TEXT)
        }.map { it.second }

    /**
     * Whether the file's first sound track — the film's own sound, as releases order them — is one
     * Media3 drops. End of Evangelion (Renewal) leads with two AAC tracks in the old naming and puts a
     * dub crew's commentary in Vorbis third: Media3 played the commentary as the film's sound.
     */
    fun mainSoundDropped(source: ByteSource): Boolean = audioCodecs(source).firstOrNull()?.let { !media3Reads(it) } ?: false

    private fun codecs(source: ByteSource, wanted: Long): List<String> = all(source).filter { it.first == wanted }.map { it.second }

    private fun all(source: ByteSource): List<Pair<Long, String>> = runCatching {
        val tracks = ChapterReader.topLevel(source, ID_TRACKS) ?: return emptyList()
        val out = mutableListOf<Pair<Long, String>>()
        var offset = tracks.dataOffset
        while (offset < tracks.end) {
            val entry = ChapterReader.element(source, offset) ?: break
            if (entry.id == ID_TRACK_ENTRY) {
                var type = 0L
                var codec = ""
                var inner = entry.dataOffset
                while (inner < entry.end) {
                    val f = ChapterReader.element(source, inner) ?: break
                    when (f.id) {
                        ID_TRACK_TYPE -> type = ChapterReader.readUInt(source, f)
                        ID_CODEC_ID -> codec = ChapterReader.readString(source, f, Charsets.US_ASCII)
                    }
                    if (f.unknownSize) break
                    inner = f.end
                }
                if (codec.isNotEmpty()) out += type to codec
            }
            if (entry.unknownSize) break
            offset = entry.end
        }
        out
    }.getOrDefault(emptyList())

    /**
     * Whether Media3's Matroska reader takes [codecId] — the ids it names, exactly. Anything else is
     * dropped by it, and the file would play without that sound.
     */
    fun media3Reads(codecId: String): Boolean = codecId in MEDIA3_AUDIO

    private val MEDIA3_AUDIO = setOf(
        "A_VORBIS", "A_OPUS", "A_AAC", "A_MPEG/L2", "A_MPEG/L3", "A_AC3", "A_EAC3", "A_TRUEHD", "A_DTS",
        "A_DTS/EXPRESS", "A_DTS/LOSSLESS", "A_FLAC", "A_MS/ACM", "A_PCM/INT/LIT", "A_PCM/INT/BIG", "A_PCM/FLOAT/IEEE",
    )

    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val TYPE_AUDIO = 2L
    private const val TYPE_SUBTITLE = 17L

    /** The subtitle ids Media3's reader names; the old `S_ASS`, `S_SSA`, `S_TEXT/ASCII` it drops. */
    private val MEDIA3_TEXT = setOf(
        "S_TEXT/UTF8", "S_TEXT/ASS", "S_TEXT/SSA", "S_TEXT/WEBVTT", "S_VOBSUB", "S_HDMV/PGS", "S_DVBSUB",
    )
}
