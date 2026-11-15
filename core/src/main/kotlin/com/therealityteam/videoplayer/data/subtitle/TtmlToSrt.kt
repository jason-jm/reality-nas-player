package com.therealityteam.videoplayer.data.subtitle

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TTML (and DFXP, its older name) as SubRip, for a player that reads no TTML: FFmpeg can write
 * it but not read it, so on the desktop a `.ttml` beside a video was a track with nothing in it.
 * The headset's ExoPlayer reads TTML itself.
 *
 * What subtitles use of it is kept: every `<p>` with its times — clock times (`00:01:02.500`,
 * `00:01:02:12` in frames), offsets (`62.5s`, `62500ms`, `1.2m`, `625000000t` in ticks, `1500f`)
 * — given on the paragraph or the spans in it, its text, and its line breaks. Styling goes.
 */
object TtmlToSrt {

    /** The file as SubRip text, or null when it is not TTML or holds no timed text. */
    fun convert(bytes: ByteArray): String? {
        val doc = runCatching {
            val f = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // A subtitle file brings no entities and no outside documents.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                isExpandEntityReferences = false
            }
            f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        }.getOrNull() ?: return null
        val root = doc.documentElement ?: return null
        if (root.localName != "tt") return null
        val rates = Rates(
            tick = attr(root, "tickRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0,
            frame = (attr(root, "frameRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 30.0) *
                (attr(root, "frameRateMultiplier")?.split(' ')?.mapNotNull { it.toDoubleOrNull() }?.let { if (it.size == 2 && it[1] != 0.0) it[0] / it[1] else null } ?: 1.0),
        )
        val cues = mutableListOf<Triple<Long, Long, String>>()
        val ps = root.getElementsByTagNameNS("*", "p")
        for (i in 0 until ps.length) {
            val p = ps.item(i) as Element
            val offset = ancestorsBegin(p, rates)
            val (begin, end) = times(p, rates) ?: firstSpanTimes(p, rates) ?: continue
            val text = textOf(p).lines().joinToString("\n") { it.trim() }.trim()
            if (text.isEmpty() || end <= begin) continue
            cues += Triple(offset + begin, offset + end, text)
        }
        if (cues.isEmpty()) return null
        return cues.sortedBy { it.first }.mapIndexed { i, (b, e, t) -> "${i + 1}\n${clock(b)} --> ${clock(e)}\n$t\n" }.joinToString("\n")
    }

    private data class Rates(val tick: Double, val frame: Double)

    /** An attribute by local name, whatever namespace prefix the file gave it. */
    private fun attr(e: Element, name: String): String? {
        val attrs = e.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if ((a.localName ?: a.nodeName.substringAfter(':')) == name) return a.nodeValue?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** [e]'s begin and end (or begin + dur), in ms, when it has a begin. */
    private fun times(e: Element, r: Rates): Pair<Long, Long>? {
        val begin = attr(e, "begin")?.let { ms(it, r) } ?: return null
        val end = attr(e, "end")?.let { ms(it, r) } ?: attr(e, "dur")?.let { d -> ms(d, r)?.let { begin + it } } ?: return null
        return begin to end
    }

    /** A paragraph without times of its own: the first and last of its spans'. */
    private fun firstSpanTimes(p: Element, r: Rates): Pair<Long, Long>? {
        val spans = p.getElementsByTagNameNS("*", "span")
        val all = (0 until spans.length).mapNotNull { times(spans.item(it) as Element, r) }
        if (all.isEmpty()) return null
        return all.minOf { it.first } to all.maxOf { it.second }
    }

    /** Begins on the `<div>`s and `<body>` around a paragraph: its times count from theirs. */
    private fun ancestorsBegin(p: Element, r: Rates): Long {
        var total = 0L
        var n: Node? = p.parentNode
        while (n is Element && n.localName != "tt") {
            attr(n, "begin")?.let { ms(it, r) }?.let { total += it }
            n = n.parentNode
        }
        return total
    }

    /** The words of a paragraph, `<br/>` a new line, spans' words in their place. */
    private fun textOf(e: Node): String = buildString {
        val kids = e.childNodes
        for (i in 0 until kids.length) {
            val k = kids.item(i)
            when {
                k.nodeType == Node.TEXT_NODE || k.nodeType == Node.CDATA_SECTION_NODE -> append(k.nodeValue.replace(Regex("\\s+"), " "))
                k is Element && k.localName == "br" -> append('\n')
                k is Element -> append(textOf(k))
            }
        }
    }

    private val CLOCK = Regex("""^(\d+):(\d{1,2}):(\d{1,2})(?:([.,])(\d+)|:(\d+(?:\.\d+)?))?$""")
    private val OFFSET = Regex("""^(\d+(?:\.\d+)?)(h|m|s|ms|f|t)$""")

    private fun ms(expr: String, r: Rates): Long? {
        CLOCK.matchEntire(expr)?.let { m ->
            val (h, mi, s) = Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong())
            val fraction = m.groupValues[5].takeIf { it.isNotEmpty() }?.let { ("0." + it).toDouble() * 1000 } ?: 0.0
            val frames = m.groupValues[6].takeIf { it.isNotEmpty() }?.let { it.toDouble() / r.frame * 1000 } ?: 0.0
            return ((h * 3600 + mi * 60 + s) * 1000 + fraction + frames).toLong()
        }
        OFFSET.matchEntire(expr)?.let { m ->
            val v = m.groupValues[1].toDouble()
            return when (m.groupValues[2]) {
                "h" -> v * 3_600_000
                "m" -> v * 60_000
                "s" -> v * 1000
                "ms" -> v
                "f" -> v / r.frame * 1000
                else -> v / r.tick * 1000
            }.toLong()
        }
        return null
    }

    private fun clock(ms: Long): String =
        String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}
