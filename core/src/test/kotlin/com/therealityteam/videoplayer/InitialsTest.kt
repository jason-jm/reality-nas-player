package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.ui.library.Initials
import org.junit.Assert.assertEquals
import org.junit.Test

/** An avatar without a photo: two letters for a Latin name, one character for a Chinese one. */
class InitialsTest {
    @Test fun `a Latin name gives its first and last initials`() {
        assertEquals("OP", Initials.of("Oleg Petrov"))
        assertEquals("RB", Initials.of("Ricardo Marco Budé"))
        assertEquals("ÉZ", Initials.of("émile zola"))
        assertEquals("J", Initials.of("Jeb"))
    }

    @Test fun `a Chinese or Japanese name gives its first character`() {
        assertEquals("萨", Initials.of("萨姆·门德斯"))
        assertEquals("宮", Initials.of("宮崎駿"))
        assertEquals("", Initials.of("  "))
    }
}
