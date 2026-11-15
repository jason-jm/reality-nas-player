package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.subtitle.CodePage
import com.therealityteam.videoplayer.data.subtitle.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

/**
 * A subtitle in its makers' old code page is read in that page — with no hint at all, and with the reader's
 * language — for every language Aura speaks. Each sample is a few lines of an .srt, as such files are.
 */
class CodePageTest {

    private fun srt(vararg lines: String) = lines.mapIndexed { i, l ->
        "${i + 1}\r\n00:00:${"%02d".format(i * 3)},000 --> 00:00:${"%02d".format(i * 3 + 2)},500\r\n$l\r\n"
    }.joinToString("\r\n")

    private val samples: List<Triple<String, String, String>> = listOf(
        Triple("ru", "windows-1251", srt("Привет, как дела?", "Я не знаю, что сказать.", "Мы должны уйти отсюда прямо сейчас!", "Где ты был всю ночь?", "Это не твоё дело.")),
        Triple("ru", "KOI8-R", srt("Привет, как дела?", "Я не знаю, что сказать.", "Мы должны уйти отсюда прямо сейчас!", "Где ты был всю ночь?", "Это не твоё дело.")),
        Triple("pl", "windows-1250", srt("Cześć, jak się masz?", "Nie wiem, co powiedzieć.", "Musimy stąd natychmiast odejść!", "Gdzie byłeś całą noc?", "To nie twoja sprawa, żółwiu.")),
        Triple("cs", "windows-1250", srt("Ahoj, jak se máš?", "Nevím, co mám říct.", "Musíme odsud hned odejít!", "Kde jsi byl celou noc?", "To není tvoje věc, přítel.")),
        // Old Romanian files write ş ţ with a cedilla: windows-1250 has no comma-below letters.
        Triple("ro", "windows-1250", srt("Bună, ce mai faci?", "Nu ştiu ce să spun.", "Trebuie să plecăm de aici imediat!", "Unde ai fost toată noaptea?", "Nu e treaba ta, băiete. Ţine-te bine.")),
        Triple("tr", "windows-1254", srt("Merhaba, nasılsın?", "Ne söyleyeceğimi bilmiyorum.", "Hemen buradan gitmeliyiz!", "Bütün gece neredeydin?", "Bu seni ilgilendirmez, şimdi git.")),
        Triple("el", "windows-1253", srt("Γεια σου, τι κάνεις;", "Δεν ξέρω τι να πω.", "Πρέπει να φύγουμε από εδώ αμέσως!", "Πού ήσουν όλη τη νύχτα;", "Δεν είναι δική σου δουλειά.")),
        Triple("th", "x-windows-874", srt("สวัสดี คุณสบายดีไหม", "ฉันไม่รู้จะพูดอะไร", "เราต้องออกไปจากที่นี่เดี๋ยวนี้", "เมื่อคืนคุณไปอยู่ที่ไหนมา", "มันไม่ใช่เรื่องของคุณ")),
        Triple("ko", "x-windows-949", srt("안녕하세요, 잘 지냈어요?", "무슨 말을 해야 할지 모르겠어요.", "우리는 지금 당장 여기서 나가야 해요!", "밤새 어디 있었어?", "그건 네가 상관할 일이 아니야.")),
        Triple("ja", "windows-31j", srt("こんにちは、元気ですか？", "何を言えばいいのか分からない。", "今すぐここから出なければならない！", "一晩中どこにいたの？", "それはあなたには関係ない。")),
        Triple("ja", "EUC-JP", srt("こんにちは、元気ですか？", "何を言えばいいのか分からない。", "今すぐここから出なければならない！", "一晩中どこにいたの？", "それはあなたには関係ない。")),
        Triple("zh-Hans", "GBK", srt("你好，最近怎么样？", "我不知道该说什么。", "我们必须马上离开这里！", "你整晚都去哪了？", "这不关你的事。")),
        Triple("zh-Hant", "Big5", srt("你好，最近怎麼樣？", "我不知道該說什麼。", "我們必須馬上離開這裡！", "你整晚都去哪了？", "這不關你的事。")),
        Triple("de", "windows-1252", srt("Hallo, wie geht's dir?", "Ich weiß nicht, was ich sagen soll.", "Wir müssen sofort von hier weg!", "Wo warst du die ganze Nacht?", "Das geht dich nichts an, Jürgen.")),
        Triple("fr", "windows-1252", srt("Salut, ça va ?", "Je ne sais pas quoi dire.", "Nous devons partir d'ici tout de suite !", "Où étais-tu toute la nuit ?", "Ce ne sont pas tes affaires, chérie.")),
        Triple("es", "windows-1252", srt("Hola, ¿cómo estás?", "No sé qué decir.", "¡Tenemos que irnos de aquí ahora mismo!", "¿Dónde estuviste toda la noche?", "No es asunto tuyo, señor.")),
        Triple("sv", "windows-1252", srt("Hej, hur mår du?", "Jag vet inte vad jag ska säga.", "Vi måste härifrån med en gång!", "Var var du hela natten?", "Det angår inte dig, älskling.")),
        Triple("da", "windows-1252", srt("Hej, hvordan går det?", "Jeg ved ikke, hvad jeg skal sige.", "Vi må væk herfra med det samme!", "Hvor var du hele natten, søster?", "Det rager ikke dig. Øjeblik.")),
        Triple("nb", "windows-1252", srt("Hei, hvordan går det?", "Jeg vet ikke hva jeg skal si.", "Vi må komme oss vekk herfra nå!", "Hvor var du hele natten?", "Det angår ikke deg, kjære søster.")),
        Triple("fi", "windows-1252", srt("Hei, mitä kuuluu?", "En tiedä, mitä sanoa.", "Meidän täytyy lähteä täältä heti!", "Missä olit koko yön?", "Se ei kuulu sinulle, ystävä.")),
        Triple("it", "windows-1252", srt("Ciao, come stai?", "Non so cosa dire, però è così.", "Dobbiamo andarcene da qui subito!", "Dov'eri tutta la notte? Di più!", "Non sono affari tuoi, perché sì.")),
        Triple("pt", "windows-1252", srt("Olá, como você está?", "Não sei o que dizer.", "Temos que sair daqui agora mesmo!", "Onde você esteve a noite toda?", "Isso não é da sua conta, irmão.")),
        Triple("de", "windows-1252", srt("Straße, Größe, Fußball.", "Das ist mir völlig gleichgültig.", "Kümmere dich um deine Angelegenheiten!", "Wir sehen uns übermorgen.", "Schön, dass du da bist.")),
    )

    private fun canonical(name: String) = when (name) {
        "x-windows-949" -> setOf("x-windows-949", "MS949", "windows-949", "EUC-KR")
        "x-windows-874" -> setOf("x-windows-874", "windows-874", "TIS-620")
        "windows-31j" -> setOf("windows-31j", "Shift_JIS")
        "GBK" -> setOf("GBK", "GB18030")
        "Big5" -> setOf("Big5", "Big5-HKSCS")
        else -> setOf(name)
    }

    private fun check(language: String) {
        val wrong = samples.mapNotNull { (lang, page, text) ->
            val bytes = text.toByteArray(Charset.forName(page))
            val got = CodePage.detect(bytes, if (language == "own") lang else language)
            // Read back as the original: two pages that agree on these bytes (GBK/GB18030) are one answer.
            if (String(bytes, got) == text || got.name() in canonical(page) || got.aliases().any { it in canonical(page) }) null
            else "$lang/$page → ${got.name()}: ${String(bytes, got).lines().getOrNull(2)}"
        }
        assertEquals("hint '$language'", emptyList<String>(), wrong)
    }

    @Test fun `every language's old code page, with no hint`() = check("xx")

    @Test fun `every language's old code page, with the reader's own language`() = check("own")

    @Test fun `every language's old code page, read by an English reader`() = check("en")

    @Test fun `a few CJK characters are still CJK, not Cyrillic`() {
        // An .nfo's <title>云图</title> is four bytes: KOI8-R read them as 「тфм╪」 when Cyrillic letters counted double.
        // Two characters read as validly in Big5 as in GBK (雲圖 is 冻瓜 there): the reader's language decides those.
        for ((text, page, hint) in listOf(Triple("云图", "GBK", "xx"), Triple("中文字幕", "GBK", "xx"), Triple("雲圖", "Big5", "zh-Hant"),
            Triple("字幕組", "Big5", "zh-Hant"), Triple("字幕", "windows-31j", "ja"))) {
            val got = CodePage.detect(text.toByteArray(Charset.forName(page)), hint)
            assertEquals("$text in $page", text, String(text.toByteArray(Charset.forName(page)), got))
        }
    }

    @Test fun `utf-8 stays utf-8`() {
        val text = srt("Привет", "Cześć", "你好", "สวัสดี")
        assertEquals(Charsets.UTF_8, SubtitleParser.detectCharset(text.toByteArray(Charsets.UTF_8)))
    }
}
