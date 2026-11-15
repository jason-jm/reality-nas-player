package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.player.EnvSource
import com.therealityteam.videoplayer.player.Environment
import com.therealityteam.videoplayer.player.Environments
import com.therealityteam.videoplayer.player.Screen
import com.therealityteam.videoplayer.player.ScreenPose
import com.therealityteam.videoplayer.player.ViewingSeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The environment catalogue: what the picker offers must exist in the APK, be our own (built from a
 * scene script of tools/envbake, 2026-10-26: the downloaded models are gone), and stand where it was
 * measured to stand.
 */
class EnvironmentCatalogTest {

    /** The app module's directory, whether the tests run from it (Gradle) or from the repository root. */
    private val module = listOf(File("."), File("app")).first { File(it, "src/main/assets").isDirectory }

    @Test
    fun `ids are unique and the default is on offer`() {
        val ids = Environments.all.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        assertNotNull(Environments.byId(Environments.DEFAULT_ID))
    }

    @Test
    fun `every packed model is in the assets`() {
        Environments.all.mapNotNull { it.source as? EnvSource.Asset }.forEach { src ->
            val f = File(module, "src/main/assets/${src.path}")
            assertTrue("missing ${src.path}", f.isFile && f.length() > 0)
        }
    }

    @Test
    fun `every environment has its picture`() {
        Environments.all.forEach { env ->
            assertTrue("no thumbnail for ${env.id}", File(module, "src/main/res/drawable-nodpi/env_${env.id}.jpg").isFile)
        }
    }

    @Test
    fun `every place on offer is one of ours, the private theater first`() {
        assertEquals(OWN, Environments.all.map { it.id }.toSet())
        assertEquals("private_theater", Environments.DEFAULT_ID)
        assertEquals(Environments.DEFAULT_ID, Environments.all.first().id)
    }

    @Test
    fun `each authored place has its scene script and expected credit`() {
        OWN.forEach { id ->
            val env = Environments.byId(id)
            assertNotNull("$id is not in the catalogue", env)
            assertEquals("$id asset credit", CREDITED[id], env!!.credit?.author)
            assertTrue("$id has no scene script", File(module, "../tools/envbake/scenes/${SCENES.getValue(id)}").isFile)
        }
    }

    @Test
    fun `a screen moves and turns with its place`() {
        // A screen 3 m along the seat's +x, 0.4 m to its +z, 1.1 m up, facing back at it (−x):
        // with the seat turned to face +x, the screen is straight ahead, a little right, facing the viewer.
        val env = Environment(id = "t", label = 0, thumb = 0, source = EnvSource.File("/x"),
            seatX = 0.05f, seatZ = -0.85f, yaw = 90f,
            screen = Screen(x = 3.05f, y = 1.1f, z = -0.45f, width = 1.8f, facing = -90f, aspect = 1.8f))
        val p = env.screenPose()!!
        assertEquals(0.4f, p.x, 1e-4f)
        assertEquals(1.1f, p.y, 1e-4f)
        assertEquals(-3f, p.z, 1e-4f)
        assertEquals(0f, p.yaw, 1e-4f)
        assertEquals(1.8f, p.width, 1e-4f)
        assertEquals(1f, p.height, 1e-4f)
        // Twice the scale: twice as far, as wide, as high off the seat's floor.
        val big = env.copy(scale = 2f, seatY = 0.5f).screenPose()!!
        assertEquals(0.8f, big.x, 1e-4f)
        assertEquals(1.2f, big.y, 1e-4f)
        assertEquals(-6f, big.z, 1e-4f)
        assertEquals(3.6f, big.width, 1e-4f)
        assertEquals(null, env.copy(screen = null).screenPose())
    }

    @Test
    fun `every screen stands in front of its seat, faces it, and is a size to watch`() {
        val screens = Environments.all.filter { it.screen != null }
        assertTrue("some places have screens", screens.size >= 4)
        screens.forEach { env ->
            val p = env.screenPose()!!
            assertTrue("${env.id} in front: ${p.z}", p.z < -2f)
            val turn = ((p.yaw % 360f) + 360f) % 360f
            assertTrue("${env.id} faces the seat: ${p.yaw}", turn < 1f || turn > 359f)
            // As wide to the eye as a TV across a room at least, and no wider than the window.
            val degrees = Math.toDegrees(2.0 * Math.atan2(p.width / 2.0, Math.hypot(p.x.toDouble(), p.z.toDouble())))
            assertTrue("${env.id} is $degrees° wide", degrees in 25.0..62.0)
        }
    }

    @Test
    fun `only a screen low and near enough reaches down behind the bar`() {
        // A foot 1 m below a standing eye, 3 m off: atan(1/3).
        val p = ScreenPose(x = 0f, y = 1.14f, z = -3f, yaw = 0f, width = 1.6f, height = 1f)
        assertEquals(18.43f, p.footBelowEye(eye = 1.64f), 0.01f)
        // A TV across a room, low and near, meets the bar; a cinema's screen high and far stands above it.
        assertTrue(ScreenPose(x = 0f, y = 1.1f, z = -3f, yaw = 0f, width = 1.75f, height = 1f).reachesBar())
        assertFalse(ScreenPose(x = 0f, y = 2.5f, z = -8.6f, yaw = 0f, width = 5.4f, height = 2.7f).reachesBar())
    }

    @Test
    fun `a seat lands under the viewer whatever the turn and scale`() {
        val env = Environment(id = "t", label = 0, thumb = 0, source = EnvSource.File("/x"),
            seatX = 3f, seatY = -2f, seatZ = 7f, yaw = 135f, scale = 4f)
        val o = env.origin()
        // Holder transform: p' = R(yaw)·(scale·p) + origin; the seat must map to (0, 0, 0).
        val r = Math.toRadians(135.0)
        val x = 12.0; val y = -8.0; val z = 28.0
        val px = x * Math.cos(r) + z * Math.sin(r) + o[0]
        val py = y + o[1]
        val pz = -x * Math.sin(r) + z * Math.cos(r) + o[2]
        assertEquals(0.0, px, 1e-3)
        assertEquals(0.0, py, 1e-3)
        assertEquals(0.0, pz, 1e-3)
    }

    @Test
    fun `selectable chairs match the authored room positions`() {
        val expected = mapOf(
            "private_theater" to listOf(-0.86f to 5.37f, 0.86f to 5.37f),
            "village_night" to listOf(-2.10f to -0.70f, 1.75f to -1.30f),
            "space_lounge" to listOf(-3.12f to -4.60f, -1.90f to -4.35f, 1.90f to -4.35f, 3.12f to -4.60f),
            "desert_camp" to emptyList(),
        )
        Environments.all.forEach { env ->
            assertEquals(env.id, expected.getValue(env.id), env.otherChairs.map { it.x to it.z })
            assertEquals(env.id, env.otherChairs.size + 1, env.seatChoices.size)
            assertEquals(env.id, env.seatChoices.distinct(), env.seatChoices)
        }
    }

    @Test
    fun `each chair keeps the viewer on its floor and the screen straight ahead`() {
        Environments.all.forEach { room ->
            listOf(1f, 2f).forEach { scale ->
                val env = room.copy(scale = scale)
                env.seatChoices.forEach { seat ->
                    val pose = env.seatPose(seat)
                    assertEquals(env.seatY, pose.y, 0f)
                    val origin = env.origin(seat)
                    val r = Math.toRadians(pose.yaw.toDouble())
                    assertEquals(0.0, scale * (pose.x * Math.cos(r) + pose.z * Math.sin(r)) + origin[0], 1e-4)
                    assertEquals(0f, scale * pose.y + origin[1], 1e-4f)
                    assertEquals(0.0, scale * (-pose.x * Math.sin(r) + pose.z * Math.cos(r)) + origin[2], 1e-4)
                    val screen = env.screenPose(seat)!!
                    assertEquals(0f, screen.x, 1e-4f)
                    assertTrue("${env.id}/$seat screen behind viewer", screen.z < 0f)
                    assertEquals(env.screen!!.facing + pose.yaw, screen.yaw, 0f)
                    assertFalse("${env.id}/$seat screen overlaps toolbar", screen.reachesBar())
                }
            }
        }
    }

    @Test
    fun `saved seats tolerate old settings and remain independent of previous choices`() {
        Environments.all.forEach { env ->
            val center = env.screenPose()
            for (key in listOf(null, "", "removed", "CENTER", "null")) {
                assertEquals(ViewingSeat.CENTER, env.savedSeat(key))
            }
            for (seat in ViewingSeat.entries.reversed()) {
                val chosen = env.savedSeat(seat.name)
                assertEquals(if (seat in env.seatChoices) seat else ViewingSeat.CENTER, chosen)
                env.screenPose(chosen)
                assertEquals(center, env.screenPose())
            }
            assertEquals(ViewingSeat.CENTER, env.copy(otherChairs = emptyList()).savedSeat("LEFT"))
        }
    }

    private companion object {
        /** The authored scenes and their Blender source files. */
        val SCENES = mapOf("private_theater" to "private_theater.py", "village_night" to "village_night.py", "space_lounge" to "space_lounge.py", "desert_camp" to "desert_camp.py")
        val OWN = SCENES.keys
        val CREDITED = mapOf("space_lounge" to "NASA Earth Observatory")
    }
}
