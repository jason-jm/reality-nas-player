package com.therealityteam.videoplayer.desktop.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.ui.components.Icon
import com.therealityteam.videoplayer.desktop.ui.components.MenuEntry
import com.therealityteam.videoplayer.desktop.ui.components.Txt
import com.therealityteam.videoplayer.desktop.ui.components.WithContextMenu
import com.therealityteam.videoplayer.desktop.ui.components.clickCursor
import com.therealityteam.videoplayer.desktop.ui.images.rememberImage
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.monogram
import kotlin.math.abs

/**
 * One title on the wall. Hover lifts it and offers Play; a click opens its page; right-click has
 * everything else. The poster sits on the title's own colour, so a wall of them reads as a set.
 */
@Composable
fun PosterCard(
    group: MediaGroup,
    live: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    menu: () -> List<MenuEntry>,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val c = Aura.colors
    val s = LocalStrings.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val lift by animateFloatAsState(if ((hovered || selected) && live) 1.035f else 1f, tween(160))
    val ring by animateFloatAsState(if (selected) 1f else 0f, tween(140))
    val rep = group.items.firstOrNull()
    WithContextMenu(entries = { if (live) menu() else emptyList() }, modifier = modifier) {
        Column(Modifier.hoverable(source)) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .graphicsLayer { scaleX = lift; scaleY = lift }
                    // The keyboard's card: a ring just outside the poster, as a TV's focus draws it.
                    .drawBehind {
                        if (ring > 0f) {
                            val gap = 4.dp.toPx()
                            val r = CARD_RADIUS.toPx() + gap
                            drawRoundRect(
                                c.accent.copy(alpha = ring), topLeft = Offset(-gap, -gap), size = Size(size.width + 2 * gap, size.height + 2 * gap),
                                cornerRadius = CornerRadius(r, r), style = Stroke(2.5.dp.toPx()),
                            )
                        }
                    }
                    .shadow(if (hovered || selected) 18.dp else 6.dp, AuraShapes.card, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(AuraShapes.card)
                    .background(tint(group, c.surface))
                    .clickable(enabled = live, interactionSource = source, indication = null, onClick = onOpen)
                    .clickCursor(),
            ) {
                val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                val poster by rememberImage(group.posterPath, widthPx)
                val img = poster
                if (img != null) {
                    Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    TitleTile(group.title)
                }
                // Badges: count top-right, watched top-left.
                if (group.isCollection) {
                    Badge(
                        if (group.countsEpisodes) s.plural("card_n_episodes", group.count) else s.plural("card_n_files", group.count),
                        Modifier.align(Alignment.TopEnd).padding(7.dp),
                    )
                }
                val seen = group.watchedFraction
                val filmLeft = if (!group.isCollection && rep != null && rep.progress.started && !rep.progress.watched) rep.progress.fraction else 0f
                if (seen >= 1f) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(7.dp).size(22.dp).clip(CircleShape)
                            .background(c.accent),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Lucide.Check, Color.White, 13.dp) }
                }
                val bar = maxOf(seen, filmLeft)
                if (bar > 0f && bar < 1f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                        Box(Modifier.fillMaxWidth(bar).fillMaxHeight().background(c.accent))
                    }
                }
                // Hover: a wash and a play button that plays without opening the page.
                val shown by animateFloatAsState(if (hovered && live) 1f else 0f, tween(140))
                if (shown > 0f) {
                    Box(
                        Modifier.fillMaxSize().alpha(shown)
                            .background(Brush.verticalGradient(listOf(Color(0x14000000), Color(0x8C000000)))),
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .size(52.dp)
                                .shadow(12.dp, CircleShape)
                                .clip(CircleShape)
                                .background(Color(0xE6FFFFFF))
                                .clickable(onClick = onPlay)
                                .clickCursor(),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Lucide.Play, Color(0xFF111111), 22.dp, Modifier.padding(start = 3.dp)) }
                    }
                }
            }
            Txt(group.title, Aura.type.bodyStrong, c.text, Modifier.padding(top = 9.dp, start = 2.dp), maxLines = 2)
            val sub = when {
                group.countsEpisodes && group.year != null -> "${group.year} · " + s.plural("card_n_episodes", group.count)
                group.countsEpisodes -> s["card_series"] + " · " + s.plural("card_n_episodes", group.count)
                group.year != null -> group.year.toString()
                else -> rep?.fileName?.substringAfterLast('.', "")?.uppercase().orEmpty()
            }
            Txt(sub, Aura.type.caption, c.textTertiary, Modifier.padding(top = 1.dp, start = 2.dp))
        }
    }
}

/** AuraShapes.card's corner, for drawing around it. */
private val CARD_RADIUS = 10.dp

@Composable
private fun Badge(text: String, modifier: Modifier) {
    Box(modifier.clip(AuraShapes.small).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Txt(text, Aura.type.micro, Color.White)
    }
}

/** The card's ground: the poster's own dominant colour, darkened; the title's hue when there is no poster. */
private fun tint(group: MediaGroup, fallback: Color): Color {
    val argb = group.dominantColor ?: return fallback
    val base = Color(argb)
    return Color(base.red * 0.45f, base.green * 0.45f, base.blue * 0.45f, 1f)
}

/** A cover for a title with no poster: its name on a gradient of its own, stable for the name. */
@Composable
fun TitleTile(title: String, modifier: Modifier = Modifier) {
    val colors = titleGradient(title)
    Box(
        modifier.fillMaxSize().background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        // The monogram, huge and faint, behind the name — so a column of these is not all text.
        BasicText(
            monogram(title),
            style = Aura.type.display.copy(fontSize = 120.sp, color = Color.White.copy(alpha = 0.10f), fontWeight = FontWeight.Black),
            maxLines = 1,
        )
        BasicText(
            title,
            style = Aura.type.title2.copy(
                fontSize = titleSize(title), lineHeight = titleSize(title) * 1.25f,
                color = Color.White.copy(alpha = 0.94f), textAlign = TextAlign.Center,
            ),
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** Three colours for [title], stable for as long as the title is — the headset's titleGradient. */
fun titleGradient(title: String): List<Color> {
    val h = abs(title.hashCode())
    val hue = (h % 360).toFloat()
    val drift = 18f + (h / 360 % 22)
    val sat = (0.50f + (h / 97 % 20) / 100f).coerceIn(0.45f, 0.72f)
    return listOf(
        Color.hsv(hue, sat * 0.88f, 0.62f),
        Color.hsv((hue + drift) % 360f, sat, 0.45f),
        Color.hsv((hue + drift * 2f) % 360f, (sat * 1.06f).coerceAtMost(0.85f), 0.30f),
    )
}

/** How big the name is set, by the room its characters take (a CJK character is two). */
fun titleSize(title: String): TextUnit {
    val room = title.sumOf { if (it.code >= 0x2E80) 2 else 1 }
    return when {
        room <= 10 -> 25.sp
        room <= 20 -> 21.sp
        room <= 36 -> 17.sp
        room <= 64 -> 14.sp
        else -> 12.sp
    }
}
