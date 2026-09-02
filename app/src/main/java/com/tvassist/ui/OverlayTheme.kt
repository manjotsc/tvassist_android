package com.tvassist.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Resolved color palette for the overlay, driven by the user's appearance settings. Provided
 * via [LocalOverlayTheme] so tile composables can be re-themed without threading colors through
 * every call. The in-app screens use [DefaultOverlayTheme] (the original hardcoded palette).
 */
data class OverlayTheme(
    val background: Color,
    val tile: Color,
    val tileFocused: Color,
    val chip: Color,
    val accent: Color,
    val accentDim: Color,
    val subText: Color,
    val trackBg: Color,
    val trackFill: Color,
    val borderColor: Color,
    val borderEnabled: Boolean,
    val iconOn: Color,
    val iconOff: Color,
    val focus: Color,
    /** Primary on-tile text — adapts to light vs dark tiles so it stays readable. */
    val text: Color,
    /** Segmented-control container background (mode/fan rows). */
    val segmentBg: Color,
    /** Unselected segmented-button background. */
    val segmentItem: Color,
)

val DefaultOverlayTheme = OverlayTheme(
    background = Color(0xFF12161B),
    tile = Color(0xFF2A2F37),
    tileFocused = Color(0xFF3B434E),
    chip = Color(0xFF3A414B),
    accent = Color(0xFFF39C12),
    accentDim = Color(0xFFF39C12).copy(alpha = 0.16f),
    subText = Color(0xFF9AA3AE),
    trackBg = Color(0xFF424A55),
    trackFill = Color(0xFFE6E2DA),
    borderColor = Color(0xFFF39C12),
    borderEnabled = true,
    iconOn = Color(0xFFEAEDF0),
    iconOff = Color(0xFF9AA3AE),
    focus = Color(0xFFF39C12),
    text = Color(0xFFF2F4F7),
    segmentBg = Color(0xFF1B1F25),
    segmentItem = Color(0xFF20242B),
)

val LocalOverlayTheme = staticCompositionLocalOf { DefaultOverlayTheme }

/**
 * Red for a failed answer — darkened on a pale surface rather than paled, so it stays legible on
 * both. A single hardcoded red cannot: on Daylight/Linen/Paper/Mint the usual light red washes out
 * to pink on near-white, and a dark enough red to fix that is unreadable on OLED.
 *
 * Keyed on [tile] rather than [background] because error text is always drawn on a tile — the
 * transcript's segment, the voice bar's body — which is the same surface [text] and [subText] are
 * derived from.
 */
val OverlayTheme.errorText: Color
    get() = if (tile.luminance() > 0.5f) Color(0xFFB3261E) else Color(0xFFEF8A8A)

private fun Color.lighten(f: Float): Color = lerp(this, Color.White, f)

/** Builds a full [OverlayTheme] from the user-chosen colors (ARGB ints). */
fun overlayThemeOf(
    bgArgb: Int,
    tileArgb: Int,
    accentArgb: Int,
    borderArgb: Int = accentArgb,
    borderEnabled: Boolean = true,
    iconOnArgb: Int = 0xFFEAEDF0.toInt(),
    iconOffArgb: Int = 0xFF9AA3AE.toInt(),
    focusArgb: Int = accentArgb,
): OverlayTheme {
    val tile = Color(tileArgb)
    val accent = Color(accentArgb)
    val focus = Color(focusArgb)
    // Light tile → dark text + segments derived darker; dark tile → white text + lighter segments.
    val light = tile.luminance() > 0.5f
    val text = if (light) Color(0xFF15181C) else Color(0xFFF2F4F7)
    return OverlayTheme(
        background = Color(bgArgb),
        // Dark tiles are slightly translucent so the panel background shows through (glass feel).
        // Light tiles stay solid: at 8% transparency the dark app behind the overlay bleeds through
        // every white tile, which reads as dirty grey panels and ghosted text from the app below.
        tile = if (light) tile else tile.copy(alpha = 0.92f),
        // Focused surfaces take on the focus color so each item clearly lights up.
        tileFocused = lerp(tile, focus, if (light) 0.20f else 0.26f),
        chip = if (light) tile.darken(0.05f) else tile.lighten(0.10f),
        accent = accent,
        accentDim = accent.copy(alpha = 0.22f),
        subText = if (light) Color(0xFF6B7480) else Color(0xFF9AA3AE),
        trackBg = if (light) tile.darken(0.10f) else tile.lighten(0.14f),
        trackFill = accent,
        borderColor = Color(borderArgb),
        borderEnabled = borderEnabled,
        iconOn = Color(iconOnArgb),
        iconOff = Color(iconOffArgb),
        focus = Color(focusArgb),
        text = text,
        segmentBg = if (light) tile.darken(0.08f) else tile.darken(0.28f),
        segmentItem = if (light) tile.darken(0.03f) else tile.darken(0.12f),
    )
}

private fun Color.darken(f: Float): Color = lerp(this, Color.Black, f)
