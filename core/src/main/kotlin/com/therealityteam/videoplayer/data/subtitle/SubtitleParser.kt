package com.therealityteam.videoplayer.data.subtitle

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Parses SRT and ASS/SSA subtitles with charset auto-detection (handles non-UTF-8 files,
 * e.g. GBK — a common source of "garbled subtitle" complaints).
 */
object SubtitleParser {

    private val SRT_TIME = Regex(
        "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})",
    )
    private val ASS_TIME = Regex("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})")
    private val TAGS = Regex("<[^>]+>|\\{[^}]*\\}")

    fun parse(bytes: ByteArray, fileName: String): SubtitleTrack {
        val text = decode(bytes)
        val lower = fileName.lowercase()
        return if (lower.endsWith(".ass") || lower.endsWith(".ssa")) parseAss(text) else parseSrt(text)
    }

    fun parseSrt(raw: String): SubtitleTrack {
        val text = raw.replace("\r\n", "\n").replace("\r", "\n")
        val cues = ArrayList<SubtitleCue>()
        for (block in text.split(Regex("\n[ \t]*\n"))) {
            val lines = block.trim().split("\n")
            val tIdx = lines.indexOfFirst { it.contains("-->") }
            if (tIdx < 0) continue
            val m = SRT_TIME.find(lines[tIdx]) ?: continue
            val start = hmsToMs(m.groupValues, 1)
            val end = hmsToMs(m.groupValues, 5)
            val body = stripTags(lines.drop(tIdx + 1).joinToString("\n")).trim()
            if (body.isNotEmpty()) cues.add(SubtitleCue(start, end, body))
        }
        return SubtitleTrack(cues.sortedBy { it.startMs }, "SRT")
    }

    fun parseAss(raw: String): SubtitleTrack {
        val lines = raw.replace("\r", "").split("\n")
        var format: List<String>? = null
        val cues = ArrayList<SubtitleCue>()
        for (line in lines) {
            val l = line.trim()
            if (format == null && l.startsWith("Format:", true) && l.contains("Start", true)) {
                format = l.removePrefix("Format:").removePrefix("format:").split(",").map { it.trim() }
            } else if (l.startsWith("Dialogue:", true)) {
                val fields = l.substringAfter(':').split(",")
                val startIdx = format?.indexOfFirst { it.equals("Start", true) }?.takeIf { it >= 0 } ?: 1
                val endIdx = format?.indexOfFirst { it.equals("End", true) }?.takeIf { it >= 0 } ?: 2
                val textIdx = format?.indexOfFirst { it.equals("Text", true) }?.takeIf { it >= 0 } ?: 9
                if (fields.size <= maxOf(startIdx, endIdx, textIdx)) continue
                val sm = ASS_TIME.find(fields[startIdx]) ?: continue
                val em = ASS_TIME.find(fields[endIdx]) ?: continue
                val body = stripTags(fields.drop(textIdx).joinToString(","))
                    .replace("\\N", "\n").replace("\\n", "\n").trim()
                if (body.isNotEmpty()) cues.add(SubtitleCue(assToMs(sm.groupValues), assToMs(em.groupValues), body))
            }
        }
        return SubtitleTrack(cues.sortedBy { it.startMs }, "ASS")
    }

    fun detectCharset(bytes: ByteArray): Charset = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            StandardCharsets.UTF_8
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> StandardCharsets.UTF_16LE
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> StandardCharsets.UTF_16BE
        isValidUtf8(bytes) -> StandardCharsets.UTF_8
        else -> CodePage.detect(bytes)
    }

    /** The hanzi met most in film dialogue, in simplified and traditional forms (and so the commonest kanji). */
    internal val COMMON_HANZI: Set<Char> = (
        "的一是不了人我在有他这中大来上个国到说们为子和你地出道也时年得就那要下以生会自着去之过家学对可她里后小么心多天" +
            "而能好都然没日于起还发成事只作当想看文无开手十用主行方又如前所本见经头面公同三已老从动两长知民样现分将外但身些" +
            "与高意进把法此实回二理美点月明其种声全工己话儿者向情部正名定女问力机给等几很业最间新什打便位因重被走电四第门相" +
            "次东海口使教西再平真听世气信北少关并内加化由却代军产入先山五太水万市眼体别处总才场师书比住员九笑性通目报立马命" +
            "张活难神数件安表原车白应路期叫死常提感金何更反合放做系计或利受光王果亲界及今京务制解各任至清物台象记边共风战干" +
            "接它许八特觉望直服林题建南度色字请交爱让认算论百吃义怎元结六功指思非流每青管夫连远队跟带花快条变联言往该领传近" +
            "留红决周保达办运半候七必城父强步完深即求转量空众轻程告语基满式写呢识极令黄收脸钱倒未持取设始双越千片容像找友孩" +
            "站改形早房音火则首单据导影失拿网似专若兵弟谁校读飞观争包落视喜离虽坐集谈拉黑且随格尽讲杀微怕母调根曾准团终乐切" +
            "级冷哪官吗吧呀啊嗯哦哈喂谢对不起没有知道什么怎么这样那样" +
            "這個們來為國說時會對過後裡經麼學現當沒動樣開從實點話兒還應邊總別處體師書員報馬張難數車間題頭見長與發電門東將" +
            "給問種氣聲聽產軍聯關嗎讓認論義歡請萬業廣條強準傳無愛親樂歲記帶錢戰華結較區視網飛專語讀殺謝誰設許該這們"
        ).toSet()

    private fun decode(bytes: ByteArray): String = String(bytes, detectCharset(bytes))

    private fun isValidUtf8(bytes: ByteArray): Boolean = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
        true
    }.getOrDefault(false)

    private fun stripTags(s: String): String = s.replace(TAGS, "")

    private fun hmsToMs(g: List<String>, i: Int): Long {
        val h = g[i].toLong()
        val m = g[i + 1].toLong()
        val s = g[i + 2].toLong()
        val ms = g[i + 3].padEnd(3, '0').take(3).toLong()
        return (h * 3600 + m * 60 + s) * 1000 + ms
    }

    private fun assToMs(g: List<String>): Long {
        val h = g[1].toLong()
        val m = g[2].toLong()
        val s = g[3].toLong()
        val cs = g[4].toLong()
        return (h * 3600 + m * 60 + s) * 1000 + cs * 10
    }
}
