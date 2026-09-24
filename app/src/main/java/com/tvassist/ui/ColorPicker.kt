package com.tvassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FormatColorReset
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import com.tvassist.ui.cards.ColorSliderRow
import kotlin.math.roundToInt

/**
 * One color as a premium accordion row: a clickable header (color chip + name + hex + chevron) that
 * expands to reveal the preset swatches and inline HSV sliders. Only one row is open at a time
 * (the one whose label == [expandedKey]) so the live preview stays visible.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ColorControlRow(
    label: String,
    argb: Int,
    swatches: List<Int>,
    expandedKey: String?,
    onExpand: (String?) -> Unit,
    onChange: (Int) -> Unit,
    // When [onReset] is set, a leading "Default (follow theme)" chip is shown in the swatch row,
    // selected while [isUnset]. Lets a control clear back to its theme default without a separate
    // reset button (used by header pills, where 0 = follow the theme's icon color).
    isUnset: Boolean = false,
    onReset: (() -> Unit)? = null,
) {
    val custom = expandedKey == label
    var h by remember { mutableFloatStateOf(0f) }
    var s by remember { mutableFloatStateOf(0f) }
    var v by remember { mutableFloatStateOf(0f) }
    // Seed HSV from the current color while the inline editor is closed.
    LaunchedEffect(argb, custom) {
        if (!custom) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(argb, hsv)
            h = hsv[0]; s = hsv[1] * 100f; v = hsv[2] * 100f
        }
    }
    fun emit() { onChange(Color.hsv(h, (s / 100f).coerceIn(0f, 1f), (v / 100f).coerceIn(0f, 1f)).toArgb()) }

    Column {
        Surface(
            onClick = { onExpand(if (custom) null else label) },
            modifier = Modifier.fillMaxWidth(),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (custom) CardFocusBg else Color.Transparent,
                focusedContainerColor = CardFocusBg,
                pressedContainerColor = CardFocusBg,
                contentColor = TxtPrimary,
                focusedContentColor = Color.White,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(1.5.dp, AppAccent), shape = RoundedCornerShape(12.dp)),
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(Color(argb))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Text(label, color = TxtPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(String.format("#%06X", 0xFFFFFF and argb), color = TxtMuted, fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
                Icon(
                    if (custom) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.ChevronRight,
                    contentDescription = null, tint = TxtMuted, modifier = Modifier.size(20.dp),
                )
            }
        }
        if (custom) {
            Spacer(Modifier.height(10.dp))
            ColorChips(
                swatches, argb, onChange,
                leading = onReset?.let { reset ->
                    {
                        // "Default" chip: theme-following (no override). Same circle style as swatches,
                        // marked with a reset glyph so it reads distinct from the plain grey swatch.
                        Surface(
                            onClick = reset,
                            shape = ClickableSurfaceDefaults.shape(CircleShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF3A414B), focusedContainerColor = Color(0xFF3A414B),
                                pressedContainerColor = Color(0xFF3A414B), contentColor = Color.White,
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
                            border = ClickableSurfaceDefaults.border(
                                border = if (isUnset) Border(BorderStroke(2.dp, Color.White), shape = CircleShape) else Border.None,
                                focusedBorder = Border(BorderStroke(2.dp, AppAccent), shape = CircleShape),
                            ),
                        ) {
                            Box(Modifier.size(27.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.FormatColorReset, contentDescription = "Default color",
                                    tint = TxtMuted, modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            ColorSliderRow("Hue", h.toDouble(), 0.0, 360.0, 4.0,
                Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) }),
                { "${it.roundToInt()}°" }, { h = it.toFloat(); emit() }, resetKey = "h$label")
            Spacer(Modifier.height(8.dp))
            ColorSliderRow("Saturation", s.toDouble(), 0.0, 100.0, 4.0,
                Brush.horizontalGradient(listOf(Color.hsv(h, 0f, v / 100f), Color.hsv(h, 1f, v / 100f))),
                { "${it.roundToInt()}%" }, { s = it.toFloat(); emit() }, resetKey = "s$label")
            Spacer(Modifier.height(8.dp))
            ColorSliderRow("Brightness", v.toDouble(), 0.0, 100.0, 4.0,
                Brush.horizontalGradient(listOf(Color.Black, Color.hsv(h, s / 100f, 1f))),
                { "${it.roundToInt()}%" }, { v = it.toFloat(); emit() }, resetKey = "v$label")
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** A focusable row of color swatches; the selected one gets a white ring, focus gets accent. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ColorChips(
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading()
        options.forEach { argb ->
            val sel = argb == selected
            Surface(
                onClick = { onSelect(argb) },
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(argb), focusedContainerColor = Color(argb),
                    pressedContainerColor = Color(argb), contentColor = Color.White,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
                border = ClickableSurfaceDefaults.border(
                    border = if (sel) Border(BorderStroke(2.dp, Color.White), shape = CircleShape) else Border.None,
                    focusedBorder = Border(BorderStroke(2.dp, AppAccent), shape = CircleShape),
                ),
            ) {
                Box(Modifier.size(27.dp))
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}
