package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Reading every file name once, on all the cores instead of one.
 *
 * Grouping a 9597-file library costs ~6 s on a headset against 0.46 s on a laptop JVM: the work is
 * the same, ART's regex engine is not. Measured on device (GroupingBenchInstrumentedTest), 2.3 s of
 * that is the first parse and classification of each name — pure per-name work behind concurrent
 * memo caches, so it parallelises exactly. The grouping proper then finds every name already read.
 */
object NameWarmup {

    /**
     * Parse and classify every name across the CPU cores; returns when the caches are warm. A library's
     * items are read for what the grouping reads of them too — each one's episode naming, each folder
     * above it as a shelf or not — which on the B3110 was the half of a cold 5.4 s regroup done on one
     * thread after this.
     */
    suspend fun warm(items: List<MediaItem>) {
        warmNames(items.map { it.fileName to it.path })
        if (items.size < 256) return
        fanOut(items) { item ->
            com.therealityteam.videoplayer.ui.library.EpisodeNaming.parse(item)
            item.path.split('/').dropLast(1).forEach { if (it.isNotEmpty()) FilenameParser.isShelfFolder(it) }
        }
    }

    private suspend fun <T> fanOut(all: List<T>, work: (T) -> Unit) {
        val lanes = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val size = (all.size + lanes - 1) / lanes
        coroutineScope {
            all.chunked(size).map { chunk -> async(Dispatchers.Default) { chunk.forEach(work) } }.awaitAll()
        }
    }

    /**
     * The same, for names that are not library items yet.
     *
     * Discovery builds a MediaItem per file, and building one parses the name — 9,597 regex passes
     * on the single thread doing the walk, the very work this object exists to spread out. Warming
     * first means every buildItem finds its name already read.
     */
    suspend fun warmNames(names: List<Pair<String, String>>) {
        if (names.size < 256) return // not worth the fan-out
        fanOut(names) { (fileName, path) ->
            FilenameParser.parse(fileName)
            FilenameParser.parseFromPath(path)
            ContentClassifier.classify(fileName, path)
        }
    }
}
