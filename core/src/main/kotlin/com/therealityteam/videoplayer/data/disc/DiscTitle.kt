package com.therealityteam.videoplayer.data.disc

import com.therealityteam.videoplayer.data.playback.ByteSource

/**
 * The film on a disc image (蓝光 / DVD 原盘 .iso): the streams it plays, in order, as runs of the image's
 * bytes — played as one file straight from the image ([com.therealityteam.videoplayer.data.playback.LocalStreamServer]).
 *
 * A Blu-ray's film is its longest playlist, its clips (BDMV/STREAM/nnnnn.m2ts) one after another — whole,
 * not cut at the playlist's in and out points (a seamlessly branched disc plays its clips' extra seconds),
 * each after the first moved on in time ([Part.shift]) to go on from where the last one ended: each clip
 * keeps its own clock, so played as they are the second started the film's time over (a 2 h film read
 * as its first clip's length, and could not be seeked past it).
 * A DVD's is its largest title set, VTS_nn_1.VOB, VTS_nn_2.VOB… (VTS_nn_0 is its menu) — its length,
 * chapters and the cells where its clock starts over read from its VTS_nn_0.IFO ([Ifo]), each run of
 * cells after such a start moved on in time as a Blu-ray's clips are.
 */
data class DiscTitle(
    val kind: Kind,
    val parts: List<Part>,
    /** The playlist's length, for a Blu-ray; its cells', for a DVD with an IFO. */
    val durationMs: Long?,
    /** Encrypted (AACS / CSS): its streams cannot be read as they are. */
    val encrypted: Boolean,
    /** Which playlist or title set it is ("00800.mpls", "VTS_01"), for the log. */
    val source: String,
    /** Where its chapters start, in milliseconds (a Blu-ray playlist's marks, a DVD's parts of titles). */
    val chaptersMs: List<Long> = emptyList(),
    /** How long finding it took, for the log: "found 820 ms, checked 310 ms". */
    val timings: String = "",
) {
    enum class Kind(val extension: String) { BLURAY("m2ts"), DVD("vob") }

    /**
     * A stream of the film: its name on the disc and where its bytes are in the image; [shift] (90 kHz)
     * what its clock is moved on by, played after the clips before it.
     */
    data class Part(val name: String, val extents: List<Udf.Extent>, val size: Long, val shift: Long = 0)

    val size: Long get() = parts.sumOf { it.size }

    /** What it is, in a line: "BLURAY 00313.mpls of 131 (then …): 6 parts of 6 streams, 2:28:53, 36 chapters, 32 GB". */
    val summary: String
        get() = "$kind $source: ${parts.size} parts of ${parts.map { it.name }.distinct().size} streams, " +
            (durationMs?.let { ms -> "%d:%02d:%02d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60) } ?: "length unknown") +
            ", ${chaptersMs.size} chapters, ${size / (1L shl 20)} MB" + (if (encrypted) ", encrypted" else "") +
            if (timings.isEmpty()) "" else "; $timings"

    /** The film's bytes, in order, as runs of the image ([Udf.Extent.offset] -1: zeros). */
    val extents: List<Udf.Extent> get() = parts.flatMap { p -> trimmed(p.extents, p.size) }

    /** What reading a disc image found. */
    sealed interface Found {
        data class Film(val title: DiscTitle) : Found
        /** A UDF image with no BDMV or VIDEO_TS film — software, a data disc. */
        data object NoFilm : Found
        /** No UDF file system at all (or unreadable). */
        data object NotADisc : Found
    }

    companion object {

        /** The film on the image [src] reads. */
        fun find(src: ByteSource): Found {
            // UDF (a film's disc, a DVD-R), else the older ISO 9660 of a data disc burnt without it.
            val files: DiscFiles = Udf.open(src) ?: Iso9660.open(src) ?: return Found.NotADisc
            return find(files, src)
        }

        /** An image's file system: its UDF, else its ISO 9660 — null for no disc. */
        fun filesOf(src: ByteSource): DiscFiles? = Udf.open(src) ?: Iso9660.open(src)

        /**
         * The videos on a data disc — a tutorial's .mov files, a camera's clips burnt to a DVD-R — by their
         * paths in it, folders deep (four at most), in the order their names count ("2 …" before "10 …").
         */
        fun videosIn(files: DiscFiles, locale: java.util.Locale = java.util.Locale.getDefault()): List<String> {
            val out = ArrayList<String>()
            fun walk(path: String, depth: Int) {
                files.list(path).orEmpty().forEach { e ->
                    val child = if (path.isEmpty()) e.name else "$path/${e.name}"
                    if (e.isDirectory) { if (depth < 4) walk(child, depth + 1) }
                    else if (e.name.substringAfterLast('.', "").lowercase() in VIDEO_FILES) out += child
                }
            }
            walk("", 0)
            // A disc in several languages keeps each in its folder (Assets/Videos/DE/, …/EN/): the reader's
            // first, then English, then what is in no language's folder (a promo clip), then the rest —
            // Internal Mixing's German copies came first by name, and then its Cubase promos.
            val reader = locale.language.lowercase()
            fun rank(path: String): Int {
                val langs = path.split('/').dropLast(1).map { it.lowercase() }.mapNotNull { LANGUAGE_FOLDERS[it] }
                return when {
                    reader in langs -> 0
                    "en" in langs -> 1
                    langs.isEmpty() -> 2
                    else -> 3
                }
            }
            return out.sortedWith(compareBy<String> { rank(it) }.then(NATURAL))
        }

        /** Folder names that are a language's (a code, or the language's name), as that language's code. */
        private val LANGUAGE_FOLDERS = mapOf(
            "en" to "en", "eng" to "en", "english" to "en", "de" to "de", "ger" to "de", "deu" to "de", "german" to "de", "deutsch" to "de",
            "fr" to "fr", "fra" to "fr", "french" to "fr", "es" to "es", "spa" to "es", "spanish" to "es", "it" to "it", "ita" to "it",
            "ja" to "ja", "jp" to "ja", "jpn" to "ja", "japanese" to "ja", "zh" to "zh", "cn" to "zh", "chs" to "zh", "cht" to "zh", "chinese" to "zh",
            "中文" to "zh", "ko" to "ko", "kr" to "ko", "korean" to "ko", "ru" to "ru", "rus" to "ru",
        )

        /** Names as a person counts them: runs of digits by their number. */
        private val NATURAL = Comparator<String> { a, b ->
            val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
            val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ra.size, rb.size)) {
                val x = ra[i]
                val y = rb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
                else x.compareTo(y)
                if (c != 0) return@Comparator c
            }
            ra.size.compareTo(rb.size)
        }

        /** What an image holds, in a line (its top folders, and its video files by kind): for a log. */
        fun contents(src: ByteSource): String {
            val udf = filesOf(src) ?: return "no disc file system"
            val top = udf.list("").orEmpty().map { if (it.isDirectory) "${it.name}/" else it.name }
            val videos = HashMap<String, Int>()
            fun walk(path: String, depth: Int) {
                udf.list(path).orEmpty().forEach { e ->
                    val child = if (path.isEmpty()) e.name else "$path/${e.name}"
                    if (e.isDirectory) { if (depth < 4) walk(child, depth + 1) }
                    else e.name.substringAfterLast('.', "").lowercase().takeIf { it in VIDEO_FILES }?.let { videos[it] = (videos[it] ?: 0) + 1 }
                }
            }
            walk("", 0)
            return "${top.take(12)}${if (top.size > 12) " …" else ""}; videos ${videos.ifEmpty { "none" }}"
        }

        private val VIDEO_FILES = setOf("mp4", "m4v", "mov", "mkv", "avi", "wmv", "flv", "mpg", "mpeg", "ts", "m2ts", "webm", "vob")

        /** The film among [files] — an image's, or a ripped disc's folder — whose bytes [src] reads. */
        fun find(files: DiscFiles, src: ByteSource): Found {
            val t0 = System.nanoTime()
            val title = bluRay(files) ?: dvd(files, src) ?: return Found.NoFilm
            val t1 = System.nanoTime()
            val encrypted = encrypted(src, title)
            val t2 = System.nanoTime()
            return Found.Film(title.copy(encrypted = encrypted, timings = "found ${(t1 - t0) / 1_000_000} ms, checked ${(t2 - t1) / 1_000_000} ms"))
        }

        private fun bluRay(udf: DiscFiles): DiscTitle? {
            udf.prefetch(listOf("BDMV/PLAYLIST", "BDMV/STREAM"))
            val playlists = udf.list("BDMV/PLAYLIST")?.filter { !it.isDirectory && it.name.endsWith(".mpls", ignoreCase = true) } ?: return null
            val streams = udf.list("BDMV/STREAM")?.filter { !it.isDirectory }?.associateBy { it.name.substringBeforeLast('.').uppercase() } ?: return null
            val sizes = HashMap<String, Pair<List<Udf.Extent>, Long>?>()
            fun clip(id: String) = sizes.getOrPut(id) {
                streams[id.uppercase()]?.let { udf.file("BDMV/STREAM/${it.name}") }
            }
            // Every playlist that plays: its clips all on the disc.
            val read = udf.readAll(playlists.map { "BDMV/PLAYLIST/${it.name}" }, 1 shl 16)
            val candidates = playlists.zip(read).mapNotNull { (entry, bytes) ->
                val list = bytes?.let(Mpls::parse) ?: return@mapNotNull null
                if (list.items.isEmpty() || list.items.any { clip(it.clip) == null }) return@mapNotNull null
                entry.name to list
            }
            // The longest, counting each part it plays once — a playlist that loops one clip (a menu's
            // background, 00300.m2ts 36 times on a real Tora! Tora! Tora!) is as long as one pass of it,
            // not the film; of two as long (within a second), the one with fewer clips repeated, then the larger.
            val ranked = candidates.sortedWith(
                compareByDescending<Pair<String, Mpls.Playlist>> { it.second.onceMs / 1000 }
                    .thenBy { (_, l) -> l.items.size - l.items.map { it.clip }.distinct().size }
                    .thenByDescending { (_, l) -> l.items.map { it.clip }.distinct().sumOf { clip(it)!!.second } },
            )
            val best = ranked.firstOrNull() ?: return null
            val passedOver = ranked.drop(1).take(3).joinToString { (name, l) -> "$name ${l.onceMs / 1000} s/${l.items.size}" }
            // Each clip's clock goes on from where the last one's playlist time ended (45 kHz → 90 kHz).
            val items = best.second.items
            var played = 0L
            val parts = items.map { item ->
                val (extents, size) = clip(item.clip)!!
                Part("${item.clip}.m2ts", extents, size, shift = 2 * (played + items.first().inTime - item.inTime))
                    .also { played += (item.outTime - item.inTime).coerceAtLeast(0) }
            }
            val source = "${best.first} of ${candidates.size}" + if (passedOver.isEmpty()) "" else " (then $passedOver)"
            return DiscTitle(Kind.BLURAY, parts, best.second.durationMs, encrypted = false, source = source, chaptersMs = best.second.chaptersMs)
        }

        private fun dvd(udf: DiscFiles, src: ByteSource): DiscTitle? {
            val files = udf.list("VIDEO_TS")?.filter { !it.isDirectory } ?: return null
            val vob = Regex("""VTS_(\d\d)_([1-9])\.VOB""", RegexOption.IGNORE_CASE)
            val sets = files.mapNotNull { e -> vob.matchEntire(e.name)?.let { m -> Triple(m.groupValues[1], m.groupValues[2].toInt(), e) } }
                .groupBy { it.first }
            val all = sets.mapValues { (_, vobs) ->
                vobs.sortedBy { it.second }.mapNotNull { (_, _, e) -> udf.file("VIDEO_TS/${e.name}")?.let { Part(e.name, it.first, it.second) } }
            }
            val best = all.maxByOrNull { (_, parts) -> parts.sumOf { it.size } } ?: return null
            if (best.value.isEmpty()) return null
            // (For the log: the title sets passed over, by size — a disc of featurettes spreads them over many.)
            val others = all.filterKeys { it != best.key }.map { (k, p) -> k to p.sumOf { it.size } }.sortedByDescending { it.second }
            val source = "VTS_${best.key} of ${all.size}" +
                if (others.isEmpty()) "" else " (then " + others.take(4).joinToString { (k, n) -> "VTS_$k ${n shr 20} MB" } + ")"
            // A disc of featurettes — no title set half of it (StarCraft II's behind-the-scenes DVD: 59, the
            // largest 13 minutes) — plays them all, in order, each a chapter; a film's disc, its film.
            val total = all.values.sumOf { p -> p.sumOf { it.size } }
            val bestSize = best.value.sumOf { it.size }
            if (all.size >= 3 && bestSize * 2 < total) {
                runCatching { everyTitleSet(udf, src, all) }.getOrNull()?.let {
                    return it.copy(source = "all ${all.size} title sets, ${total shr 20} MB (the largest VTS_${best.key}, ${bestSize shr 20} MB)")
                }
            }
            val ifo = runCatching { udf.read("VIDEO_TS/VTS_${best.key}_0.IFO")?.let(Ifo::parse) }.getOrNull()
                ?: return DiscTitle(Kind.DVD, best.value, null, encrypted = false, source = source)
            val runs = runCatching { dvdRuns(ifo, best.value, src, "VTS_${best.key}") }.getOrNull()?.first
            val parts = runs?.takeUnless { r -> r.all { it.shift == 0L } } ?: best.value
            return DiscTitle(Kind.DVD, parts, ifo.durationMs, encrypted = false, source = source, chaptersMs = ifo.chaptersMs)
        }

        /**
         * Every title set of a DVD one after another, as one timeline: each on from where the last ended
         * ([dvdRuns] with the time it is to start at), its chapters after the ones before and its own start
         * one too. Null when one cannot be placed (no IFO, no length, no times to read).
         */
        private fun everyTitleSet(udf: DiscFiles, src: ByteSource, all: Map<String, List<Part>>): DiscTitle? {
            var origin: Long? = null
            var at = 0L
            val parts = ArrayList<Part>()
            val chapters = ArrayList<Long>()
            for ((key, vobs) in all.toSortedMap()) {
                if (vobs.isEmpty()) continue
                val ifo = udf.read("VIDEO_TS/VTS_${key}_0.IFO")?.let(Ifo::parse) ?: return null
                if (ifo.durationMs <= 0) return null
                val (runs, first) = dvdRuns(ifo, vobs, src, "VTS_$key", startAt = origin?.let { it + at * 90 }) ?: return null
                if (origin == null) origin = first
                parts += runs
                (listOf(0L) + ifo.chaptersMs).forEach { chapters += at + it }
                at += ifo.durationMs
            }
            if (parts.isEmpty()) return null
            val spaced = ArrayList<Long>()
            for (t in chapters.sorted()) if (t < at - 1_000 && (spaced.isEmpty() || t - spaced.last() >= 1_000)) spaced += t
            return DiscTitle(Kind.DVD, parts, at, encrypted = false, source = "", chaptersMs = if (spaced.size < 2) emptyList() else spaced)
        }

        /**
         * A DVD's VOBs cut where its clock starts over — at a cell its IFO marks so, or the first of another
         * VOB, when its first times are not where the cells before it end — each run after moved on to go on
         * from them; all of it moved to begin at [startAt] (90 kHz) when given. Only those cells' first packs
         * are read. With its first time as it is on the disc; null when it has none to read.
         */
        private fun dvdRuns(ifo: Ifo.TitleSet, vobs: List<Part>, src: ByteSource, source: String, startAt: Long? = null): Pair<List<Part>, Long>? {
            val all = vobs.flatMap { trimmed(it.extents, it.size) }
            val reader = Reader(src, all)
            fun firstTime(cell: Ifo.Cell): Long? =
                cell.first.takeIf { it * Udf.SECTOR < reader.size }?.let { PsClock.presentationTimes(reader.read(it * Udf.SECTOR, 64 * 1024)).minOrNull() }
            val cells = ifo.cells
            val starts = ifo.startsMs
            val first = firstTime(cells.first()) ?: return null
            val origin = startAt ?: first
            val shifts = LongArray(cells.size)
            var shift = origin - first
            shifts[0] = shift
            for (k in 1 until cells.size) {
                if (cells[k].clockRestarts || cells[k].vob != cells[k - 1].vob) {
                    val at = firstTime(cells[k])
                    val wanted = origin + starts[k] * 90
                    if (at != null && kotlin.math.abs(at + shift - wanted) > 45_000) shift = wanted - at
                }
                shifts[k] = shift
            }
            // Runs of cells with one shift: from the first's first sector (the title's start, for the first run) to the next run's.
            val runs = ArrayList<Part>()
            var k = 0
            val total = all.sumOf { it.length }
            while (k < cells.size) {
                var j = k
                while (j + 1 < cells.size && shifts[j + 1] == shifts[k]) j++
                val from = if (runs.isEmpty()) 0L else cells[k].first * Udf.SECTOR
                val to = if (j + 1 < cells.size) cells[j + 1].first * Udf.SECTOR else total
                if (to > from) {
                    val extents = slice(all, from, minOf(to, total))
                    runs += Part("$source cells ${k + 1}-${j + 1}", extents, extents.sumOf { it.length }, shift = shifts[k])
                }
                k = j + 1
            }
            return runs to first
        }

        /** The runs of the image that bytes [from, to) of [extents] read as one are. */
        private fun slice(extents: List<Udf.Extent>, from: Long, to: Long): List<Udf.Extent> {
            val out = ArrayList<Udf.Extent>()
            var base = 0L
            for (e in extents) {
                val start = maxOf(from, base)
                val end = minOf(to, base + e.length)
                if (end > start) out += Udf.Extent(if (e.offset < 0) -1 else e.offset + (start - base), end - start)
                base += e.length
                if (base >= to) break
            }
            return out
        }

        /**
         * Whether the film's streams are encrypted, from a few of their units: AACS leaves each 6144-byte
         * unit's first packet header readable with its copy permission set (libaacs reads the same two
         * bits); CSS marks a scrambled DVD pack in its PES header (libdvdcss's test).
         */
        private fun encrypted(src: ByteSource, title: DiscTitle): Boolean {
            val reader = Reader(src, title.extents)
            return when (title.kind) {
                Kind.BLURAY -> {
                    val units = reader.size / ALIGNED_UNIT
                    val probes = listOf(0L, 1L, units / 3, units / 2, units * 2 / 3).filter { it < units }.distinct()
                    probes.count { u -> scrambled(reader.read(u * ALIGNED_UNIT, ALIGNED_UNIT.toInt())) } > probes.size / 2
                }
                Kind.DVD -> {
                    val sectors = reader.size / Udf.SECTOR
                    var scrambled = 0
                    var media = 0
                    for (i in 0 until 48) {
                        val s = sectors / 2 + i * 7
                        if (s >= sectors) break
                        val pack = reader.read(s * Udf.SECTOR, 0x15)
                        if (pack.size < 0x15) continue
                        val stream = pack[0x11].toInt() and 0xFF
                        if (stream != 0xE0 && stream != 0xBD && stream != 0xC0) continue
                        media++
                        if (pack[0x14].toInt() and 0x30 != 0) scrambled++
                    }
                    media > 0 && scrambled * 2 > media
                }
            }
        }

        /**
         * Whether an aligned unit (32 packets) is AACS-encrypted: its copy permission set — and its packets
         * unreadable, for a decrypted backup (a scene's full disc) can keep the bits as they were while
         * every packet after the first 16 bytes is plain again; an encrypted unit's sync bytes are noise.
         */
        private fun scrambled(unit: ByteArray): Boolean {
            if (unit.isEmpty() || unit[0].toInt() and 0xC0 == 0) return false
            val packets = unit.size / TsClock.PACKET
            if (packets < 2) return true
            val synced = (1 until packets).count { unit[it * TsClock.PACKET + 4].toInt() and 0xFF == 0x47 }
            return synced * 2 < packets - 1
        }

        /** [extents] cut to [size] bytes: a file's last extent is whole blocks, the file is not. */
        internal fun trimmed(extents: List<Udf.Extent>, size: Long): List<Udf.Extent> {
            val out = ArrayList<Udf.Extent>(extents.size)
            var left = size
            for (e in extents) {
                if (left <= 0) break
                val n = minOf(e.length, left)
                out += Udf.Extent(e.offset, n)
                left -= n
            }
            return out
        }

        private const val ALIGNED_UNIT = 6144L
    }

    /** The film's bytes read as one file, through its extents. */
    class Reader(private val src: ByteSource, private val extents: List<Udf.Extent>) {
        val size: Long = extents.sumOf { it.length }

        fun read(position: Long, length: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(length)
            var at = position
            var base = 0L
            for (e in extents) {
                if (out.size() >= length) break
                if (at < base + e.length) {
                    val inExtent = at - base
                    val n = minOf(e.length - inExtent, (length - out.size()).toLong()).toInt()
                    if (e.offset < 0) out.write(ByteArray(n)) else out.write(src.read(e.offset + inExtent, n))
                    at += n
                }
                base += e.length
            }
            return out.toByteArray()
        }
    }
}
