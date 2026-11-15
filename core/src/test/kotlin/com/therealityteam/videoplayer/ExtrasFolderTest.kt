package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.ContentClassifier
import com.therealityteam.videoplayer.data.model.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A fansub release drops its bonus content in a decorated SUBFOLDER of the show's release folder
 * (`…/Code_Geass_R2_Picture_Drama_(1920x1080)/…`). Those files must be recognised as extras, or
 * folder reconciliation treats the subfolder as a release of its own and the picture dramas end up
 * on a separate poster card from the show they belong to.
 */
class ExtrasFolderTest {

    @Test
    fun pictureDramaFolderIsExtras() {
        assertEquals(
            ContentKind.EXTRA,
            ContentClassifier.classify(
                "[Coalgirls]_Code_Geass_R2_Picture_Drama_0.56.mkv",
                "/nas/Anime/Code Geass R2/[Coalgirls]_Code_Geass_R2_Picture_Drama_(1920x1080_Blu-ray_FLAC)/" +
                    "[Coalgirls]_Code_Geass_R2_Picture_Drama_0.56.mkv",
            ),
        )
    }

    @Test
    fun cleanOpEdFolderIsExtras() {
        assertEquals(
            ContentKind.EXTRA,
            ContentClassifier.classify(
                "[OZC]Code Geass R2 Clean OP1.mkv",
                "/nas/Anime/Code Geass R2/Bonus Material/Clean OPs/[OZC]Code Geass R2 Clean OP1.mkv",
            ),
        )
    }

    @Test
    fun aNormalEpisodeIsNotExtras() {
        assertEquals(
            ContentKind.MAIN,
            ContentClassifier.classify(
                "[Coalgirls]_Code_Geass_R2_01.mkv",
                "/nas/Anime/Code Geass R2/[Coalgirls]_Code_Geass_R2_01.mkv",
            ),
        )
    }
}
