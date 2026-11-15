package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.Synopsis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A synopsis as its reader reads it. */
class SynopsisTest {

    @Test fun `bgm tv's source text after the translation is cut off`() {
        val text = "34岁，没工作又足不出户的男人，某天被家人赶出家门时车祸身亡…… [简介原文] 34歳・無職・引きこもりの男は、ある日家族から家を追い出された際に交通事故に遭い死亡"
        assertEquals("34岁，没工作又足不出户的男人，某天被家人赶出家门时车祸身亡……", Synopsis.forReader(text, "zh-CN"))
        assertEquals("故事。", Synopsis.forReader("故事。【简介原文】物語。", "en"))
    }

    @Test fun `ideographic paragraph indents are paragraph breaks, not gaps in the text`() {
        val text = "\u3000\u3000本片根据韩国特工朴采书的事迹改编。\u3000\u300020世纪90年代中期，朝鲜对外宣传已经拥有了核武器。 \u3000\u3000经过数年的经营。"
        assertEquals("本片根据韩国特工朴采书的事迹改编。\n20世纪90年代中期，朝鲜对外宣传已经拥有了核武器。\n经过数年的经营。", Synopsis.forReader(text, "zh-CN"))
        assertEquals("第一段。\n第二段。", Synopsis.forReader("第一段。\n\u3000\u3000第二段。", "zh-CN"))
        // One ideographic space is a space a writer chose: kept.
        assertEquals("《甲》\u3000《乙》", Synopsis.forReader("《甲》\u3000《乙》", "zh-CN"))
    }

    @Test fun `a Japanese synopsis is none for a Chinese reader, and kept for others`() {
        val ja = "転入先の高校で野外活動サークル、略して野クルに入会したなでしこ。サークルと言っても、会員は大垣千明と犬山あおいの二人だけ。"
        assertNull(Synopsis.forReader(ja, "zh-CN"))
        assertEquals(ja, Synopsis.forReader(ja, "ja"))
        assertEquals(ja, Synopsis.forReader(ja, "en"))
    }

    @Test fun `Chinese with a Japanese name in it stays`() {
        val zh = "从静冈搬家到山梨的女高中生·抚子，在本栖湖遇见了爱好露营的女孩子·凛。片尾曲「ふゆびより」。"
        assertEquals(zh, Synopsis.forReader(zh, "zh"))
        assertNull(Synopsis.forReader("   ", "zh"))
        assertNull(Synopsis.forReader(null, "zh"))
    }

    @Test fun `AniList's character references read as the characters`() {
        assertEquals(
            "a new weapon in the war on crime – advanced robots called Labors",
            Synopsis.forReader("a new weapon in the war on crime &ndash; advanced robots called Labors", "en"),
        )
        assertEquals("Q&A — “R.O.D” 'it's'", Synopsis.unescape("Q&amp;A &mdash; &ldquo;R.O.D&rdquo; &#39;it&#x27;s&#039;"))
        assertEquals("AT&T &bogus; & so", Synopsis.unescape("AT&T &bogus; & so"))
    }
}
