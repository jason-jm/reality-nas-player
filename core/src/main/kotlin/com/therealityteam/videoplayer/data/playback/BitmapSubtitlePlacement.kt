package com.therealityteam.videoplayer.data.playback

/** Rectangle in its owner's coordinate system, measured from the top left. */
data class FrameRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    companion object {
        val FULL = FrameRect(0f, 0f, 1f, 1f)
    }
}

/** The image's authored anchor and dimensions, in fractions of the source video frame. */
data class BitmapSubtitlePlacement(
    val x: Float, val y: Float, val width: Float, val height: Float?,
    val anchorX: Float, val anchorY: Float,
) {
    /** Pixel bounds on the destination face. A missing authored height keeps the bitmap's aspect. */
    fun bounds(frame: FrameRect, faceWidth: Float, faceHeight: Float, bitmapAspect: Float, scale: Float = 1f): FrameRect {
        val frameWidth = frame.width * faceWidth
        val frameHeight = frame.height * faceHeight
        val w = width * frameWidth * scale
        val h = height?.let { it * frameHeight * scale } ?: (w / bitmapAspect)
        return FrameRect(
            frame.left * faceWidth + x * frameWidth - anchorX * w,
            frame.top * faceHeight + y * frameHeight - anchorY * h,
            w, h,
        )
    }
}
