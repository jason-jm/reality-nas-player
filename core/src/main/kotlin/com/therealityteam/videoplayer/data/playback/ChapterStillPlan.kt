package com.therealityteam.videoplayer.data.playback

/**
 * 章节 pictures: where in each chapter its picture is taken, and in what order the chapters are
 * pictured. Pure, so it is unit-tested; the app's retriever does the decoding.
 */
object ChapterStillPlan {

    /**
     * The chapters to picture, [from] outwards — the list opens on the chapter playing, so its
     * rows fill first, the ones below a step ahead of the ones above.
     */
    fun order(count: Int, from: Int): List<Int> {
        if (count <= 0) return emptyList()
        val start = from.coerceIn(0, count - 1)
        val out = ArrayList<Int>(count)
        out += start
        var step = 1
        while (out.size < count) {
            if (start + step < count) out += start + step
            if (start - step >= 0) out += start - step
            step++
        }
        return out
    }

    /**
     * Where chapter [i]'s picture is first looked for: a few seconds in — a chapter opens on a cut,
     * often a fade from black — but inside its first third, so a short one still shows itself.
     */
    fun firstTryMs(chapters: List<Chapter>, i: Int, durationMs: Long): Long = at(chapters, i, durationMs, FIRST_MS, 3)

    /** Where to look again when the first picture came out dark (a fade, a night scene's opening). */
    fun secondTryMs(chapters: List<Chapter>, i: Int, durationMs: Long): Long = at(chapters, i, durationMs, SECOND_MS, 2)

    private fun at(chapters: List<Chapter>, i: Int, durationMs: Long, into: Long, partOf: Int): Long {
        val start = chapters[i].startMs
        val end = chapters.getOrNull(i + 1)?.startMs ?: durationMs.takeIf { it > start }
        val room = end?.let { (it - start) / partOf } ?: into
        return start + minOf(into, room).coerceAtLeast(0L)
    }

    private const val FIRST_MS = 4_000L
    private const val SECOND_MS = 15_000L
}
