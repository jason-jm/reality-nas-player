package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.source.WebDavSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** A PROPFIND answer as Apache's mod_dav writes it, with the names a real release carries. */
class WebDavParseTest {
    private val body = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:ns0="DAV:">
        <D:response xmlns:lp1="DAV:"><D:href>/Films/Dunkirk%20(2017)/</D:href><D:propstat><D:prop><lp1:resourcetype><D:collection/></lp1:resourcetype></D:prop></D:propstat></D:response>
        <D:response xmlns:lp1="DAV:"><D:href>/Films/Dunkirk%20(2017)/Dunkirk.2017.1080p.BluRay.x264-SPARKS.chs&amp;eng-R3.ass</D:href><D:propstat><D:prop><lp1:resourcetype/></D:prop></D:propstat></D:response>
        <D:response xmlns:lp1="DAV:"><D:href>/Films/Dunkirk%20(2017)/Tom%20&amp;%20Jerry%20&#x26;%20Co.mkv</D:href><D:propstat><D:prop><lp1:resourcetype/></D:prop></D:propstat></D:response>
        </D:multistatus>
    """.trimIndent()

    @Test fun `an ampersand in a name is an ampersand in its address`() {
        val entries = WebDavSource().parseEntries(body, "http://nas:5005", "http://nas:5005/Films/Dunkirk%20(2017)/")
        assertEquals(
            listOf(
                "http://nas:5005/Films/Dunkirk%20(2017)/Dunkirk.2017.1080p.BluRay.x264-SPARKS.chs&eng-R3.ass",
                "http://nas:5005/Films/Dunkirk%20(2017)/Tom%20&%20Jerry%20&%20Co.mkv",
            ),
            entries.filter { !it.isCollection }.map { it.url },
        )
        assertEquals("Tom & Jerry & Co.mkv", entries.last().name)
    }

    @Test fun `a plus in a path is a plus, not a space`() {
        // Apache leaves + in a path as it is ("Tom%20&amp;%20Jerry%20+%20Friends"): a form decoder read it as a space.
        val xml = """<D:multistatus xmlns:D="DAV:"><D:response><D:href>/video/Tom%20&amp;%20Jerry%20+%20Friends%20(2021).mp4</D:href>""" +
            """<D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response></D:multistatus>"""
        val entry = WebDavSource().parseEntries(xml, "http://nas:8092", "http://nas:8092/video/").single()
        assertEquals("Tom & Jerry + Friends (2021).mp4", entry.name)
        assertEquals("http://nas:8092/video/Tom%20&%20Jerry%20+%20Friends%20(2021).mp4", entry.url)
    }
}
