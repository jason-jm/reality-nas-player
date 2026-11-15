package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.scrape.BangumiClient
import com.therealityteam.videoplayer.data.scrape.EpisodeInfo
import com.therealityteam.videoplayer.data.scrape.EpisodeNames
import com.therealityteam.videoplayer.data.scrape.MetaResult
import com.therealityteam.videoplayer.data.scrape.MetadataClient
import com.therealityteam.videoplayer.data.scrape.ParsedName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** An episode as the database tells it: a name only where it has one, and its still from the same season. */
class EpisodeInfoTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the number a database fills in for an untitled episode is no name`() {
        listOf("第 3 集", "第3集", "第三话", "第１２話", "第 12 回", "Episode 3", "EP 03", "Ep.3", "エピソード3", "3").forEach {
            assertTrue(it, EpisodeNames.isPlaceholder(it))
        }
        listOf("曼达洛人", "孩子", "Chapter 1: The Mandalorian", "第一章", "Episode One", "3 Days Later").forEach {
            assertFalse(it, EpisodeNames.isPlaceholder(it))
        }
    }

    @Test fun `an episode's still comes from its season as the database tells it`() = runBlocking {
        val client = object : MetadataClient {
            override val name = "fake"
            override val isConfigured = true
            override suspend fun search(parsed: ParsedName): List<MetaResult> = emptyList()
            override suspend fun details(providerId: Int, kind: MediaKind): MetaResult? = null
            override suspend fun episodes(tvId: Int, season: Int): Map<Int, EpisodeInfo> =
                if (tvId == 82856 && season == 1) mapOf(2 to EpisodeInfo("孩子", "成功追踪到宝贵的目标之后……", "https://img/w300/child.jpg")) else emptyMap()
        }
        assertEquals("https://img/w300/child.jpg", client.episodeStill(82856, 1, 2))
        assertNull(client.episodeStill(82856, 1, 9))
        assertNull(client.episodeStill(82856, 2, 2))
    }

    @Test fun `a description of who made the episode is not what happens in it`() {
        assertTrue(EpisodeNames.isCredits("脚本：志茂文彦  分镜：石原立也  演出：石原立也  作画监督：池田和美"))
        assertTrue(EpisodeNames.isCredits("脚本:吉田玲子 絵コンテ:山田尚子"))
        assertFalse(EpisodeNames.isCredits("朋也在樱花飞舞的坡道上遇见了渚。"))
        assertFalse(EpisodeNames.isCredits("演出家的梦想：一位年轻导演回到故乡。"))
    }

    @Test fun `bgm tv names an anime's episodes by their number in the subject, in Chinese where it can`() {
        val body = """{"data":[
            {"ep":1,"sort":1,"name":"桜舞い散る坂道で","name_cn":"在那落樱缤纷的坡道上","desc":"脚本：志茂文彦  分镜：石原立也  演出：石原立也"},
            {"ep":2,"sort":2,"name":"最初の一歩","name_cn":"","desc":"朋也决定帮助渚。"},
            {"ep":3,"sort":25,"name":"第3話","name_cn":"","desc":""},
            {"ep":0,"sort":4.5,"name":"総集編","name_cn":"总集篇","desc":""}
        ]}"""
        val episodes = BangumiClient(tmp.root).episodesOf(body)
        assertEquals(EpisodeInfo("在那落樱缤纷的坡道上", null, null), episodes[1])
        assertEquals(EpisodeInfo("最初の一歩", "朋也决定帮助渚。", null), episodes[2])
        // A placeholder name and no synopsis is nothing to tell; a half number is no episode's.
        assertNull(episodes[3])
        assertEquals(setOf(1, 2), episodes.keys)
    }

    @Test fun `a show bgm tv never returns for its native title is found by its other name, exactly`() = runBlocking {
        // bgm.tv's answers as they came back, cached as the client caches them: for the native title
        // the show's sequels and its film, never the show; for "CLANNAD" the show, first.
        fun subject(id: Int, name: String, cn: String) =
            org.json.JSONObject().put("id", id).put("name", name).put("nameCn", cn).put("summary", org.json.JSONObject.NULL)
                .put("posterUrl", org.json.JSONObject.NULL).put("score", org.json.JSONObject.NULL)
        val native = org.json.JSONArray().put(subject(876, "CLANNAD 〜AFTER STORY〜", "")).put(subject(3686, "劇場版 CLANNAD", "剧场版 CLANNAD"))
        val other = org.json.JSONArray().put(subject(51, "CLANNAD -クラナド-", "CLANNAD")).put(subject(876, "CLANNAD 〜AFTER STORY〜", ""))
        val now = System.currentTimeMillis()
        java.io.File(tmp.root, "aura/cache").mkdirs()
        java.io.File(tmp.root, "aura/cache/bangumi-search.json").writeText(
            org.json.JSONObject()
                .put("clannad -クラナド-", org.json.JSONObject().put("v", native.toString()).put("t", now))
                .put("clannad", org.json.JSONObject().put("v", other.toString()).put("t", now))
                .put("clannad story", org.json.JSONObject().put("v", org.json.JSONArray().put(subject(876, "CLANNAD 〜AFTER STORY〜", "")).toString()).put("t", now))
                .toString(),
        )
        val bangumi = BangumiClient(tmp.root)
        assertEquals(51, bangumi.subjectId("ＣＬＡＮＮＡＤ -クラナド-", listOf("Clannad", "CLANNAD")))
        // A subject that merely holds another name's words is no answer: AFTER STORY is not "Clannad Story".
        assertNull(bangumi.subjectId("ＣＬＡＮＮＡＤ -クラナド-", listOf("Clannad Story")))
    }
}
