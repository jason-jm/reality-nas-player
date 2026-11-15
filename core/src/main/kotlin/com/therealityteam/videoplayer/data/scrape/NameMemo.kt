package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.ContentKind
import java.io.File

/**
 * What every file name was read as ([FilenameParser.parse]) and every path classified as
 * ([ContentClassifier.classify]), kept from one launch to the next.
 *
 * Reading the names is most of a regroup's cost on the headset — NameWarmup, two of a cold grouping's
 * 3.5 s for 9,686 files — and the library is regrouped from scratch whenever its file moved: a film
 * marked watched, a favourite, a new file found. Nearly all of the names are the same ones as last
 * time, and reading a name is a pure function of it: what was read stands until the code that reads
 * it changes, which comes as a new install — so the memo is stamped with when this build was
 * installed, and another stamp means it is not read.
 *
 * A line per answer, tab-separated: `P name title year season episode`, `C path kind`. A name or path
 * holding a tab or a line break is simply not kept (and read again next time).
 */
object NameMemo {

    private const val VERSION = "aura-names 1"

    /** Puts what [file] holds into the parser's and classifier's memos; how many answers, 0 if stale or none. */
    fun load(file: File, installed: Long): Int = runCatching {
        if (!file.isFile) return 0
        val parses = HashMap<String, ParsedName>()
        val kinds = HashMap<String, ContentKind>()
        file.bufferedReader().use { r ->
            if (r.readLine() != "$VERSION\t$installed") return 0
            while (true) {
                val line = r.readLine() ?: break
                val c = line.split('\t')
                when (c.firstOrNull()) {
                    "P" -> if (c.size == 6) parses[c[1]] = ParsedName(c[2], c[3].toIntOrNull(), c[4].toIntOrNull(), c[5].toIntOrNull())
                    "C" -> if (c.size == 3) runCatching { ContentKind.valueOf(c[2]) }.getOrNull()?.let { kinds[c[1]] = it }
                }
            }
        }
        FilenameParser.primeParses(parses)
        ContentClassifier.primeClassifications(kinds)
        parses.size + kinds.size
    }.getOrDefault(0)

    /** Writes the parser's and classifier's memos to [file] for the next launch of this build; how many answers. */
    fun save(file: File, installed: Long): Int = runCatching {
        val parses = FilenameParser.parsesSoFar()
        val kinds = ContentClassifier.classificationsSoFar()
        fun ok(s: String) = s.indexOf('\t') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        var n = 0
        tmp.bufferedWriter().use { w ->
            w.write("$VERSION\t$installed\n")
            for ((name, p) in parses) {
                if (!ok(name) || !ok(p.title)) continue
                w.write("P\t$name\t${p.title}\t${p.year ?: ""}\t${p.season ?: ""}\t${p.episode ?: ""}\n")
                n++
            }
            for ((path, kind) in kinds) {
                if (!ok(path)) continue
                w.write("C\t$path\t${kind.name}\n")
                n++
            }
        }
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        n
    }.getOrDefault(0)
}
