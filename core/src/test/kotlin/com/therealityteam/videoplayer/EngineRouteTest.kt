package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.EngineRoute
import com.therealityteam.videoplayer.data.playback.PlaybackFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Media3 plays what it can read; mpv what it cannot. Paths as the stream server makes them. */
class EngineRouteTest {

    @Test fun `a container Media3 has no reader for opens in mpv`() {
        assertTrue(EngineRoute.mpvFirst("http://127.0.0.1:40123/9f2c/影视帝国(bbs.cnxp.com).华氏911.CD1.rmvb"))
        assertTrue(EngineRoute.mpvFirst("http://127.0.0.1:40123/a1/Casino%20Lights%2099%20A.WMV"))
        assertTrue(EngineRoute.mpvFirst("/storage/emulated/0/Movies/cielvbr.rm"))
        assertTrue(EngineRoute.mpvFirst("http://127.0.0.1:40123/b2/youtube%20-%20haruhi%202_3.flv?x=1"))
        // An MPEG-1 system stream, which Media3 read as an MP3; a DVD's VOB.
        assertTrue(EngineRoute.mpvFirst("http://127.0.0.1:40123/d4/Phantom%20Kingdom.mpg"))
        assertTrue(EngineRoute.mpvFirst("http://127.0.0.1:40123/d4/DISC1%2002_Bring%20it%20on!.VOB"))
        // A Blu-ray's 192-byte-packet stream.
        assertTrue(EngineRoute.mpvFirst("http://127.0.0.1:40123/d4/Gladiator.m2ts"))
        assertFalse(EngineRoute.mpvFirst("http://127.0.0.1:40123/d4/LiSA%20crossing%20field.ts"))
    }

    @Test fun `everything else opens in Media3`() {
        assertFalse(EngineRoute.mpvFirst("http://127.0.0.1:40123/c3/The.Mandalorian.S01E02.2160p.mkv"))
        assertFalse(EngineRoute.mpvFirst("http://127.0.0.1:40123/c3/[激流中国].02.青岛养老院的故事.avi"))
        assertFalse(EngineRoute.mpvFirst("http://jellyfin.local/Videos/abc/stream?static=true"))
        assertFalse(EngineRoute.mpvFirst("http://127.0.0.1:40123/c3/no-extension"))
    }

    @Test fun `what Media3 failed on goes to mpv only when mpv could read it`() {
        assertTrue(EngineRoute.handsOver(PlaybackFailure.UNSUPPORTED))
        assertTrue(EngineRoute.handsOver(PlaybackFailure.DAMAGED))
        assertFalse(EngineRoute.handsOver(PlaybackFailure.UNREACHABLE))
        assertFalse(EngineRoute.handsOver(PlaybackFailure.MISSING))
        assertFalse(EngineRoute.handsOver(null))
        assertTrue(EngineRoute.handsOverPicture("video/mp4v-es"))
        assertTrue(EngineRoute.handsOverPicture("video/mpeg2"))
        // 激流中国's 03.北京的水危机.mkv: a codec Media3's MKV reader does not know.
        assertTrue(EngineRoute.handsOverPicture("video/x-unknown"))
        assertFalse(EngineRoute.handsOverPicture("video/hevc"))
        assertFalse(EngineRoute.handsOverPicture("video/dolby-vision"))
        // No picture read at all, from a video file.
        assertTrue(EngineRoute.handsOverPicture(null))
    }

    @Test fun `an H 264 only software decodes goes to mpv, an ordinary one stays`() {
        // [VCB-Studio] Mushoku Tensei S2 [02][Hi10p_1080p][x264_flac].mkv, as Media3 read it.
        assertTrue(EngineRoute.softwareOnly("avc1.6E0032"))
        assertTrue(EngineRoute.handsOverPicture("video/avc", "avc1.6E0032"))
        // High 4:2:2, High 4:4:4, CAVLC 4:4:4 Intra; an avc3 stream.
        assertTrue(EngineRoute.softwareOnly("avc1.7A0028"))
        assertTrue(EngineRoute.softwareOnly("avc1.F40033"))
        assertTrue(EngineRoute.softwareOnly("AVC3.2C001F"))
        // High, Main, Baseline: every decoder's.
        assertFalse(EngineRoute.softwareOnly("avc1.640028"))
        assertFalse(EngineRoute.softwareOnly("avc1.4D401F"))
        assertFalse(EngineRoute.softwareOnly("avc1.42E01E"))
        assertFalse(EngineRoute.handsOverPicture("video/avc", "avc1.640033"))
        // HEVC's Main 10 is the hardware's; nothing read, nothing decided.
        assertFalse(EngineRoute.softwareOnly("hvc1.2.4.L150.B0"))
        assertFalse(EngineRoute.softwareOnly("avc1"))
        assertFalse(EngineRoute.softwareOnly(null))
    }

    @Test fun `only sound from a video file means a picture Media3 passed over`() {
        assertTrue(EngineRoute.expectsPicture("http://127.0.0.1:40123/e5/Dave%20Weckl%20Demo%201.mov"))
        assertTrue(EngineRoute.expectsPicture("http://127.0.0.1:40123/e5/[字幕组]%2001.MKV"))
        assertFalse(EngineRoute.expectsPicture("http://127.0.0.1:40123/e5/Chopin%20-%20Nocturnes.flac"))
        assertFalse(EngineRoute.expectsPicture("http://jellyfin.local/Videos/abc/stream?static=true"))
        assertFalse(EngineRoute.expectsPicture(null))
    }

    @Test fun `a name that says Hi10P opens in mpv at once, an HEVC one does not`() {
        assertTrue(EngineRoute.namedSoftwareOnly("http://127.0.0.1:1/a/[VCB-Studio] Mushoku Tensei S2 [02][Hi10p_1080p][x264_flac].mkv"))
        assertTrue(EngineRoute.namedSoftwareOnly("/x/[Kamigami] Fate Zero - 02 [BD 1920x1080 x264(10bit) FLAC PGS(JP,EN)].mkv"))
        assertTrue(EngineRoute.namedSoftwareOnly("/x/Cowboy.Bebop.S01.1080p-Hi10p.BluRay.FLAC5.1.x264-CTR.mkv"))
        assertTrue(EngineRoute.namedSoftwareOnly("/x/Creditless OP1 [AVC_10bit_1080P FLAC_Jpn_2.0 PGS_Eng MKV] - kewenyu.mkv"))
        assertFalse(EngineRoute.namedSoftwareOnly("/x/[Airota&Nekomoe kissaten&VCB-Studio] Yuru Camp [02][Ma10p_1080p][x265_flac].mkv"))
        assertFalse(EngineRoute.namedSoftwareOnly("/x/Fate stay night UBW - 02 [1080p x265 Ma10p FLAC].mkv"))
        assertFalse(EngineRoute.namedSoftwareOnly("/x/The.Mandalorian.S01E02.2160p.HEVC.10bit.mkv"))
        assertFalse(EngineRoute.namedSoftwareOnly("/x/[Zero-Raws] Planetes - 05 RAW (BD 1920x1080 x264 AAC).mp4"))
    }

    @Test fun `a name that says XviD or DivX opens in mpv at once, DivX Plus does not`() {
        assertTrue(EngineRoute.namedLegacy("/x/Friends.S01E01.DVDRip.XviD-SAiNTS.avi"))
        assertTrue(EngineRoute.namedLegacy("http://127.0.0.1:1/a/[DHR][Ghost in the Shell][DivX5.1].avi"))
        assertTrue(EngineRoute.namedLegacy("/x/Film (1999) [DVDRip-DivX].avi"))
        assertFalse(EngineRoute.namedLegacy("/x/Film.2010.DivX.Plus.HD.mkv"))
        assertFalse(EngineRoute.namedLegacy("/x/Drum Lession - Mike Portnoy - Liquid Drum Theater Vol.2.avi"))
        assertFalse(EngineRoute.namedLegacy("/x/Xvidia Documentary.mkv"))
    }

    @Test fun `software stands in for a failed hardware decoder up to 4K at 30 frames`() {
        assertTrue(EngineRoute.softwareKeepsUp(1920, 1080, 60f))
        assertTrue(EngineRoute.softwareKeepsUp(3840, 2160, 29.97f))
        assertTrue(EngineRoute.softwareKeepsUp(4096, 2160, 30f))
        // A 4K 360° panorama; one whose rate the file does not give, taken as 30.
        assertTrue(EngineRoute.softwareKeepsUp(3840, 1920, 30f))
        assertTrue(EngineRoute.softwareKeepsUp(4096, 2048, 0f))
        // A size Media3 did not read: as before, handed over.
        assertTrue(EngineRoute.softwareKeepsUp(0, 0, 60f))
    }

    @Test fun `past it the viewer is told, not given a slideshow`() {
        assertFalse(EngineRoute.softwareKeepsUp(5760, 2880, 30f))
        assertFalse(EngineRoute.softwareKeepsUp(7680, 3840, 30f))
        assertFalse(EngineRoute.softwareKeepsUp(8192, 4096, 0f))
        assertFalse(EngineRoute.softwareKeepsUp(3840, 2160, 60f))
        assertFalse(EngineRoute.softwareKeepsUp(4096, 2160, 30.5f))
    }
}
