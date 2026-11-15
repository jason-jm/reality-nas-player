package com.therealityteam.videoplayer.data.source

import com.therealityteam.videoplayer.data.model.LocalMeta

/**
 * Which files beside a video describe it, by the names Kodi gives them (and Jellyfin and
 * tinyMediaManager write): `Film.nfo` / `Film-poster.jpg` / `Film-fanart.jpg` for the file itself;
 * `movie.nfo`, `poster.jpg`, `folder.jpg`, `fanart.jpg` for a folder that holds one film; and a
 * series' `tvshow.nfo` with its folder art, either beside the episodes or one folder up from a
 * `Season 1` folder.
 *
 * The folder-wide names describe the folder's ONE film. In a folder of many unrelated videos a
 * `folder.jpg` is somebody's album cover, and handing it to every file is how a shelf ends up
 * wearing one picture; a series folder is the exception, because its `tvshow.nfo` says the files
 * are one work.
 *
 * Release-proof images are not posters: `dunkirk.2017.1080p.bluray.x264-sparks.jpg` beside the
 * film of that name is a 5 KB screenshot, so a bare `<name>.jpg` is never taken.
 */
object Sidecars {

    private val IMAGE = setOf("jpg", "jpeg", "png", "webp")
    private val POSTER_NAMES = listOf("poster", "folder")
    private val FANART_NAMES = listOf("fanart", "backdrop")

    /** Whether a walk should keep [name] for [pick] to look at — nfo files and the named images only. */
    fun isCandidate(name: String): Boolean {
        val lower = name.lowercase()
        val ext = lower.substringAfterLast('.', "")
        if (ext == "nfo") return true
        if (ext !in IMAGE) return false
        val stem = lower.substringBeforeLast('.')
        return stem in POSTER_NAMES || stem in FANART_NAMES || stem.endsWith("-poster") || stem.endsWith("-fanart")
    }

    /**
     * The files describing [videoName], or null when there are none.
     *
     * @param dirFiles the candidate files in the video's folder, name → size
     * @param parentFiles the same for the folder above it (empty when the video is at the top)
     * @param videosInDir how many videos the video's folder holds
     * @param path builds a file's path from its folder and its name, as the source writes paths
     */
    fun pick(
        videoName: String,
        dir: String,
        dirFiles: Map<String, Long?>,
        parent: String?,
        parentFiles: Map<String, Long?>,
        videosInDir: Int,
        path: (dir: String, name: String) -> String,
    ): LocalMeta? {
        val here = Folder(dir, dirFiles)
        val above = parent?.let { Folder(it, parentFiles) }
        val base = videoName.substringBeforeLast('.').lowercase()
        val single = videosInDir <= 1

        val nfo = here.find("$base.nfo") ?: here.find("movie.nfo").takeIf { single }
        val show = here.find("tvshow.nfo")?.let { here to it } ?: above?.find("tvshow.nfo")?.let { above to it }
        // A folder's own pictures belong to its one film, or to the series its tvshow.nfo names.
        val folderArt = when {
            show != null -> show.first
            single -> here
            else -> null
        }
        val poster = here.image("$base-poster")?.let { here to it }
            ?: folderArt?.let { f -> POSTER_NAMES.firstNotNullOfOrNull { f.image(it) }?.let { f to it } }
        val fanart = here.image("$base-fanart")?.let { here to it }
            ?: folderArt?.let { f -> FANART_NAMES.firstNotNullOfOrNull { f.image(it) }?.let { f to it } }

        val chosen = listOfNotNull(nfo?.let { here to it }, show, poster, fanart)
        if (chosen.isEmpty()) return null
        fun full(at: Pair<Folder, String>) = path(at.first.dir, at.second)
        return LocalMeta(
            nfo = nfo?.let { path(dir, it) },
            showNfo = show?.let(::full),
            poster = poster?.let(::full),
            fanart = fanart?.let(::full),
            stamp = chosen.joinToString(";") { "${full(it)}:${it.first.files[it.second] ?: ""}" },
        )
    }

    /**
     * [pick] for every video one walk found.
     *
     * @param videos each video's folder and name
     * @param candidates the files [isCandidate] kept, by folder: name → size
     * @param separator what the source separates a path's folders with
     */
    fun pickAll(
        videos: List<Pair<String, String>>,
        candidates: Map<String, Map<String, Long?>>,
        separator: Char,
        path: (dir: String, name: String) -> String,
    ): List<LocalMeta?> {
        if (candidates.isEmpty()) return videos.map { null }
        val perDir = videos.groupingBy { it.first }.eachCount()
        return videos.map { (dir, name) ->
            // A disc's streams sit in BDMV/STREAM; its nfo and pictures are in the folder that holds
            // BDMV, and the disc is one film however many streams it has.
            val disc = discRoot(dir, separator)
            val home = disc ?: dir
            val parent = if (home.isEmpty()) null else home.substringBeforeLast(separator, "")
            pick(
                videoName = name,
                dir = home,
                dirFiles = candidates[home].orEmpty(),
                parent = parent,
                parentFiles = parent?.let { candidates[it] }.orEmpty(),
                videosInDir = if (disc != null) 1 else perDir[dir] ?: 1,
                path = path,
            )
        }
    }

    /** The folder holding a disc's BDMV / VIDEO_TS, when [dir] is inside one. */
    private fun discRoot(dir: String, separator: Char): String? {
        val parts = dir.split(separator)
        val at = parts.indexOfFirst { it.equals("BDMV", ignoreCase = true) || it.equals("VIDEO_TS", ignoreCase = true) }
        return if (at > 0) parts.take(at).joinToString(separator.toString()) else null
    }

    private class Folder(val dir: String, val files: Map<String, Long?>) {
        private val byLower = files.keys.associateBy { it.lowercase() }

        /** The real name of [lowerName] here, in whatever case the folder spells it. */
        fun find(lowerName: String): String? = byLower[lowerName]

        fun image(lowerStem: String): String? = IMAGE.firstNotNullOfOrNull { find("$lowerStem.$it") }
    }
}
