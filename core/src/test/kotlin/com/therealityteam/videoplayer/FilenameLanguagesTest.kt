package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.FilenameParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** File names as other countries' releases write them: language tags, seasons and episodes in their words. */
class FilenameLanguagesTest {

    private fun parse(name: String) = FilenameParser.parse(name).let { listOf(it.title, it.season, it.episode, it.year) }

    @Test fun `a release's language tags are not its title`() {
        assertEquals(listOf("Der Pate", null, null, null), parse("Der.Pate.GER.DL.1080p.BluRay.x264.mkv"))
        assertEquals(listOf("Le Fabuleux Destin d'Amélie Poulain", null, null, 2001), parse("Le.Fabuleux.Destin.d'Amélie.Poulain.2001.TRUEFRENCH.VFF.1080p.mkv"))
        assertEquals(listOf("La Casa de Papel", 1, 3, null), parse("La.Casa.de.Papel.S01E03.Castellano.1080p.mkv"))
        assertEquals(listOf("Wiedźmin", null, null, null), parse("Wiedźmin.Lektor.PL.720p.mkv"))
    }

    @Test fun `a language's name in a title stays, The Italian Job and The English Patient`() {
        assertEquals(listOf("The Italian Job", null, null, 1969), parse("The.Italian.Job.1969.1080p.mkv"))
        assertEquals(listOf("The English Patient", null, null, 1996), parse("The.English.Patient.1996.720p.mkv"))
        assertEquals(listOf("The Danish Girl", null, null, 2015), parse("The.Danish.Girl.2015.mkv"))
    }

    @Test fun `seasons in other languages' words`() {
        assertEquals(2, FilenameParser.parse("Dark Staffel 2 Folge 3.mkv").season)
        assertEquals(3, FilenameParser.parse("Dark Staffel 2 Folge 3.mkv").episode)
        assertEquals(2, FilenameParser.parse("Dark - 2. Staffel.mkv").season)
        assertEquals(1, FilenameParser.parse("Lupin Saison 1 Épisode 4.mkv").season)
        assertEquals(4, FilenameParser.parse("Lupin Saison 1 Épisode 4.mkv").episode)
        assertEquals(2, FilenameParser.parse("Élite 2ª Temporada.mkv").season)
        assertEquals(3, FilenameParser.parse("Метод Сезон 3 Серия 5.mkv").season)
        assertEquals(5, FilenameParser.parse("Метод Сезон 3 Серия 5.mkv").episode)
        assertEquals(2, FilenameParser.parse("Метод 2 сезон.mkv").season)
        assertEquals(2, FilenameParser.parse("킹덤 시즌2 3화.mkv").season)
        assertEquals(3, FilenameParser.parse("킹덤 시즌2 3화.mkv").episode)
        assertEquals(2, FilenameParser.parse("進撃の巨人 シーズン2.mkv").season)
        assertEquals(7, FilenameParser.parse("Muhteşem Yüzyıl Bölüm 7.mkv").episode)
    }
}
