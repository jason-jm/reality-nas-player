package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.TrackPreference.Lang
import com.therealityteam.videoplayer.data.subtitle.SubtitleLanguage
import com.therealityteam.videoplayer.data.subtitle.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A subtitle file's languages, read from its tag or — when its name says nothing — from its lines. */
class SubtitleLanguageTest {

    /** 1917's release subtitle: a Chinese line over an English one in every cue, the ASS way. */
    private val bilingualAss = """
        [Script Info]
        ScriptType: v4.00+

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:51:02.10,0:51:04.00,Default,,0,0,0,,欢迎乘坐鬼知道去哪夜间大巴\N{\fs14}Welcome aboard the night bus to a fuck- knows- where.
        Dialogue: 0,0:51:05.10,0:51:06.80,Default,,0,0,0,,你们这是要去哪儿？\N{\fs14}Where are you going?
        Dialogue: 0,0:51:07.10,0:51:08.80,Default,,0,0,0,,我们得赶上第二营\N{\fs14}We have to catch up with the Second Battalion.
        Dialogue: 0,0:51:09.10,0:51:10.80,Default,,0,0,0,,上车吧，挤一挤\N{\fs14}Get in, it might be a tight squeeze.
        Dialogue: 0,0:51:11.10,0:51:12.80,Default,,0,0,0,,谢谢你，长官\N{\fs14}Thank you, sir.
        Dialogue: 0,0:51:13.10,0:51:14.80,Default,,0,0,0,,你从哪儿来的？\N{\fs14}Where did you come from?
        Dialogue: 0,0:51:15.10,0:51:16.80,Default,,0,0,0,,说来话长\N{\fs14}It's a long story.
        Dialogue: 0,0:51:17.10,0:51:18.80,Default,,0,0,0,,那就别说了\N{\fs14}Then don't tell it.
        Dialogue: 0,0:51:19.10,0:51:20.80,Default,,0,0,0,,他们会来接我们吗？\N{\fs14}Will they come for us?
    """.trimIndent()

    private fun srt(vararg lines: String) = lines.mapIndexed { i, l -> "${i + 1}\n00:00:0$i,000 --> 00:00:0$i,900\n$l\n" }
        .joinToString("\n")

    private fun langsOf(text: String, name: String) =
        SubtitleLanguage.of(SubtitleParser.parse(text.toByteArray(), name).cues.map { it.text })

    @Test fun `a Chinese and English release subtitle is read as both, Chinese first`() {
        val langs = langsOf(bilingualAss, "1917.2019.1080p.Bluray.Atmos.TrueHD.7.1.x264-EVO.ass")
        assertEquals(listOf(Lang.SIMPLIFIED, Lang.ENGLISH), langs)
        assertEquals("chs&eng", SubtitleLanguage.tag(langs))
        // The engine matches the reader's Chinese against the main language's code.
        assertEquals("zh-Hans", SubtitleLanguage.code(langs))
    }

    @Test fun `traditional characters make a traditional subtitle`() {
        val text = srt(
            "你們這是要去哪裡？", "我們得趕上第二營", "上車吧，擠一擠", "謝謝你，長官", "說來話長",
            "那就別說了", "他們會來接我們嗎？", "現在幾點了？", "這個問題很難",
        )
        assertEquals(listOf(Lang.TRADITIONAL), langsOf(text, "x.srt"))
        assertEquals("zh-Hant", SubtitleLanguage.code(langsOf(text, "x.srt")))
    }

    @Test fun `English alone, and Japanese by its kana`() {
        val english = srt(
            "Where are you going?", "We have to catch up.", "Get in, it's a tight squeeze.", "Thank you, sir.",
            "It's a long story.", "Then don't tell it.", "Will they come for us?", "What time is it now?",
        )
        assertEquals(listOf(Lang.ENGLISH), langsOf(english, "x.srt"))
        val japanese = srt(
            "どこへ行くんですか？", "第二大隊に追いつかないと", "乗れ、狭いぞ", "ありがとうございます",
            "話せば長くなる", "じゃあ話すな", "迎えに来てくれるかな", "今、何時ですか？",
        )
        assertEquals(listOf(Lang.JAPANESE), langsOf(japanese, "x.srt"))
    }

    @Test fun `a Chinese and Japanese fansub is both, Chinese first — Japanese alone stays Japanese`() {
        // Kamigami's Fate/Zero: the Chinese line over the Japanese one in every cue.
        val bilingual = srt(
            "奏着凯歌离去的话 那就是征服王的掠夺了\n凱歌とともに立ち去るのならば それは征服王の略奪だ",
            "你说什么\n何だと",
            "这是圣杯战争\nこれは聖杯戦争だ",
            "我们走吧 少年\n行くぞ 坊主",
            "那个从者到底是谁\nあのサーヴァントは一体何者なんだ",
            "别开玩笑了\n冗談じゃない",
            "来吧 Rider\nさあ ライダー",
            "这里是冬木市\nここは冬木市",
            "国王的宴会开始了\n王の宴が始まる",
        )
        val langs = langsOf(bilingual, "[Kamigami] Fate Zero - 02.srt")
        assertEquals(listOf(Lang.SIMPLIFIED, Lang.JAPANESE), langs)
        assertEquals("zh-Hans", SubtitleLanguage.code(langs))
        // Japanese with a few lines of names in kanji alone: Japanese only.
        val japanese = srt(
            "どこへ行くんですか？", "衛宮切嗣", "乗れ、狭いぞ", "ありがとうございます", "遠坂時臣",
            "じゃあ話すな", "迎えに来てくれるかな", "今、何時ですか？", "聖杯は俺のものだ", "行くぞ",
        )
        assertEquals(listOf(Lang.JAPANESE), langsOf(japanese, "x.srt"))
    }

    @Test fun `too few lines, or a name in Latin letters among Chinese, decide nothing wrong`() {
        assertEquals(emptyList<Lang>(), langsOf(srt("Hello there.", "你好"), "x.srt"))
        // Chinese lines that name a place or a brand now and then are still Chinese only.
        val text = srt(
            "我们去 London 吧", "这是 BBC 的新闻", "你说什么？", "他们来了", "快走！", "我知道了",
            "别担心", "没问题", "OK", "谢谢",
        )
        assertEquals(listOf(Lang.SIMPLIFIED), langsOf(text, "x.srt"))
    }

    @Test fun `a file read only in part is cut at a whole line, so its UTF-8 stays UTF-8`() {
        val bytes = bilingualAss.toByteArray(Charsets.UTF_8)
        // Cut inside a Chinese character — 说 of 说来话长, three bytes in UTF-8 — one byte past its start.
        val cut = bilingualAss.substring(0, bilingualAss.indexOf("说来话长")).toByteArray(Charsets.UTF_8).size + 1
        val head = bytes.copyOf(cut)
        val whole = SubtitleLanguage.wholeLines(head)
        assertEquals('\n'.code.toByte(), whole.last())
        assertEquals(listOf(Lang.SIMPLIFIED, Lang.ENGLISH), SubtitleLanguage.ofHead(head, "x.ass", complete = false))
    }

    @Test fun `a name tag is read the way the preference reads it`() {
        assertEquals(listOf(Lang.SIMPLIFIED, Lang.ENGLISH), SubtitleLanguage.ofTag("chs&eng"))
        assertEquals(listOf(Lang.TRADITIONAL), SubtitleLanguage.ofTag("cht"))
        assertEquals("zh-Hans", SubtitleLanguage.code(SubtitleLanguage.ofTag("sc")))
        assertEquals(emptyList<Lang>(), SubtitleLanguage.ofTag("forced"))
        assertNull(SubtitleLanguage.tag(emptyList()))
    }
}
