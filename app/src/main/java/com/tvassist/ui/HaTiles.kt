package com.tvassist.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

// Home-Assistant-like palette.
internal val TileBg = Color(0xFF2A2F37)
internal val TileBgFocused = Color(0xFF3B434E)
internal val ChipBg = Color(0xFF3A414B)
internal val Accent = Color(0xFFF39C12) // HA active orange
internal val SubText = Color(0xFF9AA3AE)
internal val TrackBg = Color(0xFF424A55)
internal val TrackFill = Color(0xFFE6E2DA)

/** A circular icon chip like HA's entity badges. */
@Composable
fun HaIconChip(
    icon: ImageVector,
    on: Boolean,
    tint: Color? = null,
    size: Int = 46,
    iconContent: (@Composable (tint: Color) -> Unit)? = null,
) {
    val th = LocalOverlayTheme.current
    // When active, the chip fills with the active color (accent, or a climate mode color) so
    // on entities visibly pop; off entities stay neutral.
    val activeColor = tint ?: th.accent
    val chipBg = if (on) activeColor.copy(alpha = 0.22f) else th.chip
    Box(
        modifier = Modifier.size(size.dp).clip(CircleShape).background(chipBg),
        contentAlignment = Alignment.Center,
    ) {
        val resolved = tint ?: if (on) th.iconOn else th.iconOff
        if (iconContent != null) {
            iconContent(resolved)
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = resolved,
                modifier = Modifier.size((size * 0.52f).dp),
            )
        }
    }
}

/** A full-width rounded entity tile: icon chip + title/subtitle + optional trailing content. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HaTile(
    icon: ImageVector,
    iconOn: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    iconTint: Color? = null,
    iconContent: (@Composable (tint: Color) -> Unit)? = null,
    showIcon: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val th = LocalOverlayTheme.current
    val hasText = title.isNotBlank() || subtitle.isNotBlank()
    // With no text and no trailing, the icon is the only content → center it in the tile.
    val iconOnly = showIcon && !hasText && trailing == null
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = th.tile,
            focusedContainerColor = th.tileFocused,
            pressedContainerColor = th.tileFocused,
            contentColor = th.text,
            focusedContentColor = th.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.045f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.5.dp, th.focus), shape = RoundedCornerShape(18.dp)),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (iconOnly) Arrangement.Center else Arrangement.Start,
        ) {
            if (showIcon) {
                HaIconChip(icon, iconOn, iconTint, size = 36, iconContent = iconContent)
            }
            if (hasText) {
                if (showIcon) Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    if (title.isNotBlank()) {
                        Text(title, fontSize = 14.sp, color = th.text, maxLines = 1)
                    }
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, fontSize = 11.sp, color = th.subText, maxLines = 1)
                    }
                }
            } else if (trailing != null) {
                // No text but a trailing widget → push it to the right edge.
                Spacer(Modifier.weight(1f))
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
    }
}

/** A read-only HA-style slider track filled to [pct] (0–100). */
@Composable
fun TrackBar(pct: Int, modifier: Modifier = Modifier, height: Int = 38) {
    val th = LocalOverlayTheme.current
    Box(
        modifier = modifier.height(height.dp).clip(RoundedCornerShape(9.dp)).background(th.trackBg),
    ) {
        Box(
            Modifier.fillMaxHeight()
                .fillMaxWidth((pct.coerceIn(0, 100)) / 100f)
                .clip(RoundedCornerShape(10.dp))
                .background(th.trackFill),
        )
    }
}

/** A focusable slider row: Left/Right adjust; renders a filled track + value, HA-style. */
@Composable
fun SliderRow(
    label: String,
    pct: Int,
    valueText: String,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val th = LocalOverlayTheme.current
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) th.tileFocused else th.tile)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) when (e.key) {
                    Key.DirectionLeft -> { onLeft(); true }
                    Key.DirectionRight -> { onRight(); true }
                    else -> false
                } else false
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = th.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(if (focused) "◄ $valueText ►" else valueText, color = th.text, fontSize = 15.sp)
        }
        Spacer(Modifier.height(8.dp))
        TrackBar(pct, Modifier.fillMaxWidth())
    }
}

/**
 * A responsive D-pad slider. Holds its own [local] value so each Left/Right press moves
 * instantly (no waiting on the HA round-trip), holding the key accelerates, and the change
 * is committed to HA on a short debounce. [resetKey] (e.g. the entity id) re-seeds [local]
 * when switching entities; [value] re-seeds it when HA reports a new external value.
 */
@Composable
fun AdjustableSliderRow(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    valueLabel: (Double) -> String,
    onCommit: (Double) -> Unit,
    resetKey: Any,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val th = LocalOverlayTheme.current
    var local by remember(resetKey) { mutableDoubleStateOf(value) }
    var focused by remember { mutableStateOf(false) }

    // Re-sync from the authoritative value when it changes and we're not actively adjusting.
    LaunchedEffect(value) { if (!focused) local = value }
    // Debounced commit: holding the key only sends the final value to HA.
    LaunchedEffect(local) {
        if (abs(local - value) > 0.0001) {
            delay(160)
            onCommit(local)
        }
    }

    val span = (max - min).let { if (it <= 0.0) 1.0 else it }
    val fillPct = (((local - min) / span) * 100).roundToInt().coerceIn(0, 100)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) th.tileFocused else th.tile)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                    // Accelerate while the key is held down (repeatCount climbs).
                    val rc = e.nativeKeyEvent.repeatCount
                    val mult = when {
                        rc >= 14 -> 5.0
                        rc >= 6 -> 3.0
                        else -> 1.0
                    }
                    val delta = step * mult
                    when (e.key) {
                        Key.DirectionLeft -> { local = (local - delta).coerceIn(min, max); true }
                        Key.DirectionRight -> { local = (local + delta).coerceIn(min, max); true }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // th.text, not Color.White: on a light palette the tile is white, and white-on-white
            // made the label and value disappear entirely.
            Text(label, color = th.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                if (focused) "◄ ${valueLabel(local)} ►" else valueLabel(local),
                color = if (focused) th.accent else th.text,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        TrackBar(fillPct, Modifier.fillMaxWidth(), height = 28)
    }
}

/**
 * A D-pad color slider: a gradient [track] (e.g. a hue rainbow) with a draggable thumb.
 * Mirrors [AdjustableSliderRow]'s responsive local-value + debounced-commit behaviour, but
 * shows a thumb over a full gradient rather than a fill.
 */
@Composable
fun ColorSliderRow(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    track: Brush,
    valueLabel: (Double) -> String,
    onCommit: (Double) -> Unit,
    resetKey: Any,
    modifier: Modifier = Modifier,
) {
    val th = LocalOverlayTheme.current
    var local by remember(resetKey) { mutableDoubleStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused) local = value }
    LaunchedEffect(local) {
        if (abs(local - value) > 0.0001) { delay(160); onCommit(local) }
    }
    val span = (max - min).let { if (it <= 0.0) 1.0 else it }
    val fraction = (((local - min) / span)).toFloat().coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) th.tileFocused else th.tile)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                    val rc = e.nativeKeyEvent.repeatCount
                    val mult = when { rc >= 14 -> 5.0; rc >= 6 -> 3.0; else -> 1.0 }
                    val delta = step * mult
                    when (e.key) {
                        Key.DirectionLeft -> { local = (local - delta).coerceIn(min, max); true }
                        Key.DirectionRight -> { local = (local + delta).coerceIn(min, max); true }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = th.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                if (focused) "◄ ${valueLabel(local)} ►" else valueLabel(local),
                color = if (focused) th.accent else th.text, fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp)).background(track),
        ) {
            val thumbX = (maxWidth * fraction - 9.dp).coerceIn(0.dp, maxWidth - 18.dp)
            Box(
                modifier = Modifier.align(Alignment.CenterStart).offset(x = thumbX)
                    .size(18.dp).clip(CircleShape).background(Color.White),
            )
        }
    }
}

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

/** A segmented icon button (climate mode/fan): filled [Accent] when selected. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ModeIconButton(
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color? = null,
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
        Box(Modifier.padding(vertical = 9.dp, horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
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
                        .keepInRow(isFirst = i == 0, isLast = i == items.lastIndex),
                    activeColor = activeColor,
                )
            }
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

/** A climate fan-mode row: auto/off get an icon, real speeds get ascending [SpeedBars]. */
@Composable
fun FanModeRow(entity: Entity, actions: EntityControlActions, activeColor: Color? = null) {
    val modes = entity.fanModes
    if (modes.isEmpty()) return
    val special = setOf("auto", "off")
    val speeds = modes.filter { it.lowercase() !in special }
    Column {
        Text("Fan", color = LocalOverlayTheme.current.subText, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(LocalOverlayTheme.current.segmentBg)
                .horizontalScroll(rememberScrollState()).padding(6.dp),
        ) {
            modes.forEachIndexed { i, mode ->
                val sel = mode.equals(entity.fanMode, ignoreCase = true)
                ModeContentButton(sel, { actions.setFanMode(entity, mode) }, Modifier.padding(horizontal = 3.dp).keepInRow(i == 0, i == modes.lastIndex), activeColor = activeColor) { c ->
                    if (mode.lowercase() in special) {
                        Icon(hvacModeIcon(mode), contentDescription = mode, tint = c, modifier = Modifier.size(22.dp))
                    } else {
                        FanSpeedGlyph(speeds.indexOf(mode) + 1, speeds.size, c)
                    }
                }
            }
        }
    }
}

/** Speed + preset controls for a `fan` entity: discrete speed buttons (from speed_count). */
@Composable
fun FanSpeedControls(entity: Entity, actions: EntityControlActions, firstFocus: FocusRequester?) {
    val count = entity.fanSpeedCount
    val curPct = entity.percentage ?: 0
    val curLevel = if (count > 0) Math.round(curPct * count / 100.0).toInt() else 0
    if (count in 1..12) {
        Text("Speed", color = LocalOverlayTheme.current.subText, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(LocalOverlayTheme.current.segmentBg)
                .horizontalScroll(rememberScrollState()).padding(6.dp),
        ) {
            ModeContentButton(
                selected = !entity.isOn,
                onClick = { actions.turnOff(entity) },
                modifier = Modifier.padding(horizontal = 3.dp)
                    .keepInRow(isFirst = true, isLast = false)
                    .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
            ) { c -> Icon(hvacModeIcon("off"), contentDescription = "Off", tint = c, modifier = Modifier.size(22.dp)) }
            for (lvl in 1..count) {
                val pct = Math.round(lvl * 100.0 / count).toInt()
                ModeContentButton(
                    selected = entity.isOn && curLevel == lvl,
                    onClick = { actions.setFanPercentage(entity, pct) },
                    modifier = Modifier.padding(horizontal = 3.dp).keepInRow(isFirst = false, isLast = lvl == count),
                ) { c -> FanSpeedGlyph(lvl, count, c) }
            }
        }
    } else if (entity.percentage != null) {
        AdjustableSliderRow(
            label = "Speed", value = curPct.toDouble(), min = 0.0, max = 100.0,
            step = entity.percentageStep ?: 10.0, valueLabel = { "${it.roundToInt()}%" },
            onCommit = { actions.setFanPercentage(entity, it.roundToInt()) },
            resetKey = entity.entityId, focusRequester = firstFocus,
        )
    }
    if (entity.presetModes.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("Preset", color = LocalOverlayTheme.current.subText, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(LocalOverlayTheme.current.segmentBg)
                .horizontalScroll(rememberScrollState()).padding(6.dp),
        ) {
            entity.presetModes.forEachIndexed { i, p ->
                ModeContentButton(
                    selected = p.equals(entity.presetMode, ignoreCase = true),
                    onClick = { actions.setFanPreset(entity, p) },
                    modifier = Modifier.padding(horizontal = 3.dp).keepInRow(i == 0, i == entity.presetModes.lastIndex),
                ) { c -> Text(p, color = c, fontSize = 13.sp) }
            }
        }
    }
}

/** A compact inline climate tile: header (opens full card) + Mode/Fan icon rows. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun InlineClimateTile(
    entity: Entity,
    actions: EntityControlActions,
    onOpen: (Entity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val th = LocalOverlayTheme.current
    val on = entity.state != "off"
    val active = hvacModeColor(entity.state) // HA-style mode color (dry=amber, cool=blue, …)
    Column(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(th.tile).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            onClick = { onOpen(entity) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = th.tileFocused,
                contentColor = th.text,
                focusedContentColor = th.text,
            ),
            // No scale: this header is full-width inside the climate card, so scaling would
            // bulge it past the panel edges. The focus border is the highlight.
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(2.dp, th.focus), shape = RoundedCornerShape(12.dp)),
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HaIconChip(
                    icon = domainIcon(entity),
                    on = on,
                    tint = if (on) active else null,
                    size = 36,
                    iconContent = { tint -> EntityIconContent(entity, null, tint, 19, repository = actions.repository) },
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(entity.friendlyName, fontSize = 14.sp, color = th.text, maxLines = 1)
                    Text(
                        text = entity.currentTemperature?.let { "${cap(entity.state)} · ${fmt(it)}°" } ?: cap(entity.state),
                        fontSize = 11.sp,
                        color = th.subText,
                        maxLines = 1,
                    )
                }
            }
        }
        if (entity.hvacModes.isNotEmpty()) {
            ModeIconRow(
                label = "Mode",
                items = entity.hvacModes.map { it to hvacModeIcon(it) },
                selected = entity.state,
                onSelect = { actions.setHvacMode(entity, it) },
                activeColor = if (on) active else null,
            )
        }
        if (entity.fanModes.isNotEmpty()) {
            FanModeRow(entity, actions, activeColor = if (on) active else null)
        }
    }
}
