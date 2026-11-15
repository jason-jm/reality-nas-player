package com.therealityteam.videoplayer.data.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.pgs.PgsParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reads only the selected subtitle from the MKV index, independently of the video's keyframe seek. */
@OptIn(UnstableApi::class)
internal class PgsSeekRecovery(
    private val sources: DataSource.Factory,
    private val positionUs: () -> Long,
    private val show: (List<Cue>) -> Unit,
) {
    private data class Track(val uri: Uri, val number: Int)
    private data class Index(val value: MatroskaPgs?)
    private data class Result(val display: MatroskaPgs.Display, val cues: List<Cue>)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var track: Track? = null
    private var index: Index? = null
    private var job: Job? = null
    private var result: Result? = null
    private var seekUs: Long? = null

    fun select(uri: Uri?, number: Int?) {
        val selected = if (uri != null && number != null) Track(uri, number) else null
        if (selected == track) return
        track = selected
        index = null
        seek()
    }

    fun seek() {
        job?.cancel()
        result = null
        seekUs = null
        val selected = track ?: return
        val at = positionUs()
        seekUs = at
        job = scope.launch {
            val cached = index
            try {
                val (found, picture) = withContext(Dispatchers.IO) {
                    val task = currentCoroutineContext()
                    val source = Ranges(sources, selected.uri) { task.ensureActive() }
                    val found = cached ?: Index(MatroskaPgs.open(source, selected.number))
                    val display = found.value?.displayAt(source, at)
                    val picture = display?.let {
                        var cues: List<Cue> = emptyList()
                        if (it.packet.isNotEmpty()) {
                            PgsParser().parse(it.packet, 0, it.packet.size, SubtitleParser.OutputOptions.allCues()) { parsed ->
                                cues = parsed.cues
                            }
                        }
                        Result(it, cues)
                    }
                    found to picture
                }
                index = found
                if (seekUs == at) {
                    result = picture
                    cuesAt(positionUs())?.let(show)
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (failure: Exception) {
                // The main extractor still supplies the following display sets on unreadable/unindexed sources.
                android.util.Log.d("AuraPgs", "Could not recover the indexed subtitle: ${failure.javaClass.simpleName}")
            }
        }
    }

    /** Reset callbacks carry an empty group at the new position. A subsequent real cue takes over. */
    fun rendererCues(cues: List<Cue>, presentationUs: Long): List<Cue> {
        val at = seekUs ?: return cues
        val now = positionUs()
        // A newly reached display set, including an explicit clear, wins even if the lookup is still in flight.
        if (presentationUs > at && presentationUs <= now + 1000L) {
            result = null
            seekUs = null
        }
        return cuesAt(now) ?: cues
    }

    private fun cuesAt(timeUs: Long): List<Cue>? = result?.takeIf {
        timeUs >= it.display.startUs && timeUs < it.display.endUs
    }?.cues

    fun refresh() {
        val recovered = result ?: return
        if (positionUs() >= recovered.display.endUs) {
            result = null
            show(emptyList())
        }
    }

    fun release() = scope.cancel()

    /** Reads MKV headers in pages and fetches large packets directly through the player's URI schemes. */
    private class Ranges(val factory: DataSource.Factory, val uri: Uri, val checkActive: () -> Unit) : ByteSource {
        private val http = if (uri.scheme == "https" || uri.scheme == "http") HttpByteSource(uri.toString()) else null
        private val pages = object : LinkedHashMap<Long, ByteArray>(8, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 8
        }
        private var extent: Long? = null
        override val length: Long get() {
            if (extent == null) read(0, 1)
            return extent ?: 0L
        }

        override fun read(offset: Long, size: Int): ByteArray {
            checkActive()
            if (offset < 0 || size <= 0 || extent?.let { offset >= it } == true) return byteArrayOf()
            if (size > PAGE) return fetch(offset, size)
            val result = ByteArray(size)
            var count = 0
            while (count < size) {
                val at = offset + count
                val start = at - at % PAGE
                val page = pages.getOrPut(start) { fetch(start, PAGE) }
                val cursor = (at - start).toInt()
                if (cursor >= page.size) break
                val len = minOf(page.size - cursor, size - count)
                page.copyInto(result, count, cursor, cursor + len)
                count += len
                if (page.size < PAGE) break
            }
            return if (count == size) result else result.copyOf(count)
        }

        private fun fetch(offset: Long, size: Int): ByteArray {
            http?.let { source ->
                // A server without HTTP Range must not make a seek download the video up to the subtitle.
                val end = source.length
                if (end < 0) return byteArrayOf()
                extent = end
                val count = minOf(size.toLong(), end - offset).toInt()
                if (count <= 0) return byteArrayOf()
                return if (offset == 0L && count <= HttpByteSource.BLOCK) source.read(0, count) else source.fetch(offset, count)
            }
            val data = factory.createDataSource()
            try {
                val spec = DataSpec.Builder().setUri(uri).setPosition(offset).apply {
                    // The first open also tells us the length of a local file or content URI.
                    extent?.let { setLength(minOf(size.toLong(), it - offset)) }
                }.build()
                val remaining = data.open(spec)
                if (extent == null && remaining >= 0) extent = offset + remaining
                val buf = ByteArray(size)
                var got = 0
                while (got < size) {
                    checkActive()
                    val n = data.read(buf, got, size - got)
                    if (n <= 0) break
                    got += n
                }
                return if (got == size) buf else buf.copyOf(got)
            } finally {
                data.close()
            }
        }

        private companion object { const val PAGE = 32 * 1024 }
    }
}
