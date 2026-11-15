package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.source.Sidecars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which files beside a video describe it — the layouts Kodi, Jellyfin and tinyMediaManager write. */
class SidecarsTest {

    private fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

    /** What one walk found: videos as (folder, name), candidates by folder. */
    private fun pick(vararg files: Pair<String, Long>): List<Pair<String, com.therealityteam.videoplayer.data.model.LocalMeta?>> {
        val all = files.map { (p, size) -> Triple(p.substringBeforeLast('/', ""), p.substringAfterLast('/'), size) }
        val videos = all.filter { it.second.substringAfterLast('.') in setOf("mkv", "mp4", "m2ts", "avi") }
        val candidates = all.filter { Sidecars.isCandidate(it.second) }
            .groupBy { it.first }.mapValues { (_, fs) -> fs.associate { it.second to it.third as Long? } }
        val metas = Sidecars.pickAll(videos.map { it.first to it.second }, candidates, '/', ::join)
        return videos.mapIndexed { i, v -> join(v.first, v.second) to metas[i] }
    }

    @Test fun `a release folder's own nfo and pictures, never its proof shot`() {
        val d = "Movies/Dunkirk.2017.1080p.BluRay.x264-SPARKS[rarbg]"
        val (_, m) = pick(
            "$d/Dunkirk.2017.1080p.BluRay.x264-SPARKS.mkv" to 9_381_845_077,
            "$d/Dunkirk.2017.1080p.BluRay.x264-SPARKS.nfo" to 3_268,
            "$d/Dunkirk.2017.1080p.BluRay.x264-SPARKS-poster.jpg" to 476_799,
            "$d/Dunkirk.2017.1080p.BluRay.x264-SPARKS-fanart.jpg" to 435_173,
            "$d/dunkirk.2017.1080p.bluray.x264-sparks.jpg" to 5_170,
        ).single()
        m!!
        assertEquals("$d/Dunkirk.2017.1080p.BluRay.x264-SPARKS.nfo", m.nfo)
        assertEquals("$d/Dunkirk.2017.1080p.BluRay.x264-SPARKS-poster.jpg", m.poster)
        assertEquals("$d/Dunkirk.2017.1080p.BluRay.x264-SPARKS-fanart.jpg", m.fanart)
        assertNull(m.showNfo)
        assertTrue(m.stamp.contains("-poster.jpg:476799"))
    }

    @Test fun `folder-wide files describe a folder's one film, not a folder of many`() {
        val one = pick(
            "Films/Heat (1995)/Heat.mkv" to 1,
            "Films/Heat (1995)/movie.nfo" to 2,
            "Films/Heat (1995)/poster.jpg" to 3,
            "Films/Heat (1995)/FANART.JPG" to 4,
        ).single().second!!
        assertEquals("Films/Heat (1995)/movie.nfo", one.nfo)
        assertEquals("Films/Heat (1995)/poster.jpg", one.poster)
        assertEquals("Films/Heat (1995)/FANART.JPG", one.fanart)

        val many = pick(
            "Downloads/a.mkv" to 1,
            "Downloads/b.mkv" to 1,
            "Downloads/movie.nfo" to 2,
            "Downloads/folder.jpg" to 3,
        )
        assertTrue(many.all { it.second == null })
    }

    @Test fun `an episode takes its series' nfo and pictures from the folder above its season`() {
        val eps = pick(
            "TV/Shogun/tvshow.nfo" to 10,
            "TV/Shogun/poster.jpg" to 11,
            "TV/Shogun/fanart.jpg" to 12,
            "TV/Shogun/Season 1/Shogun.S01E01.mkv" to 1,
            "TV/Shogun/Season 1/Shogun.S01E01.nfo" to 2,
            "TV/Shogun/Season 1/Shogun.S01E02.mkv" to 1,
        ).toMap()
        val e1 = eps.getValue("TV/Shogun/Season 1/Shogun.S01E01.mkv")!!
        assertEquals("TV/Shogun/Season 1/Shogun.S01E01.nfo", e1.nfo)
        assertEquals("TV/Shogun/tvshow.nfo", e1.showNfo)
        assertEquals("TV/Shogun/poster.jpg", e1.poster)
        assertEquals("TV/Shogun/fanart.jpg", e1.fanart)
        val e2 = eps.getValue("TV/Shogun/Season 1/Shogun.S01E02.mkv")!!
        assertNull(e2.nfo)
        assertEquals("TV/Shogun/tvshow.nfo", e2.showNfo)
    }

    @Test fun `a series folder's pictures serve every episode in it`() {
        val eps = pick(
            "TV/Andor/tvshow.nfo" to 10,
            "TV/Andor/folder.png" to 11,
            "TV/Andor/Andor.S01E01.mkv" to 1,
            "TV/Andor/Andor.S01E02.mkv" to 1,
        )
        assertTrue(eps.all { it.second?.poster == "TV/Andor/folder.png" && it.second?.showNfo == "TV/Andor/tvshow.nfo" })
    }

    @Test fun `a disc's nfo sits beside its BDMV folder`() {
        val (_, m) = pick(
            "Films/Baraka/movie.nfo" to 5,
            "Films/Baraka/poster.jpg" to 6,
            "Films/Baraka/BDMV/STREAM/00001.m2ts" to 30_000_000_000,
            "Films/Baraka/BDMV/STREAM/00002.m2ts" to 1_000,
        ).first()
        assertEquals("Films/Baraka/movie.nfo", m!!.nfo)
        assertEquals("Films/Baraka/poster.jpg", m.poster)
    }

    @Test fun `what a walk keeps besides videos`() {
        assertTrue(Sidecars.isCandidate("Film-poster.PNG"))
        assertTrue(Sidecars.isCandidate("FOLDER.JPG"))
        assertTrue(Sidecars.isCandidate("backdrop.webp"))
        assertTrue(Sidecars.isCandidate("anything.nfo"))
        assertFalse(Sidecars.isCandidate("cover.jpg"))
        assertFalse(Sidecars.isCandidate("screenshot01.jpg"))
        assertFalse(Sidecars.isCandidate("Film.srt"))
    }
}
