package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.junit.Test
import java.io.File

/**
 * Runs the real [LibraryGrouping] over a hand-authored long-tail set (`synthetic_cases.tsv`) and
 * checks the poster wall comes out the way a person would draw it: seasons collapse, a 劇場版 does
 * not, a folder of unrelated one-off documentaries is not one card, films that ARE numbers stay
 * apart, and so on.
 *
 * Unlike the NAS evaluation this needs no device and no network, so every grouping change is
 * regression-checked in the normal test run. Failures are reported as the specific pairs that
 * were wrongly split or wrongly merged, not as a bare count.
 */
class SyntheticGroupingTest {

    private data class Case(val work: String, val item: MediaItem, val category: String?)

    private fun load(): List<Case> {
        val res = javaClass.classLoader!!.getResourceAsStream("synthetic_cases.tsv")
            ?: error("synthetic_cases.tsv missing from test resources")
        val lines = res.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("work\t") }
        return lines.mapIndexed { idx, line ->
            val cells = line.split('\t')
            require(cells.size >= 6) { "bad row: $line" }
            val (work, status, key, kind, tag, path) = listOf(
                cells[0], cells[1], cells[2], cells[3], cells[4], cells[5],
            )
            val fileName = path.substringAfterLast('/')
            // column 7 is the SCRAPED title when it differs from the file name — grouping uses it
            // to tell a work's release folder from a shelf, so a case that turns on that has to
            // model it (a Chinese folder over romaji file names only corresponds via this title).
            val scraped = cells.getOrNull(6)?.takeIf { it.isNotBlank() && it != "-" }
            Case(
                work,
                category = cells.getOrNull(7)?.trim()?.takeIf { it.isNotBlank() && it != "-" },
                item = MediaItem(
                    id = "syn$idx",
                    sourceId = "syn",
                    path = path,
                    fileName = fileName,
                    title = scraped ?: fileName.substringBeforeLast('.'),
                    kind = runCatching { MediaKind.valueOf(kind) }.getOrDefault(MediaKind.UNKNOWN),
                    collectionKey = key.takeIf { it != "-" },
                    contentTag = tag.takeIf { it != "-" }?.let { runCatching { ContentKind.valueOf(it) }.getOrNull() },
                    scrapeStatus = runCatching { ScrapeStatus.valueOf(status) }.getOrDefault(ScrapeStatus.PENDING),
                    // column 9 — the app's category reads genres, so a matched animated film has to
                    // be able to carry TMDB's 动画 the way the real item does
                    genres = cells.getOrNull(8)?.split('|')?.filter { it.isNotBlank() }.orEmpty(),
                    addedAt = idx.toLong(),
                ),
            )
        }
    }

    /**
     * Which SIDEBAR TAB each card lands in — a card that is grouped and named correctly but filed
     * under 电影 when it is a documentary is a card the user will not find. Only rows that state a
     * category are checked (column 8).
     */
    @Test
    fun everyCardLandsInTheJudgedCategory() {
        val cases = load().filter { it.category != null }
        val byPath = HashMap<String, String>()
        LibraryGrouping.group(load().map { it.item }).forEach { g ->
            g.items.forEach { byPath[it.path] = g.category.name }
        }
        val wrong = cases.mapNotNull { c ->
            val got = byPath[c.item.path]
            if (got == c.category) null else "${c.work}: want ${c.category}, got $got  ←  ${short(c.item.path)}"
        }
        if (wrong.isNotEmpty()) {
            throw AssertionError("cards in the wrong category tab:\n  " + wrong.distinct().joinToString("\n  "))
        }
    }

    @Test
    fun posterWallMatchesJudgedGroundTruth() {
        val cases = load()
        val expected = cases.associate { it.item.path to it.work }
        val actual = HashMap<String, String>()
        LibraryGrouping.group(cases.map { it.item }).forEach { g ->
            g.items.forEach { actual[it.path] = g.key }
        }

        val wronglyMerged = mutableListOf<String>()
        val wronglySplit = mutableListOf<String>()
        val paths = cases.map { it.item.path }
        for (i in paths.indices) {
            for (j in i + 1 until paths.size) {
                val a = paths[i]
                val b = paths[j]
                val sameExpected = expected[a] == expected[b]
                val sameActual = actual[a] != null && actual[a] == actual[b]
                if (sameExpected && !sameActual) {
                    wronglySplit += "${expected[a]}: ${short(a)}  ≠  ${short(b)}"
                } else if (!sameExpected && sameActual) {
                    wronglyMerged += "${expected[a]} + ${expected[b]}: ${short(a)}  =  ${short(b)}"
                }
            }
        }
        if (wronglyMerged.isNotEmpty() || wronglySplit.isNotEmpty()) {
            val report = buildString {
                appendLine("synthetic poster wall does not match the judged ground truth")
                appendLine("  wrongly MERGED (${wronglyMerged.size} pairs):")
                wronglyMerged.distinct().take(12).forEach { appendLine("    $it") }
                appendLine("  wrongly SPLIT (${wronglySplit.size} pairs):")
                wronglySplit.distinct().take(12).forEach { appendLine("    $it") }
            }
            throw AssertionError(report)
        }
    }

    private fun short(path: String) = path.substringAfter("/video/").let {
        if (it.length <= 58) it else "…" + it.takeLast(57)
    }

    private operator fun <T> List<T>.component6() = this[5]
}
