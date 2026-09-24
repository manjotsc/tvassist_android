package com.tvassist.ui.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvassist.ui.LocalOverlayTheme

/** The chip an ordinary row tile draws beside its text. */
internal const val TILE_CHIP_DP = 36

/** The padding above and below a tile's contents. */
internal val TILE_PAD_V = 9.dp

/** A tile's corner radius. A third of a row's height, which is what makes the panel look soft. */
internal val TILE_CORNER = 18.dp

/**
 * The height an ordinary row tile settles at: its chip plus the padding around it.
 *
 * The text never decides it — a 14sp title over an 11sp subtitle is shorter than the chip — so the
 * chip does. An `icon` tile borrows it for the one case where it cannot be square: given a slot too
 * wide to make a sensible square of, it draws a wide button of exactly this height and sits in line
 * with the rows around it.
 */
internal val TILE_ROW_HEIGHT = TILE_CHIP_DP.dp + TILE_PAD_V * 2

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
    /** Chip diameter. An icon-only tile scales it to the tile; a row keeps the default. */
    iconSize: Int = TILE_CHIP_DP,
    /**
     * Whether the icon gets its usual chip behind it.
     *
     * False on an icon-only tile: the tile is already a rounded square, and putting a second
     * rounded square inside it reads as a box in a box with a gap between them. There the tile
     * *is* the button and the glyph sits straight on it.
     */
    iconChip: Boolean = true,
) {
    val th = LocalOverlayTheme.current
    val hasText = title.isNotBlank() || subtitle.isNotBlank()
    // With no text and no trailing, the icon is the only content → center it in the tile.
    val iconOnly = showIcon && !hasText && trailing == null
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(TILE_CORNER)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = th.tile,
            focusedContainerColor = th.tileFocused,
            pressedContainerColor = th.tileFocused,
            contentColor = th.text,
            focusedContentColor = th.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.045f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.5.dp, th.focus), shape = RoundedCornerShape(TILE_CORNER)),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // fillMaxHeight, and only when there is nothing but an icon. androidx.tv's Surface
                // does not pass its min constraints down, so inside a tile with a fixed height —
                // an `aspectRatio(1f)` square, say — this Row wraps to the chip and is placed
                // top-start. `verticalAlignment` centres the Row's children against each other,
                // not the Row against the Surface, which is the trap documented in CLAUDE.md.
                .then(if (iconOnly) Modifier.fillMaxHeight() else Modifier)
                .padding(horizontal = 11.dp, vertical = TILE_PAD_V),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (iconOnly) Arrangement.Center else Arrangement.Start,
        ) {
            if (showIcon) {
                if (iconChip) {
                    HaIconChip(
                        icon, iconOn, iconTint,
                        size = iconSize,
                        iconContent = iconContent?.let { content -> { tint -> content(tint) } },
                    )
                } else {
                    // No chip: state reads from the glyph's own colour — the bulb's tint when it
                    // has one, the theme's on/off pair otherwise.
                    val resolved = iconTint ?: if (iconOn) th.iconOn else th.iconOff
                    Box(Modifier.size(iconSize.dp), contentAlignment = Alignment.Center) {
                        if (iconContent != null) {
                            iconContent(resolved)
                        } else {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = resolved,
                                modifier = Modifier.size(iconSize.dp),
                            )
                        }
                    }
                }
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
    // A pill at whatever height it is given, rather than two hardcoded radii that only looked
    // right at one size — the gradient tracks in [ColorSliderRow] are drawn the same way, and a
    // brightness bar sitting beside a warmth bar has to match it.
    val pill = RoundedCornerShape(height.dp / 2)
    Box(modifier = modifier.height(height.dp).clip(pill).background(th.trackBg)) {
        Box(
            Modifier.fillMaxHeight()
                .fillMaxWidth((pct.coerceIn(0, 100)) / 100f)
                .clip(pill)
                .background(th.trackFill),
        )
    }
}

/**
 * A tile that carries controls under its header — the shape [InlineClimateTile] has had to itself.
 *
 * The header is its own focusable and keeps the tile's press and hold behaviour, so a tile with
 * controls still opens its card on hold exactly like a plain one. Each control below is a focusable
 * of its own, which is also what lets the D-pad reach them.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun InlineControlTile(
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
    /**
     * Controls beside the header rather than under it, for a rung that needs one or two buttons
     * and no second line.
     *
     * Deliberately outside the header's `Surface`: anything focusable placed inside it would be a
     * clickable nested in a clickable, which on a remote means the header swallows the press and
     * the button never gets focus at all.
     */
    trailing: (@Composable RowScope.() -> Unit)? = null,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val th = LocalOverlayTheme.current
    Column(modifier = modifier.clip(RoundedCornerShape(TILE_CORNER)).background(th.tile)) {
        // Exactly [TILE_ROW_HEIGHT], so a rung whose controls sit beside the header rather than
        // under it is the same height as the plain rows around it. The outer 10dp padding this
        // replaced made every control tile 10dp taller than its neighbours whether it had a second
        // line or not, which showed the moment a cover's Compact rung put its buttons up here.
        Row(
            // Inset on all four sides, not just the sides. `fillMaxHeight` below makes the header
            // exactly as tall as this Row, so with no vertical padding its focus border ran flush
            // with the tile's own edge — two rounded rectangles sharing a line, which reads as the
            // highlight having square corners where it meets the tile.
            modifier = Modifier.fillMaxWidth().height(TILE_ROW_HEIGHT).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = th.tileFocused,
                    pressedContainerColor = th.tileFocused,
                    contentColor = th.text,
                    focusedContentColor = th.text,
                ),
                // No scale: the header is full-width inside the tile, so scaling bulges it past
                // the panel edge. The focus border is the highlight. As InlineClimateTile.
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(BorderStroke(2.dp, th.focus), shape = RoundedCornerShape(12.dp)),
                ),
            ) {
                Row(
                    // fillMaxHeight because androidx.tv's Surface does not pass its min constraints
                    // to its content: without it this Row wraps to the chip and is placed top-start
                    // in the header. The trap documented in CLAUDE.md.
                    modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showIcon) {
                        HaIconChip(icon, iconOn, iconTint, size = TILE_CHIP_DP, iconContent = iconContent)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        if (title.isNotBlank()) {
                            Text(title, fontSize = 14.sp, color = th.text, maxLines = 1)
                        }
                        if (subtitle.isNotBlank()) {
                            Text(subtitle, fontSize = 11.sp, color = th.subText, maxLines = 1)
                        }
                    }
                }
            }
            trailing?.invoke(this)
        }
        ControlsSlot(controls)
    }
}

/**
 * The second line of a control tile, padded only when there actually is one.
 *
 * A Compact rung may draw nothing here — a cover's two buttons live beside the header instead — and
 * an empty `Column` still claims its own padding, which is 10dp of height bought for nothing. The
 * alternative was asking each card whether it draws controls at a given style, a second source of
 * truth that would drift from [EntityCard.TileControls] the first time one changed without the
 * other. Measuring the slot cannot drift.
 */
@Composable
private fun ControlsSlot(content: @Composable ColumnScope.() -> Unit) {
    Layout(
        content = { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
    ) { measurables, constraints ->
        val side = CONTROLS_PAD.roundToPx()
        val inner = measurables[0].measure(
            constraints.offset(horizontal = -2 * side, vertical = -side),
        )
        if (inner.height == 0) {
            layout(0, 0) {}
        } else {
            layout(constraints.maxWidth, inner.height + side) {
                inner.place(side, 0)
            }
        }
    }
}

/** Breathing room around a control tile's second line, matched to the header's own inset. */
private val CONTROLS_PAD = 10.dp
