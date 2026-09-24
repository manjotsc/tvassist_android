package com.tvassist.ui.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Brush
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import com.tvassist.ui.AppAccent

/** A cohesive overlay color theme. Colors are Long ARGB (Color(Long) renders, .toInt() stores). */
internal data class OverlayThemePreset(
    val name: String,
    val bg: Long, val tile: Long, val accent: Long,
    val border: Long, val borderOn: Boolean, val iconOn: Long, val iconOff: Long, val focus: Long,
)

internal val THEME_PRESETS = listOf(
    // Dark — deeper backgrounds, lighter tiles for contrast, vivid accents.
    OverlayThemePreset("Graphite", 0xFF0E1217, 0xFF232A33, 0xFFFFA51E, 0xFFFFA51E, true, 0xFFFFFFFF, 0xFF8E97A3, 0xFFFFA51E),
    OverlayThemePreset("Midnight", 0xFF0A0F1E, 0xFF1C2742, 0xFF6E8BFF, 0xFF6E8BFF, true, 0xFFFFFFFF, 0xFF7E8AB0, 0xFF6E8BFF),
    OverlayThemePreset("OLED", 0xFF000000, 0xFF1A1A1C, 0xFF3DDC84, 0xFF3DDC84, true, 0xFFFFFFFF, 0xFF7A8088, 0xFF3DDC84),
    OverlayThemePreset("Nord", 0xFF2A2F3A, 0xFF3B4252, 0xFF8FD3E0, 0xFF8FD3E0, true, 0xFFECEFF4, 0xFF9AA3B2, 0xFF8FD3E0),
    OverlayThemePreset("Sunset", 0xFF170F19, 0xFF2E1E2A, 0xFFFF5FA2, 0xFFFF5FA2, true, 0xFFFFFFFF, 0xFFB08496, 0xFFFF5FA2),
    OverlayThemePreset("Forest", 0xFF0A150F, 0xFF19281E, 0xFF3DDC84, 0xFF3DDC84, true, 0xFFFFFFFF, 0xFF7FA38C, 0xFF3DDC84),
    OverlayThemePreset("Ocean", 0xFF07151B, 0xFF143038, 0xFF24E0FF, 0xFF24E0FF, true, 0xFFFFFFFF, 0xFF6F9AA6, 0xFF24E0FF),
    OverlayThemePreset("Mono", 0xFF0B0B0B, 0xFF242424, 0xFFFFFFFF, 0xFFFFFFFF, true, 0xFFFFFFFF, 0xFF8A8A8A, 0xFFFFFFFF),
    // Light — white tiles on a soft tint, vibrant accents.
    OverlayThemePreset("Daylight", 0xFFE7EDF6, 0xFFFFFFFF, 0xFF1A73E8, 0xFF1A73E8, true, 0xFF1A73E8, 0xFF8A93A3, 0xFF1A73E8),
    OverlayThemePreset("Linen", 0xFFF3EDE2, 0xFFFFFFFF, 0xFFD8602E, 0xFFD8602E, true, 0xFFD8602E, 0xFFA89A88, 0xFFD8602E),
    OverlayThemePreset("Paper", 0xFFE8EAF2, 0xFFFFFFFF, 0xFF5145E6, 0xFF5145E6, true, 0xFF5145E6, 0xFF8B90A0, 0xFF5145E6),
    OverlayThemePreset("Mint", 0xFFE4F3EC, 0xFFFFFFFF, 0xFF0BA86A, 0xFF0BA86A, true, 0xFF0BA86A, 0xFF7E9A8C, 0xFF0BA86A),
    // High contrast.
    OverlayThemePreset("Contrast", 0xFF000000, 0xFF1A1A1A, 0xFFFFE000, 0xFFFFE000, true, 0xFFFFFFFF, 0xFFD0D0D0, 0xFFFFE000),
    OverlayThemePreset("Hi-Light", 0xFFFFFFFF, 0xFFEEF0F3, 0xFF0A39FF, 0xFF111111, true, 0xFF111111, 0xFF555555, 0xFF0A39FF),
)

/** A tappable theme card showing a mini live preview of the overlay in that theme. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ThemeCard(preset: OverlayThemePreset, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF14181E), focusedContainerColor = Color(0xFF1C222B),
            pressedContainerColor = Color(0xFF1C222B), contentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, AppAccent), shape = RoundedCornerShape(14.dp)) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(14.dp)),
        ),
    ) {
        Column(Modifier.width(100.dp).padding(7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(9.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF2B3A55), Color(0xFF1E3A34))))
                    .padding(5.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(preset.bg))
                        .then(if (preset.borderOn) Modifier.border(1.dp, Color(preset.border).copy(alpha = 0.55f), RoundedCornerShape(6.dp)) else Modifier)
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(16.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(preset.accent)))
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(4.dp).clip(CircleShape).background(Color(preset.iconOff)))
                    }
                    MiniTile(preset, on = true)
                    MiniTile(preset, on = false)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(preset.name, color = Color.White, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun MiniTile(preset: OverlayThemePreset, on: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color(preset.tile)).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) Color(preset.iconOn) else Color(preset.iconOff)))
        Spacer(Modifier.width(3.dp))
        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(preset.iconOff).copy(alpha = 0.5f)))
        if (on) {
            Spacer(Modifier.width(3.dp))
            Box(Modifier.width(8.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(preset.accent)))
        }
    }
}
