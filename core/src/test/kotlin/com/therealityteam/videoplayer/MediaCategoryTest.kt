package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which shelf a title lands on must not depend on the language its metadata was fetched in.
 *
 * Genres arrive from TMDB in the reader's own language, so a table of Chinese and English NAMES
 * quietly emptied the 动画 / 纪录片 / 综艺 shelves for everyone else: re-running the evaluator over
 * this library with the genre column translated scored 0.9959 for a Chinese reader, 0.9834 for a
 * German one and 0.9641 for a Japanese one. Stems fixed it — all three now produce a byte-identical
 * wall — and these cases are what keeps it fixed.
 */
class MediaCategoryTest {

    private fun item(genres: List<String>, kind: MediaKind = MediaKind.MOVIE, title: String = "Some Title") = MediaItem(
        id = title, sourceId = "s", path = "/mnt/video/Mixed/$title.mkv", fileName = "$title.mkv",
        title = title, kind = kind, scrapeStatus = ScrapeStatus.MATCHED, genres = genres,
    )

    @Test fun animationIsAnimationInEveryLanguage() {
        listOf(
            "Animation",        // en, de, fr
            "动画", "动漫", "動畫",
            "アニメーション",      // ja — NFD would take the dakuten off ア…ニメ's neighbours
            "애니메이션",          // ko
            "Animación", "Animação", "Animazione",
            "мультфильм",       // ru
        ).forEach { assertEquals(it, LibCategory.ANIME, MediaCategory.of(item(listOf(it)))) }
    }

    @Test fun documentaryIsDocumentaryInEveryLanguage() {
        listOf(
            "Documentary", "Dokumentarfilm", "Dokumentation", "Documentaire", "Documental",
            "Documentário", "Documentario",
            "纪录", "纪录片", "紀錄", "记录",
            "ドキュメンタリー",     // ja — ド is ト + a combining dakuten once decomposed
            "다큐멘터리",          // ko
            "документальный",   // ru
        ).forEach { assertEquals(it, LibCategory.DOC, MediaCategory.of(item(listOf(it)))) }
    }

    @Test fun realityAndTalkAreVarietyInEveryLanguage() {
        listOf(
            "Reality", "Reality-TV", "Reality show", "Réalité", "Talk", "Talk show",
            "真人秀", "脱口秀", "综艺", "實境",
            "リアリティ番組", "トーク",
            "리얼리티", "토크",
            "Реалити-шоу", "Ток-шоу",
        ).forEach {
            assertEquals(it, LibCategory.VARIETY, MediaCategory.of(item(listOf(it), MediaKind.SHOW)))
        }
    }

    @Test fun aStemNeverSwallowsAGenreItDoesNotMean() {
        // The whole point of a stem is that the genre vocabulary is closed; these are the names that
        // live nearest to one, and none of them may be pulled onto the wrong shelf.
        mapOf(
            "Drama" to LibCategory.MOVIE, "Music" to LibCategory.MOVIE, "音乐" to LibCategory.MOVIE,
            "Crime" to LibCategory.MOVIE, "Adventure" to LibCategory.MOVIE, "Action" to LibCategory.MOVIE,
            "Komödie" to LibCategory.MOVIE, "News" to LibCategory.MOVIE, "Soap" to LibCategory.MOVIE,
            "TV Movie" to LibCategory.MOVIE,
            // Music stays a film: TMDB puts that genre on films ABOUT music, which is why the music
            // shelf ignores it for anything whose kind is already MOVIE.
            // AniList's own vocabulary, which is most of this library's genres
            "Mecha" to LibCategory.MOVIE, "Slice of Life" to LibCategory.MOVIE,
            "Psychological" to LibCategory.MOVIE, "Supernatural" to LibCategory.MOVIE,
        ).forEach { (g, want) -> assertEquals(g, want, MediaCategory.of(item(listOf(g)))) }
    }

    @Test fun kindStillDecidesWhatGenreDoesNot() {
        assertEquals(LibCategory.MOVIE, MediaCategory.of(item(emptyList(), MediaKind.MOVIE)))
        assertEquals(LibCategory.TV, MediaCategory.of(item(emptyList(), MediaKind.SHOW)))
        assertEquals(LibCategory.OTHER, MediaCategory.of(item(emptyList(), MediaKind.UNKNOWN)))
    }
}
