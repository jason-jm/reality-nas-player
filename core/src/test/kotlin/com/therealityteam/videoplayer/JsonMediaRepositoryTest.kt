package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaJson
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The library file is read on its own thread; every write has to wait for that read. A write that
 * did not once ran on an empty map and persisted it — a 9.6k-file library became `[]`.
 */
class JsonMediaRepositoryTest {


    private fun item(n: Int) = MediaItem(
        id = "id$n", sourceId = "s", path = "smb://nas/video/Movie/Film $n/Film $n.mkv", fileName = "Film $n.mkv",
        title = "Film $n", kind = MediaKind.MOVIE,
    )

    @Test
    fun aWriteThatArrivesBeforeTheReadFinishesKeepsEveryItem() {
        val dir = Files.createTempDirectory("aura-repo").toFile()
        val file = File(dir, "aura/library.json").apply { parentFile!!.mkdirs() }
        // A library big enough that its read takes a moment.
        val existing = (1..3000).map(::item)
        file.writeText(JSONArray().also { arr -> existing.forEach { arr.put(MediaJson.item(it)) } }.toString())

        // The loader is held back half a second, so the write is certainly first in line.
        val repo = JsonMediaRepository(dir, loadDelayMsForTest = 500)
        repo.upsert(item(9999))
        assertNotNull(repo.get("id1"))
        assertNotNull(repo.get("id9999"))
        assertEquals(3001, repo.all().size)

        // …and what reaches the disk is the whole library, never the write alone.
        val deadline = System.currentTimeMillis() + 15_000
        var onDisk = -1
        while (System.currentTimeMillis() < deadline) {
            onDisk = runCatching { JSONArray(file.readText()).length() }.getOrDefault(-1)
            if (onDisk == 3001) break
            Thread.sleep(200)
        }
        assertEquals(3001, onDisk)
    }

    @Test
    fun awkwardTextSurvivesTheStreamedRead() {
        // The library is split into objects as it streams, so a brace or a quote inside a VALUE has
        // to stay text. Real libraries carry both: `…/[Group] Title {v2}/…`, a title with quotes,
        // a Windows path ending in a backslash.
        val dir = Files.createTempDirectory("aura-repo-text").toFile()
        val file = File(dir, "aura/library.json").apply { parentFile!!.mkdirs() }
        val odd = MediaItem(
            id = "odd", sourceId = "s", kind = MediaKind.MOVIE,
            path = """smb://nas/video/[Group] Title {v2}/a"b}.mkv""",
            fileName = """a"b}.mkv""",
            title = """He said "}" and left\""",
            overview = "line one\nline two, with { and } and \\ backslash",
            aliases = listOf("Samurai Champloo", "サムライチャンプルー"),
        )
        file.writeText(JSONArray().also { it.put(MediaJson.item(odd)) }.put(MediaJson.item(item(2))).toString())

        val repo = JsonMediaRepository(dir)
        val deadline = System.currentTimeMillis() + 10_000
        while (!repo.loaded.value && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val back = repo.get("odd")
        assertNotNull(back)
        assertEquals(odd.path, back!!.path)
        assertEquals(odd.title, back.title)
        assertEquals(odd.overview, back.overview)
        assertEquals(odd.aliases, back.aliases)
        assertEquals(2, repo.all().size)
    }

    @Test
    fun aDamagedLibraryIsSetAsideAndTheLastWholeCopyReadInstead() {
        val dir = Files.createTempDirectory("aura-repo").toFile()
        val file = File(dir, "aura/library.json").apply { parentFile!!.mkdirs() }
        val whole = JSONArray().also { arr -> (1..50).forEach { arr.put(MediaJson.item(item(it))) } }.toString()
        file.writeText(whole)

        // A launch that reads it whole keeps a copy.
        loaded(JsonMediaRepository(dir))
        val backup = File(dir, "aura/library.json.bak")
        val deadline = System.currentTimeMillis() + 10_000
        while (!backup.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(whole, backup.readText())

        // The file is then cut short (a kill, a bad copy). The next launch refuses it, keeps it aside,
        // reads the copy, and puts it back — and a write after that keeps all fifty.
        file.writeText(whole.substring(0, whole.length / 3))
        val repo = loaded(JsonMediaRepository(dir))
        assertEquals(50, repo.all().size)
        assertEquals(whole, file.readText())
        assertEquals(1, File(dir, "aura").listFiles()!!.count { it.name.startsWith("library.json.damaged-") })
        repo.upsert(item(51))
        repo.flush()
        assertEquals(51, JSONArray(file.readText()).length())
    }

    @Test
    fun aDamagedLibraryWithNoCopyIsKeptAsideNotWrittenOver() {
        val dir = Files.createTempDirectory("aura-repo").toFile()
        val file = File(dir, "aura/library.json").apply { parentFile!!.mkdirs() }
        val cut = JSONArray().also { arr -> (1..50).forEach { arr.put(MediaJson.item(item(it))) } }.toString().let { it.substring(0, it.length / 2) }
        file.writeText(cut)
        val repo = loaded(JsonMediaRepository(dir))
        assertEquals(0, repo.all().size)
        repo.upsert(item(1))
        repo.flush()
        // The damaged file survives, set aside, for whoever can still make something of it.
        val kept = File(dir, "aura").listFiles()!!.single { it.name.startsWith("library.json.damaged-") }
        assertEquals(cut, kept.readText())
    }

    private fun loaded(repo: JsonMediaRepository): JsonMediaRepository {
        val deadline = System.currentTimeMillis() + 10_000
        while (!repo.loaded.value && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return repo
    }
}
