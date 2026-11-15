package com.therealityteam.videoplayer.ui.immersive

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.therealityteam.videoplayer.data.playback.CaptionFollow
import com.therealityteam.videoplayer.data.playback.SoundField
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2

/**
 * The viewer at the centre of a 360°/180° picture. A panorama is filmed from one point and a stereo one
 * from one pair of eyes: seen from anywhere else, its near things slide and the eyes stop agreeing. While
 * the stage is open the head is tracked (HMDTrackingProvider, Full Space only), and [PanoFollowSystem]
 * moves the sphere to it every frame — where it is, not which way it looks: the picture stays still as
 * the head turns. [headYaw] tells 回正 which way the head looks.
 */
object PanoFollow {
    /** The sphere the stage shows (PlaybackViewModel.ensureSphere). */
    @Volatile var sphere: Entity? = null
    /** 沉浸空间's subtitles' anchor (PlaybackViewModel.captionAnchor): kept ahead of the head, lazily. */
    @Volatile var captions: Entity? = null
    /** The panorama remote turns with the subtitles and follows the viewer's chosen height. */
    @Volatile var remote: Entity? = null
    @Volatile private var remoteAtEyeLevel: StateFlow<Boolean>? = null
    private var captionsAt: CaptionFollow.State? = null
    @Volatile private var provider: HMDTrackingProvider? = null

    fun start(remoteHeight: StateFlow<Boolean>) {
        remoteAtEyeLevel = remoteHeight
        if (provider != null) return
        val p = HMDTrackingProvider()
        val result = runCatching { p.start() }
        android.util.Log.i("AuraPano", "head tracking: ${result.getOrNull() ?: result.exceptionOrNull()?.message}")
        provider = p
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
        remoteAtEyeLevel = null
    }

    /** Where the head is, in [space]'s own coordinates; null before the first pose. */
    internal fun headPosition(space: Entity): Vector3? {
        val p = provider ?: return null
        if (p.state != DataProvider.State.STARTED) return null
        val pose = runCatching { p.latestData.hmdPose }.getOrNull() ?: return null
        return space.convertPositionFrom(pose.position, null)
    }

    /**
     * Which way the head looks round the up axis, degrees counter-clockwise from [space]'s forward (−z);
     * null when the head is not tracked.
     */
    fun headYaw(space: Entity): Float? {
        val q = headRotation(space) ?: return null
        val f = q.rotateVector(Vector3(0f, 0f, -1f))
        if (f.x * f.x + f.z * f.z < 1e-6f) return null
        return Math.toDegrees(atan2(-f.x, -f.z).toDouble()).toFloat()
    }

    /**
     * The subtitles' anchor [distance] metres ahead of the head, at its height, facing it, turned
     * toward where the head points as [CaptionFollow] has it. Before the head is tracked: straight
     * ahead of [space]'s origin.
     */
    internal fun followCaptions(space: Entity, dt: Float, distance: Float) {
        val anchor = captions ?: return
        val at = headPosition(space) ?: Vector3(0f, 0f, 0f)
        val state = CaptionFollow.step(captionsAt, headYaw(space) ?: captionsAt?.yaw ?: 0f, dt)
        captionsAt = state
        val yaw = Math.toRadians(state.yaw.toDouble())
        val t = anchor.components[TransformComponent::class.java] ?: TransformComponent().also { anchor.components.set(it) }
        t.setPosition(Vector3(at.x - (kotlin.math.sin(yaw) * distance).toFloat(), at.y, at.z - (kotlin.math.cos(yaw) * distance).toFloat()))
        t.setQuaternion(Quat(Vector3(0f, 1f, 0f), yaw.toFloat()))
        // The nearby remote faces the eyes at either height.
        remote?.let { r ->
            if (!r.enabled) r.enabled = true
            val rt = r.components[TransformComponent::class.java] ?: TransformComponent().also { r.components.set(it) }
            val drop = if (remoteAtEyeLevel?.value == true) 0f else REMOTE_DROP
            rt.setPosition(Vector3(at.x - (kotlin.math.sin(yaw) * REMOTE_DISTANCE).toFloat(), at.y - drop, at.z - (kotlin.math.cos(yaw) * REMOTE_DISTANCE).toFloat()))
            val tilt = -kotlin.math.atan2(drop, REMOTE_DISTANCE)
            rt.setQuaternion(Quat(Vector3(0f, 1f, 0f), yaw.toFloat()) * Quat(Vector3(1f, 0f, 0f), tilt))
        }
    }

    /**
     * The lower position is about 34° below the eyes and keeps the middle of the panorama clear.
     */
    private const val REMOTE_DISTANCE = 0.75f
    private const val REMOTE_DROP = 0.5f

    /** The stage closed: the next one starts the lines where the head then points. */
    fun forgetCaptions() {
        captionsAt = null
    }

    /** Which way the head faces, in [space]'s own coordinates; null when it is not tracked. */
    private fun headRotation(space: Entity): Quat? {
        val p = provider ?: return null
        if (p.state != DataProvider.State.STARTED) return null
        val pose = runCatching { p.latestData.hmdPose }.getOrNull() ?: return null
        return space.convertRotationFrom(pose.rotation, null)
    }

    /**
     * The film's sound field as the head hears it ([SoundField.rotation]): each of the film's axes turned
     * as the sphere is, then seen from the head — so a sound stays on its picture however the head turns
     * and however the picture was turned (回正, 左转 / 右转, the file's own pose).
     */
    internal fun soundField(sphere: Entity, space: Entity): FloatArray {
        val turn = sphere.components[TransformComponent::class.java]?.quaternion ?: Quat.identity()
        val head = headRotation(space)?.conjugate()
        val m = FloatArray(9)
        for (c in 0..2) {
            var v = turn.rotateVector(Vector3(if (c == 0) 1f else 0f, if (c == 1) 1f else 0f, if (c == 2) 1f else 0f))
            if (head != null) v = head.rotateVector(v)
            m[c] = v.x; m[3 + c] = v.y; m[6 + c] = v.z
        }
        return m
    }
}

/**
 * Each frame: the sphere's centre to the head, the film's sound field to the head's turn (全景声), and
 * the subtitles ahead of it.
 * Registered only while the stage is open (ImmersiveScene).
 */
class PanoFollowSystem : System() {
    override fun update(context: SceneUpdateContext) {
        val sphere = PanoFollow.sphere ?: return
        // No panorama round the viewer (a place, the film still in its window): no remote for one.
        if (!sphere.enabled) { PanoFollow.remote?.takeIf { it.enabled }?.enabled = false; return }
        val space = sphere.getParent() ?: return
        SoundField.rotation = PanoFollow.soundField(sphere, space)
        PanoFollow.followCaptions(space, context.deltaTime, com.therealityteam.videoplayer.ui.player.PANO_CAPTION_DISTANCE)
        val at = PanoFollow.headPosition(space) ?: return
        val t = sphere.components[TransformComponent::class.java] ?: TransformComponent().also { sphere.components.set(it) }
        t.setPosition(at)
    }
}
