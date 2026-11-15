package com.therealityteam.videoplayer.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.FilePlus
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.SkipBack
import com.composables.icons.lucide.SkipForward
import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.playback.PlaylistMode
import com.therealityteam.videoplayer.data.playback.SleepMode
import com.therealityteam.videoplayer.data.playback.SubtitleColor
import com.therealityteam.videoplayer.data.playback.SubtitleFont
import com.therealityteam.videoplayer.data.playback.SubtitleStyle
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.desktop.platform.Desktop
import com.therealityteam.videoplayer.desktop.platform.Pickers
import com.therealityteam.videoplayer.desktop.ui.components.AuraSlider
import com.therealityteam.videoplayer.desktop.ui.components.AuraSwitch
import com.therealityteam.videoplayer.desktop.ui.components.Divider
import com.therealityteam.videoplayer.desktop.ui.components.Icon
import com.therealityteam.videoplayer.desktop.ui.components.IconButton
import com.therealityteam.videoplayer.desktop.ui.components.PopoverHeader
import com.therealityteam.videoplayer.desktop.ui.components.PopoverRow
import com.therealityteam.videoplayer.desktop.ui.components.Segmented
import com.therealityteam.videoplayer.desktop.ui.components.Txt
import com.therealityteam.videoplayer.desktop.ui.components.clickCursor
import com.therealityteam.videoplayer.desktop.ui.library.queueTitle
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch

@Composable
fun SubtitlePanel(player: PlayerController, onClose: () -> Unit) {
    val s = LocalStrings.current
    val settings by player.graphSettings().collectAsState()
    val tracks = player.subtitleTracks
    PopoverHeader(s["pc_subtitles_n", tracks.size])
    PopoverRow(s["pc_off"], checked = tracks.none { it.selected }, onClick = { player.selectSubtitle(null) })
    if (tracks.isEmpty()) {
        Txt(s["pc_no_subtitles"], Aura.type.caption, Aura.colors.textTertiary, Modifier.padding(horizontal = 40.dp, vertical = 6.dp), maxLines = 3)
    }
    // Sidecar files share their video's name; what tells them apart is drawn strong.
    val stem = sharedSidecarStem(tracks.filter { it.external }.map { trackName(it, s) })
    tracks.forEach { t ->
        val name = trackName(t, s)
        PopoverRow(
            title = name,
            checked = t.selected,
            onClick = { player.selectSubtitle(t.id) },
            detail = trackDetail(t, s, audio = false),
            badge = languageBadge(t.language) ?: "CC",
            maxTitleLines = 3,
            sharedPrefix = if (t.external && stem != null && name.startsWith(stem)) stem.length else 0,
        )
    }
    PopoverRow(s["d_add_subtitle_file"], checked = false, onClick = {
        Pickers.chooseFiles(null, s["d_add_subtitle_file"]).firstOrNull()?.let { player.addSubtitleFile(it) }
    })
    Divider(Modifier.padding(vertical = 8.dp))
    PopoverHeader(s["pc_size_and_place"])
    LabeledSlider(s["pc_size"], "${(settings.subtitleScale * 100).toInt()}%", settings.subtitleScale, 0.6f..2.0f, 14) { v ->
        player.updateSettings { it.copy(subtitleScale = v) }
    }
    LabeledSlider(s["pc_position"], "${(settings.subtitleRaise * 100).toInt()}%", settings.subtitleRaise, 0f..0.35f, 7) { v ->
        player.updateSettings { it.copy(subtitleRaise = v) }
    }
    Stepper(s["pc_delay"], delayText(player.subtitleDelayMs), onMinus = { player.setSubtitleDelay(player.subtitleDelayMs - 100) },
        onPlus = { player.setSubtitleDelay(player.subtitleDelayMs + 100) }, onReset = { player.setSubtitleDelay(0) })
    Divider(Modifier.padding(vertical = 8.dp))
    PopoverHeader(s["pc_style"])
    Choice {
        Segmented(
            options = SubtitleStyle.entries.map { it to s["d_style_" + it.name.lowercase()] },
            selected = settings.subtitleStyle,
            onSelect = { v -> player.updateSettings { it.copy(subtitleStyle = v) } },
        )
    }
    if (settings.subtitleStyle == SubtitleStyle.OUTLINE) {
        Stepper(s["pc_outline"], settings.subtitleOutline.toString(),
            onMinus = { player.updateSettings { it.copy(subtitleOutline = (it.subtitleOutline - 1).coerceAtLeast(1)) } },
            onPlus = { player.updateSettings { it.copy(subtitleOutline = (it.subtitleOutline + 1).coerceAtMost(4)) } })
    }
    PopoverHeader(s["pc_font_and_colour"])
    Choice {
        Segmented(
            options = SubtitleFont.entries.map { it to s["d_font_" + it.name.lowercase()] },
            selected = settings.subtitleFont,
            onSelect = { v -> player.updateSettings { it.copy(subtitleFont = v) } },
        )
    }
    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SubtitleColor.entries.forEach { col ->
            val on = settings.subtitleColor == col
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(Color(col.argb))
                    .border(if (on) 3.dp else 1.dp, if (on) Aura.colors.accent else Aura.colors.strokeStrong, CircleShape)
                    .clickable { player.updateSettings { it.copy(subtitleColor = col) } }
                    .clickCursor(),
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(s["pc_keep_file_colours"], Aura.type.callout, Aura.colors.text, Modifier.weight(1f))
        AuraSwitch(settings.subtitleKeepStyles, { v -> player.updateSettings { it.copy(subtitleKeepStyles = v) } })
    }
}

@Composable
fun AudioPanel(player: PlayerController, onClose: () -> Unit) {
    val s = LocalStrings.current
    val settings by player.graphSettings().collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { player.refreshAudioDevices() }
    val tracks = player.audioTracks
    PopoverHeader(s["pc_audio_n", tracks.size])
    if (tracks.isEmpty()) PopoverRow(s["pc_default_track"], checked = true, onClick = {})
    tracks.forEach { t ->
        PopoverRow(
            title = trackName(t, s),
            checked = t.selected,
            onClick = { player.selectAudio(t.id) },
            detail = trackDetail(t, s, audio = true),
            badge = languageBadge(t.language) ?: if (t.channels > 0) player.channelLayout(t.channels) else null,
        )
    }
    Divider(Modifier.padding(vertical = 8.dp))
    PopoverHeader(s["pc_av_sync"])
    Stepper(s["pc_offset"], delayText(player.audioDelayMs), onMinus = { player.setAudioDelay(player.audioDelayMs - 100) },
        onPlus = { player.setAudioDelay(player.audioDelayMs + 100) }, onReset = { player.setAudioDelay(0) })
    PopoverHeader(s["pc_volume_boost"])
    Choice {
        Segmented(
            options = listOf(100 to s["pc_off"], 125 to "125%", 150 to "150%", 200 to "200%"),
            selected = player.boost,
            onSelect = { player.changeBoost(it) },
        )
    }
    Divider(Modifier.padding(vertical = 8.dp))
    PopoverHeader(s["d_audio_output"])
    val chosen = settings.audioDevice
    player.audioDevices.forEach { (name, description) ->
        PopoverRow(
            title = if (name == "auto") s["d_audio_default"] else description,
            checked = name == chosen,
            onClick = { player.chooseAudioDevice(name) },
        )
    }
}

@Composable
fun QueuePanel(player: PlayerController, onClose: () -> Unit) {
    val s = LocalStrings.current
    PopoverHeader(s["pc_queue_n", player.queue.size])
    player.queue.forEachIndexed { i, item ->
        val p = item.progress
        val detail = when {
            p.watched -> s["pc_watched", item.fileName]
            p.started -> s["pc_stopped_at", formatTime(p.positionMs), item.fileName]
            else -> item.fileName
        }
        PopoverRow(
            title = "${i + 1}.  " + queueTitle(s, item),
            checked = i == player.index,
            onClick = { player.jumpTo(i); onClose() },
            detail = detail,
        )
    }
}

@Composable
fun SettingsPanel(player: PlayerController, onClose: () -> Unit, onInfo: () -> Unit, onFlash: (String) -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    PopoverHeader(s["pc_speed"])
    Choice {
        Segmented(
            options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { it to fmtSpeed(it) + "×" },
            selected = player.speed,
            onSelect = { player.changeSpeed(it) },
            fill = true,
        )
    }
    PopoverHeader(s["pc_repeat_mode"])
    Choice {
        Segmented(
            options = PlaylistMode.entries.map { it to s["d_mode_" + it.name.lowercase()] },
            selected = player.mode,
            onSelect = { player.mode = it },
            fill = true,
        )
    }
    PopoverHeader(s["pc_ab_repeat"])
    val a = player.abA
    val b = player.abB
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val status = when {
            a != null && b != null -> s["pc_ab_looping", formatTime(a), formatTime(b)]
            a != null -> s["pc_ab_waiting", formatTime(a)]
            else -> s["pc_unset"]
        }
        Txt(status, Aura.type.callout, Aura.colors.textSecondary, Modifier.weight(1f))
        SmallButton(
            when {
                a == null -> s["pc_set_a"]
                b == null -> s["pc_set_b"]
                else -> s["pc_clear_loop"]
            },
        ) { player.cycleAb() }
    }
    Divider(Modifier.padding(vertical = 8.dp))
    // What the frame holds — the headset's five formats — and, for a flat picture, its shape.
    PopoverHeader(s["d_picture_format"])
    Choice {
        Segmented(
            options = PictureFormat.entries.map { it to s["d_fmt_" + it.name.lowercase()] },
            selected = player.format,
            onSelect = { player.changeFormat(it) },
            fill = true,
        )
    }
    val pano = player.pano
    when {
        pano != null -> {
            // How the panorama lies in its frame — the headset's 全景格式 — and a fisheye's lens.
            PopoverHeader(s["pc_pano_format"])
            Choice {
                Segmented(
                    options = PanoChoice.kinds(pano.projection).map { it to s[PanoChoice.labelKey(it)] },
                    selected = pano.projection.takeIf { pano.ownMesh == null },
                    onSelect = { p -> p?.let { player.changePano(PanoChoice(it, if (it == ProjectionMode.FISHEYE) PanoChoice.LENSES.first() else 0)) } },
                    fill = true,
                )
            }
            if (pano.projection == ProjectionMode.FISHEYE) {
                PopoverHeader(s["d_pano_lens"])
                Choice {
                    Segmented(
                        options = PanoChoice.LENSES.map { it to "$it°" },
                        selected = pano.lensFov.toInt(),
                        onSelect = { player.changePano(PanoChoice(ProjectionMode.FISHEYE, it)) },
                        fill = true,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(s["d_view_hint"], Aura.type.caption, Aura.colors.textSecondary, Modifier.weight(1f), maxLines = 2)
                SmallButton(s["d_view_reset"]) { player.resetView() }
            }
            // 画面方向: a crooked horizon levelled, kept for the film (the headset's 上移 … 右旋).
            val (tilt, roll) = player.level
            PopoverHeader(s["pc_pano_direction"], trailing = if (tilt != 0f || roll != 0f) "↑%.0f° ↻%.0f°".format(tilt, roll) else null)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton(s["pc_pano_tilt_up"]) { player.nudgeLevel(player.LEVEL_STEP, 0f) }
                SmallButton(s["pc_pano_tilt_down"]) { player.nudgeLevel(-player.LEVEL_STEP, 0f) }
                SmallButton(s["pc_pano_roll_left"]) { player.nudgeLevel(0f, -player.LEVEL_STEP) }
                SmallButton(s["pc_pano_level"]) { player.resetLevel() }
                SmallButton(s["pc_pano_roll_right"]) { player.nudgeLevel(0f, player.LEVEL_STEP) }
            }
            Txt(s["pc_pano_level_hint"], Aura.type.caption, Aura.colors.textSecondary, Modifier.padding(horizontal = 14.dp, vertical = 2.dp), maxLines = 2)
        }
        player.format != PictureFormat.FLAT_2D ->
            Txt(s["d_fmt_one_eye"], Aura.type.caption, Aura.colors.textSecondary, Modifier.padding(horizontal = 14.dp, vertical = 4.dp), maxLines = 2)
        else -> {
            PopoverHeader(s["pc_aspect"])
            Choice {
                Segmented(
                    options = Aspect.entries.map { it to aspectLabel(it, s) },
                    selected = player.aspect,
                    onSelect = { player.changeAspect(it) },
                    fill = true,
                )
            }
        }
    }
    if (player.chapters.isNotEmpty()) {
        Divider(Modifier.padding(vertical = 8.dp))
        val current = player.currentChapter(player.player.currentPositionMillis.value)
        PopoverHeader(s["pc_chapters_at", current + 1, player.chapters.size])
        // The headset's two buttons: back to this chapter's start (or the one before, near it), on.
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(s["d_prev_chapter"], Lucide.SkipBack) { player.prevChapter() }
            if (current < player.chapters.lastIndex) SmallButton(s["d_next_chapter"], Lucide.SkipForward) { player.nextChapter() }
        }
        player.chapters.forEachIndexed { i, ch ->
            PopoverRow(
                title = ch.title ?: s["pc_chapter_n", i + 1],
                checked = i == current,
                onClick = { player.seekTo(ch.startMs) },
                detail = formatTime(ch.startMs),
            )
        }
    }
    Divider(Modifier.padding(vertical = 8.dp))
    val sleepHeader = player.sleepEndsAt?.let { s["pc_sleep_left", formatTime(it - System.currentTimeMillis())] } ?: s["pc_sleep"]
    PopoverHeader(sleepHeader)
    // Five times across; "at the end of this file" is a line of its own — six will not fit.
    Choice {
        Segmented(
            options = SleepMode.entries.filter { it != SleepMode.END_OF_ITEM }.map { m ->
                m to if (m == SleepMode.OFF) s["pc_off"] else "${m.minutes}′"
            },
            selected = player.sleep,
            onSelect = { player.changeSleep(it) },
            fill = true,
        )
    }
    PopoverRow(
        title = s["d_sleep_end_row"],
        checked = player.sleep == SleepMode.END_OF_ITEM,
        onClick = { player.changeSleep(if (player.sleep == SleepMode.END_OF_ITEM) SleepMode.OFF else SleepMode.END_OF_ITEM) },
    )
    Divider(Modifier.padding(vertical = 8.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallButton(s["pc_screenshot"], Lucide.Camera) {
            scope.launch {
                val f = player.screenshot()
                onFlash(if (f != null) s["d_screenshot_saved"] else s["d_screenshot_failed"])
                if (f != null) Desktop.reveal(f.absolutePath)
            }
        }
        SmallButton(s["pc_media_info"], Lucide.Info) { onInfo(); onClose() }
    }
}

fun aspectLabel(a: Aspect, s: Strings): String = when (a) {
    Aspect.AUTO -> s["pc_auto"]
    Aspect.FILL -> s["d_aspect_crop"]
    Aspect.STRETCH -> s["d_aspect_stretch"]
    else -> a.override.orEmpty()
}

@Composable
private fun Choice(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) { content() }
}

@Composable
private fun LabeledSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Aura.type.callout, Aura.colors.text, Modifier.width(82.dp))
        AuraSlider(v, onChange, Modifier.weight(1f), range = range, steps = steps, trackHeight = 3.dp, knobSize = 12.dp, label = label)
        Txt(value, Aura.type.caption, Aura.colors.textSecondary, Modifier.width(48.dp).padding(start = 10.dp))
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, onReset: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Aura.type.callout, Aura.colors.text, Modifier.width(82.dp))
        val s = LocalStrings.current
        IconButton(Lucide.Minus, null, onMinus, size = 26.dp, iconSize = 14.dp, description = s["d_a11y_less", label])
        Txt(value, Aura.type.callout, Aura.colors.text, Modifier.width(72.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(Lucide.Plus, null, onPlus, size = 26.dp, iconSize = 14.dp, description = s["d_a11y_more", label])
        Spacer(Modifier.weight(1f))
        if (onReset != null) IconButton(Lucide.RotateCcw, LocalStrings.current["pc_reset"], onReset, size = 26.dp, iconSize = 13.dp)
    }
}

@Composable
private fun SmallButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val c = Aura.colors
    Row(
        Modifier.clip(AuraShapes.control).background(c.hover).border(1.dp, c.stroke, AuraShapes.control)
            .clickable(onClick = onClick).clickCursor().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, c.textSecondary, 14.dp)
            Spacer(Modifier.width(6.dp))
        }
        Txt(text, Aura.type.callout, c.text)
    }
}

// ---- Naming tracks ------------------------------------------------------------------------

/**
 * What a video's sidecar files all share — its stem, up to the last dot of their common prefix
 * ("X.chs.srt" and "X.chs&eng.srt" share "X.chs", so the stem is "X."). Null with fewer than two,
 * where nothing can be compared. The headset's rule.
 */
internal fun sharedSidecarStem(names: List<String>): String? {
    if (names.size < 2) return null
    val prefix = names.reduce { a, b -> a.commonPrefixWith(b) }
    val dot = prefix.lastIndexOf('.')
    return if (dot <= 0) null else prefix.substring(0, dot + 1)
}

/** What names a track: an external file's name, a titled track's title, else its language. */
fun trackName(t: Track, s: Strings): String = when {
    t.external && !t.externalFile.isNullOrBlank() -> t.externalFile.substringAfterLast('/').substringAfterLast('\\')
    !t.title.isNullOrBlank() -> t.title
    !t.language.isNullOrBlank() -> languageName(t.language, s.locale)
    else -> s["d_track_n", t.id]
}

fun trackDetail(t: Track, s: Strings, audio: Boolean): String = buildList {
    add(s[if (t.external) "d_track_external" else "d_track_embedded"])
    t.codec?.let { add(codecName(it)) }
    if (audio && t.channels > 0) add(channelText(t.channels))
    if (audio && t.sampleRate > 0) add("${t.sampleRate / 1000.0} kHz".replace(".0 ", " "))
    t.language?.let { add(languageName(it, s.locale)) }
    if (t.forced) add(s["d_track_forced"])
    if (t.hearingImpaired) add("SDH")
}.distinct().joinToString(" · ")

private fun channelText(n: Int) = when (n) {
    1 -> "1.0"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; else -> "${n}ch"
}

private fun codecName(c: String): String = when (c.lowercase()) {
    "subrip" -> "SRT"
    "ass", "ssa" -> "ASS"
    "hdmv_pgs_subtitle" -> "PGS"
    "dvd_subtitle" -> "VobSub"
    "webvtt" -> "WebVTT"
    "mov_text" -> "TX3G"
    "truehd" -> "TrueHD"
    "eac3" -> "E-AC3"
    "ac3" -> "AC3"
    "dts" -> "DTS"
    "aac" -> "AAC"
    "flac" -> "FLAC"
    "opus" -> "Opus"
    else -> c.uppercase()
}

/** A two/three-letter badge for a language tag, or null when there is none. */
fun languageBadge(tag: String?): String? = tag?.takeIf { it.isNotBlank() }?.let {
    when (it.lowercase()) {
        "chs", "sc", "zh-hans", "zh-cn" -> "简"
        "cht", "tc", "zh-hant", "zh-tw", "zh-hk" -> "繁"
        else -> it.take(3).uppercase()
    }
}

private val languageNames = ConcurrentHashMap<String, String>()

/** A track's language, named in the reader's language: "jpn" → "日语" / "Japanese". */
fun languageName(tag: String, reader: Locale): String = languageNames.getOrPut(tag + "|" + reader.toLanguageTag()) {
    when (tag.lowercase()) {
        "chs", "sc", "zh-hans" -> return@getOrPut Locale.SIMPLIFIED_CHINESE.getDisplayName(reader)
        "cht", "tc", "zh-hant" -> return@getOrPut Locale.TRADITIONAL_CHINESE.getDisplayName(reader)
    }
    val direct = Locale.forLanguageTag(tag).getDisplayLanguage(reader)
    if (direct.isNotBlank() && !direct.equals(tag, ignoreCase = true)) return@getOrPut direct
    // Three-letter codes (ISO 639-2, as MKV writes them): find the language that has it.
    val iso = Locale.getISOLanguages().firstOrNull { l ->
        runCatching { Locale.of(l).isO3Language.equals(tag, true) }.getOrDefault(false)
    } ?: when (tag.lowercase()) {
        "chi" -> "zh"; "ger" -> "de"; "fre" -> "fr"; "dut" -> "nl"; "cze" -> "cs"; "gre" -> "el"
        "per" -> "fa"; "rum" -> "ro"; "slo" -> "sk"; "wel" -> "cy"; "ice" -> "is"; "mac" -> "mk"
        "may" -> "ms"; "bur" -> "my"; "arm" -> "hy"; "geo" -> "ka"; "baq" -> "eu"; "alb" -> "sq"
        else -> null
    }
    iso?.let { Locale.of(it).getDisplayLanguage(reader) } ?: tag
}
