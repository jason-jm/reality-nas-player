package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.source.DirectoryWalk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * What overlapping the listings is worth, on the tree a real library actually has.
 *
 * There is no SMB server here to time, and the thing being timed is not computation anyway — it is
 * waiting for one round trip after another. So the tree comes from real paths (pass
 * -Daura.paths=<file>) and the only thing modelled is how long a listing takes, swept across the
 * range a LAN plausibly covers. What the walk costs is (directories x latency) / lanes, and the
 * point of the sweep is to show that holds on a real tree's shape rather than an even one.
 */
class DirectoryWalkBenchTest {

    @Test
    fun overlappingTheRoundTrips() {
        val src = System.getProperty("aura.paths") ?: return
        val paths = File(src).readLines().filter { it.isNotBlank() && it.startsWith("smb://") }
        // host and share are not part of the walk; the walk starts inside the share.
        val rel = paths.map { it.split('/').drop(4).joinToString("/") }
        val dirs = HashMap<String, MutableList<DirectoryWalk.Entry>>()
        fun ensure(dir: String) {
            if (dir in dirs) return
            dirs[dir] = mutableListOf()
            if (dir.isEmpty()) return
            val parent = dir.substringBeforeLast('/', "")
            ensure(parent)
            dirs[parent]!! += DirectoryWalk.Entry(dir.substringAfterLast('/'), isDirectory = true)
        }
        rel.forEach { p ->
            val dir = p.substringBeforeLast('/', "")
            ensure(dir)
            dirs[dir]!! += DirectoryWalk.Entry(p.substringAfterLast('/'), isDirectory = false, size = 1)
        }
        println("WALKBENCH ${rel.size} files under ${dirs.size} directories")

        listOf(5L, 20L, 50L).forEach { latency ->
            val line = StringBuilder("WALKBENCH latency=${latency}ms ")
            listOf(1, 2, 4, 8).forEach { lanes ->
                val listers = List(lanes) {
                    DirectoryWalk.Lister { d -> Thread.sleep(latency); dirs[d] }
                }
                var files = 0
                val ms = measureTimeMillis {
                    files = runBlocking {
                        DirectoryWalk.walk(listers, "", 12, 50_000, '/', { true }, { error("root") }).size
                    }
                }
                check(files == rel.size) { "found $files of ${rel.size} with $lanes lanes" }
                line.append(" lanes=$lanes:${ms}ms ")
            }
            println(line)
        }
    }
}
