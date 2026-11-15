package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.source.LocalSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A local folder that would not open is reported, so the scan does not take its files for gone. */
class LocalSourceListingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun `a folder that cannot be read is listed as unread, the rest as found`() = runBlocking {
        val root = tmp.newFolder("Movies")
        File(root, "Alien").apply { mkdirs() }.resolve("alien.mkv").writeBytes(ByteArray(8))
        val locked = File(root, "Locked").apply { mkdirs() }
        locked.resolve("secret.mkv").writeBytes(ByteArray(8))
        assumeTrue(locked.setReadable(false, false)) // not on a file system that ignores it
        try {
            val config = MediaSourceConfig(id = "s", name = "Movies", type = SourceType.LOCAL, uri = root.absolutePath)
            val listing = LocalSource().listing(config)
            assertEquals(listOf("alien.mkv"), listing.videos.map { it.fileName })
            assertEquals(listOf(locked.absolutePath + File.separator), listing.unread)
            assertFalse(listing.cut)
        } finally {
            locked.setReadable(true, false)
        }
    }
}
