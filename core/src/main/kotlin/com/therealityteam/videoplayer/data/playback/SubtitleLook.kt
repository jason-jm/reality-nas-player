package com.therealityteam.videoplayer.data.playback

/** The typeface a subtitle line is set in — the system's, or a serif or monospace face. */
enum class SubtitleFont(val label: String) {
    DEFAULT("默认"),
    SERIF("衬线"),
    MONO("等宽"),
}

/** The colour of subtitle text that carries no colour of its own. */
enum class SubtitleColor(val label: String, val argb: Long) {
    WHITE("白", 0xFFFFFFFF),
    YELLOW("黄", 0xFFFFE04D),
    CYAN("青", 0xFF7FE9FF),
    GREEN("绿", 0xFF9CFF7A),
}

/**
 * Where a cue asked to sit — an ASS `\an7`–`\an9` alignment, a WebVTT `line:` setting — reduced
 * to the three lanes the overlay honours. Anything unset lands in the bottom lane.
 */
enum class CuePlacement {
    BOTTOM, MIDDLE, TOP;

    companion object {
        /**
         * [line] as a fraction of the picture height when [lineIsFraction]; [lineAnchor] is
         * 0 start / 1 middle / 2 end (Media3's anchor constants). A line number, an unset
         * line, or one outside the picture means the default lane.
         */
        fun of(line: Float, lineIsFraction: Boolean, lineAnchor: Int): CuePlacement {
            if (!lineIsFraction || line.isNaN() || line < 0f || line > 1f) return BOTTOM
            // Roughly where the text's middle lands, given which edge the line pins.
            val centre = when (lineAnchor) {
                0 -> line + 0.05f
                2 -> line - 0.05f
                else -> line
            }
            return when {
                centre < 0.35f -> TOP
                centre <= 0.65f -> MIDDLE
                else -> BOTTOM
            }
        }
    }
}

/**
 * An ASS vector drawing — `{\p1}m 0 0 l 1920 0 1920 1080 0 1080{\p0}`, a mask behind a sign or a
 * shape in a karaoke — as Media3's reader leaves it: the tags gone, the commands left as if they
 * were words. Not a line to show ([isVectorDrawing]); mpv's own reader drops them the same way.
 */
object AssDrawing {
    /** Whether [text] is nothing but drawing commands: `m` first, then only command letters and numbers. */
    fun isVectorDrawing(text: CharSequence): Boolean {
        val tokens = text.trim().split(WHITESPACE)
        if (tokens.size < 3 || tokens[0] != "m") return false
        var numbers = 0
        for (t in tokens) {
            if (t.length == 1 && t[0] in COMMANDS) continue
            t.toDoubleOrNull() ?: return false
            numbers++
        }
        return numbers >= 2
    }

    private val WHITESPACE = Regex("""\s+""")
    private const val COMMANDS = "mnlbspc"
}
