package com.tvassist.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

// --- App chrome palette (premium dark theme) ---
internal val AppAccent = Color(0xFF5C7CFA)
internal val AppBgTop = Color(0xFF11151C)
internal val AppBgBottom = Color(0xFF090B0F)
internal val RailBg = Color(0xFF0A0C10)
internal val CardBg = Color(0xFF161B22)
internal val CardFocusBg = Color(0xFF1F2632)
internal val ChipDim = Color(0xFF232B36)
internal val TxtPrimary = Color(0xFFF2F4F7)
internal val TxtMuted = Color(0xFF8A94A3)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun accentBorder(radius: Int) = ClickableSurfaceDefaults.border(
    focusedBorder = Border(BorderStroke(1.5.dp, AppAccent), shape = RoundedCornerShape(radius.dp)),
)

/** Primary action button with the premium accent-border focus (replaces stark white focus). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AccentButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    // Theme-driven rather than the fixed dark palette: these buttons appear inside the overlay's
    // control card, where a light theme left dark buttons stranded on a white card. In-app screens
    // provide no theme, so they fall back to DefaultOverlayTheme and stay dark as before.
    val th = LocalOverlayTheme.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = th.chip,
            focusedContainerColor = th.tileFocused,
            pressedContainerColor = th.tileFocused,
            contentColor = th.text,
            focusedContentColor = th.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = accentBorder(14),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** A selectable pill chip: accent-filled when selected, accent-border on focus. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChipButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dense: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(if (dense) 10.dp else 12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AppAccent else ChipDim,
            focusedContainerColor = if (selected) AppAccent else CardFocusBg,
            pressedContainerColor = if (selected) AppAccent else CardFocusBg,
            contentColor = if (selected) Color.White else TxtPrimary,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                BorderStroke(1.5.dp, if (selected) Color.White.copy(alpha = 0.4f) else AppAccent),
                shape = RoundedCornerShape(if (dense) 10.dp else 12.dp),
            ),
        ),
    ) {
        Box(
            modifier = if (dense) Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            else Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(label, fontSize = if (dense) 12.sp else 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** A compact icon-only action button (reorder/delete) with premium focus. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PremiumIconButton(icon: ImageVector, desc: String, onClick: () -> Unit, modifier: Modifier = Modifier, dense: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(if (dense) 10.dp else 12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ChipDim,
            focusedContainerColor = CardFocusBg,
            pressedContainerColor = CardFocusBg,
            contentColor = TxtPrimary,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = accentBorder(if (dense) 10 else 12),
    ) {
        Box(modifier = Modifier.padding(if (dense) 6.dp else 11.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = desc, modifier = Modifier.size(if (dense) 15.dp else 18.dp))
        }
    }
}

/** A premium full-width list row: icon chip + title/subtitle + trailing, accent-border focus. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PremiumRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable (focused: Boolean) -> Unit)? = null,
    iconContent: (@Composable (tint: Color) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CardBg,
            focusedContainerColor = CardFocusBg,
            pressedContainerColor = CardFocusBg,
            contentColor = TxtPrimary,
            focusedContentColor = TxtPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
        border = accentBorder(18),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(CircleShape)
                    .background(if (focused) AppAccent.copy(alpha = 0.18f) else ChipDim),
                contentAlignment = Alignment.Center,
            ) {
                if (iconContent != null) {
                    iconContent(if (focused) AppAccent else Color(0xFFB6C0CC))
                } else {
                    Icon(
                        icon, contentDescription = null,
                        tint = if (focused) AppAccent else Color(0xFFB6C0CC),
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, color = TxtPrimary, fontWeight = FontWeight.Medium, maxLines = 1)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 12.sp, color = TxtMuted, maxLines = 1)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing(focused)
            }
        }
    }
}
