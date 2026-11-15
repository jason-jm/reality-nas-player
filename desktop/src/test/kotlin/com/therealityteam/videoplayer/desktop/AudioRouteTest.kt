package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.desktop.platform.AudioRoute
import com.therealityteam.videoplayer.desktop.platform.AudioRoute.Route
import com.therealityteam.videoplayer.desktop.platform.Host
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the sound moving elsewhere should pause a film: only when it lands on the computer's own speakers. */
class AudioRouteTest {
    private fun fourCc(s: String) = s.fold(0) { acc, ch -> (acc shl 8) or ch.code }
    private val speakers = Route(73, fourCc("bltn"), fourCc("ispk"))
    private val jack = Route(73, fourCc("bltn"), fourCc("hdpn"))
    private val airPods = Route(120, fourCc("blue"), null)
    private val tv = Route(88, fourCc("hdmi"), null)
    private val macMini = Route(60, fourCc("bltn"), null)

    @Test
    fun `headphones out, AirPods away or a TV unplugged pause the film`() {
        assertTrue(AudioRoute.becameNoisy(jack, speakers), "the jack's headphones pulled out")
        assertTrue(AudioRoute.becameNoisy(airPods, speakers), "AirPods put away")
        assertTrue(AudioRoute.becameNoisy(tv, speakers), "the TV's cable pulled")
        assertTrue(AudioRoute.becameNoisy(airPods, macMini), "a speaker with no data source is the speakers")
    }

    @Test
    fun `anything else plays on`() {
        assertFalse(AudioRoute.becameNoisy(speakers, jack), "headphones plugged in")
        assertFalse(AudioRoute.becameNoisy(speakers, airPods), "AirPods connected")
        assertFalse(AudioRoute.becameNoisy(airPods, tv), "from one private output to another")
        assertFalse(AudioRoute.becameNoisy(speakers, speakers))
        assertFalse(AudioRoute.becameNoisy(null, speakers), "the first reading")
    }

    @Test
    fun `the route can be read here`() {
        if (!Host.isMac) return
        val r = AudioRoute.current()
        println("AUDIOROUTE now: $r (built-in speakers: ${r?.builtInSpeakers})")
        assertTrue(r != null, "CoreAudio answered")
    }
}
