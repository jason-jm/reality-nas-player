package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.scrape.ContentClassifier
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.NameMemo
import com.therealityteam.videoplayer.data.scrape.ParsedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What the names were read as, kept from one launch to the next of the same build — and only of it. */
class NameMemoTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `names read in one launch are taken as read in the next, by the same build only`() {
        val f = tmp.root.resolve("aura/names.tsv")
        FilenameParser.forgetParses()
        ContentClassifier.forgetClassifications()
        val name = "The.Bear.S01E05.1080p.WEB-DL.mkv"
        val path = "smb://nas/video/The Bear/Season 1/$name"
        val read = FilenameParser.parse(name)
        val kind = ContentClassifier.classify(name, path)
        FilenameParser.primeParses(mapOf("odd\tname.mkv" to ParsedName("odd")))
        assertTrue(NameMemo.save(f, installed = 7L) >= 2)

        // Another launch: nothing in memory; a memo written by the same build is taken up as it was.
        FilenameParser.forgetParses()
        ContentClassifier.forgetClassifications()
        FilenameParser.primeParses(emptyMap())
        assertEquals(0, NameMemo.load(f, installed = 8L))
        assertEquals(emptyMap<String, ParsedName>(), FilenameParser.parsesSoFar())
        assertTrue(NameMemo.load(f, installed = 7L) >= 2)
        assertEquals(read, FilenameParser.parsesSoFar()[name])
        assertEquals(kind, ContentClassifier.classificationsSoFar()[path])
        // A name with a tab in it was not kept (a line per answer, tab-separated).
        assertEquals(null, FilenameParser.parsesSoFar()["odd\tname.mkv"])
        assertEquals(ContentKind.MAIN, ContentClassifier.classificationsSoFar()[path])
    }
}
