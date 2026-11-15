package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.desktop.platform.CrashLog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrashLogTest {
    @Test
    fun `a crash leaves its stack, and only the newest five stay`() {
        val dir = Files.createTempDirectory("aura-crash").toFile()
        repeat(7) { i -> java.io.File(dir, "crash-2026010${i}-000000.txt").writeText("old $i") }
        val f = CrashLog.write(dir, Thread.currentThread(), IllegalStateException("the wall fell over"))
        val text = f.readText()
        assertTrue(text.contains("IllegalStateException: the wall fell over") && text.contains("thread: ") && text.contains("app: Reality NAS Player"))
        assertEquals(5, dir.listFiles()!!.count { it.name.startsWith("crash-") })
        assertTrue(dir.listFiles()!!.any { it == f }, "the new one is among them")
    }
}
