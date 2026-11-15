package com.therealityteam.videoplayer.data.subtitle

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * A fansub's fonts beside its episodes — `[Airota&Nekomoe kissaten&VCB-Studio] Yuru Camp Season 2 [Fonts].7z`,
 * a Fonts/ folder, or the font files loose in the folder — which its .ass subtitles are typeset in. Without
 * them libass draws every line in the system's face (on the B3110: 汉仪正圆, DFPHannotate-W7 and 方正兰亭中黑
 * all came out as PICO Sans).
 */
object FontPacks {

    enum class Kind { FOLDER, SEVEN_ZIP, ZIP, LOOSE }

    /** A pack beside a video: [name] in its folder ("Fonts/", "X [Fonts].7z"; empty for fonts loose in the folder itself). */
    data class Pack(val name: String, val kind: Kind)

    private val FOLDERS = setOf("fonts", "font", "字体", "字體", "fonts & tools")
    private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

    private val EXTRAS = setOf("sps", "sp", "extras", "extra", "bonus", "menu", "menus", "特典", "映像特典", "ncop", "nced", "pv", "cm", "cds", "scans")

    /** A release's folder of specials (SPs, Extras, 特典…), whose fonts are with the episodes a folder up. */
    fun isExtrasFolder(name: String): Boolean = name.trim().lowercase() in EXTRAS

    fun isFontFile(name: String): Boolean = name.substringAfterLast('/').substringAfterLast('.', "").lowercase() in FONT_EXTENSIONS

    /** The font packs among the names in a video's folder (folders end in "/"): folders first, then archives, then loose fonts. */
    fun packsIn(names: List<String>): List<Pack> {
        val folders = names.filter { it.endsWith("/") && it.trimEnd('/').lowercase() in FOLDERS }.map { Pack(it.trimEnd('/'), Kind.FOLDER) }
        val archives = names.filter { !it.endsWith("/") }.mapNotNull { n ->
            val l = n.lowercase()
            val named = "font" in l || "字体" in l || "字體" in l
            when {
                !named -> null
                l.endsWith(".7z") -> Pack(n, Kind.SEVEN_ZIP)
                l.endsWith(".zip") -> Pack(n, Kind.ZIP)
                else -> null
            }
        }
        val loose = if (names.any { !it.endsWith("/") && isFontFile(it) }) listOf(Pack("", Kind.LOOSE)) else emptyList()
        return folders + archives + loose
    }
}

/** The names a font answers to — its families, full names and PostScript name — from its `name` table (a .ttc's every font). */
object FontNames {

    fun of(b: ByteArray): Set<String> {
        if (b.size < 12) return emptySet()
        if (String(b, 0, 4, Charsets.ISO_8859_1) == "ttcf") {
            val n = u32(b, 8).toInt().coerceIn(0, 64)
            return (0 until n).flatMap { i -> if (12 + 4 * i + 4 <= b.size) one(b, u32(b, 12 + 4 * i).toInt()) else emptySet() }.toSet()
        }
        return one(b, 0)
    }

    private fun one(b: ByteArray, at: Int): Set<String> {
        if (at < 0 || at + 12 > b.size) return emptySet()
        val tables = u16(b, at + 4)
        for (i in 0 until tables) {
            val rec = at + 12 + 16 * i
            if (rec + 16 > b.size) break
            if (String(b, rec, 4, Charsets.ISO_8859_1) == "name") return names(b, u32(b, rec + 8).toInt(), u32(b, rec + 12).toInt())
        }
        return emptySet()
    }

    private fun names(b: ByteArray, off: Int, len: Int): Set<String> {
        if (off < 0 || off + 6 > b.size) return emptySet()
        val count = u16(b, off + 2)
        val strings = off + u16(b, off + 4)
        val out = LinkedHashSet<String>()
        for (i in 0 until count) {
            val r = off + 6 + 12 * i
            if (r + 12 > b.size || r + 12 > off + len) break
            val platform = u16(b, r)
            val encoding = u16(b, r + 2)
            val id = u16(b, r + 6)
            if (id != 1 && id != 4 && id != 6 && id != 16) continue
            val start = strings + u16(b, r + 10)
            val n = u16(b, r + 8)
            if (start < 0 || start + n > b.size) continue
            val charset = when (platform) {
                0, 3 -> Charsets.UTF_16BE
                // A Mac record: Roman, or the CJK encodings a Japanese or Chinese font may name itself in.
                1 -> when (encoding) {
                    0 -> Charsets.ISO_8859_1
                    1 -> runCatching { charset("Shift_JIS") }.getOrNull()
                    2 -> runCatching { charset("Big5") }.getOrNull()
                    25 -> runCatching { charset("GB2312") }.getOrNull()
                    else -> null
                }
                else -> null
            } ?: continue
            String(b, start, n, charset).trim().trim('\u0000').takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, at: Int) = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()
}

/** The fonts an .ass script is typeset in: its styles' faces and the ones its lines switch to (\fn). */
object AssFonts {

    private val FN = Regex("""\\fn([^\\}]+)""")

    fun wanted(script: String): Set<String> {
        val out = LinkedHashSet<String>()
        var section = ""
        var fontField = 1
        for (raw in script.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                section = line.lowercase()
                continue
            }
            if (section.startsWith("[v4")) {
                if (line.startsWith("Format:", ignoreCase = true)) {
                    fontField = line.substringAfter(':').split(',').map { it.trim().lowercase() }.indexOf("fontname").takeIf { it >= 0 } ?: 1
                } else if (line.startsWith("Style:", ignoreCase = true)) {
                    line.substringAfter(':').split(',').getOrNull(fontField)?.let(::face)?.let(out::add)
                }
            } else if (section == "[events]" && line.contains("\\fn")) {
                FN.findAll(line).forEach { m -> face(m.groupValues[1])?.let(out::add) }
            }
        }
        return out
    }

    /** "@方正兰亭中黑_GBK" is the same face set vertically. */
    private fun face(raw: String): String? = raw.trim().removePrefix("@").trim().takeIf { it.isNotEmpty() }
}

/**
 * Font packs taken out once and kept: a show's 7z of fonts is fetched for its first episode, and every
 * episode after (and every play again) picks from what was taken out. Each pack is a folder of its fonts
 * with an index of the names each answers to; the oldest go when the whole passes [maxBytes].
 */
class FontCache(private val root: File, private val maxBytes: Long = 1L shl 30) {

    data class Font(val file: File, val names: Set<String>)

    private fun dir(key: String): File = File(root, sha1(key))

    /** The pack kept for [key], or null when it was never taken out. */
    fun cached(key: String): List<Font>? {
        val index = File(dir(key), INDEX)
        if (!index.isFile) return null
        index.setLastModified(System.currentTimeMillis())
        return runCatching {
            val a = JSONArray(index.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val names = o.getJSONArray("names")
                Font(File(dir(key), o.getString("file")), (0 until names.length()).mapTo(LinkedHashSet()) { names.getString(it) })
            }.filter { it.file.isFile }
        }.getOrNull()
    }

    /** The fonts in an archive ([kind] 7z or zip) kept for [key]. */
    fun fromArchive(key: String, archive: File, kind: FontPacks.Kind): List<Font> {
        val fonts = ArrayList<Pair<String, ByteArray>>()
        when (kind) {
            FontPacks.Kind.ZIP -> ZipInputStream(archive.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!e.isDirectory && FontPacks.isFontFile(e.name)) bounded(z)?.let { fonts += e.name to it }
                }
            }
            FontPacks.Kind.SEVEN_ZIP -> SevenZFile.builder().setFile(archive).get().use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory || !e.hasStream() || !FontPacks.isFontFile(e.name) || e.size > MAX_FONT) continue
                    val buf = ByteArray(e.size.toInt())
                    var at = 0
                    while (at < buf.size) {
                        val n = z.read(buf, at, buf.size - at)
                        if (n <= 0) break
                        at += n
                    }
                    if (at == buf.size) fonts += e.name to buf
                }
            }
            else -> Unit
        }
        return fromFiles(key, fonts)
    }

    /** These fonts ([name] to bytes) kept for [key]. */
    fun fromFiles(key: String, fonts: List<Pair<String, ByteArray>>): List<Font> {
        val d = dir(key).apply { deleteRecursively(); mkdirs() }
        val kept = ArrayList<Font>()
        val index = JSONArray()
        fonts.forEach { (name, bytes) ->
            val names = FontNames.of(bytes)
            if (names.isEmpty()) return@forEach
            // Only the file's own name: a path inside an archive stays inside the pack's folder.
            val safe = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "font" }
            var f = File(d, safe)
            var n = 1
            while (f.exists()) f = File(d, "${n++}_$safe")
            f.writeBytes(bytes)
            kept += Font(f, names)
            index.put(JSONObject().put("file", f.name).put("names", JSONArray(names.toList())))
        }
        File(d, INDEX).writeText(index.toString())
        trim()
        return kept
    }

    /** The oldest packs go while the whole is past [maxBytes]; the newest stays. */
    private fun trim() {
        val packs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { File(it, INDEX).lastModified() } ?: return
        var total = packs.sumOf { p -> p.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        for (p in packs.dropLast(1)) {
            if (total <= maxBytes) break
            total -= p.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            p.deleteRecursively()
        }
    }

    companion object {
        private const val INDEX = "index.json"
        const val MAX_FONT = 48L * 1024 * 1024

        /** Of [fonts], the ones answering to a name in [wanted] (case aside), smallest first, to [maxBytes] in all. */
        fun pick(fonts: List<Font>, wanted: Set<String>, maxBytes: Long): List<Font> {
            val want = wanted.mapTo(HashSet()) { it.lowercase() }
            var total = 0L
            return fonts.filter { f -> f.names.any { it.lowercase() in want } }.sortedBy { it.file.length() }
                .filter { f -> (total + f.file.length() <= maxBytes).also { if (it) total += f.file.length() } }
        }

        private fun bounded(input: InputStream): ByteArray? {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_FONT) return null
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    }
}
