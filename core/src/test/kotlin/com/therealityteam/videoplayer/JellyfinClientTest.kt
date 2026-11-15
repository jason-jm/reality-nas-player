package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.source.JellyfinClient
import com.therealityteam.videoplayer.data.source.JellyfinClient.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing pinned against RESPONSES A REAL SERVER SENT — demo.jellyfin.org (10.11.11), captured
 * into resources/jellyfin/ with a test token and synthetic per-item UserData keys. Hand-written "example"
 * JSON would only prove the parser agrees with my imagination; these prove it agrees with
 * Jellyfin.
 */
class JellyfinClientTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/jellyfin/$name")!!.bufferedReader().use { it.readText() }

    private val session = JellyfinClient.Session("http://nas:8096", "user-1", "tok en") // 空格故意的：URL 编码必须处理

    // ---- 认证 ----------------------------------------------------------------------------

    @Test
    fun authYieldsTheTokenAndUserIdTheServerSent() {
        val s = JellyfinClient.parseAuth("http://nas:8096", fixture("auth.json"))
        assertEquals("test-token-0123456789abcdef", s.token)
        assertEquals("4ed1b8b42a7c4ea682f0fe5d08d4e278", s.userId)
        assertEquals("http://nas:8096", s.baseUrl)
    }

    // ---- 条目解析 -------------------------------------------------------------------------

    @Test
    fun aRealItemsPageParsesEveryItemAndTheTotal() {
        val (items, total) = JellyfinClient.parseItemsPage(fixture("items.json"))
        assertEquals(18, total)          // 服务器共 18 条
        assertEquals(12, items.size)     // 这一页请求了 limit=12
        assertEquals(6, items.count { it.type == ItemType.EPISODE })
        assertEquals(6, items.count { it.type == ItemType.MOVIE })
    }

    @Test
    fun aMovieCarriesTheIdentityTheServerAlreadySettled() {
        val (items, _) = JellyfinClient.parseItemsPage(fixture("items.json"))
        val movie = items.first { it.name == "The Boy in the Plastic Bubble" }
        // 这就是整条 Jellyfin 路的意义：身份是现成的，一次外部刮削都不用发。
        assertEquals(9878, movie.tmdbId)
        assertEquals("tt0074236", movie.imdbId)
        assertEquals(1976, movie.year)
        assertEquals("PG", movie.officialRating)
        assertEquals(listOf("TV Movie", "Drama"), movie.genres)
        assertEquals(93, movie.runtimeMinutes)  // 55842000000 ticks
        assertTrue(movie.overview!!.startsWith("Tod Lubitch is born"))
        assertEquals("John Travolta", movie.people.first().name)
        assertEquals("Tod Lubitch", movie.people.first().role)
    }

    @Test
    fun anEpisodeKnowsItsPlaceAndItsShowsPoster() {
        val (items, _) = JellyfinClient.parseItemsPage(fixture("items.json"))
        val ep = items.first { it.imdbId == "tt1834645" }
        assertEquals(ItemType.EPISODE, ep.type)
        assertEquals("Pioneer One", ep.seriesName)
        assertEquals(1, ep.season)
        assertEquals(4, ep.episode)
        // 墙上的卡片要穿剧集的海报，不是本集截图 —— 两个 tag 缺一不可。
        assertEquals("9bd5926326d651153928e0d11c6ee7cb", ep.seriesId)
        assertEquals("b2dfa2770ea69e5bd933b4bf2784597a", ep.seriesPrimaryImageTag)
        val url = JellyfinClient.seriesImageUrl(session, ep)!!
        assertTrue(url, url.startsWith("http://nas:8096/Items/9bd5926326d651153928e0d11c6ee7cb/Images/Primary?tag=b2dfa2"))
    }

    @Test
    fun anItemWithoutArtworkYieldsNoUrlRatherThanABrokenOne() {
        val bare = JellyfinClient.parseItem(org.json.JSONObject("""{"Id":"x","Name":"n","Type":"Movie"}"""))!!
        assertNull(JellyfinClient.primaryImageUrl(session, bare))
        assertNull(JellyfinClient.seriesImageUrl(session, bare))
        assertNull(JellyfinClient.backdropImageUrl(session, bare))
        assertNull("NaN CommunityRating 不是 0 分", bare.communityRating)
    }

    // ---- URL 构造 -------------------------------------------------------------------------

    @Test
    fun whatTheUserTypesBecomesABaseUrl() {
        assertEquals("http://192.168.1.126:8096", JellyfinClient.normalizeBaseUrl(" 192.168.1.126:8096/ "))
        assertEquals("https://jf.example.com", JellyfinClient.normalizeBaseUrl("https://jf.example.com/"))
        // 反代挂在子路径下的安装必须活下来 —— 只修剪斜杠，不修剪路径。
        assertEquals("http://nas/jellyfin", JellyfinClient.normalizeBaseUrl("nas/jellyfin/"))
    }

    @Test
    fun theStreamUrlEncodesTheTokenItCarries() {
        val url = JellyfinClient.streamUrl(session, "abc")
        assertEquals("http://nas:8096/Videos/abc/stream?static=true&api_key=tok+en", url)
    }

    @Test
    fun theAuthHeaderOnlyCarriesATokenOnceThereIsOne() {
        assertTrue(!JellyfinClient.authHeader(null).contains("Token"))
        assertTrue(JellyfinClient.authHeader("t0k").endsWith("""Token="t0k""""))
    }
}
