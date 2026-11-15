package com.therealityteam.videoplayer.desktop.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.therealityteam.videoplayer.util.Log
import org.w3c.dom.Element
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's words in the reader's language.
 *
 * Two sets of Android-format `strings.xml` files: the headset's own (copied in at build time, so
 * the two apps say the same thing in the same words) and the desktop's, which adds what only a
 * desktop has and rewords what reads wrong on one ("tap" is "click" here). The desktop's win.
 *
 * Languages: the headset's — English (the fallback for every key), Simplified and Traditional Chinese
 * and twenty more (FOLDERS). Chinese in Taiwan, Hong Kong or Macau — or any tag naming the Hant
 * script — reads Traditional; any Norwegian reads Bokmål; Portugal reads the Brazilian Portuguese.
 */
class Strings private constructor(
    val locale: Locale,
    private val table: Map<String, String>,
    private val plurals: Map<String, Map<String, String>>,
) {
    operator fun get(key: String, vararg args: Any?): String {
        val raw = table[key] ?: return key.also { missing(it) }
        return if (args.isEmpty()) raw else runCatching { String.format(locale, raw, *args) }.getOrDefault(raw)
    }

    /** A counted phrase ("3 episodes"): the language's plural form for [count], [count] as its first argument. */
    fun plural(key: String, count: Int, vararg args: Any?): String {
        val forms = plurals[key] ?: return "$count".also { missing(key) }
        val raw = forms[quantity(locale.language, count)] ?: forms["other"] ?: forms.values.first()
        val all = arrayOf<Any?>(count, *args)
        return runCatching { String.format(locale, raw, *all) }.getOrDefault(raw)
    }

    fun has(key: String): Boolean = key in table

    companion object {
        private val loaded = ConcurrentHashMap<String, Strings>()

        /** [tag] is a BCP 47 tag, or "" for the system's language. */
        fun forLanguage(tag: String): Strings {
            val locale = if (tag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(tag)
            val folder = folderFor(locale)
            return loaded.getOrPut(folder) { load(locale, folder) }
        }

        /** The languages with a strings folder of their own, beside English's values/ and Chinese's two. */
        private val FOLDERS = setOf("ja", "ko", "de", "fr", "es", "it", "pt", "ru", "nl", "pl", "sv", "da", "nb", "fi", "cs", "el", "ro", "tr", "th", "ms")

        /** Which strings folder a locale reads: values, values-zh, values-b+zh+Hant, or values-<language>. */
        fun folderFor(locale: Locale): String {
            val lang = locale.language.let { if (it == "no" || it == "nn") "nb" else it }
            if (lang != "zh") return if (lang in FOLDERS) "values-$lang" else "values"
            val hant = locale.script.equals("Hant", ignoreCase = true) ||
                (locale.script.isEmpty() && locale.country.uppercase() in setOf("TW", "HK", "MO"))
            return if (hant) "values-b+zh+Hant" else "values-zh"
        }

        /**
         * The plural category CLDR gives a whole number [n] in [language] — what Android's getQuantityString picks
         * on the headset. English and German say one/other; French and Portuguese count 0 as one; Romanian has
         * few (0, 2–19, …02–…19); Russian and Polish few/many by the last digits; Czech few for 2–4.
         */
        fun quantity(language: String, n: Int): String {
            val m10 = n % 10
            val m100 = n % 100
            return when (language) {
                "ja", "ko", "zh", "th", "ms" -> "other"
                "fr", "pt" -> if (n == 0 || n == 1) "one" else if (n != 0 && n % 1_000_000 == 0) "many" else "other"
                "es", "it" -> if (n == 1) "one" else if (n != 0 && n % 1_000_000 == 0) "many" else "other"
                "ro" -> if (n == 1) "one" else if (n == 0 || m100 in 2..19) "few" else "other"
                "ru" -> when {
                    m10 == 1 && m100 != 11 -> "one"
                    m10 in 2..4 && m100 !in 12..14 -> "few"
                    else -> "many"
                }
                "pl" -> when {
                    n == 1 -> "one"
                    m10 in 2..4 && m100 !in 12..14 -> "few"
                    else -> "many"
                }
                "cs" -> if (n == 1) "one" else if (n in 2..4) "few" else "other"
                else -> if (n == 1) "one" else "other"
            }
        }

        private fun load(locale: Locale, folder: String): Strings {
            val table = HashMap<String, String>()
            val plurals = HashMap<String, Map<String, String>>()
            // English first, then the language's own: a key missing in translation still reads.
            val layers = buildList {
                add("headset/values"); add("desktop/values")
                if (folder != "values") { add("headset/$folder"); add("desktop/$folder") }
            }
            layers.forEach { parse("/i18n/$it/strings.xml", table, plurals) }
            return Strings(locale, table, plurals)
        }

        private fun parse(resource: String, table: MutableMap<String, String>, plurals: MutableMap<String, Map<String, String>>) {
            val stream = Strings::class.java.getResourceAsStream(resource) ?: return
            runCatching {
                val doc = stream.use {
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
                }
                val strings = doc.getElementsByTagName("string")
                for (i in 0 until strings.length) {
                    val e = strings.item(i) as Element
                    table[e.getAttribute("name")] = unescape(e.textContent)
                }
                val ps = doc.getElementsByTagName("plurals")
                for (i in 0 until ps.length) {
                    val p = ps.item(i) as Element
                    val items = p.getElementsByTagName("item")
                    plurals[p.getAttribute("name")] = (0 until items.length).associate { j ->
                        val item = items.item(j) as Element
                        item.getAttribute("quantity") to unescape(item.textContent)
                    }
                }
            }.onFailure { Log.w("AuraStrings", "could not read $resource", it) }
        }

        /** Android resource escaping: \' \" \n \t \\ \@ \?, and a value wrapped whole in quotes. */
        internal fun unescape(raw: String): String {
            var s = raw
            if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s = s.substring(1, s.length - 1)
            if ('\\' !in s) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        else -> out.append(n)
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        private val reported = ConcurrentHashMap.newKeySet<String>()
        private fun missing(key: String) {
            if (reported.add(key)) Log.w("AuraStrings", "no string '$key'")
        }
    }
}

/** The words of the window being drawn. */
val LocalStrings = staticCompositionLocalOf { Strings.forLanguage("") }

/** A string of the current language, by the headset's resource name. */
@Composable
fun tr(key: String, vararg args: Any?): String = LocalStrings.current.get(key, *args)

@Composable
fun trPlural(key: String, count: Int, vararg args: Any?): String = LocalStrings.current.plural(key, count, *args)
