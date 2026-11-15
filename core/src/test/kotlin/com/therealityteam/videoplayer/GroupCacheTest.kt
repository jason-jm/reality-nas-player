package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.CreditsTable
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaJson
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.ui.library.GroupCache
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.WallSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Reviving the wall from the last session's answer.
 *
 * The cards are a pure function of the library, so keeping them is safe exactly as long as nothing
 * they depend on has moved — and wrong in a way nobody would notice if that check is loose. These
 * are the ways it can be wrong.
 */
class GroupCacheTest {

    private lateinit var dir: File

    /** The app build the cards were made by; the same throughout, so only the library can move. */
    private val installed = 1L

    private fun context(): File {
        dir = Files.createTempDirectory("aura-gc").toFile()
        File(dir, "aura").mkdirs()
        File(dir, "aura/library.json").writeText("[]")
        return dir
    }

    private fun item(n: Int, show: String) = MediaItem(
        id = "id$n", sourceId = "s", path = "smb://nas/video/Anime/$show/$show - 0$n.mkv",
        fileName = "$show - 0$n.mkv", title = show, kind = MediaKind.EPISODE, tmdbId = show.hashCode(),
    )

    private fun libraryOf(vararg shows: String): List<MediaItem> =
        shows.flatMapIndexed { s, show -> (1..3).map { item(s * 10 + it, show) } }

    private fun touchLibrary(ctx: File, items: List<MediaItem>) {
        File(ctx, "aura/library.json")
            .writeText(JSONArray().also { a -> items.forEach { a.put(MediaJson.item(it)) } }.toString())
    }

    @Test fun `the same library gives back the same cards, with real files on them`() {
        val ctx = context()
        val items = libraryOf("Alpha", "Beta")
        touchLibrary(ctx, items)
        val groups = LibraryGrouping.group(items)
        GroupCache.save(ctx, installed, groups)

        val painted = WallSnapshot.decode(WallSnapshot.encode(groups))
        val revived = GroupCache.revive(ctx, installed, painted, items)
        assertNotNull("nothing was revived", revived)
        assertEquals(groups.map { it.key }, revived!!.map { it.key })
        assertEquals(groups.map { it.title }, revived.map { it.title })
        assertEquals(groups.map { g -> g.items.map { it.id } }, revived.map { g -> g.items.map { it.id } })
        assertTrue("the cards carry real files, not stubs", revived.none { WallSnapshot.isStub(it) })
    }

    @Test fun `a library that has been written since is not trusted`() {
        val ctx = context()
        val items = libraryOf("Alpha")
        touchLibrary(ctx, items)
        GroupCache.save(ctx, installed, LibraryGrouping.group(items))
        val painted = WallSnapshot.decode(WallSnapshot.encode(LibraryGrouping.group(items)))
        assertNotNull(GroupCache.revive(ctx, installed, painted, items))

        // Anything at all rewriting the library invalidates it — that is the whole guarantee.
        Thread.sleep(1_100) // lastModified has second granularity on some filesystems
        touchLibrary(ctx, items + item(99, "Gamma"))
        assertNull(GroupCache.revive(ctx, installed, painted, items))
    }

    @Test fun `a file the library no longer has throws the whole thing away`() {
        val ctx = context()
        val items = libraryOf("Alpha", "Beta")
        touchLibrary(ctx, items)
        val groups = LibraryGrouping.group(items)
        GroupCache.save(ctx, installed, groups)
        val painted = WallSnapshot.decode(WallSnapshot.encode(groups))
        // Same file, same stamp, but one item is gone from what was loaded.
        assertNull(GroupCache.revive(ctx, installed, painted, items.drop(1)))
    }

    @Test fun `a card the snapshot does not have throws the whole thing away`() {
        val ctx = context()
        val items = libraryOf("Alpha", "Beta")
        touchLibrary(ctx, items)
        val groups = LibraryGrouping.group(items)
        GroupCache.save(ctx, installed, groups)
        val painted = WallSnapshot.decode(WallSnapshot.encode(groups)).drop(1)
        assertNull(GroupCache.revive(ctx, installed, painted, items))
    }

    @Test fun `nothing saved means nothing revived`() {
        val ctx = context()
        val items = libraryOf("Alpha")
        touchLibrary(ctx, items)
        assertNull(GroupCache.revive(ctx, installed, emptyList(), items))
    }

    @Test fun `a file written by a version this build does not know is ignored`() {
        val ctx = context()
        val items = libraryOf("Alpha")
        touchLibrary(ctx, items)
        val groups = LibraryGrouping.group(items)
        GroupCache.save(ctx, installed, groups)
        val f = GroupCache.file(ctx)
        f.writeText(JSONObject(f.readText()).put("v", 999).toString())
        assertNull(GroupCache.revive(ctx, installed, WallSnapshot.decode(WallSnapshot.encode(groups)), items))
    }

    @Test fun `rubbish in the file is not a crash`() {
        val ctx = context()
        val items = libraryOf("Alpha")
        touchLibrary(ctx, items)
        GroupCache.file(ctx).writeText("{not json")
        assertNull(GroupCache.revive(ctx, installed, emptyList(), items))
    }

    @Test fun `every file in a real library lands on exactly one card`() {
        // The revive refuses unless the cards account for every item, so this had better be true of
        // what grouping produces — otherwise the check would quietly disable the whole feature.
        val path = System.getProperty("aura.library") ?: return
        val arr = JSONArray(File(path).readText())
        var credits: CreditsTable? = null
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (CreditsTable.isHeader(o)) { credits = CreditsTable.read(o); null } else MediaJson.item(o, credits)
        }
        val groups: List<MediaGroup> = LibraryGrouping.group(items)
        val placed = groups.flatMap { g -> g.items.map { it.id } }
        assertEquals("no file is on two cards", placed.size, placed.toSet().size)
        assertEquals("every file is on a card", items.size, placed.size)
    }
}
