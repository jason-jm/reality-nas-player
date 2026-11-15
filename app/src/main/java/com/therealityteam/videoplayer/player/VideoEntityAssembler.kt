package com.therealityteam.videoplayer.player

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.VideoComponent
import com.pico.spatial.core.ecs.VideoPlayerComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.SurfaceRenderTexture
import com.pico.spatial.core.ecs.resource.VideoMaterial
import com.pico.spatial.core.ecs.video.CypressMediaPlayer
import com.pico.spatial.core.ecs.video.VideoDimensionMode
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import com.therealityteam.videoplayer.data.playback.FrameCrop
import com.therealityteam.videoplayer.data.playback.PanoLayout
import com.therealityteam.videoplayer.data.playback.Panorama
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Binds a video source to a rendering [Entity], along either of the SDK's two paths:
 * - [VideoPlayerComponent] + the built-in [CypressMediaPlayer];
 * - [VideoComponent] + a [SurfaceRenderTexture] whose Surface any third-party player
 *   (here: ExoPlayer) renders into — the official architecture for custom engines.
 * Shapes: flat rounded panel (Shared Space) or inverted sphere (Full Space immersion),
 * with MONO/SBS/OU set per the detected 3D mode.
 */
object VideoEntityAssembler {

    const val CORNER_RADIUS = 0.02f

    /**
     * Flat video panel mesh + material bound to the player. The panel's depth placement is
     * handled by the layout (depth(0) + alignDepth(DepthBack) in VideoScreen), which pins it
     * to the back of the window so the Toolbar in front is never occluded — no manual Z push.
     */
    fun assemblePanel(
        entity: Entity,
        player: CypressMediaPlayer,
        widthMeters: Float,
        heightMeters: Float,
        dimension: VideoDimensionMode = VideoDimensionMode.MONO,
    ) {
        val mesh = MeshResource.createVideoPanel(widthMeters, heightMeters, CORNER_RADIUS)
        val material = VideoMaterial(BlendingMode.OPAQUE, dimension, MaterialCullingMode.BACK)
        entity.components.set(VideoPlayerComponent(player, mesh, material))
    }

    /**
     * Inverted sphere for 180/360 immersive viewing — culling FRONT renders the inner
     * surface so the viewer sees the video from inside the sphere.
     */
    fun assembleSphere(
        entity: Entity,
        player: CypressMediaPlayer,
        radius: Float = 50f,
        dimension: VideoDimensionMode = VideoDimensionMode.MONO,
    ) {
        val mesh = MeshResource.createSphere(radius)
        val material = VideoMaterial(BlendingMode.OPAQUE, dimension, MaterialCullingMode.FRONT)
        entity.components.set(VideoPlayerComponent(player, mesh, material))
    }

    /**
     * A flat panel's mesh, apart from its entity, so that it can be made off the main thread: a
     * resource may be, its component may not (see PlaybackViewModel.preparePanel). With a [crop],
     * the panel shows only the picture inside the frame's black bars — the SDK's panel shows the
     * whole frame, so that one is made here, the same rounded rectangle with its texture coordinates
     * drawn in to the picture.
     */
    fun panelMesh(
        widthMeters: Float,
        heightMeters: Float,
        cornerRadius: Float = CORNER_RADIUS,
        crop: FrameCrop = FrameCrop.NONE,
    ): MeshResource =
        if (!crop.crops) MeshResource.createVideoPanel(widthMeters, heightMeters, cornerRadius)
        else croppedPanel(widthMeters, heightMeters, cornerRadius, crop)

    /**
     * A rounded rectangle facing +Z, fanned from its centre, its corners [CORNER_STEPS] segments
     * each; texture coordinates run over [crop]'s picture only (u left to right, v bottom to top).
     */
    private fun croppedPanel(w: Float, h: Float, radius: Float, crop: FrameCrop): MeshResource {
        val r = radius.coerceIn(0f, minOf(w, h) / 2f)
        val outline = ArrayList<Pair<Float, Float>>()
        // Counter-clockwise seen from the front: top-right, top-left, bottom-left, bottom-right.
        val centres = listOf(w / 2 - r to h / 2 - r, -w / 2 + r to h / 2 - r, -w / 2 + r to -h / 2 + r, w / 2 - r to -h / 2 + r)
        centres.forEachIndexed { corner, (cx, cy) ->
            for (step in 0..CORNER_STEPS) {
                val a = (corner + step / CORNER_STEPS.toFloat()) * (PI / 2).toFloat()
                outline += (cx + r * cos(a)) to (cy + r * sin(a))
            }
        }
        val points = listOf(0f to 0f) + outline
        val uSpan = 1f - crop.left - crop.right
        val vSpan = 1f - crop.top - crop.bottom
        val positions = points.map { (x, y) -> Vector3(x, y, 0f) }
        // The video texture's v runs up from its bottom row, as GL's do (top-down, the picture stood on its head).
        val uv0 = points.map { (x, y) -> Vector2(crop.left + (x / w + 0.5f) * uSpan, crop.bottom + (y / h + 0.5f) * vSpan) }
        val indices = ArrayList<Int>()
        for (i in 1..outline.size) indices += listOf(0, i, if (i == outline.size) 1 else i + 1)
        val model = MeshModel(
            positions = positions,
            triangleIndices = indices,
            normals = points.map { Vector3(0f, 0f, 1f) },
            uv0 = uv0,
        )
        val bounds = BoundingBox(Vector3.ZERO, Vector3(w / 2, h / 2, 0.001f))
        return MeshResource.createWithMeshModel(model, bounds, "aura_cropped_panel")
    }

    private const val CORNER_STEPS = 8

    /** Flat panel fed by an external player through [texture]'s Surface; square with [cornerRadius] 0. */
    fun assembleSurfacePanel(
        entity: Entity,
        texture: SurfaceRenderTexture,
        widthMeters: Float,
        heightMeters: Float,
        dimension: VideoDimensionMode = VideoDimensionMode.MONO,
        cornerRadius: Float = CORNER_RADIUS,
        prepared: MeshResource? = null,
        crop: FrameCrop = FrameCrop.NONE,
    ) {
        val mesh = prepared ?: panelMesh(widthMeters, heightMeters, cornerRadius, crop)
        val material = VideoMaterial(BlendingMode.OPAQUE, dimension, MaterialCullingMode.BACK)
        material.bindSurfaceRenderTexture(texture)
        entity.components.set(VideoComponent(mesh, material))
    }

    /**
     * The mesh a 360°/180° picture is shown on round the viewer ([Panorama]): its own shape for its
     * projection — a whole sphere, a VR180's front half, a fisheye's dome, a cubemap's six faces —
     * with the texture coordinates of each point. A resource, so it may be made off the main thread.
     */
    fun panoMesh(layout: PanoLayout, radius: Float = SPHERE_RADIUS): MeshResource {
        val m = Panorama.mesh(layout, radius)
        val n = m.vertexCount
        val positions = ArrayList<Vector3>(n)
        val normals = ArrayList<Vector3>(n)
        val uv0 = ArrayList<Vector2>(n)
        for (k in 0 until n) {
            val x = m.positions[3 * k]
            val y = m.positions[3 * k + 1]
            val z = m.positions[3 * k + 2]
            positions += Vector3(x, y, z)
            // Facing the viewer at the centre.
            normals += Vector3(-x / radius, -y / radius, -z / radius)
            uv0 += Vector2(m.uvs[2 * k], m.uvs[2 * k + 1])
        }
        val model = MeshModel(positions = positions, triangleIndices = m.indices.toList(), normals = normals, uv0 = uv0)
        val bounds = BoundingBox(Vector3.ZERO, Vector3(radius, radius, radius))
        return MeshResource.createWithMeshModel(model, bounds, "aura_pano_${layout.projection.name.lowercase()}")
    }

    /**
     * A 360°/180° picture on [mesh], fed by an external player through [texture]'s Surface. Both faces
     * drawn: the viewer is inside, and each projection's mesh winds its own way.
     */
    fun assembleSurfacePano(entity: Entity, texture: SurfaceRenderTexture, mesh: MeshResource, dimension: VideoDimensionMode) {
        val material = VideoMaterial(BlendingMode.OPAQUE, dimension, MaterialCullingMode.NONE)
        material.bindSurfaceRenderTexture(texture)
        entity.components.set(VideoComponent(mesh, material))
    }

    /**
     * The window as a window onto a panorama: its face a portal (PortalMaterial) into [world], a world of its
     * own holding the sphere — seen from where the eyes are, both of them, and turned without a mesh made.
     */
    fun assemblePortal(panel: Entity, world: Entity, width: Float, height: Float) {
        panel.components.remove(VideoComponent::class.java)
        panel.components.set(com.pico.spatial.core.ecs.ModelComponent(MeshResource.createVideoPanel(width, height, CORNER_RADIUS), com.pico.spatial.core.ecs.resource.PortalMaterial()))
        panel.components.set(com.pico.spatial.core.ecs.PortalComponent(world, true, false, false, true))
    }

    /** The window's face a plain panel again: no portal. */
    fun clearPortal(panel: Entity) {
        panel.components.remove(com.pico.spatial.core.ecs.PortalComponent::class.java)
        panel.components.remove(com.pico.spatial.core.ecs.ModelComponent::class.java)
    }

    /** The same, played by the SDK's own player. */
    fun assemblePano(entity: Entity, player: CypressMediaPlayer, mesh: MeshResource, dimension: VideoDimensionMode) {
        val material = VideoMaterial(BlendingMode.OPAQUE, dimension, MaterialCullingMode.NONE)
        entity.components.set(VideoPlayerComponent(player, mesh, material))
    }

    /** How far round the viewer a 360°/180° picture is hung: far enough that both eyes see it at infinity. */
    const val SPHERE_RADIUS = 50f

    /** Immersive sphere fed by an external player through [texture]'s Surface. */
    fun assembleSurfaceSphere(
        entity: Entity,
        texture: SurfaceRenderTexture,
        radius: Float = 50f,
        dimension: VideoDimensionMode = VideoDimensionMode.MONO,
    ) {
        val mesh = MeshResource.createSphere(radius)
        val material = VideoMaterial(BlendingMode.OPAQUE, dimension, MaterialCullingMode.FRONT)
        material.bindSurfaceRenderTexture(texture)
        entity.components.set(VideoComponent(mesh, material))
    }
}
