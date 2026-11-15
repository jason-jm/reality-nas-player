package com.therealityteam.videoplayer.data.source

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.playback.ProjectionDetector
import com.therealityteam.videoplayer.data.repository.MediaRepository
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.ScrapeEngine
import com.therealityteam.videoplayer.util.Log
import com.therealityteam.videoplayer.util.elapsedMillis

/**
 * Scans a configured source into library entries (PENDING) and kicks off scraping so the
 * poster wall fills in. Picks the right provider by source type.
 */
/**
 * The entries a scan keeps: those it [found], and every [existing] one under a folder the walk could
 * not read ([unread], path prefixes ending in "/") — not seen is not gone.
 */
/**
 * A file found again by a source added anew, carrying what its entry under the removed source knew —
 * its match, progress, favourite, picked tracks — under the new source's id; what the walk sees now
 * (what lies beside it, the projection it reads as) is the walk's, unless the user set the projection.
 */
internal fun adopt(built: MediaItem, orphan: MediaItem): MediaItem = orphan.copy(
    id = built.id,
    sourceId = built.sourceId,
    local = built.local,
    video = if (orphan.video.userOverridden || orphan.video.fromFile) orphan.video else built.video,
)

internal fun idsToKeep(found: Set<String>, existing: List<MediaItem>, unread: List<String>): Set<String> =
    if (unread.isEmpty()) found else found + existing.filter { e -> unread.any(e.path::startsWith) }.map { it.id }

class SourceScanner(
    private val repo: MediaRepository,
    private val sourceRepo: SourceRepository,
    private val scrape: ScrapeEngine,
    providers: List<MediaSourceProvider>,
    private val jellyfin: JellyfinSource? = null,
    private val plex: PlexSource? = null,
    /**
     * Entries a removed source left that a scan took over, old id → new: what the app keeps by an
     * entry's id elsewhere (its download, its bookmarks, the bars found in its frame) follows.
     */
    private val onAdopted: (Map<String, String>) -> Unit = {},
) {
    private val byType = providers.associateBy { it.type }

    /** Scan every enabled configured source. @return total items discovered. */
    suspend fun scanAll(): Int {
        var total = 0
        for (config in sourceRepo.all().filter { it.enabled }) {
            total += runCatching { scan(config) }.getOrDefault(0)
        }
        return total
    }

    /**
     * @return number of items discovered. Provider errors (auth/host/share) propagate.
     * [onScrapeProgress] reports the scraping phase that runs after discovery (done, total).
     */
    suspend fun scan(
        config: MediaSourceConfig,
        onScrapeProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val n = discover(config)
        // What the source brought, and anything still waiting — not the library's unmatched files again.
        scrape.scrapeAll(repo.all(), retryUnmatched = false, onProgress = onScrapeProgress)
        return n
    }

    /**
     * Discover a source into the library as PENDING entries — WITHOUT scraping. Lets a multi-source
     * scan list every share first (so all files appear fast) then run a single scrape pass after,
     * instead of blocking each share's discovery behind the previous one's rate-limited scrape.
     * @return number of items discovered.
     */
    suspend fun discover(config: MediaSourceConfig): Int {
        if (config.type == SourceType.JELLYFIN || config.type == SourceType.EMBY) return discoverJellyfin(config)
        if (config.type == SourceType.PLEX) return discoverPlex(config)
        val provider = byType[config.type] ?: return 0
        // The walk is the whole of a scan on a NAS — one request per directory over the network —
        // and until now it reported nothing at all, so "the scan is slow" could not be told from
        // "the server is slow". The scrape has said where its time goes for a while; this should too.
        val walkStartedAt = elapsedMillis()
        val listing = provider.listing(config)
        // A disc image only when it may be a film's (its size, its name): installers are .iso too.
        val videos = listing.videos.filter {
            !com.therealityteam.videoplayer.data.disc.DiscImages.isImage(it.fileName) ||
                com.therealityteam.videoplayer.data.disc.DiscImages.mayBeFilm(it.fileName, it.size)
        }
        val walkMs = elapsedMillis() - walkStartedAt
        // Note: don't early-return on empty — we still reconcile below so emptying a folder
        // (e.g. after deleting files) purges that source's now-stale library entries.

        // Read every name once, on all the cores, before building anything. buildItem parses the
        // name it is given, and doing that one at a time on the walking thread is the same work the
        // poster wall already learned to spread out (see NameWarmup).
        com.therealityteam.videoplayer.data.scrape.NameWarmup.warmNames(videos.map { it.fileName to it.path })

        val now = System.currentTimeMillis()
        val items = mutableListOf<MediaItem>()
        var i = 0
        // Entries a removed source left behind (移除来源 keeps them), by path: a share added again —
        // under a new source, so new ids — finds its files there and takes them over, match, progress
        // and all, rather than standing beside them as a second copy of every film to scrape again.
        val live = sourceRepo.all().mapTo(HashSet()) { it.id }
        val orphans = repo.all().filter { it.sourceId !in live }.associateBy { it.path }
        val adopted = mutableListOf<MediaItem>()
        val newIds = HashMap<String, String>()
        // An entry this source already has for the file is the one that counts — newer, and its
        // own: the removed source's copy of it only goes.
        val mine = if (orphans.isEmpty()) emptySet() else repo.all().filter { it.sourceId == config.id }.mapTo(HashSet()) { it.id }
        fun take(built: MediaItem): MediaItem {
            val orphan = orphans[built.path] ?: return built
            adopted += orphan
            newIds[orphan.id] = built.id
            return if (built.id in mine) built else adopt(built, orphan)
        }

        // A Blu-ray/DVD rip is one title, not one card per BDMV/STREAM/*.m2ts stream. Collapse
        // each disc folder to a single entry whose playable file is the largest stream (the main
        // feature) and whose title comes from the disc folder name — so it scrapes correctly
        // instead of each numeric stream matching garbage on its own.
        val (discFiles, flat) = videos.partition { discRoot(it.path) != null }
        flat.forEach { v ->
            items += take(buildItem(config, now, i++, v.path, v.fileName, v))
        }
        discFiles.groupBy { discRoot(it.path)!! }.forEach { (_, files) ->
            val main = files.maxByOrNull { it.size ?: -1L } ?: files.first()
            items += take(buildItem(config, now, i++, main.path, main.fileName, main))
        }

        val existing = repo.all().count { it.sourceId == config.id }
        repo.upsertAll(items)
        if (adopted.isNotEmpty()) {
            // The old copies go, a removed source at a time (one write each).
            adopted.groupBy { it.sourceId }.forEach { (old, taken) ->
                val gone = taken.mapTo(HashSet()) { it.id }
                repo.retainForSource(old, repo.all().filter { it.sourceId == old && it.id !in gone }.mapTo(HashSet()) { it.id })
            }
            Log.i("AuraScan", "${adopted.size} entries of a removed source taken over by ${config.uri}")
            onAdopted(newIds)
        }
        // Reconcile-delete files removed on disk (and the per-stream BDMV junk now collapsed into one
        // disc) — but ONLY when the scan looks COMPLETE. A long SMB walk that drops its connection
        // mid-listing returns a TRUNCATED set; purging to match it would silently wipe whole folders
        // (this is exactly how 逮捕令 / Patlabor kept vanishing). So when the scan found far fewer than
        // we already had, keep the survivors — upsert still added everything found, and a later full
        // scan reconciles. A genuinely emptied source is cleared by removing it, not by a flaky walk.
        //
        // And within a scan that does look complete, nothing under a folder the walk could not read
        // goes: the NAS went quiet for the last seconds of an auto scan (a film had just started, the
        // Wi-Fi dropped) and the deepest folders — listed last — took 15 entries with them, their
        // matches and progress too. A walk cut short by its file limit removes nothing.
        if (!listing.cut && (existing == 0 || items.size >= existing * 0.7)) {
            val keep = idsToKeep(items.mapTo(HashSet()) { it.id }, repo.all().filter { it.sourceId == config.id }, listing.unread)
            if (listing.unread.isNotEmpty()) Log.i("AuraScan", "${listing.unread.size} folder(s) could not be read: their entries kept")
            repo.retainForSource(config.id, keep)
        }
        repo.flush() // discovery is a bulk write; don't let a coalescing window outlive the process
        Log.i(
            "AuraPerf",
            "scan ${config.uri}: ${videos.size} files found in ${walkMs}ms, " +
                "${items.size} items built + merged in ${elapsedMillis() - walkStartedAt - walkMs}ms",
        )
        return items.size
    }

    /**
     * A Jellyfin source arrives ALREADY MATCHED — items go straight into the library and the
     * scrape pass that follows finds nothing to do for them. Two departures from the file walk:
     * progress/favorites are carried over by id (a fresh mapping would wipe them on every scan),
     * and the purge runs unconditionally — allItems either completes or throws, so unlike a
     * flaky SMB walk a short result here really does mean the server has less.
     */
    private suspend fun discoverJellyfin(config: MediaSourceConfig): Int {
        val jf = jellyfin ?: return 0
        val result = jf.listMatched(config)
        return adoptServerItems(config, result.items, result.token, result.userId)
    }

    private suspend fun discoverPlex(config: MediaSourceConfig): Int {
        val px = plex ?: return 0
        val result = px.listMatched(config)
        return adoptServerItems(config, result.items, result.token)
    }

    /** A media server's items replace the source's entries wholesale, keeping progress and favourites. */
    private suspend fun adoptServerItems(config: MediaSourceConfig, fresh: List<MediaItem>, token: String, userId: String? = null): Int {
        val keep = repo.all().asSequence().filter { it.sourceId == config.id }.associateBy { it.id }
        val items = fresh.map { fresh ->
            keep[fresh.id]?.let { old ->
                fresh.copy(progress = old.progress, favorite = old.favorite)
            } ?: fresh
        }
        repo.upsertAll(items)
        repo.retainForSource(config.id, items.mapTo(HashSet()) { it.id })
        repo.flush()
        // Playback needs the fresh session token; the item paths stay token-free on purpose.
        sourceRepo.upsert(config.copy(token = token, serverUserId = userId ?: config.serverUserId))
        return items.size
    }

    private fun buildItem(
        config: MediaSourceConfig,
        now: Long,
        index: Int,
        path: String,
        fileName: String,
        v: DiscoveredVideo,
    ): MediaItem {
        val parsed = FilenameParser.parseFromPath(path)
        return MediaItem(
            id = MediaItem.idFor(config.id, path),
            sourceId = config.id,
            path = path,
            fileName = fileName,
            title = parsed.title,
            year = parsed.year,
            season = parsed.season,
            episode = parsed.episode,
            showTitle = if (parsed.isEpisode) parsed.title else null,
            // "No episode number" is not the same claim as "this is a film", and saying the
            // second one on the strength of the first is what put camera clips and game captures
            // on the film shelf — nothing ever contradicted a guess nobody recorded as a guess.
            // A year or a release tag is evidence; without either, say so, and let the match settle
            // it. If no match ever does, the folder decides.
            kind = when {
                parsed.isEpisode -> MediaKind.EPISODE
                parsed.year != null || FilenameParser.looksPublished(fileName) -> MediaKind.MOVIE
                else -> MediaKind.UNKNOWN
            },
            video = ProjectionDetector.detect(fileName, v.width, v.height),
            local = v.local,
            // PENDING means "waiting to be looked up". A file nothing could have an entry for is
            // not waiting for anything, and marking it so is what keeps it out of the resume check
            // and out of the progress count.
            scrapeStatus = if (FilenameParser.nothingToLookUp(path, fileName, parsed.title)) {
                ScrapeStatus.UNMATCHED
            } else {
                ScrapeStatus.PENDING
            },
            addedAt = now - index,
        )
    }

    /** The disc folder (parent of BDMV/VIDEO_TS) for a Blu-ray/DVD stream path, else null. */
    private fun discRoot(path: String): String? = com.therealityteam.videoplayer.data.disc.DiscImages.folderOf(path)
}
