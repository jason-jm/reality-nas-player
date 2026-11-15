package com.therealityteam.videoplayer.data.playback

/**
 * Why a file would not play, as far as the player can tell — so the message can say what to do
 * rather than "无法播放此文件" for everything: a share that cannot be reached is not a broken file.
 */
enum class PlaybackFailure {
    /** A file on a share or server that could not be read: the NAS off, the network down, the file moved. */
    UNREACHABLE,
    /** A file on the headset that is not where it was. */
    MISSING,
    /** The container or stream is malformed — an unfinished download, a damaged file. */
    DAMAGED,
    /** No decoder for it on this headset. */
    UNSUPPORTED,
    /**
     * A picture past what the headset decodes: the hardware decoder failed on it, and it is too big for
     * software to keep up with (EngineRoute.softwareKeepsUp) — an 8K panorama, a 4K film at 60 frames.
     * What it is and what the hardware said of it: [Oversize].
     */
    TOO_LARGE,
    /** A disc image (原盘 .iso) whose film is encrypted — AACS on a Blu-ray, CSS on a DVD. */
    DISC_ENCRYPTED,
    /** A disc image with no film in it: no BDMV or VIDEO_TS — software, a data disc. */
    DISC_NO_FILM,
    OTHER;

    /** Whether trying again could help — not for a file the headset can never read. */
    val retryable: Boolean get() = this == UNREACHABLE || this == OTHER

    companion object {
        /**
         * From Media3's PlaybackException error code (IO 2xxx, parsing 3xxx, decoding 4xxx) and
         * whether the file is on the network.
         *
         * A container no extractor reads (3003, RMVB, WMV) is a format the headset lacks, not a
         * damaged file; and a reader that broke over a file's content ([readerCrashed]: Media3's
         * FLV reader indexing past its array) is no share that could not be reached — both read
         * 「文件不完整或已损坏」 and 「NAS 关机或休眠」 for files that were neither.
         */
        fun of(errorCode: Int, onNetwork: Boolean, readerCrashed: Boolean = false): PlaybackFailure = when {
            readerCrashed -> UNSUPPORTED
            errorCode == CONTAINER_UNSUPPORTED || errorCode == MANIFEST_UNSUPPORTED -> UNSUPPORTED
            // A read past the file's end: the file is there, shorter than its own index says — cut short.
            errorCode == READ_POSITION_OUT_OF_RANGE -> DAMAGED
            errorCode in 2000..2999 -> if (onNetwork) UNREACHABLE else MISSING
            errorCode in 3000..3999 -> DAMAGED
            errorCode in 4000..4999 -> UNSUPPORTED
            else -> OTHER
        }

        /** Media3's ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED / _MANIFEST_UNSUPPORTED. */
        private const val CONTAINER_UNSUPPORTED = 3003
        private const val MANIFEST_UNSUPPORTED = 3004

        /** Media3's ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE. */
        private const val READ_POSITION_OUT_OF_RANGE = 2008

        /**
         * The old formats the headset has no reader or decoder for, by a file's extension: RMVB and
         * RM (RealVideo), WMV and ASF (Windows Media), FLV. Named in the failure, as the format it is.
         */
        fun legacyFormat(fileName: String): String? =
            fileName.substringAfterLast('.', "").lowercase().takeIf { it in LEGACY }?.uppercase()

        private val LEGACY = setOf("rmvb", "rm", "wmv", "asf", "flv")

        /**
         * …and the old video codecs the headset has no decoder for, by the MIME type of the track that
         * failed, named as people know them: an AVI's MPEG-4 Part 2 is XviD or DivX.
         */
        fun legacyCodec(mimeType: String?): String? = when (mimeType) {
            "video/mp4v-es" -> "XviD/DivX"
            "video/mpeg2" -> "MPEG-2"
            "video/mpeg" -> "MPEG-1"
            "video/3gpp" -> "H.263"
            "video/wvc1" -> "VC-1"
            else -> null
        }
    }
}
