package com.tvassist.ui.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Icon
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.OverlayTheme
import com.tvassist.ui.overlayThemeOf

/** Live preview pane: a faux TV backdrop with the overlay panel docked where it really docks. */
@Composable
internal fun OverlayPreviewPane(settings: com.tvassist.data.settings.Settings, pos: com.tvassist.data.settings.OverlayPosition) {
    val theme = remember(settings) {
        overlayThemeOf(
            settings.overlayBgColor, settings.overlayTileColor, settings.overlayAccentColor,
            settings.overlayBorderColor, settings.overlayBorderEnabled,
            settings.overlayIconOnColor, settings.overlayIconOffColor, settings.overlayFocusColor,
        )
    }
    val alpha = settings.overlayOpacity.coerceIn(0, 100) / 100f
    val base = Color(settings.overlayBgColor)
    val panelBrush = Brush.verticalGradient(
        listOf(base.copy(alpha = alpha), lerp(base, Color.Black, 0.22f).copy(alpha = alpha)),
    )
    val shape = RoundedCornerShape(settings.overlayCornerRadius.dp.coerceAtMost(28.dp))
    val borderMod = if (settings.overlayBorderEnabled) {
        Modifier.border(1.dp, Color(settings.overlayBorderColor).copy(alpha = 0.55f), shape)
    } else {
        Modifier
    }
    val alignment = when (pos) {
        com.tvassist.data.settings.OverlayPosition.RIGHT -> Alignment.CenterEnd
        com.tvassist.data.settings.OverlayPosition.LEFT -> Alignment.CenterStart
        com.tvassist.data.settings.OverlayPosition.TOP -> Alignment.TopCenter
        com.tvassist.data.settings.OverlayPosition.BOTTOM -> Alignment.BottomCenter
    }

    Box(
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B3A55), Color(0xFF4A3B5E), Color(0xFF1E3A34))))
            .padding(12.dp),
        contentAlignment = alignment,
    ) {
        CompositionLocalProvider(LocalOverlayTheme provides theme) {
            Column(
                modifier = Modifier.width(178.dp)
                    .clip(shape).background(panelBrush).then(borderMod).padding(9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Living Room", color = theme.subText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(theme.subText.copy(alpha = 0.25f)))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(theme.tile).padding(horizontal = 7.dp, vertical = 3.dp)) {
                        Text("21°", color = theme.text, fontSize = 11.sp)
                    }
                }
                PreviewTile(theme, Icons.Rounded.Lightbulb, "Lamp", "On · 80%", accentValue = true, focused = true)
                PreviewTile(theme, Icons.Rounded.DeviceThermostat, "Thermostat", "Heat · 22°", accentValue = false)
            }
        }
    }
}

@Composable
internal fun PreviewTile(theme: OverlayTheme, icon: ImageVector, title: String, sub: String, accentValue: Boolean, focused: Boolean = false) {
    val tileShape = RoundedCornerShape(11.dp)
    Row(
        modifier = Modifier.fillMaxWidth().clip(tileShape)
            .background(if (focused) theme.tileFocused else theme.tile)
            .then(if (focused) Modifier.border(1.5.dp, theme.focus, tileShape) else Modifier)
            .padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .background(if (accentValue) theme.accent.copy(alpha = 0.22f) else theme.chip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (accentValue) theme.iconOn else theme.iconOff, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = theme.text, fontSize = 12.sp, maxLines = 1)
            Text(sub, color = theme.subText, fontSize = 10.sp, maxLines = 1)
        }
        Text(if (accentValue) "80%" else "22°", color = if (accentValue) theme.accent else theme.text, fontSize = 12.sp)
    }
}

// Curated color swatches for the overlay (ARGB). Background = dark, tile = mid, accent = vivid.
internal val BG_SWATCHES = listOf(
    0xFF12161B, 0xFF0B0E12, 0xFF161B2E, 0xFF101A14, 0xFF1E1420, 0xFF1B2127, 0xFF201A12, 0xFF0E0E0E,
).map { it.toInt() }
internal val TILE_SWATCHES = listOf(
    0xFF2A2F37, 0xFF1F2937, 0xFF2E2A3A, 0xFF243042, 0xFF323A2E, 0xFF3A2E2E, 0xFF26313A, 0xFF2C2C2C,
).map { it.toInt() }
internal val ACCENT_SWATCHES = listOf(
    0xFFF39C12, 0xFF5C7CFA, 0xFF34D399, 0xFFF472B6, 0xFFA78BFA, 0xFF22D3EE, 0xFFEF4444, 0xFFFACC15,
).map { it.toInt() }
internal val BORDER_SWATCHES = listOf(
    0xFFF39C12, 0xFF5C7CFA, 0xFF34D399, 0xFFF472B6, 0xFFFFFFFF, 0xFF9AA3AE, 0xFF3A4350, 0xFF000000,
).map { it.toInt() }
internal val ICON_ON_SWATCHES = listOf(
    0xFFEAEDF0, 0xFFFFFFFF, 0xFFF39C12, 0xFF5C7CFA, 0xFF34D399, 0xFFFACC15, 0xFF22D3EE, 0xFFF472B6,
).map { it.toInt() }
internal val ICON_OFF_SWATCHES = listOf(
    0xFF9AA3AE, 0xFF6B7280, 0xFF4B5563, 0xFF808890, 0xFF5A6472, 0xFF3A4350, 0xFFB6C0CC, 0xFF7A8696,
).map { it.toInt() }
