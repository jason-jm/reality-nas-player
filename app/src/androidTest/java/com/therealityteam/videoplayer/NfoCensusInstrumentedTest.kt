package com.therealityteam.videoplayer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.repository.MediaRepository
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.KodiNfo
import com.therealityteam.videoplayer.data.source.SmbSource
import com.therealityteam.videoplayer.data.source.SourceSidecarReader
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * Dev tool, read only: what the shares' walk finds beside each video — nfo files, posters, fanart —
 * and how the ids those nfo files carry compare with the identity each file has in the library now.
 * Nothing is written to the library; the table goes to `files/aura/nfo-census.tsv`.
 */
@RunWith(AndroidJUnit4::class)
class NfoCensusInstrumentedTest {
    @Test
    fun census() {
        val koin = GlobalContext.get()
        val repo = koin.get<MediaRepository>()
        val sources = koin.get<SourceRepository>()
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (!repo.loaded.value && android.os.SystemClock.elapsedRealtime() - t0 < 120_000) Thread.sleep(50)
        val byId = repo.items.value.associateBy { it.id }
        val reader = SourceSidecarReader(sources)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.filesDir, "aura/nfo-census.tsv")
        val rows = mutableListOf(
            listOf(
                "path", "nfo", "showNfo", "poster", "fanart", "nfoKind", "nfoTmdb", "nfoTmdbKind", "nfoImdb", "nfoTitle",
                "nfoYear", "anime", "status", "tmdb", "key", "title", "year", "fromNfoEngine",
            ).joinToString("\t"),
        )
        var videos = 0
        var withLocal = 0
        var withId = 0
        var agree = 0
        var disagree = 0
        var newIdentity = 0
        runBlocking {
            for (config in sources.all().filter { it.enabled && it.type == SourceType.SMB }) {
                val found = runCatching { SmbSource().list(config) }
                    .onFailure { Log.w(TAG, "${config.uri}: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull() ?: continue
                videos += found.size
                for (v in found) {
                    val local = v.local ?: continue
                    withLocal++
                    val item = byId[MediaItem.idFor(config.id, v.path)]
                    val probe = item ?: MediaItem(MediaItem.idFor(config.id, v.path), config.id, v.path, v.fileName, title = v.fileName)
                    fun nfoAt(path: String?) = path?.let { p ->
                        runBlocking { reader.read(probe, p, 1 shl 20) }?.let { KodiNfo.parse(KodiNfo.text(it)) }
                    }
                    val own = nfoAt(local.nfo)
                    val show = local.showNfo?.takeIf { it != local.nfo }?.let(::nfoAt)
                    val identity = own?.takeIf { it.kind != KodiNfo.Kind.EPISODE && it.hasId } ?: show?.takeIf { it.hasId }
                    val anime = FilenameParser.isUnderAnimeRoot(v.path)
                    if (identity?.tmdbId != null) {
                        withId++
                        when {
                            item?.tmdbId == null -> newIdentity++
                            item.tmdbId == identity.tmdbId -> agree++
                            else -> disagree++
                        }
                    }
                    rows += listOf(
                        v.path, local.nfo.orEmpty(), local.showNfo.orEmpty(), local.poster.orEmpty(), local.fanart.orEmpty(),
                        identity?.kind?.name ?: own?.kind?.name.orEmpty(), identity?.tmdbId?.toString().orEmpty(),
                        identity?.tmdbKind?.name.orEmpty(), identity?.imdbId.orEmpty(),
                        (identity ?: own)?.title.orEmpty(), (identity ?: own)?.year?.toString().orEmpty(), anime.toString(),
                        item?.scrapeStatus?.name.orEmpty(), item?.tmdbId?.toString().orEmpty(), item?.collectionKey.orEmpty(),
                        item?.title.orEmpty(), item?.year?.toString().orEmpty(), (identity != null && !anime).toString(),
                    ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
                }
            }
        }
        reader.release()
        out.parentFile?.mkdirs()
        out.writeText(rows.joinToString("\n") + "\n")
        Log.i(
            TAG,
            "census: $videos videos, $withLocal with files beside them, $withId with a tmdb id " +
                "(agree $agree, disagree $disagree, unmatched now $newIdentity) -> ${out.absolutePath}",
        )
    }

    private companion object { const val TAG = "NfoCensus" }
}
