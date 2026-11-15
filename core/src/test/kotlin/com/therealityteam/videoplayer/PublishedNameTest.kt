package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.FilenameParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a name carries the mark of something somebody released.
 *
 * The scanner used to call every file without an episode number a film, and nothing downstream ever
 * questioned it: 1,846 files on a real library held that title on no evidence whatsoever. This is
 * the evidence — not "is it a film", which no filename can answer, but the far smaller claim that
 * it was encoded and published by someone.
 *
 * Deliberately generous. A false yes leaves a file exactly where it sits today; a false no takes a
 * real film off the film shelf, which somebody notices.
 */
class PublishedNameTest {

    @Test fun `a release carries its own tags`() {
        listOf(
            "Interstellar.2014.2160p.BluRay.x264.8bit.DoVi.DTS-HD.MA.5.1-BiG.mkv",
            "The.Bear.S01E05.1080p.WEB-DL.mkv",
            "[1944][BluRay-720P.MKV]2.1GB[中文字幕].mkv",
            "Alien 1979 REMUX.mkv",
            "[VCB-Studio] SHIROBAKO [01][Ma10p_1080p][x265_flac].mkv",
            "某片 HDTV 国语中字.mp4",
            // THORA/UTW write the whole tag block comma-separated. Read as one token it matches
            // nothing, and this file was the last one left flipping between 电影 and 其他 on every
            // single scrape because of it.
            "The_Borrowers_Arrietty_(2010)_[1080p,BluRay,x264,DTS]_-_THORA-UTW.mkv",
            "[Kamigami] Fate Zero - 01 [1280x720,x264,AAC].mp4",
        ).forEach { assertTrue(it, FilenameParser.looksPublished(it)) }
    }

    @Test fun `what a camera or a download folder leaves behind does not`() {
        listOf(
            "C1284.MP4", "00063.m2ts", "IMG_4410.mp4", "我的云盘缓存.mp4",
            "Berwick Saga OP.wmv", "Chrono Cross Opening Movie.avi", "Blade Storm.wmv",
            "映像の世紀バタフライエフェクト「銀座 百年の記憶」.mkv",
        ).forEach { assertFalse(it, FilenameParser.looksPublished(it)) }
    }

    @Test fun `a tag glued to punctuation still counts`() {
        // Real names separate tags with dots, brackets, spaces and underscores interchangeably, and
        // a rule that only read spaces would have missed most of the library.
        listOf("x.1080p.mkv", "x[1080p].mkv", "x 1080p.mkv", "x_1080p.mkv", "x(1080p).mkv")
            .forEach { assertTrue(it, FilenameParser.looksPublished(it)) }
        // …and two tags glued by a hyphen are still two tags.
        assertTrue(FilenameParser.looksPublished("[1944][BluRay-720P.MKV]2.1GB.mkv"))
        // But a tag has to be a word of its own: 「合集1080」 is not a resolution, and a hyphenated
        // title is not a release — 「K-19」 must not read as one.
        assertFalse(FilenameParser.looksPublished("蓝光原盘合集1080.mkv"))
        assertFalse(FilenameParser.looksPublished("K-19 The Widowmaker.mkv"))
    }

    @Test fun `a film library root is recognised in the languages one gets named in`() {
        listOf("Movies", "Film", "电影", "映画", "영화", "蓝光电影")
            .forEach { assertTrue(it, FilenameParser.isUnderMovieRoot("smb://n/video/$it/x.mkv")) }
        assertFalse(FilenameParser.isUnderMovieRoot("smb://n/迅雷/下载/x.mkv"))
        // A folder merely containing the word is not a root: only whole segments match.
        assertFalse(FilenameParser.isUnderMovieRoot("smb://n/video/Movie Night 2018/x.mkv"))
    }
}
