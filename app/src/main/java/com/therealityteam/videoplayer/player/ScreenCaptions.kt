package com.therealityteam.videoplayer.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Vector3
import com.therealityteam.videoplayer.data.playback.CuePlacement
import com.therealityteam.videoplayer.data.playback.FrameRect
import com.therealityteam.videoplayer.data.playback.SubtitleCue
import com.therealityteam.videoplayer.data.playback.SubtitleFont
import com.therealityteam.videoplayer.data.playback.SubtitleStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** How the window draws subtitles, for a place's screen to draw them the same. */
data class CaptionLook(
    val scale: Float,
    /** Lifted off the bottom, as a share of the face's height (the window's 字幕位置). */
    val raise: Float,
    val style: SubtitleStyle,
    val font: SubtitleFont,
    val color: Int,
    val outlineDp: Int,
    val keepStyles: Boolean,
    /** A 字幕 panel is open: its bottom lane joins the top one, as in the window. */
    val preview: Boolean,
    /** A flat film's frame on this face. The panorama's head-following caption panel has no film frame. */
    val frameRect: FrameRect? = null,
)

/**
 * Subtitles on a place's screen (在环境的屏幕上播放): the lines the window would draw, drawn into a
 * picture and laid on the screen's face, so they are read where the film is. Left in the window they
 * hung two metres nearer than the TV they belong to, and the eyes had to leave the film to read them.
 *
 * [entity] is a child of the screen; a quad a centimetre in front of the film, as wide as the face and
 * as tall as the band the lines take. The picture is painted off the main thread and becomes the
 * quad's texture on it; the one it replaces is closed then.
 */
class ScreenCaptions(private val scope: CoroutineScope) {

    val entity: Entity = Entity().apply {
        setName("aura_screen_captions")
        enabled = false
    }

    private var material: UnlitMaterial? = null
    private var texture: TextureResource? = null
    private var bitmap: Bitmap? = null
    private var job: Job? = null

    /**
     * A fansub's .ass as libass drew it (AssRenderer), on its own quad beside [entity]'s lines: the
     * picture is the box that has something in it, laid where it lies on the film.
     */
    val assEntity: Entity = Entity().apply {
        setName("aura_screen_ass")
        enabled = false
    }
    private var assMaterial: UnlitMaterial? = null
    private var assTexture: TextureResource? = null
    private var assBitmap: Bitmap? = null

    /** The face the lines are laid on, in metres, and how much larger than the window's they are set on it. */
    private var faceW = 1.6f
    private var faceH = 0.9f
    /** How much larger than the window's the lines are set on this face (see [fit]) — libass's too. */
    var boost = 1f
        private set
    private var lastCues: List<SubtitleCue> = emptyList()
    private var lastLook: CaptionLook? = null

    /**
     * The screen's face is [width] × [height] metres, [distance] from the viewer: lines laid out for
     * another face are laid out again. A screen narrower to the eye than the window sets its lines
     * larger for it — the window's share of the picture on a TV across the room is a line too small
     * to read — up to [MAX_BOOST], as a TV's own subtitles are.
     */
    fun fit(width: Float, height: Float, distance: Float) {
        val degrees = Math.toDegrees(2.0 * kotlin.math.atan2(width / 2.0, distance.toDouble())).toFloat()
        val wanted = (REFERENCE_DEGREES / degrees.coerceAtLeast(1f)).coerceIn(1f, MAX_BOOST)
        if (width == faceW && height == faceH && wanted == boost) return
        faceW = width
        faceH = height
        boost = wanted
        lastLook?.let { show(lastCues, it) }
    }

    /** Shows [cues] as [look] dresses them, or nothing when there are none. */
    fun show(cues: List<SubtitleCue>, look: CaptionLook) {
        lastCues = cues
        lastLook = look
        job?.cancel()
        if (cues.isEmpty()) {
            entity.enabled = false
            return
        }
        val aspect = faceW / faceH
        val boost = boost
        job = scope.launch {
            val band = withContext(Dispatchers.Default) {
                runCatching { CaptionPainter.paint(cues, look, aspect, boost) }
                    .onFailure { Log.w(TAG, "captions not painted: ${it.message}") }
                    .getOrNull()
            }
            if (band == null) {
                entity.enabled = false
                return@launch
            }
            apply(band)
        }
    }

    private fun apply(band: CaptionPainter.Band) {
        runCatching {
            val m = material ?: UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                // The lines' own white, not the scene's tone curve over it.
                setApplyToneMapping(false)
                setDepthWrite(false)
                toGlobal()
            }.also {
                material = it
                entity.components.set(ModelComponent(MeshResource.createPlane(1f, 1f), it))
            }
            val next = TextureResource.create(band.bitmap)
            m.setBaseColorTexture(next)
            texture?.close()
            texture = next
            bitmap?.recycle()
            bitmap = band.bitmap
            val h = faceH * band.height / band.faceHeight
            val centre = faceH * (0.5f - (band.top + band.height / 2f) / band.faceHeight)
            val t = entity.components[TransformComponent::class.java]
                ?: TransformComponent().also { entity.components.set(it) }
            t.setScaleVector(Vector3(faceW, h, 1f))
            t.setPosition(Vector3(0f, centre, LIFT))
            entity.enabled = true
        }.onFailure {
            Log.w(TAG, "captions not shown: ${it.message}")
            entity.enabled = false
        }
    }

    /**
     * libass's [picture] — a [pictureW]×[pictureH] box at ([x], [y]) of a [frameW]×[frameH] frame —
     * on the film, whose whole frame lies at [left], [top], [width] × [height] of the face (fractions;
     * past the film where 去黑边 cut). Null takes it down. The picture is copied: the renderer draws
     * into its own again.
     */
    fun showAss(picture: Bitmap?, x: Int, y: Int, frameW: Int, frameH: Int, left: Float, top: Float, width: Float, height: Float) {
        if (picture == null || frameW <= 0 || frameH <= 0) {
            assEntity.enabled = false
            return
        }
        runCatching {
            val m = assMaterial ?: UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setApplyToneMapping(false)
                setDepthWrite(false)
                toGlobal()
            }.also {
                assMaterial = it
                assEntity.components.set(ModelComponent(MeshResource.createPlane(1f, 1f), it))
            }
            val copy = picture.copy(Bitmap.Config.ARGB_8888, false)
            val next = TextureResource.create(copy)
            m.setBaseColorTexture(next)
            assTexture?.close()
            assTexture = next
            assBitmap?.recycle()
            assBitmap = copy
            val fw = faceW * width
            val fh = faceH * height
            val frameLeft = -faceW / 2f + faceW * left
            val frameTop = faceH / 2f - faceH * top
            val t = assEntity.components[TransformComponent::class.java]
                ?: TransformComponent().also { assEntity.components.set(it) }
            t.setScaleVector(Vector3(fw * copy.width / frameW, fh * copy.height / frameH, 1f))
            t.setPosition(
                Vector3(
                    frameLeft + fw * (x + copy.width / 2f) / frameW,
                    frameTop - fh * (y + copy.height / 2f) / frameH,
                    LIFT,
                ),
            )
            assEntity.enabled = true
        }.onFailure {
            Log.w(TAG, "ASS not shown: ${it.message}")
            assEntity.enabled = false
        }
    }

    /** Takes libass's picture down and lets it go. */
    fun clearAss() {
        assEntity.enabled = false
        assTexture?.let { t -> runCatching { t.close() } }
        assTexture = null
        assBitmap?.recycle()
        assBitmap = null
    }

    /** Takes the lines down and lets their picture go; the next [show] paints anew. */
    fun clear() {
        job?.cancel()
        job = null
        lastCues = emptyList()
        lastLook = null
        entity.enabled = false
        texture?.let { t -> runCatching { t.close() } }
        texture = null
        bitmap?.recycle()
        bitmap = null
    }

    private companion object {
        const val TAG = "AuraScreen"

        /** How far in front of the film the lines float: enough to never sink into it. */
        const val LIFT = 0.01f

        /** How wide the window looks from where it opens, about: lines are set for a picture this wide to the eye. */
        const val REFERENCE_DEGREES = 50f

        /** The most a narrower screen enlarges its lines: on a TV across the room, a TV's own share of its height. */
        const val MAX_BOOST = 1.6f
    }
}

/**
 * Lays the lines out as the window does — its 1280dp across, its lanes, paddings, sizes and looks
 * (see VideoScreen's SubtitleOverlay) — on a face of the screen's shape, and paints only the band
 * they take, [FACE_PX] wide.
 */
internal object CaptionPainter {

    /** The window's width in dp: every size below is the window's, scaled to the face. */
    private const val WINDOW_DP = 1280f

    /** Pixels across the face: finer than a headset resolves a screen filling a wall at its distance. */
    const val FACE_PX = 1920

    /** The band the lines take, in pixels of a face [faceHeight] tall, and its picture. */
    class Band(val bitmap: Bitmap, val top: Float, val height: Float, val faceHeight: Float)

    private class Block(val height: Float, val draw: (Canvas, Float) -> Unit)

    /**
     * [boost] enlarges the lines themselves — their size, edge, shadow, box and gaps — but not where
     * the lanes sit: those keep the window's share of the face.
     */
    fun paint(cues: List<SubtitleCue>, look: CaptionLook, aspect: Float, boost: Float = 1f): Band? {
        val k = FACE_PX / WINDOW_DP
        val kt = k * boost
        val faceHeight = FACE_PX / aspect
        val laneWidth = FACE_PX - 2 * LANE_SIDE_DP * k
        val pictures = mutableListOf<Pair<Bitmap, RectF>>()
        val frame = look.frameRect
        val laneCues = mutableListOf<SubtitleCue>()
        for (cue in cues) {
            val image = cue.bitmap
            val bounds = frame?.let { cue.pictureBounds(it, FACE_PX.toFloat(), faceHeight, look.scale,
                look.raise * faceHeight, look.preview) }
            if (image == null || bounds == null) {
                laneCues.add(cue)
            } else {
                val rect = RectF(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height)
                if (rect.intersects(0f, 0f, FACE_PX.toFloat(), faceHeight)) pictures.add(image to rect)
            }
        }
        val lanes = laneCues.groupBy { if (look.preview && it.placement == CuePlacement.BOTTOM) CuePlacement.TOP else it.placement }
        val placed = lanes.mapNotNull { (lane, group) ->
            val blocks = group.mapNotNull { block(it, look, k, kt, laneWidth) }
            if (blocks.isEmpty()) return@mapNotNull null
            val height = blocks.sumOf { (LINE_GAP_DP * kt + it.height).toDouble() }.toFloat()
            val top = when (lane) {
                CuePlacement.TOP -> TOP_DP * k
                CuePlacement.MIDDLE -> (faceHeight - height) / 2f
                CuePlacement.BOTTOM -> faceHeight - (BOTTOM_DP * k + look.raise * faceHeight) - height
            }
            Triple(top, height, blocks)
        }
        if (placed.isEmpty() && pictures.isEmpty()) return null
        val margin = MARGIN_DP * kt
        val top = minOf(placed.minOfOrNull { it.first - margin } ?: faceHeight,
            pictures.minOfOrNull { it.second.top } ?: faceHeight)
        val bottom = maxOf(placed.maxOfOrNull { it.first + it.second + margin } ?: 0f,
            pictures.maxOfOrNull { it.second.bottom } ?: 0f)
        val bandTop = kotlin.math.floor(max(0f, top))
        val bandBottom = kotlin.math.ceil(min(faceHeight, bottom))
        val bandHeight = bandBottom - bandTop
        if (bandHeight < 1f) return null
        val bitmap = Bitmap.createBitmap(FACE_PX, bandHeight.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(0f, -bandTop)
        if (pictures.isNotEmpty()) {
            val filter = Paint(Paint.FILTER_BITMAP_FLAG)
            for ((image, rect) in pictures) canvas.drawBitmap(image, null, rect, filter)
        }
        for ((top, _, blocks) in placed) {
            var y = top
            for (b in blocks) {
                y += LINE_GAP_DP * kt
                b.draw(canvas, y)
                y += b.height
            }
        }
        return Band(bitmap, bandTop, bandHeight, faceHeight)
    }

    /**
     * One cue as the window's CueLine draws it: a picture (PGS / VobSub), or text in the chosen look —
     * the lane's measures in [k], the line's own in [kt] (the window's dp, enlarged for the screen).
     */
    private fun block(cue: SubtitleCue, look: CaptionLook, k: Float, kt: Float, laneWidth: Float): Block? {
        val laneLeft = LANE_SIDE_DP * k
        cue.bitmap?.let { image ->
            if (image.width <= 0 || image.height <= 0) return null
            val w = laneWidth * IMAGE_WIDTH
            val h = w * image.height / image.width
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            return Block(h) { c, y ->
                val left = laneLeft + (laneWidth - w) / 2f
                c.drawBitmap(image, null, RectF(left, y, left + w, y + h), paint)
            }
        }
        val text = cue.text?.takeIf { it.isNotBlank() } ?: return null
        // A file's own size is relative to its picture; 48pt at 720 lines is the 24sp base.
        val sizeFactor = cue.sizeFraction?.let { (it / (48f / 720f)).coerceIn(0.6f, 1.8f) } ?: 1f
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = BASE_SP * look.scale * sizeFactor * kt
            typeface = when (look.font) {
                SubtitleFont.DEFAULT -> Typeface.DEFAULT
                SubtitleFont.SERIF -> Typeface.SERIF
                SubtitleFont.MONO -> Typeface.MONOSPACE
            }
            color = look.color
        }
        val align = when (cue.hAlign) {
            -1 -> Layout.Alignment.ALIGN_NORMAL
            1 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        // The text's room: the lane less the look's own padding either side (the box's is the same 16).
        val pad = SIDE_PAD_DP * kt
        val width = (laneWidth - 2 * pad).toInt().coerceAtLeast(1)
        fun layout(source: CharSequence, paint: TextPaint) =
            StaticLayout.Builder.obtain(source, 0, source.length, paint, width).setAlignment(align).build()
        val left = laneLeft + pad
        // Under the letters, for the shadowed and outlined looks: the same lines stroked in dark, the
        // weights kept and the colours not, as the window's stroke pass does.
        fun stroked(strokeWidth: Float, strokeColor: Int) = layout(
            styled(text, keepColours = false),
            TextPaint(fill).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                color = strokeColor
            },
        )
        return when (look.style) {
            // 阴影 (the default): a hairline of soft dark round the letters, and a shadow under them.
            SubtitleStyle.NONE -> {
                val edge = stroked(SOFT_EDGE_DP * kt, EDGE)
                fill.setShadowLayer(SHADOW_BLUR_DP * kt, 0f, SHADOW_DROP_DP * kt, SHADOW)
                val body = layout(styled(text, keepColours = look.keepStyles), fill)
                Block(body.height.toFloat()) { c, y ->
                    c.save()
                    c.translate(left, y)
                    edge.draw(c)
                    body.draw(c)
                    c.restore()
                }
            }
            // 背景框: the lines on a dark rounded box that hugs them.
            SubtitleStyle.BOX -> {
                val body = layout(styled(text, keepColours = look.keepStyles), fill)
                val padX = BOX_PAD_X_DP * kt
                val padY = BOX_PAD_Y_DP * kt
                val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BOX }
                val lineLeft = (0 until body.lineCount).minOf { body.getLineLeft(it) }
                val lineRight = (0 until body.lineCount).maxOf { body.getLineRight(it) }
                Block(body.height + 2 * padY) { c, y ->
                    c.save()
                    c.translate(left, y + padY)
                    c.drawRoundRect(
                        RectF(lineLeft - padX, -padY, lineRight + padX, body.height + padY),
                        BOX_RADIUS_DP * kt, BOX_RADIUS_DP * kt, box,
                    )
                    body.draw(c)
                    c.restore()
                }
            }
            // 描边: a real stroke under the letters, at the chosen thickness.
            SubtitleStyle.OUTLINE -> {
                val outline = stroked(look.outlineDp * 2 * kt, android.graphics.Color.BLACK)
                val body = layout(styled(text, keepColours = look.keepStyles), fill)
                Block(body.height.toFloat()) { c, y ->
                    c.save()
                    c.translate(left, y)
                    outline.draw(c)
                    body.draw(c)
                    c.restore()
                }
            }
        }
    }

    /** The cue's own weights, slants, lines and sizes — and its colours only when [keepColours]. */
    private fun styled(text: CharSequence, keepColours: Boolean): CharSequence {
        val spanned = text as? Spanned ?: return text.toString()
        val out = SpannableString(spanned.toString())
        for (span in spanned.getSpans(0, spanned.length, Any::class.java)) {
            val keep = when (span) {
                is StyleSpan, is UnderlineSpan, is StrikethroughSpan, is RelativeSizeSpan -> true
                is ForegroundColorSpan -> keepColours
                else -> false
            }
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (keep && start >= 0 && end > start) out.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    // The window's measures (VideoScreen: SubtitleOverlay, CueLine), in its dp.
    private const val BASE_SP = 24f
    private const val LANE_SIDE_DP = 48f
    private const val TOP_DP = 36f
    private const val BOTTOM_DP = 28f
    private const val LINE_GAP_DP = 4f
    private const val SIDE_PAD_DP = 16f
    private const val BOX_PAD_X_DP = 16f
    private const val BOX_PAD_Y_DP = 6f
    private const val BOX_RADIUS_DP = 10f
    private const val SOFT_EDGE_DP = 2f
    private const val SHADOW_BLUR_DP = 3f
    private const val SHADOW_DROP_DP = 1f
    private const val IMAGE_WIDTH = 0.85f

    /** Room above and below the lines for their edge and shadow. */
    private const val MARGIN_DP = 8f

    private const val EDGE = 0x8C000000.toInt()
    private const val SHADOW = 0xB3000000.toInt()
    private const val BOX = 0x99000000.toInt()
}
