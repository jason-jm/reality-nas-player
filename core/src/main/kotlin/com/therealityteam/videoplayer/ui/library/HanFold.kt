package com.therealityteam.videoplayer.ui.library

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Han characters as one script, for search: 宮崎駿 and 宫崎骏, 進撃 and 进击, 新海誠 and 新海诚 are one
 * name. A library is named in three — AniList's Japanese, TMDB's simplified Chinese, a Taiwanese
 * release's traditional — and its reader types in one of them: 「宫崎骏」 found nothing credited
 * 宮崎駿. Both sides of a search are folded alike, the names and the query, so a fold can only join
 * forms, never part a name from itself.
 *
 * Traditional forms go by the platform's ICU (`Hant-Hans`, on the headset and the desktop alike); the
 * Japanese forms it does not know — 撃, 錬, 沢, 戦, 桜… — by [SHINJITAI] first. Without a platform
 * transliterator (JVM tests), the table alone. Memoised.
 */
object HanFold {

    private val cache = ConcurrentHashMap<String, String>()
    private val HAN = Regex("""\p{IsHan}""")

    private val hantHans by lazy { runCatching { Romanizer.transliterators("Hant-Hans") }.getOrNull() }

    /** Japanese forms that are neither the traditional nor the simplified one, as the simplified. */
    private val SHINJITAI: Map<Char, Char> = buildMap {
        val pairs = "亜亚悪恶圧压囲围為为壱壹隠隐栄荣営营衛卫駅驿円圆塩盐縁缘応应桜樱穏稳価价絵绘拡扩殻壳覚觉楽乐渇渴" +
            "巻卷陥陷勧劝寛宽関关歓欢観观気气帰归亀龟偽伪戯戏犠牺拠据挙举暁晓駆驱勲勋掲揭渓溪経经蛍萤軽轻継继鶏鸡撃击" +
            "倹俭剣剑圏圈険险検检権权県县顕显験验厳严広广鉱矿黒黑済济砕碎斎斋剤剂雑杂桟栈賛赞糸丝歯齿児儿実实釈释収收" +
            "従从渋涩獣兽縦纵粛肃処处緒绪奨奖焼烧証证乗乘浄净剰剩畳叠譲让醸酿図图粋粹酔醉穂穗髄髓瀬濑斉齐摂摄専专戦战" +
            "銭钱繊纤荘庄捜搜挿插巣巢総总騒骚増增蔵藏続续対对帯带滝泷択择沢泽単单嘆叹団团弾弹遅迟鋳铸庁厅徴征聴听鎮镇" +
            "逓递鉄铁転转伝传稲稻闘斗徳德読读縄绳弐贰悩恼脳脑廃废拝拜売卖発发髪发抜拔浜滨併并辺边変变歩步豊丰毎每満满" +
            "黙默訳译薬药揺摇様样謡谣頼赖覧览竜龙両两猟猎緑绿塁垒涙泪隷隶霊灵齢龄暦历歴历労劳録录姫姬錬炼蝋蜡鋭锐鉛铅" +
            "鋼钢戻戾恵惠層层贈赠仏佛冊册効效仮假"
        for (i in pairs.indices step 2) put(pairs[i], pairs[i + 1])
    }

    /** How much time folding has taken, for the AuraPerf log beside the romanising. */
    val spentNanos = AtomicLong(0)

    /** The texts folded so far — for TextMemo to keep across launches. */
    fun soFar(): Map<String, String> = HashMap(cache)

    /** Answers from an earlier launch (TextMemo), taken as given. */
    fun prime(answers: Map<String, String>) = cache.putAll(answers)

    /** [text] with its Han characters in their simplified forms; text with none comes back as it is. */
    fun fold(text: String): String {
        if (!HAN.containsMatchIn(text)) return text
        cache[text]?.let { return it }
        val t0 = System.nanoTime()
        val table = buildString(text.length) { text.forEach { c -> append(SHINJITAI[c] ?: c) } }
        val out = hantHans?.let { t -> runCatching { t(table) }.getOrNull() } ?: table
        spentNanos.addAndGet(System.nanoTime() - t0)
        if (cache.size > 32_768) cache.clear()
        cache[text] = out
        return out
    }
}
