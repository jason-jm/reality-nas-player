package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.BangumiClient
import com.therealityteam.videoplayer.data.scrape.BangumiClient.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which of bgm.tv's answers is the work. Each list below is what bgm.tv returned, in its order, for
 * the Japanese title AniList gave (2026-09-23); the names are the entry's own romaji/English ones.
 */
class BangumiPickTest {

    private val client = BangumiClient(File(System.getProperty("java.io.tmpdir"), "aura-bgm-test"))

    private fun s(name: String, cn: String?) = Subject(name, cn, summary = null, posterUrl = null, score = null)

    private val adventChildren = listOf(
        s("FINAL FANTASY VII ADVENT CHILDREN", "最终幻想7：降临之子"),
        s("FINAL FANTASY VII ADVENT CHILDREN COMPLETE", "最终幻想7：降临之子 完全版"),
        s("ファイナルファンタジーⅦ アドベントチルドレン 〜On the Way to a Smile〜", "最终幻想7：通向微笑之路 丹泽尔篇"),
        s("LAST ORDER -FINAL FANTASY VII-", "最终幻想7：终极指令"),
        s("FINAL FANTASY", "最终幻想：水晶传说"),
    )

    @Test fun `the subject filed under the work's English name beats one that contains its Japanese one`() {
        val q = "ファイナルファンタジーVII アドベントチルドレン"
        val info = client.pick(q, listOf("Final Fantasy VII: Advent Children"), adventChildren)
        assertEquals("最终幻想7：降临之子", info?.nameCn)
        assertTrue(info!!.namesWork)
        assertEquals("最终幻想7：降临之子", client.nameFor(q, info))
        // Asked by the Japanese title alone, only the short film on the same disc answers to it.
        assertEquals("最终幻想7：通向微笑之路 丹泽尔篇", client.pick(q, emptyList(), adventChildren)?.nameCn)
    }

    @Test fun `a sequel is not named after the series it continues`() {
        val q = "CLANNAD ~After Story~ クラナド アフターストーリー"
        val answer = listOf(
            s("CLANNAD 〜AFTER STORY〜もうひとつの世界 杏編", "CLANNAD ～AFTER STORY～另一个世界 杏篇"),
            s("CLANNAD -クラナド-", "CLANNAD"),
            s("CLANNAD 〜AFTER STORY〜", null),
            s("CLANNAD もうひとつの世界 智代編", "CLANNAD 另一个世界 智代篇"),
            s("劇場版 CLANNAD", "剧场版 CLANNAD"),
        )
        val info = client.pick(q, listOf("CLANNAD: After Story", "Clannad: After Story"), answer)
        assertEquals("CLANNAD 〜AFTER STORY〜", info?.nameJp)
        // bgm.tv has no Chinese name for it, so the card keeps AniList's own title…
        assertNull(client.nameFor(q, info))
        // …where the first subject that corresponded was the first season, whose name it took.
        val before = client.firstCorresponding(q, answer)
        assertEquals("CLANNAD", before?.nameCn)
        assertFalse(client.isBroaderWork(q, before?.name))
    }

    @Test fun `the query itself, spelled exactly, beats the franchise it contains`() {
        // Without the tildes bgm.tv's name is the query's; ゼロの使い魔 came first and was refused as
        // broader, which left the second season with its English title.
        val q = "ゼロの使い魔 ~双月の騎士~"
        val answer = listOf(
            s("ゼロの使い魔 ～三美姫の輪舞～", "零之使魔 三美姬的轮舞"),
            s("ゼロの使い魔 ～三美姫の輪舞～OVA", "零之使魔 三美姬的轮舞 OVA"),
            s("ゼロの使い魔", "零之使魔"),
            s("ゼロの使い魔 双月の騎士", "零之使魔 双月骑士"),
            s("ゼロの使い魔F", "零之使魔F"),
        )
        assertEquals("零之使魔 双月骑士", client.nameFor(q, client.pick(q, emptyList(), answer)))
    }

    @Test fun `a name that is the work's own is never the broader subject`() {
        // AniList's native title carries the reading in brackets; bgm.tv files it as MUNTO, which
        // is shorter than the query and so used to read as the franchise.
        val q = "MUNTO [ムント]"
        val answer = listOf(s("MUNTO", "蒙特"), s("天上人とアクト人最後の戦い", "天上人与亚克托人最后的战斗"))
        assertEquals("蒙特", client.nameFor(q, client.pick(q, listOf("MUNTO"), answer)))
        assertTrue(client.isBroaderWork(q, "MUNTO"))
    }

    @Test fun `a subject with every word of the title, and more between, is the work`() {
        // bgm.tv puts 劇場版 and the chapter between the words AniList's native title has.
        val answer = listOf(
            s("劇場版 空の境界 俯瞰風景3D", "剧场版 空之境界 俯瞰风景3D"),
            s("劇場版 空の境界 第一章 俯瞰風景", "剧场版 空之境界 第一章 俯瞰风景"),
            s("劇場版 空の境界 第四章 伽藍の洞", "剧场版 空之境界 第四章 伽蓝之洞"),
            s("劇場版 空の境界 第三章 痛覚残留", "剧场版 空之境界 第三章 痛觉残留"),
            s("劇場版 空の境界 第二章 殺人考察(前)", "剧场版 空之境界 第二章 杀人考察（前）"),
        )
        // The 3D edition has the letters of 俯瞰風景 but not the word, and used to win by coming first.
        assertEquals("剧场版 空之境界 第一章 俯瞰风景", client.nameFor("空の境界 俯瞰風景", client.pick("空の境界 俯瞰風景", emptyList(), answer)))
        // Nothing but that corresponded to 第二章's title, so the film kept its English name.
        val q = "空の境界 殺人考察(前)"
        assertNull(client.firstCorresponding(q, answer))
        assertEquals("剧场版 空之境界 第二章 杀人考察（前）", client.nameFor(q, client.pick(q, emptyList(), answer)))
    }

    @Test fun `a folder's words are not held to that`() {
        // The folder holds the TV series; bgm.tv spells it without the space, and the second film
        // happens to have every word of the folder's name.
        val q = "魔法少女奈叶 A's"
        val answer = listOf(
            s("魔法少女リリカルなのはA's", "魔法少女奈叶A's"),
            s("魔法少女リリカルなのは The MOVIE 2nd A's", "魔法少女奈叶 The MOVIE 2nd A's"),
        )
        assertEquals("魔法少女リリカルなのはA's", client.pick(q, emptyList(), answer, official = false)?.nameJp)
    }

    @Test fun `no answer that names the work is no answer`() {
        val q = "UFO之夏 伊里野的天空"
        val answer = listOf(s("UFOロボ グレンダイザーVSグレートマジンガー", "UFO机器人古连泰沙对大魔神"))
        assertNull(client.pick(q, emptyList(), answer))
        assertNull(client.pick(q, listOf("Iriya no Sora, UFO no Natsu"), emptyList()))
    }
}
