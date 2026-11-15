package com.therealityteam.videoplayer.data.playback

import com.therealityteam.videoplayer.util.PRODUCT_NAME
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves a still of the current frame — Movist's ⌘S. Re-decodes the frame at the playing
 * position with [MediaMetadataRetriever] (which tone-maps HDR to SDR on this OS, so the PNG
 * matches what the panel shows) — or takes the PNG mpv wrote of it ([save]), for what the
 * retriever cannot read — and writes it to the shared Pictures/Reality NAS Player album via MediaStore, so it
 * lands in the gallery with no storage permission.
 */
object SnapshotSaver {

    /** @return the saved image's display name on success, or null on any failure. */
    suspend fun capture(context: Context, uri: String, positionMs: Long, title: String): String? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val bitmap = try {
                when {
                    // A content:// uri from another app's 打开方式: works while we hold read
                    // access (a persistable grant, or the media permission). An intent-scoped
                    // grant dies with the launcher activity, so this can fail after playback
                    // has begun — the caller then shows 截图失败 rather than a stale frame.
                    uri.startsWith("content://") ->
                        retriever.setDataSource(context, android.net.Uri.parse(uri))
                    uri.startsWith("/") || uri.startsWith("file://") ->
                        retriever.setDataSource(uri.removePrefix("file://"))
                    else -> retriever.setDataSource(uri, emptyMap()) // http proxy URL
                }
                retriever.getFrameAtTime(
                    positionMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                )
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            } ?: return@withContext null

            publish(context, title) { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
        }

    /** A PNG the engine wrote itself ([PlayerEngine.captureFrame]), into the same album. */
    suspend fun save(context: Context, png: java.io.File, title: String): String? =
        withContext(Dispatchers.IO) {
            publish(context, title) { stream -> png.inputStream().use { it.copyTo(stream) }; true }
                .also { png.delete() }
        }

    /** Pictures/Reality NAS Player/<title>-<time>.png through MediaStore — no storage permission needed. */
    private fun publish(context: Context, title: String, write: (java.io.OutputStream) -> Boolean): String? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeTitle = title.ifBlank { "snapshot" }.take(60).replace(Regex("[/\\\\:]"), "_")
        val name = "$safeTitle-$stamp.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$PRODUCT_NAME")
        }
        val resolver = context.contentResolver
        val out = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        val ok = runCatching {
            resolver.openOutputStream(out)?.use(write) ?: false
        }.getOrDefault(false)
        return if (ok) name else { runCatching { resolver.delete(out, null, null) }; null }
    }
}
