package com.tvassist.ui.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.cap

/**
 * Keeps D-pad focus inside a segmented row: blocks LEFT on the first item and RIGHT on the last,
 * so navigating past an edge doesn't jump to the next entity. Up/Down still leave the row normally.
 */
fun Modifier.keepInRow(isFirst: Boolean, isLast: Boolean): Modifier = onKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown) {
        (isFirst && e.key == Key.DirectionLeft) || (isLast && e.key == Key.DirectionRight)
    } else {
        false
    }
}

/**
 * [keepInRow], or nothing at all when the row is drawn somewhere Left and Right are needed for
 * getting out of it — a tile sharing its line with other tiles.
 */
fun Modifier.thenKeepInRow(trap: Boolean, isFirst: Boolean, isLast: Boolean): Modifier =
    if (trap) keepInRow(isFirst, isLast) else this

/** Every segmented icon button reserves this, whatever its glyph is drawn at. */
private val ICON_BOX = 20.dp

/** A segmented icon button (climate mode/fan): filled [Accent] when selected. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ModeIconButton(
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color? = null,
    /**
     * Optical size, not the box size.
     *
     * Material's glyphs do not all fill their 24dp viewport equally: the transport shapes are
     * near-solid triangles and bars, while a speaker is a small trapezoid with thin arcs. Drawn at
     * the same 20dp the speaker measured about two-thirds the width of the keys beside it, which
     * reads as a mistake rather than as a different icon.
     *
     * The glyph grows **without the button growing with it** — the box stays [ICON_BOX] and the
     * icon is drawn at its required size inside, spilling into the padding. Otherwise correcting
     * one glyph's weight makes its key taller than every key beside it, which is a worse fault
     * than the one being fixed.
     */
    iconSize: Dp = ICON_BOX,
) {
    val th = LocalOverlayTheme.current
    val active = activeColor ?: th.accent
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) active else th.segmentItem,
            focusedContainerColor = if (selected) active.copy(alpha = 0.85f) else th.tileFocused,
            pressedContainerColor = if (selected) active.copy(alpha = 0.85f) else th.tileFocused,
            contentColor = if (selected) Color(0xFF1A1A1A) else th.text,
            focusedContentColor = if (selected) Color(0xFF1A1A1A) else th.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, th.focus), shape = RoundedCornerShape(12.dp)),
        ),
    ) {
        Box(
            modifier = Modifier.padding(vertical = 9.dp, horizontal = 14.dp).size(ICON_BOX),
            contentAlignment = Alignment.Center,
        ) {
            // requiredSize, not size: it ignores the box's constraints, so a glyph corrected for
            // optical weight overflows into the padding instead of resizing the button.
            Icon(icon, contentDescription = null, modifier = Modifier.requiredSize(iconSize))
        }
    }
}

/** A labelled row of [ModeIconButton]s wrapped in a segmented container. */
@Composable
fun ModeIconRow(
    label: String,
    items: List<Pair<String, ImageVector>>,
    selected: String?,
    onSelect: (String) -> Unit,
    activeColor: Color? = null,
    /**
     * Whether Left on the first item and Right on the last stay inside the row.
     *
     * True on a control card, where nothing sits beside the row and holding the keys in is the
     * kindness. **False on a sidebar tile**: there the keys belong to the tiles left and right, and
     * swallowing them strands every one of them — the same trap the Normal light row had.
     */
    trapHorizontal: Boolean = true,
) {
    Column {
        Text(label, color = LocalOverlayTheme.current.subText, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(LocalOverlayTheme.current.segmentBg)
                .horizontalScroll(rememberScrollState())
                .padding(6.dp),
        ) {
            items.forEachIndexed { i, (value, icon) ->
                ModeIconButton(
                    icon = icon,
                    selected = value.equals(selected, ignoreCase = true),
                    onClick = { onSelect(value) },
                    modifier = Modifier.padding(horizontal = 3.dp)
                        .thenKeepInRow(trapHorizontal, i == 0, i == items.lastIndex),
                    activeColor = activeColor,
                )
            }
        }
    }
}

/**
 * A labelled segmented row whose options are *words* — presets, swing positions — rather than
 * icons. [ModeIconRow]'s sibling, and the same `Column` shape so it is one child of its parent.
 *
 * Values arrive from Home Assistant lowercase (`auto`, `both`, `vertical`) and are capitalised for
 * display only: what is sent back is always the untouched string HA gave us, since these are opaque
 * ids to everything except the integration that published them.
 */
@Composable
fun ModeTextRow(
    label: String,
    items: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    activeColor: Color? = null,
    trapHorizontal: Boolean = true,
    /** The card's opening focus, claimed by the first option. */
    firstFocus: FocusRequester? = null,
) {
    if (items.isEmpty()) return
    Column {
        // Blank means no label line at all, for a row whose meaning the card header already
        // carries — a lock's Lock/Unlock under a header reading "Locked" needs no third word.
        if (label.isNotBlank()) {
            Text(label, color = LocalOverlayTheme.current.subText, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(LocalOverlayTheme.current.segmentBg)
                .horizontalScroll(rememberScrollState())
                .padding(6.dp),
        ) {
            items.forEachIndexed { i, value ->
                ModeContentButton(
                    selected = value.equals(selected, ignoreCase = true),
                    onClick = { onSelect(value) },
                    modifier = Modifier.padding(horizontal = 3.dp)
                        .thenKeepInRow(trapHorizontal, i == 0, i == items.lastIndex)
                        .then(
                            if (i == 0 && firstFocus != null) {
                                Modifier.focusRequester(firstFocus)
                            } else {
                                Modifier
                            },
                        ),
                    activeColor = activeColor,
                ) { c -> Text(cap(value), color = c, fontSize = 13.sp) }
            }
        }
    }
}

/** A segmented mode button (like [ModeIconButton]) but with arbitrary tinted content. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ModeContentButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color? = null,
    content: @Composable (contentColor: Color) -> Unit,
) {
    val th = LocalOverlayTheme.current
    val active = activeColor ?: th.accent
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) active else th.segmentItem,
            focusedContainerColor = if (selected) active.copy(alpha = 0.85f) else th.tileFocused,
            pressedContainerColor = if (selected) active.copy(alpha = 0.85f) else th.tileFocused,
            contentColor = if (selected) Color(0xFF1A1A1A) else th.text,
            focusedContentColor = if (selected) Color(0xFF1A1A1A) else th.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, th.focus), shape = RoundedCornerShape(12.dp)),
        ),
    ) {
        Box(Modifier.padding(vertical = 8.dp, horizontal = 13.dp), contentAlignment = Alignment.Center) {
            content(if (selected) Color(0xFF1A1A1A) else th.text)
        }
    }
}

/** A mini speed dial: a 270° gauge filled to speed [level] of [max], with the level number. */
@Composable
fun FanSpeedGlyph(level: Int, max: Int, color: Color, modifier: Modifier = Modifier) {
    val frac = if (max > 0) (level.toFloat() / max).coerceIn(0f, 1f) else 0f
    Box(modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 2.5.dp.toPx()
            val arcSize = Size(size.width - sw, size.height - sw)
            val topLeft = Offset(sw / 2, sw / 2)
            drawArc(color.copy(alpha = 0.25f), 135f, 270f, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            if (frac > 0f) {
                drawArc(color, 135f, 270f * frac, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        Text("$level", color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}
