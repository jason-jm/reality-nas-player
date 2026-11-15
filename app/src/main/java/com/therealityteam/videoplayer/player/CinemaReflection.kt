package com.therealityteam.videoplayer.player

import android.util.Log
import com.pico.spatial.core.ecs.DynamicShadowComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.VideoComponent
import com.pico.spatial.core.ecs.VideoPlayerComponent
import com.pico.spatial.core.ecs.resource.AssetBundle
import com.pico.spatial.core.ecs.resource.ShaderGraphMaterial
import com.pico.spatial.core.ecs.video.VideoBlurConfig
import com.pico.spatial.core.ecs.video.VideoTextureSampleMode
import com.pico.spatial.core.math.Vector3

/** A narrow reflective strip around the physical screen. The mesh has a hole over the entire picture. */
internal class CinemaReflection {
    private var bundle: AssetBundle? = null
    private var bezel: Entity? = null
    private var requested = false
    private var unavailable = false

    fun fit(screen: Entity, width: Float, height: Float, depth: Float) {
        if (unavailable) return
        val frame = bezel ?: runCatching {
            val assets = AssetBundle.load("cinema-glow.bundle", LoadType.FROM_ASSETS)
            bundle = assets
            val mesh = assets.loadMeshResource("Scenes/CinemaGlow/Root/Bezel")
            val finish = ShaderGraphMaterial.loadFromAssetBundle(assets, "Scenes/CinemaGlow/Root/ScreenReflection")
            Entity().apply {
                setName("aura_screen_reflection")
                enabled = false
                components.set(TransformComponent())
                components.set(ModelComponent(mesh, finish))
                components.set(DynamicShadowComponent(false, false))
                screen.addChild(this)
            }
        }.onFailure {
            unavailable = true
            Log.w(TAG, "Could not load the screen bezel", it)
        }.getOrNull()?.also { bezel = it } ?: return
        frame.components[TransformComponent::class.java]?.apply {
            setScaleVector(Vector3(width, height, 1f))
            setPosition(Vector3(0f, 0f, depth))
        }
    }

    /** A newly attached video component starts with sampling off even if the previous one was on. */
    fun sourceChanged() {
        requested = false
        bezel?.enabled = false
    }

    fun show(screen: Entity, visible: Boolean) {
        val frame = bezel ?: return
        if (visible == requested) return
        requested = visible
        val mode = if (visible) VideoTextureSampleMode.RAW_AND_BLURRED else VideoTextureSampleMode.NONE
        val surface = screen.components[VideoComponent::class.java]
        val player = screen.components[VideoPlayerComponent::class.java]
        val applied = when {
            surface != null -> {
                if (visible) surface.setTextureSampleName(SAMPLE_NAME)
                surface.setTextureSampleMode(mode)
                visible && surface.setVideoBlurConfig(BLUR)
            }
            player != null -> {
                if (visible) player.setTextureSampleName(SAMPLE_NAME)
                player.setTextureSampleMode(mode)
                visible && player.setVideoBlurConfig(BLUR)
            }
            else -> false
        }
        if (visible && !applied) {
            surface?.setTextureSampleMode(VideoTextureSampleMode.NONE)
            player?.setTextureSampleMode(VideoTextureSampleMode.NONE)
            Log.w(TAG, "Video component declined the screen reflection")
        }
        frame.enabled = applied
        if (applied) Log.d(TAG, "Cinema bezel sampling the blurred video texture")
    }

    fun release(screen: Entity?) {
        screen?.let { show(it, false) }
        bezel?.let { if (it.valid) it.destroy() }
        bezel = null
        bundle?.close()
        bundle = null
        unavailable = false
    }

    private companion object {
        const val TAG = "AuraScreen"
        // Matches the Video Texture LOD node in CinemaGlow.usda.
        const val SAMPLE_NAME = "auraCinema"
        val BLUR = VideoBlurConfig(isBlurEnabled = true, width = 32, height = 32, refreshIntervalFrames = 3)
    }
}
