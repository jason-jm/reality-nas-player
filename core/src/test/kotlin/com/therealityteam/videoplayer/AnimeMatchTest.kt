package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.scrape.AnimeMatch
import com.therealityteam.videoplayer.data.scrape.AnimeQueries
import com.therealityteam.videoplayer.data.scrape.MetaResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guard + picker that decide which database entry a file is filed under. */
class AnimeMatchTest {

    private fun hit(id: Int, title: String, pop: Double = 0.0, romaji: String? = null) =
        MetaResult(id, MediaKind.SHOW, title = title, romajiTitle = romaji, popularity = pop)

    private fun film(id: Int, title: String, year: Int, pop: Double = 0.0) =
        MetaResult(id, MediaKind.MOVIE, title = title, year = year, popularity = pop)

    @Test
    fun aRawReleaseGroupSaysAnimeWhereverTheFileIsFiled() {
        // In the downloads, not the Anime shelf: routed to TMDB, it was filed as Patlabor 2.
        assertTrue(com.therealityteam.videoplayer.data.scrape.AnimeParse.parse(
            "[Shiniori-Raws] Kidou Keisatsu Patlabor The Movie Remastered (BD 3840x2160 x265 10bit FLAC).mkv").isAnimeLikely)
        assertTrue(com.therealityteam.videoplayer.data.scrape.AnimeParse.parse(
            "[VCB-S&philosophy-raws][Ghost in the Shell：The New Movie][Movie][BDRIP].mkv").isAnimeLikely)
        // A bare [RAW] also tags a Japanese TV drama, which AniList does not have.
        assertFalse(com.therealityteam.videoplayer.data.scrape.AnimeParse.parse(
            "[RAW] Some.Drama.S01E01.1080p.WEB-DL.mkv").isAnimeLikely)
    }

    @Test
    fun theNameUpToItsNumeralIsAskedWhenTheSubtitleIsSpeltOtherwise() {
        // The file says "Moon Butterfly"; AniList says "Moonlight Butterfly", and answers
        // "Turn A Gundam II" with the second film alone.
        assertEquals("Turn A Gundam II", AnimeQueries.upToItsNumber("Turn A Gundam II Moon Butterfly"))
        assertEquals("Turn A Gundam II", AnimeQueries.upToItsNumber("Turn A Gundam Movie II Moon Butterfly"))
        // Nothing after the numeral, nothing to leave out; a digit is as often part of a name.
        assertEquals(null, AnimeQueries.upToItsNumber("Turn A Gundam II"))
        assertEquals(null, AnimeQueries.upToItsNumber("Mobile Suit Gundam 00 Second Season"))
        assertEquals(null, AnimeQueries.upToItsNumber("Gundam X Under the Moonlight"))
    }

    @Test
    fun aFilmNumberedByItsOwnRomanNumeralIsThatFilm() {
        // AniList's answer for the box's name "Mobile Suit Gundam Zeta A New Translation", in its order.
        val zeta = listOf(
            MetaResult(1968, MediaKind.MOVIE, title = "Mobile Suit Zeta Gundam: A New Translation II - Lovers",
                englishTitle = "Mobile Suit Zeta Gundam: A New Translation II - Lovers", year = 2005),
            MetaResult(1967, MediaKind.MOVIE, title = "Mobile Suit Zeta Gundam: A New Translation I - Heir to the Stars",
                englishTitle = "Mobile Suit Zeta Gundam: A New Translation I - Heir to the Stars", year = 2005),
            MetaResult(1969, MediaKind.MOVIE, title = "Mobile Suit Zeta Gundam: A New Translation III - Love Is the Pulse of the Stars",
                englishTitle = "Mobile Suit Zeta Gundam: A New Translation III - Love Is the Pulse of the Stars", year = 2006),
        )
        val box = "Mobile Suit Gundam Zeta A New Translation"
        // Without a number every file was the first film…
        assertEquals(1967, AnimeMatch.pick(box, zeta, wantMovie = true)?.providerId)
        // …the files name theirs: `Mobile_Suit_Z_Gundam_II_-_Lovers`, `…_III_-_Love_is_the_Pulse…`.
        assertEquals(2, AnimeMatch.romanOrdinal("Mobile Suit Z Gundam II - Lovers"))
        assertEquals(1968, AnimeMatch.pick(box, zeta, wantMovie = true, filmNumber = 2)?.providerId)
        assertEquals(1969, AnimeMatch.pick(box, zeta, wantMovie = true, filmNumber = 3)?.providerId)
        // I, V and X are letters as often as numbers.
        assertEquals(null, AnimeMatch.romanOrdinal("Mobile Suit Z Gundam I - Heirs to the Stars"))
        assertEquals(null, AnimeMatch.romanOrdinal("After War Gundam X"))
        assertEquals(null, AnimeMatch.romanOrdinal("Victory Gundam V"))
    }

    @Test
    fun theFormTheFileNamesDecidesWhenNoFolderNamesOne() {
        // AniList's answer for "Munto", in its order. The folder is just `Munto`; the files say
        // `[SumiSora][Munto][TV][01]`, and the 2009 series is the only TV entry among them.
        val munto = listOf(
            MetaResult(1174, MediaKind.SHOW, title = "Munto", romajiTitle = "MUNTO", format = "OVA", year = 2003),
            MetaResult(5702, MediaKind.SHOW, title = "Sora wo Miageru Shoujo no Hitomi ni Utsuru Sekai",
                synonyms = listOf("Munto TV"), format = "TV", year = 2009),
            MetaResult(6169, MediaKind.MOVIE, title = "Tenjou-bito to Akuto-bito Saigo no Tatakai",
                synonyms = listOf("Munto Movie", "Munto Finale"), format = "MOVIE", year = 2009),
        )
        assertEquals(1174, AnimeMatch.pick("Munto", munto)?.providerId)
        assertEquals(5702, AnimeMatch.pick("Munto", munto, pathForm = AnimeQueries.formOfType("TV"))?.providerId)
        assertEquals("OVA", AnimeQueries.formOfType("OAD"))
        assertEquals(null, AnimeQueries.formOfType("Movie"))
    }

    @Test
    fun theFormThePathNamesSurvivesAQueryThatDoesNotSpellIt() {
        // "機動警察パトレイバー TV" is not how AniList spells the TV run, so that query finds nothing
        // and the search falls through to the bare franchise name — which returns the OVA first,
        // it being the more popular entry. The path still says TV; 48 episodes depended on it.
        val patlabor = listOf(
            MetaResult(1288, MediaKind.SHOW, title = "Kidou Keisatsu Patlabor", format = "OVA", popularity = 20938.0),
            MetaResult(324, MediaKind.SHOW, title = "Kidou Keisatsu Patlabor ON TELEVISION", format = "TV", popularity = 14381.0),
        )
        assertEquals(1288, AnimeMatch.pick("Kidou Keisatsu Patlabor", patlabor)?.providerId)
        assertEquals(324, AnimeMatch.pick("Kidou Keisatsu Patlabor", patlabor, pathForm = "TV")?.providerId)
        // The query still speaks first where it names a form of its own.
        assertEquals(1288, AnimeMatch.pick("Kidou Keisatsu Patlabor OVA", patlabor, pathForm = "TV")?.providerId)
        // A SYNONYM carrying the form word is the opposite signal: someone added "ROD OVA" to tell
        // the OVA from the TV series, and counting it as part of the name killed the hint.
        val rod = listOf(
            MetaResult(209, MediaKind.SHOW, title = "R.O.D -THE TV-", romajiTitle = "R.O.D -THE TV-", format = "TV", year = 2003, popularity = 17637.0),
            MetaResult(208, MediaKind.SHOW, title = "R.O.D -READ OR DIE-", romajiTitle = "R.O.D -READ OR DIE-", format = "OVA", year = 2001, popularity = 20600.0, synonyms = listOf("リード・オア・ダイ", "ROD OVA")),
        )
        assertEquals(208, AnimeMatch.pick("R.O.D -The", rod, pathForm = "OVA")?.providerId)
    }

    @Test
    fun aFormWordThatIsPartOfTheNameIsNotAFormHint() {
        // 「逮捕しちゃうぞ Special」 is what a 1999 series is CALLED. Read as a form note it preferred
        // a 2008 one-shot whose AniList format is SPECIAL over the exact title the search put first.
        val specials = listOf(
            MetaResult(2011, MediaKind.SHOW, title = "Taiho Shichauzo: Special", originalTitle = "逮捕しちゃうぞ Special", format = "TV_SHORT", popularity = 2564.0),
            MetaResult(6312, MediaKind.SHOW, title = "Taiho Shichauzo: Full Throttle Special", originalTitle = "逮捕しちゃうぞ フルスロットル Special", format = "SPECIAL", popularity = 1345.0),
        )
        assertEquals(2011, AnimeMatch.pick("逮捕しちゃうぞ Special", specials)?.providerId)
        // …while a form the works do NOT spell still tells an OVA from the series it shares a name
        // with. Patlabor's TV entry is called ON TELEVISION, so nothing but the query says OVA.
        val patlabor = listOf(
            MetaResult(324, MediaKind.SHOW, title = "Kidou Keisatsu Patlabor ON TELEVISION", format = "TV", popularity = 14381.0),
            MetaResult(1288, MediaKind.SHOW, title = "Kidou Keisatsu Patlabor", format = "OVA", popularity = 20938.0),
        )
        assertEquals(1288, AnimeMatch.pick("Kidou Keisatsu Patlabor OVA", patlabor)?.providerId)
    }

    @Test
    fun aFilmDoesNotSettleForTheSeriesItSpunOffFrom() {
        // AniList gives each film its own entry and the search returns it, but the TV series is
        // always the more popular hit — so popularity handed it the card. Both 超时空要塞F films
        // landed on the TV entry as episodes of it, and so did the EVA and G-Reco films.
        val results = listOf(
            dated(3572, "Macross Frontier", 2008, pop = 90000.0),
            film(4116, "Macross Frontier: Itsuwari no Utahime", 2009, pop = 20000.0),
            film(8663, "Macross Frontier: Sayonara no Tsubasa", 2011, pop = 18000.0),
        )
        assertEquals(4116, AnimeMatch.pick("Macross Frontier", results, wantMovie = true)?.providerId)
        // `劇場版2` means the second FILM, not the second season.
        assertEquals(
            8663,
            AnimeMatch.pick("Macross Frontier", results, season = 2, wantMovie = true)?.providerId,
        )
        // A file that does not name a film is untouched — the series still wins on popularity.
        assertEquals(3572, AnimeMatch.pick("Macross Frontier", results)?.providerId)
    }

    private fun dated(id: Int, title: String, year: Int, pop: Double = 0.0) =
        MetaResult(id, MediaKind.SHOW, title = title, year = year, popularity = pop)

    @Test
    fun aSeasonTitledBySubtitleIsFoundByItsPlaceInTheChronology() {
        // AniList calls 零之使魔's second season `Futatsuki no Kishi` — no "2" in any of its titles,
        // so every candidate scores zero on the season test and popularity decides. Season 1 is
        // always the most popular, which is how 零之使魔 and 零之使魔2 ended up on one card.
        val franchise = listOf(
            dated(1195, "Zero no Tsukaima", 2006, pop = 90000.0),
            dated(1841, "Zero no Tsukaima: Futatsuki no Kishi", 2007, pop = 40000.0),
            dated(3712, "Zero no Tsukaima: Princesses no Rondo", 2008, pop = 30000.0),
        )
        assertEquals(1841, AnimeMatch.pick("Zero no Tsukaima", franchise, season = 2)?.providerId)
        assertEquals(3712, AnimeMatch.pick("Zero no Tsukaima", franchise, season = 3)?.providerId)
        // Season 1, and no season at all, still take the most popular entry.
        assertEquals(1195, AnimeMatch.pick("Zero no Tsukaima", franchise, season = 1)?.providerId)
        assertEquals(1195, AnimeMatch.pick("Zero no Tsukaima", franchise)?.providerId)
    }

    @Test
    fun chronologyOnlyDecidesWhereTheDatabaseNamesNoSeason() {
        // An entry that says which season it is keeps deciding it — order is the fallback, not a
        // replacement, or a franchise whose 2nd season aired first as an OVA would be misread.
        val marked = listOf(
            dated(1, "Natsume Yuujinchou", 2008, pop = 120000.0),
            dated(2, "Natsume Yuujinchou Second Season", 2009, pop = 40000.0),
            dated(3, "Natsume Yuujinchou Shi", 2011, pop = 30000.0),
        )
        assertEquals(2, AnimeMatch.pick("Natsume Yuujinchou", marked, season = 2)?.providerId)
        // Asking for a season the franchise does not have means the query was wrong, not the order.
        val two = listOf(dated(1, "Some Show", 2010, pop = 100.0), dated(2, "Some Show II", 2011))
        assertEquals(1, AnimeMatch.pick("Some Show", two, season = 4)?.providerId)
    }

    @Test
    fun queryMustNotMatchInsideAWord() {
        // `甜蜜吻痕 SS Plus` searches "SS Plus"; "Macross Plus" contains it only as macro|ss plus.
        assertFalse(AnimeMatch.corresponds("SS Plus", hit(474, "Macross Plus", 90000.0)))
        assertTrue(AnimeMatch.corresponds("SS Plus", hit(11235, "Amagami SS+ plus", 9000.0)))
    }

    @Test
    fun theMorePopularFranchiseDoesNotSwallowASeason() {
        // "Natsume Yuujinchou Shi" also matches season 1 (query contains its title), and season 1 is
        // far more popular — the specific entry has to win anyway.
        val s1 = hit(4081, "Natsume's Book of Friends", 120000.0, romaji = "Natsume Yuujinchou")
        val s4 = hit(11665, "Natsume's Book of Friends Season 4", 40000.0, romaji = "Natsume Yuujinchou Shi")
        assertEquals(11665, AnimeMatch.pick("Natsume Yuujinchou Shi", listOf(s1, s4))?.providerId)
    }

    @Test
    fun popularityStillBreaksTiesWithinATier() {
        val petit = hit(4130, "Petit Eva: Evangelion@School", 7308.0)
        val nge = hit(30, "Neon Genesis Evangelion", 487614.0)
        assertEquals(30, AnimeMatch.pick("Evangelion", listOf(petit, nge))?.providerId)
    }

    @Test
    fun cjkContainmentStillWorksWithoutWordBoundaries() {
        assertTrue(AnimeMatch.corresponds("千与千寻", hit(1, "千与千寻的神隐")))
    }

    @Test
    fun theSeasonThePathNamesDecidesBetweenHits() {
        // `Mobile Suit Gundam 00 1080P BDRIP/2nd_Season/` cannot say so in its query — AniList
        // answers "Mobile Suit Gundam 00 Season 2" and "…2nd Season" with nothing. It does return
        // BOTH seasons for the plain title, so the folder picks between them.
        val s1 = hit(2581, "Mobile Suit Gundam 00", 90_000.0)
        val s2 = hit(3927, "Mobile Suit Gundam 00 Second Season", 60_000.0)
        val hits = listOf(s1, s2)
        assertEquals(3927, AnimeMatch.pick("Mobile Suit Gundam 00", hits, season = 2)?.providerId)
        // …and with no season named, popularity decides as before.
        assertEquals(2581, AnimeMatch.pick("Mobile Suit Gundam 00", hits)?.providerId)
        assertEquals(2581, AnimeMatch.pick("Mobile Suit Gundam 00", hits, season = 1)?.providerId)
    }

    @Test
    fun theSeasonIsReadFromTheFolderAboveTheFile() {
        assertEquals(2, AnimeQueries.seasonOf("smb://n/video/Anime/Gundam 00 BDRIP/2nd_Season/ep01.mkv"))
        assertEquals(2, AnimeQueries.seasonOf("smb://n/video/Anime/Yuru Camp Season 2/ep01.mkv"))
        assertEquals(null, AnimeQueries.seasonOf("smb://n/video/Anime/Gundam 00 BDRIP/1st_Season/ep01.mkv"))
    }

    @Test fun `a numbered sequel is not the work a query without a number asked for`() {
        // AniList answers "機動警察パトレイバー the Movie" with exactly ONE entry — the SECOND film —
        // and the first film's own card went to it. A tie-break cannot save a sole survivor, so the
        // entry has to be dropped: the next query gets its turn, and no card is better than a wrong one.
        val second = film(1096, "Mobile Police Patlabor 2: The Movie", 1993, pop = 15786.0)
            .copy(romajiTitle = "Kidou Keisatsu Patlabor 2: the Movie")
        assertEquals(null, AnimeMatch.pick("機動警察パトレイバー the Movie", listOf(second), wantMovie = true))
    }

    @Test fun `the film that spells its own number still gets it`() {
        val second = film(1096, "Mobile Police Patlabor 2: The Movie", 1993)
            .copy(romajiTitle = "Kidou Keisatsu Patlabor 2: the Movie", originalTitle = "機動警察パトレイバー2 the Movie")
        assertEquals(1096, AnimeMatch.pick("機動警察パトレイバー 2 the Movie", listOf(second), wantMovie = true)?.providerId)
    }

    @Test fun `an entry that IS the query keeps its card however it spells its number`() {
        // "Shi" is four, in a word. The entry answering it exactly still says "Season 4" in English,
        // and reading that number as an instalment the query did not ask for threw the answer away.
        val s4 = hit(11665, "Natsume's Book of Friends Season 4", 40000.0, romaji = "Natsume Yuujinchou Shi")
        assertEquals(11665, AnimeMatch.pick("Natsume Yuujinchou Shi", listOf(s4))?.providerId)
    }

    @Test fun `a season the path names still decides among numbered entries`() {
        // The drop must not reach the machinery that exists to pick a season: seasonFit and
        // byAirDate can only choose between entries they are given.
        val s2 = hit(3927, "Mobile Suit Gundam 00 Second Season", romaji = "Kidou Senshi Gundam 00 Second Season")
        assertEquals(3927, AnimeMatch.pick("Mobile Suit Gundam 00", listOf(s2), season = 2)?.providerId)
    }

    @Test fun `a number that names the work is not an instalment`() {
        // "4-koma" counts panels and "3-gatsu" counts months; neither is a second anything. As a
        // tie-break a wrong yes cost a little, as a filter it costs the entry.
        val koma = hit(13561, "Guilty Crown: 4-koma Gekijou", romaji = "Guilty Crown: 4-koma Gekijou")
        assertEquals(13561, AnimeMatch.pick("guilty crown gekijou", listOf(koma))?.providerId)
        val lion = hit(31646, "March Comes in Like a Lion", romaji = "3-gatsu no Lion")
        assertEquals(31646, AnimeMatch.pick("March Comes in Like a Lion", listOf(lion))?.providerId)
    }

    @Test fun `a franchise whose entries share a year is not a run of seasons`() {
        // "Zero no Tsukaima: Princesses no Rondo" answers with the TV run, an OVA and a Picture
        // Drama, all 2008. Ordering those by year leaves the database id deciding, and season three
        // went to the Picture Drama. The TV run wins on popularity, as it did before a season was
        // read from the folder at all.
        val tv = hit(3712, "The Familiar of Zero: Rondo of Princesses", 77261.0, romaji = "Zero no Tsukaima: Princesses no Rondo").copy(year = 2008)
        val ova = hit(5764, "The Familiar of Zero: Rondo of Princesses OVA", 18650.0, romaji = "Zero no Tsukaima: Princesses no Rondo - Yuuwaku no Sunahama").copy(year = 2008)
        val pd = hit(6489, "Zero no Tsukaima: Princesses no Rondo Picture Drama", 9948.0, romaji = "Zero no Tsukaima: Princesses no Rondo Picture Drama").copy(year = 2008)
        assertEquals(
            3712,
            AnimeMatch.pick("Zero no Tsukaima Princesses no Rondo", listOf(tv, ova, pd), season = 3)?.providerId,
        )
    }

    @Test fun `seasons that really are years apart still order by their chronology`() {
        // What byAirDate exists for: AniList titles 零之使魔's second season by subtitle, with no
        // number anywhere, so only its place in time tells it from the first.
        val s1 = hit(1195, "The Familiar of Zero", 90000.0, romaji = "Zero no Tsukaima").copy(year = 2006)
        val s2 = hit(2921, "The Familiar of Zero: Knight of the Twin Moons", 40000.0, romaji = "Zero no Tsukaima: Futatsuki no Kishi").copy(year = 2007)
        assertEquals(2921, AnimeMatch.pick("Zero no Tsukaima", listOf(s1, s2), season = 2)?.providerId)
    }
}

/**
 * The candidate PAGE has to contain the right answer before any picker can choose it. These are
 * the real AniList results for "Love Live!" (2026-08): the franchise's own first season ranks
 * NINTH — one past the old page of 8 — so every S1 file matched Sunshine!! instead. With the
 * page widened the picker gets S1 (pop 113k, exact prefix) and chooses it over the spin-offs.
 */
class AnimeCandidatePageTest {
    private fun hit(id: Int, title: String, pop: Double, romaji: String? = null) =
        com.therealityteam.videoplayer.data.scrape.MetaResult(
            id, com.therealityteam.videoplayer.data.model.MediaKind.SHOW,
            title = title, romajiTitle = romaji ?: title, popularity = pop,
        )

    @org.junit.Test
    fun theFirstSeasonWinsOnceItIsOnThePage() {
        val page = listOf(
            hit(21584, "Love Live! Sunshine!!", 59970.0),
            hit(20766, "Love Live! The School Idol Movie", 40454.0),
            hit(114979, "Love Live! Superstar!!", 29886.0),
            hit(105906, "Love Live! μ's Final Love Live! Opening Animation", 3730.0),
            hit(6667, "Love to Live By", 453.0),
            hit(98349, "Love Live! Sunshine!! 2nd Season", 38311.0),
            hit(155502, "Love Live! Superstar!! 3rd Season", 9609.0),
            hit(140642, "Love Live! Superstar!! 2nd Season", 15625.0),
            hit(15051, "Love Live! School idol project", 113265.0), // ranks 9th on the live API
        )
        val picked = com.therealityteam.videoplayer.data.scrape.AnimeMatch.pick("Love Live!", page)
        org.junit.Assert.assertEquals(15051, picked?.providerId)
    }
}
