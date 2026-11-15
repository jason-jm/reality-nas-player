package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.subtitle.SubtitleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Sidecar discovery, the way desktop players match: `Movie.srt` and `Movie.chs.srt` both
 * belong to `Movie.mkv`; other stems and non-subtitle files do not.
 */
class SidecarDiscoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(name: String, bytes: Int = 10) =
        tmp.newFile(name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun exactAndTaggedNamesBothMatchAndTagBecomesTheLabel() {
        val video = touch("Movie.2024.mkv")
        touch("Movie.2024.srt")
        touch("Movie.2024.chs.ass")
        touch("Movie.2024.eng.vtt")

        val found = SubtitleEngine.discoverSidecars(video.absolutePath)
        assertEquals(3, found.size)
        assertTrue(found.any { it.mimeType == "application/x-subrip" && it.language == null })
        assertTrue(found.any { it.mimeType == "text/x-ssa" && it.language == "chs" && "chs" in it.label })
        assertTrue(found.any { it.mimeType == "text/vtt" && it.language == "eng" })
    }

    @Test
    fun otherStemsOtherExtensionsAndEmptyFilesDoNotMatch() {
        val video = touch("Alien.mkv")
        touch("Aliens.srt") // different film, shared prefix without the dot boundary
        touch("Alien.nfo")
        touch("Alien.txt")
        tmp.newFile("Alien.srt") // zero bytes — a placeholder, not a subtitle

        assertEquals(emptyList<Any>(), SubtitleEngine.discoverSidecars(video.absolutePath))
    }

    @Test
    fun aDownloadedCopysVobSubIsFoundOnlyWhenAskedForAndOnlyWithItsPictures() {
        val video = touch("Steve.Jobs.2015.720p.BluRay.x264-DRONES.mkv")
        touch("Steve.Jobs.2015.720p.BluRay.x264-DRONES.idx")
        touch("Steve.Jobs.2015.720p.BluRay.x264-DRONES.sub")
        touch("Steve.Jobs.2015.720p.BluRay.x264-DRONES.steve.jobs.idx") // its pictures never came

        assertEquals(emptyList<Any>(), SubtitleEngine.discoverSidecars(video.absolutePath))
        val found = SubtitleEngine.discoverSidecars(video.absolutePath, imageFormats = true)
        assertEquals(listOf("Steve.Jobs.2015.720p.BluRay.x264-DRONES.idx"), found.map { it.label })
        assertTrue(SubtitleEngine.isPicture(found.single().mimeType))
    }

    @Test
    fun networkPathsReturnEmptyFromTheLocalDiscoverer() {
        assertEquals(emptyList<Any>(), SubtitleEngine.discoverSidecars("smb://nas/video/Movie.mkv"))
    }

    // ---- the pure matcher the SMB path feeds with a directory listing ---------------------

    @Test
    fun aDirectoryListingMatchesTheSameWayTheFilesystemDoes() {
        val found = SubtitleEngine.matchSidecars(
            "Show.S01E02.1080p.mkv",
            listOf(
                "Show.S01E02.1080p.srt",
                "Show.S01E02.1080p.chs.ass",
                "Show.S01E01.1080p.srt", // the previous episode's subtitle
                "Show.S01E02.1080p.nfo",
                "poster.jpg",
            ),
        )
        assertEquals(2, found.size)
        assertEquals("Show.S01E02.1080p.chs.ass", found[0].fileName)
        assertEquals("chs", found[0].language)
        assertEquals("Show.S01E02.1080p.srt", found[1].fileName)
    }

    @Test
    fun aVideoWithoutAnExtensionMatchesNothing() {
        assertEquals(emptyList<Any>(), SubtitleEngine.matchSidecars("", listOf("a.srt")))
    }
}
