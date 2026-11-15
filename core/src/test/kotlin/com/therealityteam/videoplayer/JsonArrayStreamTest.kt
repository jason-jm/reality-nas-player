package com.therealityteam.videoplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The splitter that lets the library be read one item at a time instead of all at once. */
class JsonArrayStreamTest {

    private fun split(text: String): Pair<List<String>, Boolean> {
        val out = mutableListOf<String>()
        val whole = com.therealityteam.videoplayer.data.model.JsonArrayStream
            .forEachObject(text.reader()) { out += it }
        return out to whole
    }

    @Test fun `each object comes out whole`() {
        val (objs, whole) = split("""[{"a":1},{"b":2},{"c":3}]""")
        assertEquals(listOf("""{"a":1}""", """{"b":2}""", """{"c":3}"""), objs)
        assertTrue(whole)
    }

    @Test fun `nesting does not end an object early`() {
        val (objs, _) = split("""[{"a":{"b":[{"c":1}]},"d":2}]""")
        assertEquals(listOf("""{"a":{"b":[{"c":1}]},"d":2}"""), objs)
    }

    @Test fun `braces inside a string are text, not structure`() {
        // Real paths carry them: `…/[Group] Title {v2}/file.mkv`.
        val (objs, _) = split("""[{"path":"/a/{b}/c}"},{"n":1}]""")
        assertEquals(2, objs.size)
        assertEquals("""{"path":"/a/{b}/c}"}""", objs[0])
    }

    @Test fun `an escaped quote does not end the string`() {
        val (objs, _) = split("""[{"t":"say \"}\" now"},{"n":1}]""")
        assertEquals(2, objs.size)
        assertEquals("""{"t":"say \"}\" now"}""", objs[0])
    }

    @Test fun `a backslash before the closing quote still closes it`() {
        // `"c:\\"` ends the string: the escape consumed the first backslash, not the quote.
        val (objs, _) = split("""[{"p":"c:\\"},{"n":1}]""")
        assertEquals(2, objs.size)
        assertEquals("""{"p":"c:\\"}""", objs[0])
    }

    @Test fun `whitespace and newlines between items are skipped`() {
        val (objs, whole) = split("[\n  {\"a\":1},\n  {\"b\":2}\n]\n")
        assertEquals(2, objs.size)
        assertTrue(whole)
    }

    @Test fun `an empty array yields nothing and is still whole`() {
        val (objs, whole) = split("[]")
        assertTrue(objs.isEmpty())
        assertTrue(whole)
    }

    @Test fun `a truncated file is reported as not whole`() {
        // A half-written library must not be mistaken for a short one: the caller refuses it.
        val (objs, whole) = split("""[{"a":1},{"b":""")
        assertEquals(1, objs.size)
        assertFalse(whole)
    }

    @Test fun `a file that never opens an array is not whole`() {
        assertFalse(split("""{"a":1}""").second)
        assertFalse(split("").second)
    }
}
