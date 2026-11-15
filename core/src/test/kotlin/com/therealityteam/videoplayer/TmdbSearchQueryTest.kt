package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.TmdbMetadataClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLDecoder

/** What TMDB's search is asked: the title folded as TMDB folds its own index. */
class TmdbSearchQueryTest {

    private fun asked(title: String) = URLDecoder.decode(TmdbMetadataClient().searchQuery(title), "UTF-8")

    @Test fun `a Roman numeral is asked as the letters TMDB files it under`() {
        // TMDB has 「劇場版 ガンダム Ｇのレコンギスタ V 死線を越えて」; the release says Ⅴ (U+2164).
        assertEquals("劇場版 GのレコンギスタV 死線を越えて", asked("劇場版 GのレコンギスタⅤ 死線を越えて"))
    }

    @Test fun `fullwidth letters and digits are asked as ASCII`() {
        assertEquals("NHKスペシャル 1941", asked("ＮＨＫスペシャル　１９４１"))
    }

    @Test fun `an ordinary title is asked as it is`() {
        assertEquals("你的名字", asked("你的名字"))
        assertEquals("Your Name", asked("Your Name"))
    }
}
