package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.PublicMovieCatalog
import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import com.therealityteam.videoplayer.util.TransientHttpException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Offline replay of anonymous HTTP responses. Oracle titles are kept out of the matcher. */
class PublicMovieBenchmark {
    @Test fun replay() {
        val root = System.getProperty("aura.publicMovies.replay")?.let(::File) ?: return
        val requests = linkedSetOf<String>()
        val cases = JSONArray(File(root, "cohort.json").readText())
        val original = TmdbConfig.language
        try {
            TmdbConfig.language = "zh-CN"
            val catalog = PublicMovieCatalog { url, _ ->
                val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val body = File(root, "bodies/$hash.json")
                if (!body.exists()) { requests += url; null } else {
                    val j = JSONObject(body.readText())
                    if (j.has("_error") || j.optInt("_http") in setOf(429, 500, 502, 503, 504)) throw TransientHttpException(url)
                    j
                }
            }
            File(root, "predictions.jsonl").bufferedWriter().use { out ->
                runBlocking {
                    for (i in 0 until cases.length()) {
                        val row = cases.getJSONObject(i)
                        val path = row.getString("path").replaceFirst("/Volumes/", "smb://inventory.invalid/")
                        val parsed = FilenameParser.parseFromPath(path)
                        val names = PublicMovieCatalog.fileNames(path, parsed)
                        if (names.isEmpty()) { out.appendLine(JSONObject().put("id", row.getString("id")).toString()); continue }
                        val result = JSONObject().put("id", row.getString("id")).put("fileQueries",
                            JSONArray(names.map { JSONObject().put("title", it.title).put("year", it.year) }))
                        for (source in PublicMovieCatalog.Source.entries) {
                            val hit = try { names.firstNotNullOfOrNull { catalog.match(source, it) } } catch (_: TransientHttpException) {
                                result.put(source.prefix, JSONObject().put("unavailable", true)); continue
                            }
                            if (hit != null) result.put(source.prefix, JSONObject().put("key", hit.publicMovieKey)
                                .put("title", hit.title).put("original", hit.originalTitle).put("year", hit.year)
                                .put("aliases", JSONArray(hit.synonyms)).put("imdb", hit.imdbId)
                                .put("poster", hit.posterUrl).put("overview", hit.overview?.take(140)))
                        }
                        try {
                            val filename = path.substringAfterLast('/')
                            val eligible = PublicMovieCatalog.eligible(path, filename, parsed.title, parsed)
                            result.put("eligible", eligible)
                            val selected = if (eligible) catalog.match(names.first(), names.drop(1)) else null
                            result.put("selected", selected?.publicMovieKey ?: "")
                            if (selected != null) result.put("selectedRecord", JSONObject().put("key", selected.publicMovieKey)
                                .put("title", selected.title).put("original", selected.originalTitle).put("year", selected.year)
                                .put("aliases", JSONArray(selected.synonyms)).put("imdb", selected.imdbId)
                                .put("poster", selected.posterUrl).put("overview", selected.overview?.take(140)))
                        } catch (_: TransientHttpException) { result.put("comboUnavailable", true) }
                        out.appendLine(result.toString())
                    }
                }
            }
        } finally { TmdbConfig.language = original }
        File(root, "requested.txt").writeText(requests.joinToString("\n"))
    }
}
