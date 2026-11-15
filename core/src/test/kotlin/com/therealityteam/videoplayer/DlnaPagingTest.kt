package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.source.DlnaPaging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A ContentDirectory container read a page at a time, against servers that answer in their own ways. */
class DlnaPagingTest {

    private fun didl(from: Int, n: Int) = (from until from + n).joinToString("") { "<item id=\"$it\"></item>" }

    @Test fun `450 children are read in three pages when the server says how many`() {
        val asked = mutableListOf<Int>()
        val pages = DlnaPaging.pages(page = 200, maxPages = 50) { start, count ->
            asked += start
            val n = minOf(count, 450 - start)
            DlnaPaging.Page(didl(start, n), returned = n, total = 450)
        }!!
        assertEquals(listOf(0, 200, 400), asked)
        assertEquals(3, pages.didl.size)
        assertTrue(pages.complete)
    }

    @Test fun `a server that leaves the counts out is counted by what it sent`() {
        val pages = DlnaPaging.pages(page = 200, maxPages = 50) { start, _ ->
            val n = when (start) { 0, 200 -> 200; else -> 0 }
            DlnaPaging.Page(didl(start, n), returned = null, total = null)
        }!!
        assertEquals(3, pages.didl.size)
        assertTrue(pages.complete)
    }

    @Test fun `a server that never says done stops at the page limit, not complete`() {
        val pages = DlnaPaging.pages(page = 200, maxPages = 3) { start, count -> DlnaPaging.Page(didl(start, count), count, 0) }!!
        assertEquals(3, pages.didl.size)
        assertFalse(pages.complete)
    }

    @Test fun `a page that fails leaves the container unknown`() {
        assertNull(DlnaPaging.pages(page = 200, maxPages = 50) { start, count ->
            if (start == 0) DlnaPaging.Page(didl(0, count), count, 450) else null
        })
    }
}
