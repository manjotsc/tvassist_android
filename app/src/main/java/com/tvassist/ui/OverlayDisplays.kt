package com.tvassist.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.tvassist.data.settings.DisplayCorner
import com.tvassist.data.settings.OverlayDisplay
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The always-on display overlays (ambiance dimming + corner clock) that the keep-alive window
 * draws behind any pushed notifications. Pure Compose drawing only — no Surface/Texture views,
 * which an overlay window can't composite.
 */
@Composable
fun OverlayDisplays(display: OverlayDisplay) {
    Box(Modifier.fillMaxSize()) {
        DimLayer(display.dimLevel)
        if (display.clockEnabled) ClockOverlay(display)
    }
}

/** A flat translucent black scrim over the TV picture, [level] = 0-95 % opacity. */
@Composable
private fun DimLayer(level: Int) {
    if (level <= 0) return
    val alpha by animateFloatAsState(targetValue = (level.coerceIn(0, 95)) / 100f, label = "dim")
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)))
}

@Composable
private fun ClockOverlay(cfg: OverlayDisplay) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cfg.clockSeconds) {
        while (true) {
            now = System.currentTimeMillis()
            // Re-tick at the top of the next second (or minute) so the clock stays aligned.
            val period = if (cfg.clockSeconds) 1000L else 60_000L
            delay(period - (System.currentTimeMillis() % period))
        }
    }

    val formatter = remember(cfg.clockSeconds, cfg.clock24Hour) {
        val pattern = buildString {
            append(if (cfg.clock24Hour) "HH:mm" else "h:mm")
            if (cfg.clockSeconds) append(":ss")
            if (!cfg.clock24Hour) append(" a")
        }
        SimpleDateFormat(pattern, Locale.getDefault())
    }
    val text = remember(now, formatter) { formatter.format(Date(now)) }
    val color = if (cfg.clockColor == 0) Color.White else Color(cfg.clockColor)

    // Pixel-shift: drift the clock a few px on a slow cycle so it never burns into the panel.
    val shift = rememberInfiniteTransition(label = "clockShift")
    val dx by shift.driftFloat(0f, 6f, "dx", 90_000)
    val dy by shift.driftFloat(0f, 6f, "dy", 140_000)

    Box(
        Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 30.dp),
        contentAlignment = alignmentFor(cfg.clockCorner),
    ) {
        Text(
            text,
            color = color,
            fontSize = cfg.clockSize.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(start = dx.dp, top = dy.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.32f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/** Convenience wrapper so each axis can repeat on its own slow period. */
@Composable
private fun androidx.compose.animation.core.InfiniteTransition.driftFloat(
    from: Float,
    to: Float,
    label: String,
    durationMs: Int,
): androidx.compose.runtime.State<Float> = animateFloat(
    initialValue = from,
    targetValue = to,
    animationSpec = infiniteRepeatable(
        animation = tween(durationMs, easing = LinearEasing),
        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
    ),
    label = label,
)

private fun alignmentFor(corner: DisplayCorner): Alignment = when (corner) {
    DisplayCorner.TOP_START -> Alignment.TopStart
    DisplayCorner.TOP_END -> Alignment.TopEnd
    DisplayCorner.BOTTOM_START -> Alignment.BottomStart
    DisplayCorner.BOTTOM_END -> Alignment.BottomEnd
}
