package com.therealityteam.videoplayer.player

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.therealityteam.videoplayer.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Where an environment's model comes from. */
sealed interface EnvSource {
    /** A model in the APK's assets: a .glb baked by tools/envbake from one of its scenes/. */
    data class Asset(val path: String) : EnvSource

    /** A model on the device, for trying a download before it is packed (`-e aura.env_file`). */
    data class File(val path: String) : EnvSource
}

/**
 * A screen in a place for the film to play on (在环境的屏幕上播放) — a frame screen in a square, the panel at a
 * lounge's end. Its centre is in the model's own units, as the seat is, and so is [width]; [facing] is
 * the turn about the vertical that brings a face looking down +z onto the way the screen looks in the
 * model (−90 for one on a wall that faces −x), and [aspect] its face's shape. The film is fitted inside
 * the face as it is in the window. What it leaves is the model's own (a TV's black glass) — or, for a
 * screen the model does not have, [backing]: a dark face with a narrow border, so that a screen
 * hung in the air reads as one, letterbox and all.
 */
data class Screen(
    val x: Float,
    val y: Float,
    val z: Float,
    val width: Float,
    val facing: Float,
    val aspect: Float = 16f / 9f,
    val backing: Boolean = false,
)

/** Where a [Screen] lands on the stage: its centre and the turn it faces with, its face in metres. */
data class ScreenPose(
    val x: Float,
    val y: Float,
    val z: Float,
    val yaw: Float,
    val width: Float,
    val height: Float,
    val backing: Boolean = false,
) {
    /**
     * How far below the eye line the screen's foot lies, in degrees, for a viewer at the seat with
     * their eye [eye] metres above the floor — standing by default, the highest eye there is.
     */
    fun footBelowEye(eye: Float = STANDING_EYE): Float =
        Math.toDegrees(atan2(eye - (y - height / 2), hypot(x, z)).toDouble()).toFloat()

    /**
     * Whether the screen reaches down into the band where the window's bar hangs — the system places
     * a window so that the top of its Toolbar lies 14.3° below the eye line (measured from the
     * emulator's seat), and a foot within 2° of that is behind the bar or touching it. A TV across a
     * room (18°) would; our places' screens, high and far (a few degrees), stand above it.
     */
    fun reachesBar(): Boolean = footBelowEye() > BAR_REACH_DEG

    companion object {
        /** A standing viewer's eye above the floor — the emulator's, and so every screenshot's. */
        const val STANDING_EYE = 1.64f

        private const val BAR_REACH_DEG = 12.3f
    }
}

/** Who made a model, or imagery in one, and on what terms. Listed under 设置 › 致谢, as CC BY (or NASA) asks. */
data class Credit(val title: String, val author: String, val licence: String, val url: String)

enum class ViewingSeat(@StringRes val label: Int) {
    LEFT_WINDOW(R.string.pc_seat_left_window),
    LEFT(R.string.pc_seat_left),
    CENTER(R.string.pc_seat_center),
    RIGHT(R.string.pc_seat_right),
    RIGHT_WINDOW(R.string.pc_seat_right_window),
}

/** Centre of an authored chair on the room's seating floor, in glTF coordinates. */
data class Chair(val seat: ViewingSeat, val x: Float, val z: Float)

data class SeatPose(val x: Float, val y: Float, val z: Float, val yaw: Float) {
    /** Apply the model holder's transform, R(yaw) · scale · (point − seat). */
    fun stagePoint(px: Float, py: Float, pz: Float, scale: Float): FloatArray {
        val angle = Math.toRadians(yaw.toDouble())
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        val dx = (px - x) * scale
        val dz = (pz - z) * scale
        return floatArrayOf(dx * c + dz * s, (py - y) * scale, -dx * s + dz * c)
    }
}

/**
 * A place to watch in: a model drawn around the video window on a Full-Space stage.
 *
 * The seat is the spot in the model's own units, before [scale], where the viewer sits; its y is
 * the floor there, because the stage's origin is the floor under the viewer. [yaw] is the turn about
 * the vertical that brings the direction the seat faces onto the viewer's forward: 0 when the seat
 * already faces −z, 90 when it faces +x. [light] is the stage's environment light as the SDK's exponent
 * (the Welcome Space sample's 8; each step doubles it), which PBR materials need and unlit, baked ones ignore.
 */
data class Environment(
    val id: String,
    @StringRes val label: Int,
    @DrawableRes val thumb: Int,
    val source: EnvSource,
    val seatX: Float = 0f,
    val seatY: Float = 0f,
    val seatZ: Float = 0f,
    val yaw: Float = 0f,
    val scale: Float = 1f,
    val light: Float = 8f,
    val credit: Credit? = null,
    /** The screen the film can play on here, or null where it stays in its window. */
    val screen: Screen? = null,
    val otherChairs: List<Chair> = emptyList(),
) {
    val seatChoices: List<ViewingSeat>
        get() = ViewingSeat.entries.filter { it == ViewingSeat.CENTER || otherChairs.any { chair -> chair.seat == it } }

    fun savedSeat(key: String?): ViewingSeat =
        otherChairs.firstOrNull { it.seat.name == key }?.seat ?: ViewingSeat.CENTER

    fun seatPose(seat: ViewingSeat): SeatPose {
        val chair = otherChairs.firstOrNull { it.seat == seat } ?: return SeatPose(seatX, seatY, seatZ, yaw)
        val turn = screen?.let { Math.toDegrees(atan2(it.x - chair.x, chair.z - it.z).toDouble()).toFloat() } ?: yaw
        return SeatPose(chair.x, seatY, chair.z, turn)
    }

    fun origin(seat: ViewingSeat = ViewingSeat.CENTER): FloatArray =
        seatPose(seat).stagePoint(0f, 0f, 0f, scale)

    /**
     * Where [screen] lands on the stage — moved as the model is, R(yaw)·(scale·(screen − seat)), and
     * turned with it — or null where there is none.
     */
    fun screenPose(seat: ViewingSeat = ViewingSeat.CENTER): ScreenPose? {
        val sc = screen ?: return null
        val pose = seatPose(seat)
        val (x, y, z) = pose.stagePoint(sc.x, sc.y, sc.z, scale)
        val width = sc.width * scale
        return ScreenPose(x, y, z, pose.yaw + sc.facing, width, width / sc.aspect, sc.backing)
    }
}

/**
 * The environments on offer, in the order the picker shows them: our own places, each built from
 * nothing in Blender by a script of tools/envbake/scenes/ and baked in light layers (2026-10-26, the
 * user: only the places we made — the downloaded models, the PICO sample's living room and the
 * Sketchfab ones packed by tools/envpack, are gone). Scans in them are Poly Haven's, CC0: no credit owed; the lounge's
 * Earth is NASA's, credited.
 */
object Environments {

    const val DEFAULT_ID = "private_theater"

    val all: List<Environment> = listOf(
        Environment(
            id = "private_theater",
            label = R.string.env_private_theater,
            thumb = R.drawable.env_private_theater,
            source = EnvSource.Asset("env/private_theater.glb"),
            // Ours, built in Blender (tools/envbake/scenes/private_theater.py) and baked in light layers — the
            // screen's glow, and the room's own lights over it for 灯光 to switch. The second row's middle recliner,
            // one either side and no others: 5.4 m from a 5.4 m screen (53° wide) — the back row's 7 m sat a
            // little far — its centre a little above a seated eye, the arms either side.
            seatX = 0f, seatY = 0.36f, seatZ = 5.37f, yaw = 0f,
            otherChairs = listOf(Chair(ViewingSeat.LEFT, -0.86f, 5.37f), Chair(ViewingSeat.RIGHT, 0.86f, 5.37f)),
            // The scope screen on the front wall, the film two centimetres in front of its face and the backing's
            // black between: what the film leaves of the screen (a narrower film's sides, a sliver at the edge)
            // is dark as a cinema's masking, not the lit white face. Its border goes behind the frame.
            screen = Screen(x = 0f, y = 2.0798f, z = 0.07f, width = 5.4f, facing = 0f, aspect = 2.39f, backing = true),
        ),
        Environment(
            id = "village_night",
            label = R.string.env_village_night,
            thumb = R.drawable.env_village_night,
            source = EnvSource.Asset("env/village_night.glb"),
            // Ours, built in Blender (tools/envbake/scenes/village_night.py) after the user's references: an open-air screen
            // in a hill village's square at night, baked in light layers — the moon, and the village's lanterns and
            // windows for 灯光 to switch, the film's light on the cobbles. A timber armchair on a jute rug, 8.6 m from a
            // 5.4 m screen (35° wide).
            seatX = 0f, seatY = 0f, seatZ = 0f, yaw = 0f,
            // The canvas in its timber frame, the film two centimetres in front of it on the dark backing.
            screen = Screen(x = 0f, y = 2.2f, z = -8.58f, width = 5.4f, facing = 0f, aspect = 2f, backing = true),
            otherChairs = listOf(Chair(ViewingSeat.LEFT, -2.10f, -0.70f), Chair(ViewingSeat.RIGHT, 1.75f, -1.30f)),
        ),
        Environment(
            id = "space_lounge",
            label = R.string.env_space_lounge,
            thumb = R.drawable.env_space_lounge,
            source = EnvSource.Asset("env/space_lounge.glb"),
            // Ours, built in Blender (tools/envbake/scenes/space_lounge.py) after the user's references: a station's
            // observation lounge under the Earth and the Milky Way, baked in light layers — the Earth's light, and the
            // lounge's own lines, lamps and uplit planters for 灯光 to switch, the film's light on the marble. A velvet
            // club chair in the middle of the hall, 11.5 m from a 6 m screen (29° wide). The Earth in its windows is NASA's
            // Blue Marble map on a sphere, credited as NASA asks.
            seatX = 0f, seatY = 0f, seatZ = 0f, yaw = 0f,
            credit = Credit(
                title = "Blue Marble: Next Generation",
                author = "NASA Earth Observatory",
                licence = "NASA imagery, free to use with credit",
                url = "https://science.nasa.gov/earth/earth-observatory/blue-marble-next-generation/",
            ),
            // The screen in its dark panel at the hall's end, the film two centimetres in front of it on the backing.
            screen = Screen(x = 0f, y = 2.45f, z = -11.45f, width = 6f, facing = 0f, aspect = 2f, backing = true),
            otherChairs = listOf(
                Chair(ViewingSeat.LEFT_WINDOW, -3.12f, -4.60f), Chair(ViewingSeat.LEFT, -1.90f, -4.35f),
                Chair(ViewingSeat.RIGHT, 1.90f, -4.35f), Chair(ViewingSeat.RIGHT_WINDOW, 3.12f, -4.60f),
            ),
        ),
        Environment(
            id = "desert_camp",
            label = R.string.env_desert_camp,
            thumb = R.drawable.env_desert_camp,
            source = EnvSource.Asset("env/desert_camp.glb"),
            // Ours, built in Blender (tools/envbake/scenes/desert_camp.py) after the user's references: the high desert at
            // dusk among Joshua trees and granite boulders, baked in light layers — the dusk sky and its afterglow, and the
            // trailer's lamps and the lanterns for 灯光 to switch, the film's light on the sand. A wooden armchair on a woven
            // rug, 8.8 m from a 4.6 m screen (29° wide).
            seatX = 0f, seatY = 0f, seatZ = 0f, yaw = 0f,
            // The screen on its frame, the film two centimetres in front of it on the backing.
            screen = Screen(x = 0f, y = 2.03f, z = -8.77f, width = 4.6f, facing = 0f, aspect = 2f, backing = true),
        ),
    )

    fun byId(id: String?): Environment? = all.firstOrNull { it.id == id }

    /** A model tried from the device, seated at its origin until `-e aura.env_pose` says otherwise. */
    fun fromFile(path: String) = Environment(
        id = "file",
        label = R.string.env_file,
        thumb = R.drawable.env_private_theater,
        source = EnvSource.File(path),
    )
}
