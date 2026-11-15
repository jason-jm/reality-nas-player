package com.therealityteam.videoplayer.data.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The ASS streams of the MKV Media3 is reading, as their author wrote them — for libass
 * ([AssRenderer]) to draw. Media3's own reader turns an ASS event into plain lines as it reads
 * it; this keeps each event too, with the moment it starts in the film, as the file is read:
 * a stream's header (its styles), then its events as they come. Seeking reads some again —
 * libass drops an event it already has (by its ReadOrder).
 *
 * Filled from Media3's loading threads ([AssTapExtractorsFactory]); read from anywhere.
 */
class AssStreams {

    class Event(val chunk: ByteArray, val startMs: Long, val durationMs: Long)

    /** One stream: its header, the events read so far, and who wants the next ones. */
    class Stream(val header: ByteArray) {
        val events = CopyOnWriteArrayList<Event>()
        val listeners = CopyOnWriteArrayList<(Event) -> Unit>()
    }

    /** Which file's streams these are: a reader made for the file before files nothing here. */
    @Volatile var file = 0
        private set
    private val streams = ConcurrentHashMap<String, Stream>()

    /** A new file is opened: the streams so far are another file's. */
    fun reset() {
        file++
        streams.clear()
    }

    /** The stream Media3 knows by [id] (its Format's id), once its header has been read. */
    fun stream(id: String): Stream? = streams[id]

    internal fun onHeader(file: Int, id: String, header: ByteArray) {
        if (file != this.file) return
        streams.putIfAbsent(id, Stream(header))
    }

    companion object {
        /**
         * An MKV's ASS sample as Media3 writes it — "Dialogue: 0:00:00:00,H:MM:SS:CC," (its length) and
         * then the block itself, "ReadOrder,Layer,Style,…,Text" — as libass takes it, at [timeUs].
         */
        internal fun event(sample: ByteArray, timeUs: Long): AssStreams.Event? {
            val prefix = "Dialogue: ".toByteArray()
            if (sample.size < prefix.size || !sample.copyOfRange(0, prefix.size).contentEquals(prefix)) return null
            val first = sample.indexOf(','.code.toByte(), prefix.size)
            if (first < 0) return null
            val second = sample.indexOf(','.code.toByte(), first + 1)
            if (second < 0) return null
            val end = String(sample, first + 1, second - first - 1, Charsets.US_ASCII).trim().split(':')
            if (end.size != 4) return null
            val n = end.map { it.toLongOrNull() ?: return null }
            val durationMs = ((n[0] * 60 + n[1]) * 60 + n[2]) * 1000 + n[3] * 10
            return AssStreams.Event(sample.copyOfRange(second + 1, sample.size), timeUs / 1000, durationMs)
        }

        private fun ByteArray.indexOf(b: Byte, from: Int): Int {
            for (i in from until size) if (this[i] == b) return i
            return -1
        }
    }

    internal fun onEvent(file: Int, id: String, event: Event) {
        if (file != this.file) return
        val s = streams[id] ?: return
        s.events += event
        s.listeners.forEach { it(event) }
    }
}

/**
 * Media3's extractors, but an MKV's reader hands its subtitle samples over untouched
 * (FLAG_EMIT_RAW_SUBTITLE_DATA) to a tap that keeps the ASS ones ([AssStreams]) and passes every
 * sample on to Media3's own transcoding — so the player gets exactly what it got before.
 */
@OptIn(UnstableApi::class)
class AssTapExtractorsFactory(
    private val streams: AssStreams,
    private val subtitleReadFromUs: (Long) -> Long,
) : ExtractorsFactory {

    // A raw stream with no index — a 外挂音轨's .ac3, .aac (ADTS), .mp3 — is seekable by its bitrate: unseekable,
    // it starts from its beginning when chosen mid-film, and a merged source (ExoPlaybackManager) refuses
    // children enabled at different positions — the film stopped.
    private val inner = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
    private var parsers: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var transcoding = true

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        parsers = subtitleParserFactory
        inner.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    @Deprecated("Media3's")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        transcoding = textTrackTranscodingEnabled
        @Suppress("DEPRECATION")
        inner.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun createExtractors(): Array<Extractor> = tapped(inner.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        tapped(inner.createExtractors(uri, responseHeaders))

    private fun tapped(extractors: Array<Extractor>): Array<Extractor> =
        if (!transcoding) extractors
        else Array(extractors.size) { i ->
            if (extractors[i] is MatroskaExtractor) {
                TapExtractor(MatroskaExtractor(parsers, MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA), parsers, streams, subtitleReadFromUs)
            } else {
                extractors[i]
            }
        }
}

@OptIn(UnstableApi::class)
private class TapExtractor(
    private val inner: MatroskaExtractor,
    private val parsers: SubtitleParser.Factory,
    private val streams: AssStreams,
    private val subtitleReadFromUs: (Long) -> Long,
) : Extractor {
    private val file = streams.file
    private var transcoder: SubtitleTranscodingExtractorOutput? = null
    private var index: SeekMap? = null
    private var subtitlePrerollPosition: Long? = null

    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun init(output: ExtractorOutput) {
        val t = SubtitleTranscodingExtractorOutput(output, parsers)
        transcoder = t
        inner.init(TapOutput(t))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val preroll = subtitlePrerollPosition
        subtitlePrerollPosition = null
        if (preroll != null && input.position != preroll) {
            seekPosition.position = preroll
            return Extractor.RESULT_SEEK
        }
        return inner.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        transcoder?.resetSubtitleParsers()
        // The video keeps its requested presentation time. A delayed subtitle's packet can be
        // in an earlier cluster than the video keyframe, so start reading at that cluster.
        val fromUs = subtitleReadFromUs(timeUs)
        subtitlePrerollPosition = if (fromUs < timeUs) {
            index?.getSeekPoints(fromUs)?.first?.position?.takeIf { it < position }
        } else null
        inner.seek(subtitlePrerollPosition ?: position, timeUs)
    }

    override fun release() = inner.release()

    override fun getUnderlyingImplementation(): Extractor = inner

    private inner class TapOutput(private val next: ExtractorOutput) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val track = next.track(id, type)
            return if (type == C.TRACK_TYPE_TEXT) TapTrack(track) else track
        }

        override fun endTracks() = next.endTracks()
        override fun seekMap(seekMap: SeekMap) {
            index = seekMap
            next.seekMap(seekMap)
        }
    }

    /** A text track's samples, kept when it is ASS and passed on as they are. */
    private inner class TapTrack(private val next: TrackOutput) : TrackOutput {
        private var id: String? = null
        private var sample = ByteArray(1024)
        private var length = 0

        override fun format(format: Format) {
            id = null
            if (format.sampleMimeType == MimeTypes.TEXT_SSA) {
                // An MKV's: Media3's own Format line, then the file's header (CodecPrivate).
                val header = format.initializationData.getOrNull(1)
                val key = format.id
                if (header != null && key != null) {
                    id = key
                    streams.onHeader(file, key, header)
                }
            }
            next.format(format)
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (id == null) return next.sampleData(input, length, allowEndOfInput, sampleDataPart)
            // Read it here first, then hand the same bytes on.
            grow(this.length + length)
            val read = input.read(sample, this.length, length)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw java.io.EOFException()
            }
            next.sampleData(ParsableByteArray(sample.copyOfRange(this.length, this.length + read)), read, sampleDataPart)
            this.length += read
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (id != null) {
                grow(this.length + length)
                System.arraycopy(data.data, data.position, sample, this.length, length)
                this.length += length
            }
            next.sampleData(data, length, sampleDataPart)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val key = id
            if (key != null) {
                val end = length - offset
                val start = end - size
                if (start >= 0) AssStreams.event(sample.copyOfRange(start, end), timeUs)?.let { streams.onEvent(file, key, it) }
                // What follows this sample (offset) is the next one's beginning.
                System.arraycopy(sample, end, sample, 0, length - end)
                length -= end
            }
            next.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }

        private fun grow(size: Int) {
            if (size > sample.size) sample = sample.copyOf(maxOf(size, sample.size * 2))
        }
    }
}
