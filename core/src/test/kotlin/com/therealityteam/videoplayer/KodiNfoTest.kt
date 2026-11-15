package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.data.scrape.KodiNfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nfo files as the tools that write them lay them out — the movie one shaped like those a TV
 * box's player left beside the films in ~/Movies (Chinese title over the original, a Douban id
 * among the uniqueids, its file paths on the NAS), the others after Kodi's and Jellyfin's.
 */
class KodiNfoTest {

    private val movie = """
        <?xml version="1.0" encoding="UTF-8"?><movie>
        <title>敦刻尔克</title>
        <originaltitle>Dunkirk</originaltitle>
        <ratings>
        <rating name="tmdb" max="10" default="true">
        <value>7.455</value>
        <votes>15676</votes>
        </rating>
        <rating name="imdb" max="10"><value>7.8</value><votes>760,512</votes></rating>
        <rating name="metacritic" max="100"><value>94</value></rating>
        </ratings>
        <plot>二战初期，40万英法盟军被敌军围困于敦刻尔克的海滩之上……</plot>
        <runtime>107</runtime>
        <thumb aspect="poster" preview="http://image.tmdb.org/t/p/w92/b4Oe.jpg">http://image.tmdb.org/t/p/original/b4Oe.jpg</thumb>
        <fanart>
        <thumb preview="http://image.tmdb.org/t/p/w300/ddIk.jpg">http://image.tmdb.org/t/p/original/ddIk.jpg</thumb>
        </fanart>
        <mpaa>PG-13</mpaa>
        <path>/data/system/smb/192.168.1.36#video/Movies/Dunkirk.2017.1080p.BluRay.x264-SPARKS[rarbg]/Dunkirk.mkv</path>
        <id>tt5013056</id>
        <uniqueid type="tmdb" default="true">374720</uniqueid>
        <uniqueid type="imdb" default="false">tt5013056</uniqueid>
        <uniqueid type="douban" default="false">26607693</uniqueid>
        <genre>战争</genre>
        <genre>动作</genre>
        <genre>剧情</genre>
        <director>Christopher Nolan</director>
        <credits>Christopher Nolan</credits>
        <actor><name>Fionn Whitehead</name><role>Tommy</role><thumb>https://image.tmdb.org/t/p/original/fw.jpg</thumb></actor>
        <actor><name>Tom Hardy</name><role>Farrier</role></actor>
        <premiered>2017-07-19</premiered>
        <art>
        <poster>http://image.tmdb.org/t/p/original/b4Oe.jpg</poster>
        <fanart>http://image.tmdb.org/t/p/original/ddIk.jpg</fanart>
        </art>
        </movie>
    """.trimIndent()

    @Test fun `a movie nfo gives its ids, its names and what it says about the film`() {
        val n = KodiNfo.parse(movie)!!
        assertEquals(KodiNfo.Kind.MOVIE, n.kind)
        assertEquals(374720, n.tmdbId)
        assertEquals("tt5013056", n.imdbId)
        assertEquals(MediaKind.MOVIE, n.tmdbKind)
        assertEquals("敦刻尔克", n.title)
        assertEquals("Dunkirk", n.originalTitle)
        assertEquals(2017, n.year)
        assertEquals(107, n.runtime)
        assertEquals("PG-13", n.mpaa)
        assertEquals(listOf("战争", "动作", "剧情"), n.genres)
        assertEquals(listOf("Christopher Nolan"), n.directors)
        assertEquals(listOf("Christopher Nolan"), n.writers)
        assertEquals(listOf("Fionn Whitehead", "Tom Hardy"), n.actors.map { it.name })
        assertEquals("Tommy", n.actors.first().role)
        assertEquals("http://image.tmdb.org/t/p/original/b4Oe.jpg", n.posterUrl)
        assertEquals("http://image.tmdb.org/t/p/original/ddIk.jpg", n.fanartUrl)
        assertTrue(n.plot!!.startsWith("二战初期"))
    }

    @Test fun `each site's score is shown its own way, the default one is the headline`() {
        val n = KodiNfo.parse(movie)!!
        val bySite = n.ratings.associate { it.source to it.value }
        assertEquals("7.5", bySite[RatingSource.TMDB])
        assertEquals("7.8", bySite[RatingSource.IMDB])
        assertEquals("94", bySite[RatingSource.METACRITIC])
        assertEquals(760512, n.ratings.first { it.source == RatingSource.IMDB }.votes)
        assertEquals(7.455, n.rating!!, 1e-9)
    }

    @Test fun `as an answer it is filed the way a database answer is`() {
        val meta = KodiNfo.parse(movie)!!.toMetaResult(374720)
        assertEquals(374720, meta.providerId)
        assertEquals("敦刻尔克", meta.title)
        assertEquals(listOf("导演", "编剧"), meta.crew.map { it.role })
        assertEquals("https://image.tmdb.org/t/p/original/fw.jpg", meta.cast.first().imageUrl)
    }

    @Test fun `an actor's or a set's name is never the film's`() {
        val n = KodiNfo.parse(
            """
            <movie>
              <set><name>Dollars Trilogy</name><title>wrong</title></set>
              <actor><name>Clint Eastwood</name><title>wrong too</title></actor>
              <title>The Good, the Bad and the Ugly</title>
              <year>1966</year>
            </movie>
            """.trimIndent(),
        )!!
        assertEquals("The Good, the Bad and the Ugly", n.title)
        assertNull(n.tmdbId)
    }

    @Test fun `a URL nfo is a pointer, and a tv URL names a show`() {
        val tv = KodiNfo.parse("https://www.themoviedb.org/tv/1399-game-of-thrones\n")!!
        assertNull(tv.kind)
        assertEquals(1399, tv.tmdbId)
        assertEquals(MediaKind.SHOW, tv.tmdbKind)
        val imdb = KodiNfo.parse("http://www.imdb.com/title/tt0133093/")!!
        assertEquals("tt0133093", imdb.imdbId)
        assertNull(imdb.tmdbId)
        assertNull(KodiNfo.parse("Ripped by SPARKS. Greets to all.\n"))
    }

    @Test fun `a combined nfo is XML with the URL after it`() {
        val n = KodiNfo.parse(
            "<movie><title>The Matrix</title><year>1999</year></movie>\nhttps://www.themoviedb.org/movie/603-the-matrix",
        )!!
        assertEquals("The Matrix", n.title)
        assertEquals(603, n.tmdbId)
        assertEquals(MediaKind.MOVIE, n.tmdbKind)
    }

    @Test fun `an episode nfo says which episode, and its tmdb id is the episode's own`() {
        val n = KodiNfo.parse(
            """
            <episodedetails>
              <title>Winter Is Coming</title>
              <showtitle>Game of Thrones</showtitle>
              <season>1</season>
              <episode>1</episode>
              <uniqueid type="tmdb" default="true">63056</uniqueid>
              <aired>2011-04-17</aired>
            </episodedetails>
            <episodedetails><title>The Kingsroad</title><season>1</season><episode>2</episode></episodedetails>
            """.trimIndent(),
        )!!
        assertEquals(KodiNfo.Kind.EPISODE, n.kind)
        assertEquals("Winter Is Coming", n.title)
        assertEquals("Game of Thrones", n.showTitle)
        assertEquals(1, n.season)
        assertEquals(1, n.episode)
        assertEquals(2011, n.year)
        // The engine never takes an episode nfo's id for the series: the series is the tvshow.nfo's.
        assertEquals(63056, n.tmdbId)
    }

    @Test fun `a tvshow nfo names a series`() {
        val n = KodiNfo.parse(
            """<tvshow><title>Shōgun</title><uniqueid type="tmdb">126308</uniqueid><uniqueid type="tvdb">392573</uniqueid><premiered>2024-02-27</premiered></tvshow>""",
        )!!
        assertEquals(KodiNfo.Kind.SHOW, n.kind)
        assertEquals(126308, n.tmdbId)
        assertEquals(MediaKind.SHOW, n.tmdbKind)
        assertEquals(2024, n.year)
    }

    @Test fun `entities, CDATA and the ampersands nobody escaped`() {
        val n = KodiNfo.parse(
            """
            <movie>
              <title>Fast &amp; Furious</title>
              <originaltitle><![CDATA[Fast & Furious]]></originaltitle>
              <plot>Rock & roll &#x2014; and &#169; too</plot>
              <rating>7.4</rating>
              <genre>Action / Crime</genre>
            </movie>
            """.trimIndent(),
        )!!
        assertEquals("Fast & Furious", n.title)
        assertEquals("Fast & Furious", n.originalTitle)
        assertEquals("Rock & roll — and © too", n.plot)
        assertEquals(7.4, n.rating!!, 1e-9)
        assertTrue(n.ratings.isEmpty())
        assertEquals(listOf("Action", "Crime"), n.genres)
    }

    @Test fun `text is read by its mark, its declaration, or as the old Chinese code page`() {
        val xml = "<movie><title>云图</title></movie>"
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + xml.toByteArray(Charsets.UTF_16LE)
        assertEquals("云图", KodiNfo.parse(KodiNfo.text(utf16))!!.title)
        val gbk = xml.toByteArray(charset("GB18030"))
        assertEquals("云图", KodiNfo.parse(KodiNfo.text(gbk))!!.title)
        val declared = """<?xml version="1.0" encoding="GBK"?>$xml""".toByteArray(charset("GBK"))
        assertEquals("云图", KodiNfo.parse(KodiNfo.text(declared))!!.title)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + xml.toByteArray()
        assertEquals("云图", KodiNfo.parse(KodiNfo.text(bom))!!.title)
        // A Taiwan tool's, in Big5 and saying nothing: no longer read as GB18030.
        val big5 = "<movie><title>悲情城市</title><plot>這是一部關於我們的家的電影。</plot></movie>".toByteArray(charset("Big5"))
        assertEquals("悲情城市", KodiNfo.parse(KodiNfo.text(big5))!!.title)
    }
}
