package com.therealityteam.videoplayer.data.playback

import android.content.Context
import java.io.File

/**
 * The fonts libass draws ASS with — mpv's (MpvPlaybackManager) and the app's own ([AssRenderer]):
 * fontconfig over the headset's fonts. A subtitle's font the headset lacks (a fansub's 方正准圆,
 * unless the file carries it) falls back to sans-serif: PICO Sans, the system's own face, then
 * Noto CJK for whatever it lacks. fontconfig's cache goes in the app's: the first scan of
 * /system/fonts takes seconds, the next a moment.
 */
object SubtitleFonts {

    /** The directory holding [conf] — mpv's config dir, which holds nothing else. */
    fun dir(context: Context): File = File(context.filesDir, "mpv")

    /** fonts.conf, written if it is not there as this build writes it. Not on the main thread. */
    @Synchronized
    fun conf(context: Context): File {
        val file = File(dir(context), "fonts.conf")
        val order = cjkOrder(java.util.Locale.getDefault())
        val cjk = order.joinToString("") { "<family>Noto Sans CJK $it</family>" }
        // PICO Sans draws the mainland's Han: first for a reader of that, after their own face for any other CJK reader.
        val sans = if (order.first() == "SC") "<family>PICO Sans SC</family>$cjk" else "$cjk<family>PICO Sans SC</family>"
        val text = FONTS_CONF.replace("@CACHE@", File(context.cacheDir, "fontconfig").absolutePath)
            .replace("@SANS@", sans)
            .replace("@CJK_SERIF@", cjkOrder(java.util.Locale.getDefault()).joinToString("") { "<family>Noto Serif CJK $it</family>" })
        try {
            if (!file.exists() || file.readText() != text) {
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
        } catch (e: java.io.IOException) {
            android.util.Log.w("AuraFonts", "fonts.conf not written: ASS has only the files' own fonts", e)
        }
        return file
    }

    /**
     * The order of Noto CJK's faces for a reader: one character is drawn differently in Japan, Korea, Taiwan and
     * the mainland (直, 骨, 角…), and libass asks fontconfig by family alone — so a Japanese reader's subtitles take
     * the JP face first, a Korean's the KR, a Traditional reader's the TC (HK in Hong Kong), everyone else's the SC.
     */
    internal fun cjkOrder(locale: java.util.Locale): List<String> = when {
        locale.language == "ja" -> listOf("JP", "KR", "SC", "TC")
        locale.language == "ko" -> listOf("KR", "JP", "SC", "TC")
        locale.language == "zh" && (locale.country == "HK" || locale.country == "MO") -> listOf("HK", "TC", "SC", "JP")
        locale.language == "zh" && (locale.script == "Hant" || locale.country == "TW") -> listOf("TC", "HK", "SC", "JP")
        else -> listOf("SC", "TC", "JP", "KR")
    }

    // PICO Sans and Noto CJK in the reader's order (above), Roboto and Noto Sans for what they lack, Noto Sans Thai
    // for Thai — libass falls back character by character down this list.
    private const val FONTS_CONF = """<?xml version="1.0"?>
<fontconfig>
  <dir>/system/fonts</dir>
  <dir>/product/fonts</dir>
  <cachedir>@CACHE@</cachedir>
  <alias><family>sans-serif</family><prefer>@SANS@<family>Roboto</family><family>Noto Sans</family><family>Noto Sans Thai</family></prefer></alias>
  <alias><family>serif</family><prefer>@CJK_SERIF@<family>Noto Serif</family><family>Noto Serif Thai</family></prefer></alias>
  <alias><family>monospace</family><prefer><family>Droid Sans Mono</family></prefer></alias>
</fontconfig>
"""
}
