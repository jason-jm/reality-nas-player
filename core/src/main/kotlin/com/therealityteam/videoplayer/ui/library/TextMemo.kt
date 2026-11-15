package com.therealityteam.videoplayer.ui.library

import java.io.File

/**
 * The library's titles as romanised ([Romanizer]: pinyin, romaji) and folded to simplified Han
 * ([HanFold]), kept from one launch to the next — the search keys' two seconds of ICU on the headset
 * at every start, for the same few thousand names each time.
 *
 * The answers are the platform's ICU's: the memo is stamped with when this build was installed and the
 * system it runs on, and another stamp means it is not read. A line per answer, tab-separated:
 * `R text romanised` (empty: nothing to romanise), `H text folded`; a text holding a tab or a line
 * break is not kept.
 */
object TextMemo {

    private const val VERSION = "aura-texts 1"

    /** This build on this system: what the answers hold for. */
    fun stamp(installed: Long): String = "$installed ${System.getProperty("os.version").orEmpty()}"

    /** Puts what [file] holds into the romaniser's and folder's memos; how many answers, 0 if stale or none. */
    fun load(file: File, stamp: String): Int = runCatching {
        if (!file.isFile) return 0
        val roman = HashMap<String, String>()
        val folded = HashMap<String, String>()
        file.bufferedReader().use { r ->
            if (r.readLine() != "$VERSION\t$stamp") return 0
            while (true) {
                val line = r.readLine() ?: break
                val c = line.split('\t')
                if (c.size != 3) continue
                when (c[0]) {
                    "R" -> roman[c[1]] = c[2]
                    "H" -> folded[c[1]] = c[2]
                }
            }
        }
        Romanizer.prime(roman)
        HanFold.prime(folded)
        roman.size + folded.size
    }.getOrDefault(0)

    /** Writes both memos to [file] for the next launch; how many answers. */
    fun save(file: File, stamp: String): Int = runCatching {
        fun ok(s: String) = s.indexOf('\t') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        var n = 0
        tmp.bufferedWriter().use { w ->
            w.write("$VERSION\t$stamp\n")
            for ((text, out) in Romanizer.soFar()) if (ok(text) && ok(out)) { w.write("R\t$text\t$out\n"); n++ }
            for ((text, out) in HanFold.soFar()) if (ok(text) && ok(out)) { w.write("H\t$text\t$out\n"); n++ }
        }
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        n
    }.getOrDefault(0)
}
