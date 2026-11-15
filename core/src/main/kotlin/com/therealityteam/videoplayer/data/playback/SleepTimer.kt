package com.therealityteam.videoplayer.data.playback

/**
 * 睡眠定时 — pause playback after a while, the way media players do for lean-back / bedtime
 * viewing. Time modes count wall-clock minutes; [END_OF_ITEM] instead lets the current file
 * finish and then stops (no auto-advance).
 */
enum class SleepMode(val label: String, val minutes: Int) {
    OFF("关闭", 0),
    M15("15 分钟", 15),
    M30("30 分钟", 30),
    M60("60 分钟", 60),
    M90("90 分钟", 90),
    END_OF_ITEM("播放完当前", -1);

    val isTimed: Boolean get() = minutes > 0
}
