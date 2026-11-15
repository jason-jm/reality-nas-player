package com.therealityteam.videoplayer.data.playback

/**
 * How subtitle text is dressed: shadowed (the default — white text with a hairline of soft dark
 * round it and a shadow under it, so it reads on a bright frame too), on a translucent box, or with
 * a dark outline of a chosen thickness for busy pictures. NONE keeps its name: it is what settings
 * saved before the default gained its edge.
 */
enum class SubtitleStyle(val label: String) {
    NONE("阴影"),
    BOX("背景框"),
    OUTLINE("描边"),
}
