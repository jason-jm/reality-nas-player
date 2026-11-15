package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.ui.library.DetailPlay
import org.junit.Assert.assertEquals
import org.junit.Test

/** A detail page's rows say which file they are: repeated labels numbered in list order. */
class NumberRepeatsTest {
    @Test fun `repeated labels are numbered in order and single ones left alone`() {
        assertEquals(
            listOf("正片", "预告 1", "预告 2", "先导预告 1", "先导预告 2", "CM"),
            DetailPlay.numberRepeats(listOf("正片", "预告", "预告", "先导预告", "先导预告", "CM")),
        )
        assertEquals(listOf("A", "B 1", "A2", "B 2"), DetailPlay.numberRepeats(listOf("A", "B", "A2", "B")))
        assertEquals(emptyList<String>(), DetailPlay.numberRepeats(emptyList()))
    }
}
