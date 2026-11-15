package com.therealityteam.videoplayer.data.playback

/** A file an MKV carries beside its streams — a fansub's fonts, a cover — where its bytes lie in it. */
data class Attachment(val name: String, val mimeType: String, val dataOffset: Long, val size: Long) {
    /** A font: the ASS of the file is set in it. */
    val isFont: Boolean
        get() = mimeType.lowercase() in FONT_TYPES || name.substringAfterLast('.', "").lowercase() in FONT_EXTENSIONS

    private companion object {
        val FONT_TYPES = setOf(
            "application/x-truetype-font", "application/x-font-ttf", "application/x-font-otf",
            "application/vnd.ms-opentype", "application/font-sfnt", "font/ttf", "font/otf", "font/sfnt",
            "font/collection", "application/x-font",
        )
        val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")
    }
}

/**
 * The attachments of a Matroska file ([list]) — its `Attachments` element, before the clusters or
 * where its SeekHead says — named and placed, not read: a fansub's fonts can be megabytes, and only
 * the fonts are wanted. Pure byte parsing over a [ByteSource], so it is unit-tested.
 */
object MatroskaAttachments {

    fun list(source: ByteSource): List<Attachment> = runCatching {
        val attachments = ChapterReader.topLevel(source, ID_ATTACHMENTS) ?: return emptyList()
        val out = mutableListOf<Attachment>()
        var offset = attachments.dataOffset
        while (offset < attachments.end) {
            val file = ChapterReader.element(source, offset) ?: break
            if (file.id == ID_ATTACHED_FILE) {
                var name = ""
                var mime = ""
                var data: ChapterReader.Element? = null
                var inner = file.dataOffset
                while (inner < file.end) {
                    val f = ChapterReader.element(source, inner) ?: break
                    when (f.id) {
                        ID_FILE_NAME -> name = ChapterReader.readString(source, f, Charsets.UTF_8)
                        ID_FILE_MIME_TYPE -> mime = ChapterReader.readString(source, f, Charsets.US_ASCII)
                        ID_FILE_DATA -> data = f
                    }
                    if (f.unknownSize) break
                    inner = f.end
                }
                data?.takeIf { it.dataSize > 0 }?.let { out += Attachment(name, mime, it.dataOffset, it.dataSize) }
            }
            if (file.unknownSize) break
            offset = file.end
        }
        out
    }.getOrDefault(emptyList())

    private const val ID_ATTACHMENTS = 0x1941A469L
    private const val ID_ATTACHED_FILE = 0x61A7L
    private const val ID_FILE_NAME = 0x466EL
    private const val ID_FILE_MIME_TYPE = 0x4660L
    private const val ID_FILE_DATA = 0x465CL
}
