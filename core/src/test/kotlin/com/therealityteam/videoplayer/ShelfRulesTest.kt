package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.FolderReconcile
import com.therealityteam.videoplayer.data.scrape.ShelfFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a shelf never takes, and which of its folders name no work — on the user's real library. */
class ShelfRulesTest {

    @Test fun `a documentary shelf never takes an animation or a talk show, and keeps its films about real events`() {
        assertTrue(ShelfFit.neverOnDocumentaryShelf(listOf("剧情", "动画"))) // 明治 → 明治撃剣－1874－
        assertTrue(ShelfFit.neverOnDocumentaryShelf(listOf("脱口秀"))) // 华尔街 → Wall Street Week
        assertTrue(ShelfFit.neverOnDocumentaryShelf(listOf("Reality")))
        assertFalse(ShelfFit.neverOnDocumentaryShelf(listOf("剧情", "悬疑", "惊悚"))) // 总统班底
        assertFalse(ShelfFit.neverOnDocumentaryShelf(listOf("纪录")))
        assertFalse(ShelfFit.neverOnDocumentaryShelf(emptyList()))
    }

    private val music = "smb://192.168.1.126/video/Music"

    @Test fun `a folder right under the music shelf is a category, a concert's own folder is not`() {
        assertTrue(FilenameParser.inMusicCategoryFolder("$music/Guitar/Angel Wing - Taka Minamino.mp4"))
        assertTrue(FilenameParser.inMusicCategoryFolder("$music/ACG/primenotes - ピアノ 『ソナタ第2番 第一楽章 -鳥の詩-』.flv"))
        assertTrue(FilenameParser.inMusicCategoryFolder("$music/Drum & Bass/A Japan Girl - about2.avi"))
        assertFalse(FilenameParser.inMusicCategoryFolder("$music/ACG/NANA MIZUKI LIVE FORMULA at SAITAMA SUPER ARENA/DISC1 01_OPENING.avi"))
        assertFalse(FilenameParser.inMusicCategoryFolder("$music/ACG/VOICES ~Music from Final Fantasy 2002/101 Voices Opening.avi"))
        // Not a music shelf, or no folder of its own.
        assertFalse(FilenameParser.inMusicCategoryFolder("smb://192.168.1.126/video/Movie/Guitar/Guitar.2008.mkv"))
        assertFalse(FilenameParser.inMusicCategoryFolder("$music/loose clip.mp4"))
    }

    @Test fun `a music shelf keeps a hit its clip names, and refuses one that merely holds the clip's words`() {
        // Named by the file, or the file's whole name inside a longer one.
        assertTrue(ShelfFit.namesTheClip("Joe Pass - Solo Jazz Guitar", listOf("Joe Pass: Solo Jazz Guitar")))
        assertTrue(ShelfFit.namesTheClip("Casino Lights 99 A", listOf("Casino Lights '99 - Live At The Montreux Jazz Festival")))
        assertTrue(ShelfFit.namesTheClip("Duke Ellington - At The Cote D'Azur", listOf("Duke Ellington at the Côte d'Azur with Ella Fitzgerald and Joan Miro")))
        assertTrue(ShelfFit.namesTheClip("NANA MIZUKI LIVE FORMULA at SAITAMA SUPER ARENA", listOf("NANA MIZUKI LIVE FORMULA 2007 - 2008 at SAITAMA SUPER ARENA")))
        // Any of the hit's names will do: its Chinese title says nothing of the file's English one.
        assertTrue(ShelfFit.namesTheClip("The Art Of Violin", listOf("小提琴的艺术", "The Art of Violin")))
        // A phrase the hit merely holds.
        assertFalse(ShelfFit.namesTheClip("jamming", listOf("Jamming on an Old Saya")))
        assertFalse(ShelfFit.namesTheClip("Chapter 1 Interface Overview", listOf("That Chapter")))
        assertFalse(ShelfFit.namesTheClip("Luna Sea -", listOf("LUNA SEA: THE FINAL ACT TOKYO DOME")))
        assertFalse(ShelfFit.namesTheClip("- Concert Tour", listOf("Mr.Children Concert Tour Q 2000～2001")))
        assertFalse(ShelfFit.namesTheClip("水樹奈々", listOf("水樹奈々 LIVE CASTLE×JOURNEY -KING-NIGHT-")))
        // Words every concert says name none of them.
        assertFalse(ShelfFit.namesTheClip("LIVE DOCUMENT", listOf("Arashi Live & Document")))
        assertTrue(ShelfFit.namesTheClip("Clark Terry - Live in Concert", listOf("Clark Terry: Live in Concert")))
        // One Latin word is a kind of clip or a brand, even spelled exactly; a lone letter is no word.
        assertFalse(ShelfFit.namesTheClip("Guitar", listOf("Guitar")))
        assertFalse(ShelfFit.namesTheClip("Roland", listOf("Carmen - Roland Petit")))
        assertFalse(ShelfFit.namesTheClip("S E N S", listOf("P.E.N.S. (Poetic Energy Needed in Society)")))
    }

    private var n = 0

    private fun clip(folder: String, name: String, id: Int? = null) = MediaItem(
        id = "c${n++}", sourceId = "s", path = "$music/$folder/$name", fileName = name,
        title = name.substringBeforeLast('.'), kind = MediaKind.UNKNOWN, tmdbId = id,
        scrapeStatus = if (id != null) ScrapeStatus.MATCHED else ScrapeStatus.UNMATCHED,
    )

    @Test fun `one clip's match is not its music folder's, but a concert's own files are`() {
        // An artist's folder: one of 34 clips matched a film, and the other 33 are not that film.
        val coenen = listOf(clip("Guitar/Marcel Coenen", "jamming.rm", 815153)) +
            (1..33).map { clip("Guitar/Marcel Coenen", "Marcel Coenen - lick $it.wmv") }
        assertTrue(FolderReconcile.plan(coenen).isEmpty())
        // A category folder never names a work, however many of its clips agree.
        val audio = (1..11).map { clip("Audio", "CE教程_$it.avi", 124874) } + (1..5).map { clip("Audio", "Cubase $it.flv") }
        assertTrue(FolderReconcile.plan(audio).isEmpty())
        // A concert's folder, its discs matched: the bonus clip beside them joins them.
        val concert = (1..3).map { clip("ACG/Animelo Summer Live 2007", "Animelo Summer Live 2007 Disk$it.avi", 537954) } +
            clip("ACG/Animelo Summer Live 2007", "Animelo Summer Live 2007 Making.avi")
        assertEquals(listOf(537954), FolderReconcile.plan(concert).map { it.tmdbId })
    }
}
