package com.therealityteam.videoplayer.platform

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import java.io.File

/**
 * `-e aura.i18n_scan <name>` (debug builds): every text Aura's windows show right now, read off their Compose
 * semantics, and the ones cut short — an ellipsis, or text running out of its box — or broken inside a word. A
 * screenshot (`pico-cli capture screenshot --spatial`) shows one page as it looks; this reads every text of it, so
 * a translation too long for its button is found in each language without looking at each.
 *
 * Logged under AuraI18n (a count, then each text cut short); every text, one a line (`…` before one cut short), goes
 * to files/i18n/<name>.txt, for comparing a page across languages — a text that stays English in German is one
 * taken from no resource.
 */
object I18nScan {

    fun run(context: Context, name: String) {
        val texts = mutableListOf<Pair<String, Boolean>>()
        val broken = mutableListOf<String>()
        val roots = runCatching { android.view.inspector.WindowInspector.getGlobalWindowViews() }.getOrDefault(emptyList())
        roots.flatMap(::composeRoots).forEach { root ->
            runCatching {
                root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).forEach { node ->
                    val layout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: return@forEach
                    val results = mutableListOf<TextLayoutResult>()
                    if (layout.action?.invoke(results) != true) return@forEach
                    results.forEach { r ->
                        texts += r.layoutInput.text.text to cutShort(r)
                        if (brokenWord(r)) broken += r.layoutInput.text.text
                    }
                    // A field's text and an icon's name come without a layout of their own.
                    node.config.getOrNull(SemanticsProperties.EditableText)?.let { texts += it.text to false }
                }
            }.onFailure { android.util.Log.w(TAG, "$name: a window's semantics unreadable: $it") }
        }
        val cut = texts.filter { it.second }.map { it.first }.distinct()
        android.util.Log.i(TAG, "$name: ${roots.size} windows, ${texts.size} texts, ${cut.size} cut short, ${broken.distinct().size} broken")
        cut.forEach { android.util.Log.i(TAG, "$name cut: $it") }
        broken.distinct().forEach { android.util.Log.i(TAG, "$name broken: $it") }
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "i18n").apply { mkdirs() }
            File(dir, "$name.txt").writeText(texts.distinct().joinToString("\n") { (t, c) -> (if (c) "… " else "  ") + t.replace('\n', '⏎') })
        }
    }

    // Not hasVisualOverflow: on PICO's Compose its width half reads true for every text narrower than its box (a
    // 112 px word in 400 px). A line wider than the text's box, an ellipsis, lines past maxLines, or a line more than
    // half below the box (not a diacritic's few pixels over: Romanian "Lăsați gol" read as cut, and showed whole).
    private fun cutShort(r: TextLayoutResult): Boolean =
        r.multiParagraph.didExceedMaxLines ||
            (0 until r.lineCount).any {
                r.isLineEllipsized(it) || r.getLineRight(it) - r.getLineLeft(it) > r.size.width + 1 ||
                    (r.getLineTop(it) + r.getLineBottom(it)) / 2 > r.size.height
            }

    // A line ending inside a word of a spaced script ("Informacj / e"): letters either side of the break. Not for
    // CJK, Thai or Hangul, which break between letters by rule.
    private fun brokenWord(r: TextLayoutResult): Boolean {
        val text = r.layoutInput.text.text
        return (0 until r.lineCount - 1).any { line ->
            val end = r.getLineEnd(line)
            end in 1 until text.length && spaced(text[end - 1]) && spaced(text[end])
        }
    }

    private fun spaced(c: Char): Boolean = c.isLetter() && Character.UnicodeScript.of(c.code) in SPACED

    private val SPACED = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK)

    private fun composeRoots(v: View): List<RootForTest> = when {
        v is RootForTest -> listOf(v)
        v is ViewGroup -> (0 until v.childCount).flatMap { composeRoots(v.getChildAt(it)) }
        else -> emptyList()
    }

    private const val TAG = "AuraI18n"
}
