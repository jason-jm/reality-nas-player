package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.util.DurableFile
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The sources and the settings survive a write cut short: the last whole version is read instead. */
class DurableFileTest {

    private val isArray: (String) -> Boolean = { JSONArray(it); true }

    @Test fun `a damaged file is set aside and the version before it read and put back`() {
        val dir = Files.createTempDirectory("durable").toFile()
        val f = File(dir, "aura/sources.json")
        assertNull(DurableFile.read(f, isArray))
        assertTrue(DurableFile.write(f, """[{"id":"a"}]"""))
        assertTrue(DurableFile.write(f, """[{"id":"a"},{"id":"b"}]"""))
        // The second write kept the first as the backup.
        assertEquals("""[{"id":"a"}]""", File(dir, "aura/sources.json.bak").readText())
        f.writeText("""[{"id":"a"},{"i""") // cut short
        assertEquals("""[{"id":"a"}]""", DurableFile.read(f, isArray))
        assertEquals("""[{"id":"a"}]""", f.readText())
        assertEquals(1, File(dir, "aura").listFiles()!!.count { it.name.startsWith("sources.json.damaged-") })
    }

    @Test fun `a kill between the two renames leaves the backup, which is read`() {
        val dir = Files.createTempDirectory("durable").toFile()
        val f = File(dir, "settings.json")
        DurableFile.write(f, "[1]")
        DurableFile.write(f, "[1,2]")
        f.delete() // as if killed after the old file became the backup, before the new one landed
        assertEquals("[1]", DurableFile.read(f, isArray))
        assertEquals("[1]", f.readText())
    }

    @Test fun `a good file is read as it is`() {
        val dir = Files.createTempDirectory("durable").toFile()
        val f = File(dir, "x.json")
        DurableFile.write(f, "[3]")
        assertEquals("[3]", DurableFile.read(f, isArray))
        assertEquals(0, dir.listFiles()!!.count { it.name.contains("damaged") })
    }
}
