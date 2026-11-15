package com.therealityteam.videoplayer.data.playback

/** The 解码 lines of the info panel — which decoders run, and the frame tally. Pure. */
object DecodeLabel {
    /** Software decoders by name prefix; anything else on Android is a vendor (hardware) codec. */
    private val SOFTWARE_PREFIXES = listOf("OMX.google.", "c2.android.", "ffmpeg", "OMX.ffmpeg.", "c2.ffmpeg.")

    fun isSoftware(decoderName: String): Boolean =
        SOFTWARE_PREFIXES.any { decoderName.startsWith(it, ignoreCase = true) }

    /**
     * "硬解 HEVC · 音频 AC-3 (FFmpeg)", or null while no decoder is known yet. Each side is named by
     * what it decodes — a component's own name (c2.qti.hevc.decoder, ffmpegLavc60.3.100-ac3) means
     * nothing to a reader — and the sound by FFmpeg when the app's own decoder plays it (the headset
     * has none for DTS, TrueHD, AC-3). A name that says no codec is shown as it is. The words are the
     * caller's, in the reader's language (this module has no resources); the defaults are Chinese.
     */
    fun decoders(
        video: String?,
        audio: String?,
        hardware: String = "硬解",
        software: String = "软解",
        audioWord: String = "音频",
    ): String? {
        val v = video?.let { (if (isSoftware(it)) software else hardware) + " " + (codecOf(it) ?: it) }
        val a = audio?.let { name ->
            val codec = codecOf(name)
            val ffmpeg = isFfmpeg(name)
            "$audioWord " + when {
                codec != null && ffmpeg -> "$codec (FFmpeg)"
                codec != null -> codec
                ffmpeg -> "FFmpeg"
                else -> name
            }
        }
        return listOfNotNull(v, a).joinToString(" · ").ifBlank { null }
    }

    /** What a decoder decodes, read from its component name — H.264, HEVC, AC-3, TrueHD — or null. */
    fun codecOf(decoderName: String): String? {
        val lower = decoderName.lowercase()
        if ("dolby-vision" in lower || "dolbyvision" in lower) return "Dolby Vision"
        // FFmpeg's extension names itself after libavcodec, the codec last: ffmpegLavc60.3.100-ac3.
        val tokens = if (isFfmpeg(lower)) listOf(lower.substringAfterLast('-')) else lower.split('.', '-')
        for (t in tokens) {
            CODECS[t]?.let { return it }
            if (t.startsWith("pcm")) return "PCM"
        }
        return null
    }

    private fun isFfmpeg(name: String) = name.startsWith("ffmpeg", ignoreCase = true) || name.contains(".ffmpeg.", ignoreCase = true)

    private val CODECS = mapOf(
        "avc" to "H.264", "h264" to "H.264", "hevc" to "HEVC", "h265" to "HEVC", "dvhe" to "Dolby Vision",
        "dvav" to "Dolby Vision", "vp8" to "VP8", "vp9" to "VP9", "av1" to "AV1", "av01" to "AV1",
        "mpeg2" to "MPEG-2", "mpeg4" to "MPEG-4", "h263" to "H.263", "vc1" to "VC-1",
        "aac" to "AAC", "mp3" to "MP3", "opus" to "Opus", "vorbis" to "Vorbis", "flac" to "FLAC", "alac" to "ALAC",
        "raw" to "PCM", "ac3" to "AC-3", "eac3" to "E-AC-3", "ac4" to "AC-4", "dts" to "DTS", "dca" to "DTS",
        "truehd" to "TrueHD", "mlp" to "TrueHD", "amrnb" to "AMR-NB", "amrwb" to "AMR-WB", "g711" to "G.711",
    )

    /** "丢帧 3 / 已渲染 1420", or null until a frame has been rendered or dropped; [words] formats it. */
    fun frames(
        rendered: Int,
        dropped: Int,
        words: (dropped: Int, rendered: Int) -> String = { d, r -> "丢帧 $d / 已渲染 $r" },
    ): String? = if (rendered <= 0 && dropped <= 0) null else words(dropped, rendered)
}
