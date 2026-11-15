package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which shelf a file lands on.
 *
 * Written against what a real library actually contains rather than tidy examples, because the
 * cases that go wrong are the ones where two answers are defensible: a film ABOUT music, a concert
 * a database happens to call a documentary, an anime music video, a clip off a camera.
 *
 * The rule the order encodes: what somebody filed beats what a database guessed.
 */
class CategoryShelvesTest {

    private fun item(
        path: String,
        title: String = path.substringAfterLast('/').substringBeforeLast('.'),
        genres: List<String> = emptyList(),
        kind: MediaKind = MediaKind.MOVIE,
        key: String? = null,
        certification: String? = null,
    ) = MediaItem(
        id = "x", sourceId = "s", path = path, fileName = path.substringAfterLast('/'),
        title = title, genres = genres, kind = kind, collectionKey = key, certification = certification,
    )

    private fun shelf(vararg args: MediaItem) = args.map { MediaCategory.of(it) }

    // ── what the person filed ────────────────────────────────────────────────────────────────

    @Test fun `a folder the person named decides, whatever a database says`() {
        // Karajan's Beethoven is tagged 纪录 by TMDB and sits in the music library. It is music.
        assertEquals(
            LibCategory.MUSIC,
            MediaCategory.of(item("smb://n/video/Music/Classical/Karajan Symphonie 5.mkv", genres = listOf("纪录", "音乐"))),
        )
        // …and a film filed under Game is the one the person put there.
        assertEquals(LibCategory.GAME, MediaCategory.of(item("smb://n/video/Game/Diablo 3/cinematic.mkv")))
    }

    @Test fun `the folder is read in every language the shelf might be named in`() {
        listOf(
            "smb://n/video/音乐/x.mkv", "smb://n/video/演唱会/x.mkv", "smb://n/video/Concert/x.mkv",
            "smb://n/video/ライブ/x.mkv", "smb://n/video/음악/x.mkv",
        ).forEach { assertEquals(it, LibCategory.MUSIC, MediaCategory.of(item(it))) }
        listOf("smb://n/video/游戏/x.mkv", "smb://n/video/ゲーム/x.mkv", "smb://n/video/게임/x.mkv")
            .forEach { assertEquals(it, LibCategory.GAME, MediaCategory.of(item(it))) }
        listOf("smb://n/video/儿童/x.mkv", "smb://n/video/Kids/x.mkv", "smb://n/video/こども/x.mkv")
            .forEach { assertEquals(it, LibCategory.KIDS, MediaCategory.of(item(it))) }
        listOf("smb://n/video/我的/x.mkv", "smb://n/video/DCIM/x.mkv", "smb://n/video/Home Videos/x.mkv")
            .forEach { assertEquals(it, LibCategory.PERSONAL, MediaCategory.of(item(it))) }
    }

    @Test fun `a name only a camera would give is personal wherever it sits`() {
        // This is what a database matched to a 1947 work called "C1284" on a real library.
        listOf("C1284.MP4", "DSC_0031.MOV", "IMG_4410.mp4", "VID_20250903_101500.mp4", "GOPR0142.MP4", "20250903_101500.mp4")
            .forEach {
                assertEquals(it, LibCategory.PERSONAL, MediaCategory.of(item("smb://n/video/Movie/$it")))
            }
    }

    @Test fun `an ordinary name in an ordinary folder is not personal`() {
        assertEquals(LibCategory.MOVIE, MediaCategory.of(item("smb://n/video/Movie/Alien.1979.mkv")))
        // A title that merely starts with a letter and digits is not a camera file.
        assertEquals(LibCategory.MOVIE, MediaCategory.of(item("smb://n/video/Movie/E01 The Beginning.mkv")))
    }

    // ── then what the databases say ──────────────────────────────────────────────────────────

    @Test fun `a film about music is a film, not music`() {
        // TMDB puts its Music genre on 爆裂鼓手, 波西米亚狂想曲, 海上钢琴师 and on MJ's biography.
        listOf("爆裂鼓手", "波西米亚狂想曲", "海上钢琴师", "迈克尔·杰克逊：巨星之路").forEach {
            assertEquals(
                it, LibCategory.MOVIE,
                MediaCategory.of(item("smb://n/video/Movie/$it.mkv", title = it, genres = listOf("音乐", "剧情"))),
            )
        }
    }

    @Test fun `a recording of a concert is music`() {
        listOf(
            "Clark Terry: Live in Concert", "嵐 ARASHI LIVE TOUR 2019 演唱会",
            "Nirvana - MTV Unplugged in New York", "Chet Baker - Live at Ronnie Scott's",
        ).forEach {
            assertEquals(it, LibCategory.MUSIC, MediaCategory.of(item("smb://n/video/Movie/$it.mkv", title = it)))
        }
    }

    @Test fun `an anime music video stays anime`() {
        // AniList's Music genre is for anime music videos; anime is decided before music.
        assertEquals(
            LibCategory.ANIME,
            MediaCategory.of(item("smb://n/video/Movie/x.mkv", genres = listOf("Music"), key = "anilist:123")),
        )
        assertEquals(
            LibCategory.ANIME,
            MediaCategory.of(item("smb://n/video/Movie/超时空辉夜姬.mkv", genres = listOf("动画", "音乐", "科幻"))),
        )
    }

    @Test fun `a variety show is variety, in any of the languages it is labelled in`() {
        listOf("真人秀", "脱口秀", "Reality", "Talk Show", "리얼리티", "реалити").forEach {
            assertEquals(
                it, LibCategory.VARIETY,
                MediaCategory.of(item("smb://n/video/TV/Running Man/e1.mkv", genres = listOf(it), kind = MediaKind.SHOW)),
            )
        }
    }

    @Test fun `what a database will not show unasked goes on its own shelf`() {
        assertEquals(LibCategory.ADULT, MediaCategory.of(item("smb://n/video/Anime/x/e1.mkv", certification = "R18+")))
        assertEquals(LibCategory.ADULT, MediaCategory.of(item("smb://n/video/Anime/x/e1.mkv", genres = listOf("Hentai"))))
    }

    @Test fun `a content warning is not the adult shelf`() {
        // 207 files of Ecchi on a real library are ordinary anime with a warning on them.
        assertEquals(
            LibCategory.ANIME,
            MediaCategory.of(item("smb://n/video/Anime/x/e1.mkv", genres = listOf("Ecchi", "Comedy"))),
        )
    }

    // ── and when nothing at all is known ─────────────────────────────────────────────────────

    @Test fun `a file nothing identified is not filed as a film`() {
        // 1,846 files on a real library carried no year, no release tag and no episode number, and
        // sat on the film shelf for no better reason than that nothing had contradicted it.
        assertEquals(
            LibCategory.OTHER,
            MediaCategory.of(item("smb://n/迅雷/下载/我的云盘缓存.mp4", kind = MediaKind.UNKNOWN)),
        )
    }

    @Test fun `the folder decides when nothing else can`() {
        // This is what stands between a library that never reached a database — no key, no network,
        // a region block — and a wall that is nothing but 其他. Simulating the scrape away on a real
        // library leaves 72 cards there without this rule and 26 with it.
        assertEquals(
            LibCategory.MOVIE,
            MediaCategory.of(item("smb://n/video/Movie/1944/[1944][BluRay-720P].mkv", kind = MediaKind.UNKNOWN)),
        )
        assertEquals(
            LibCategory.TV,
            MediaCategory.of(item("smb://n/video/TV/鎌倉殿の13人/ep.mkv", kind = MediaKind.UNKNOWN)),
        )
    }

    @Test fun `the folder is read only after everything that knows more`() {
        // A film under TV/ that a database actually identified stays a film — the fallback is for
        // silence, not for overruling an answer.
        assertEquals(
            LibCategory.MOVIE,
            MediaCategory.of(item("smb://n/video/TV/John Adams/tpz-johnadams1.avi", kind = MediaKind.MOVIE)),
        )
        // …and a documentary filed under Movie/ is still a documentary.
        assertEquals(
            LibCategory.DOC,
            MediaCategory.of(item("smb://n/video/Movie/x.mkv", genres = listOf("纪录"), kind = MediaKind.UNKNOWN)),
        )
    }

    @Test fun `children's programming is found before animation swallows it`() {
        assertEquals(
            LibCategory.KIDS,
            MediaCategory.of(item("smb://n/video/TV/Teletubbies/e1.mkv", genres = listOf("儿童", "动画"), kind = MediaKind.SHOW)),
        )
    }

    @Test fun `a recording of somebody playing is a game, wherever it was filed`() {
        // Read off the NAME, which is the weak half of this: only 22 of 156 game files on a real
        // library can be told apart without the folder. It still beats the kind fallback, because
        // the only thing marking these films is that they have no episode number.
        listOf("Dark Souls gameplay part 3", "塞尔达 实况 01", "2012 OSL Season 2 Final", "Elden Ring speedrun")
            .forEach {
                assertEquals(it, LibCategory.GAME, MediaCategory.of(item("smb://n/video/Movie/$it.mkv", title = it)))
            }
    }
}
