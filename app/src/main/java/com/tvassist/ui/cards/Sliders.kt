package com.tvassist.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import com.tvassist.ui.LocalOverlayTheme

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
        // 22dp, matching ColorSliderRow's gradient track below. They stack in the same tile — a
        // light's Compact rung is brightness, warmth and colour in a column — and a fill bar 6dp
        // fatter than the two under it reads as a mistake.
        TrackBar(fillPct, Modifier.fillMaxWidth(), height = SLIDER_TRACK_DP)
    }
}

/** Shared by both slider rows, so a fill bar and a gradient bar are never different heights. */
internal const val SLIDER_TRACK_DP = 22

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
    /** Same hook as [AdjustableSliderRow]: a card's first row claims the opening focus. */
    focusRequester: FocusRequester? = null,
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
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
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
            modifier = Modifier
                .fillMaxWidth()
                .height(SLIDER_TRACK_DP.dp)
                .clip(RoundedCornerShape(SLIDER_TRACK_DP.dp / 2))
                .background(track),
        ) {
            val thumbX = (maxWidth * fraction - 9.dp).coerceIn(0.dp, maxWidth - 18.dp)
            Box(
                // Outlined, not bare white. A gradient track is light in places — the pale end of
                // Saturation, the middle of a colour-temperature ramp — and a white thumb on it
                // vanishes completely, so the row reads as having no thumb at all.
                modifier = Modifier.align(Alignment.CenterStart).offset(x = thumbX)
                    .size(18.dp).clip(CircleShape).background(Color.White)
                    .border(2.dp, Color(0x59000000), CircleShape),
            )
        }
    }
}
