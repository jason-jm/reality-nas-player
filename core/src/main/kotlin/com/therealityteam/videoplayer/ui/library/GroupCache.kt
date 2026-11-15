package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.MediaItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Which files ended up on which card, kept so the next launch does not work it out again.
 *
 * Working it out is most of a cold start: reading 9,597 file names and deciding what belongs with
 * what took about twenty seconds of the twenty-eight the wall needed before it could be touched.
 * And it is a pure function of the library — the same files, read by the same code, give the same
 * cards. Between closing the app and opening it, none of that has changed.
 *
 * So the answer is written down with a fingerprint of everything it depends on, and read back when
 * that fingerprint still holds:
 *
 *  - the library file's own length and modified time. Nothing edits it in place, and playback writes
 *    positions to a sidecar rather than to it, so "unchanged file" really does mean "unchanged
 *    library".
 *  - when the app was last installed. Any change to how grouping works arrives as a new install,
 *    so a code change invalidates this by itself — nobody has to remember to bump a version.
 *  - the language, which decides which of a work's names is shown and how the wall sorts.
 *
 * This carries only what cannot be worked out cheaply: which item ids are on each card, and the
 * names it is searched by. Everything else a card draws with is already in [WallSnapshot], which
 * the wall has read by this point anyway.
 *
 * If anything does not line up — a missing id, a card the snapshot does not have, a fingerprint that
 * has moved — the whole thing is discarded and the library is grouped as before. There is no
 * halfway: a partly-revived wall would be worse than a slow one.
 */
object GroupCache {

    private const val PATH = "aura/groups.json"
    private const val VERSION = 1

    fun file(filesDir: File): File = File(filesDir, PATH)

    /** What the cards were worked out FROM, so a change to any of it throws them away. */
    data class Stamp(val libraryLength: Long, val libraryModified: Long, val installed: Long, val language: String) {
        fun json(): JSONObject = JSONObject()
            .put("libLen", libraryLength).put("libAt", libraryModified)
            .put("app", installed).put("lang", language)

        companion object {
            fun of(o: JSONObject?): Stamp? = o?.let {
                Stamp(it.optLong("libLen", -1), it.optLong("libAt", -1), it.optLong("app", -1), it.optString("lang"))
            }

            /** [installed] is when this build of the app was installed — its code's fingerprint. */
            fun current(filesDir: File, installed: Long): Stamp {
                val library = File(filesDir, "aura/library.json")
                val language = com.therealityteam.videoplayer.data.scrape.TmdbConfig.language +
                    "|" + java.util.Locale.getDefault().toLanguageTag()
                return Stamp(library.length(), library.lastModified(), installed, language)
            }
        }
    }

    fun save(filesDir: File, installed: Long, groups: List<MediaGroup>) {
        val f = file(filesDir)
        runCatching {
            val cards = JSONArray()
            groups.forEach { g ->
                cards.put(
                    JSONObject()
                        .put("k", g.key)
                        .put("i", JSONArray(g.items.map { it.id }))
                        .put("t", JSONArray(g.searchTitles)),
                )
            }
            val body = JSONObject()
                .put("v", VERSION)
                .put("stamp", Stamp.current(filesDir, installed).json())
                .put("cards", cards)
                .toString()
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(f)) { f.writeText(body); tmp.delete() }
        }.onFailure { f.delete() }
    }

    /**
     * The cards as they last stood, rebuilt on the real library — or null when anything is off, in
     * which case the caller groups from scratch as it always did.
     *
     * [painted] is the wall snapshot the app has already read to draw with; everything a card shows
     * comes from there, and everything that makes it real comes from [items].
     */
    fun revive(filesDir: File, installed: Long, painted: List<MediaGroup>, items: List<MediaItem>): List<MediaGroup>? {
        if (painted.isEmpty() || items.isEmpty()) return null
        val f = file(filesDir)
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText())
            if (o.optInt("v") != VERSION) return null
            if (Stamp.of(o.optJSONObject("stamp")) != Stamp.current(filesDir, installed)) return null

            val byKey = painted.associateBy { it.key }
            val byId = items.associateBy { it.id }
            val cards = o.optJSONArray("cards") ?: return null
            val out = ArrayList<MediaGroup>(cards.length())
            for (i in 0 until cards.length()) {
                val c = cards.optJSONObject(i) ?: return null
                val shell = byKey[c.optString("k")] ?: return null
                val ids = c.optJSONArray("i") ?: return null
                val members = ArrayList<MediaItem>(ids.length())
                for (j in 0 until ids.length()) {
                    members += byId[ids.optString(j)] ?: return null
                }
                if (members.isEmpty()) return null
                val titles = c.optJSONArray("t")
                    ?.let { t -> (0 until t.length()).map { t.optString(it) } }
                    .orEmpty()
                out += shell.copy(items = members, searchTitles = titles)
            }
            // Every file has to be on exactly one card, or this is not the wall the library makes.
            if (out.sumOf { it.items.size } != items.size) return null
            out
        }.getOrNull()
    }
}
