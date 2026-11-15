package com.therealityteam.videoplayer

import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.data.scrape.ArtworkCache
import com.therealityteam.videoplayer.ui.library.decodeProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A face the scraper did not download still arrives when somebody looks at it.
 *
 * The scraper stopped fetching headshots — they were 3,524 of 5,348 artwork files for pictures only
 * the cast row of a detail page ever shows. That trade only holds if the cast row can get one on
 * demand, which is what this checks against the real CDN.
 */
class LazyArtworkInstrumentedTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aHeadshotIsFetchedTheFirstTimeItIsDrawn() = runBlocking {
        val url = "https://image.tmdb.org/t/p/w185/yFjTzJHE6AFbwQifOlnNDzmiwlq.jpg"
        val file = File(ctx.filesDir, "aura/artwork/${ArtworkCache.personKey(url)}__${url.hashCode().toUInt().toString(16)}.jpg")
        // Start from nothing on disk, the way a library scraped after the change starts.
        val kept = if (file.isFile) file.readBytes() else null
        file.delete()
        try {
            val drawn = decodeProfile(ctx, Person("Someone", "role", profileUrl = url), reqWidthPx = 160)
            assertNotNull("the cast row got no picture", drawn)
            assertTrue("nothing was filed for next time", file.isFile && file.length() > 0)
        } finally {
            kept?.let { file.writeBytes(it) }
        }
    }

    @Test
    fun aPersonWithNoPictureOnRecordCostsNoRequest() = runBlocking {
        assertNull(decodeProfile(ctx, Person("Nobody"), reqWidthPx = 160))
    }
}
