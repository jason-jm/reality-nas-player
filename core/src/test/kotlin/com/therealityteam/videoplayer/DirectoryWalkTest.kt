package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.source.DirectoryWalk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The tree walk, against a tree in memory.
 *
 * There is no SMB server to test against here, so the traversal is separated from the transport and
 * tested on its own: what it finds, what it refuses to climb, and that running it across several
 * lanes finds exactly what one lane finds.
 */
class DirectoryWalkTest {

    /** A tree written as "dir" -> entries; a name with a dot is a file. */
    private class Tree(private val dirs: Map<String, List<String>>) {
        val calls = AtomicInteger(0)
        fun lister(unreadable: Set<String> = emptySet()) = DirectoryWalk.Lister { dir ->
            calls.incrementAndGet()
            if (dir in unreadable) null
            else dirs[dir]?.map { DirectoryWalk.Entry(it, isDirectory = !it.contains('.'), size = it.length.toLong()) }
                ?: emptyList()
        }
    }

    private val tree = Tree(
        mapOf(
            "" to listOf("Movies", "TV", "readme.txt"),
            "Movies" to listOf("Alien", "Aliens", "loose.mkv"),
            "Movies/Alien" to listOf("alien.mkv", "alien.nfo"),
            "Movies/Aliens" to listOf("aliens.mkv", "Extras"),
            "Movies/Aliens/Extras" to listOf("deleted.mkv"),
            "TV" to listOf("Friends"),
            "TV/Friends" to listOf("S01"),
            "TV/Friends/S01" to listOf("e01.mkv", "e02.mkv"),
        ),
    )

    private fun walk(
        lanes: Int,
        maxDepth: Int = 12,
        maxFiles: Int = 1000,
        unreadable: Set<String> = emptySet(),
        listerCount: AtomicInteger? = null,
    ): List<String> = runBlocking {
        DirectoryWalk.walk(
            lanes = List(lanes) { tree.lister(unreadable) },
            root = "",
            maxDepth = maxDepth,
            maxFiles = maxFiles,
            separator = '/',
            keep = { it.endsWith(".mkv") },
            onRootFailure = { error("root unreadable") },
        ).map { if (it.dir.isEmpty()) it.name else "${it.dir}/${it.name}" }.sorted()
    }

    private val everything = listOf(
        "Movies/Alien/alien.mkv",
        "Movies/Aliens/Extras/deleted.mkv",
        "Movies/Aliens/aliens.mkv",
        "Movies/loose.mkv",
        "TV/Friends/S01/e01.mkv",
        "TV/Friends/S01/e02.mkv",
    ).sorted()

    @Test fun `one lane finds every video and nothing else`() {
        assertEquals(everything, walk(lanes = 1))
    }

    @Test fun `more lanes find exactly the same files`() {
        listOf(2, 3, 4, 8).forEach { assertEquals("with $it lanes", everything, walk(lanes = it)) }
    }

    @Test fun `a depth limit stops the climb`() {
        // depth 0 is the root itself, so 2 reaches Movies/Alien but not Aliens/Extras.
        val shallow = walk(lanes = 4, maxDepth = 2)
        assertTrue(shallow.contains("Movies/Alien/alien.mkv"))
        assertTrue(shallow.none { it.contains("Extras") })
    }

    @Test fun `a file limit is honoured`() {
        assertEquals(3, walk(lanes = 4, maxFiles = 3).size)
    }

    @Test fun `a directory that cannot be read is skipped, not fatal`() {
        val got = walk(lanes = 4, unreadable = setOf("TV/Friends/S01"))
        assertTrue(got.none { it.startsWith("TV/") })
        assertTrue(got.contains("Movies/Alien/alien.mkv"))
    }

    @Test fun `a directory that cannot be read is reported, so its files are not taken for gone`() {
        val unread = java.util.concurrent.ConcurrentLinkedQueue<String>()
        runBlocking {
            DirectoryWalk.walk(
                lanes = List(4) { tree.lister(setOf("TV/Friends/S01", "Movies/Aliens/Extras")) },
                root = "", maxDepth = 12, maxFiles = 1000, separator = '/',
                keep = { it.endsWith(".mkv") },
                onRootFailure = { error("root unreadable") },
                onUnread = { unread += it },
            )
        }
        assertEquals(listOf("Movies/Aliens/Extras", "TV/Friends/S01"), unread.sorted())
    }

    @Test fun `a scan keeps what it found and everything under a folder it could not read`() {
        fun item(id: String, path: String) = com.therealityteam.videoplayer.data.model.MediaItem(id, "s", path, path.substringAfterLast('/'), title = id)
        val existing = listOf(
            item("a", "smb://nas/video/Anime/Show/01.mkv"),
            item("b", "smb://nas/video/Anime/Show 2/01.mkv"), // a sibling whose name only starts the same
            item("c", "smb://nas/video/Anime/Deleted/01.mkv"),
            item("d", "smb://nas/video/Film.mkv"),
        )
        val keep = com.therealityteam.videoplayer.data.source.idsToKeep(setOf("d"), existing, listOf("smb://nas/video/Anime/Show/"))
        assertEquals(setOf("a", "d"), keep)
        assertEquals(setOf("d"), com.therealityteam.videoplayer.data.source.idsToKeep(setOf("d"), existing, emptyList()))
    }

    @Test fun `a root that cannot be read is fatal`() {
        // A configured path that has been renamed must not look like an empty folder.
        assertThrows(IllegalStateException::class.java) { walk(lanes = 4, unreadable = setOf("")) }
    }

    @Test fun `every directory is listed exactly once`() {
        val t = Tree(tree.let { _ -> mapOf("" to listOf("a", "b"), "a" to listOf("x.mkv"), "b" to listOf("y.mkv")) })
        val listers = List(4) { t.lister() }
        runBlocking {
            DirectoryWalk.walk(listers, "", 12, 1000, '/', { it.endsWith(".mkv") }, { error("x") })
        }
        assertEquals("root + a + b", 3, t.calls.get())
    }

    @Test fun `an empty tree is not an error`() {
        val t = Tree(mapOf("" to emptyList()))
        val got = runBlocking {
            DirectoryWalk.walk(listOf(t.lister()), "", 12, 1000, '/', { true }, { error("x") })
        }
        assertEquals(emptyList<DirectoryWalk.Found>(), got)
    }
}
