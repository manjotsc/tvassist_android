package com.tvassist.ui.notify

/*
 * Turning Home Assistant's style strings into Compose values: colour names and #rrggbb,
 * background/opacity specs, alignment and the flash period. Shared by the notification card
 * and the fixed pills, which is the only reason these are not private to one of them.
 */

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color

// Named colors (Material-ish palette) accepted in any color/background field.
internal val COLOR_NAMES: Map<String, String> = mapOf(
    "red" to "#F44336", "pink" to "#E91E63", "purple" to "#9C27B0", "deeppurple" to "#673AB7",
    "indigo" to "#3F51B5", "blue" to "#2196F3", "lightblue" to "#03A9F4", "cyan" to "#00BCD4",
    "teal" to "#009688", "green" to "#4CAF50", "lightgreen" to "#8BC34A", "lime" to "#CDDC39",
    "yellow" to "#FFEB3B", "amber" to "#FFC107", "orange" to "#FF9800", "deeporange" to "#FF5722",
    "brown" to "#795548", "grey" to "#9E9E9E", "gray" to "#9E9E9E", "bluegrey" to "#607D8B",
    "bluegray" to "#607D8B", "black" to "#000000", "white" to "#FFFFFF", "silver" to "#C0C0C0",
    "gold" to "#FFD700", "magenta" to "#FF00FF", "fuchsia" to "#FF00FF", "violet" to "#EE82EE",
    "navy" to "#000080", "maroon" to "#800000", "olive" to "#808000", "aqua" to "#00FFFF",
    "turquoise" to "#40E0D0", "salmon" to "#FA8072", "coral" to "#FF7F50",
)

internal val NUMBER_RE = Regex("""[-+]?\d*\.?\d+""")

/** Parses an rgb()/rgba() string, alpha as 0-1 or 0-255. */
internal fun parseRgb(s: String): Color? {
    val n = NUMBER_RE.findAll(s).mapNotNull { it.value.toFloatOrNull() }.toList()
    if (n.size < 3) return null
    fun ch(v: Float) = (v.coerceIn(0f, 255f)) / 255f
    val a = n.getOrNull(3)
    val alpha = when { a == null -> 1f; a <= 1f -> a; else -> a / 255f }
    return Color(red = ch(n[0]), green = ch(n[1]), blue = ch(n[2]), alpha = alpha)
}

/** Accepts hex (#RGB/#RRGGBB/#AARRGGBB, or without '#'), rgb()/rgba(), or a named color. */
internal fun String.toColorOrNull(): Color? {
    val s = trim().lowercase()
    if (s.isEmpty()) return null
    COLOR_NAMES[s.replace(" ", "")]?.let {
        return runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
    }
    if (s.startsWith("rgb")) return parseRgb(s)
    val hex = if (s.startsWith("#")) s else "#$s"
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
}

/** True if [spec] should be loaded as a raster bitmap (photo/PNG/JPG) rather than a tinted vector. */
internal fun isRasterIcon(spec: String): Boolean {
    val s = spec.lowercase()
    if (s.isBlank()) return false
    if (s.endsWith(".svg")) return false
    return s.endsWith(".png") || s.endsWith(".jpg") || s.endsWith(".jpeg") ||
        s.endsWith(".webp") || s.endsWith(".gif") || s.startsWith("/api/") ||
        (s.startsWith("http") && !s.endsWith(".svg"))
}

/** Resolve a card background spec to a color, or null to use the theme tile. */
internal fun cardBackground(spec: String, tile: Color): Color = when (spec.lowercase()) {
    "" -> tile
    "transparent" -> Color.Transparent
    else -> spec.toColorOrNull() ?: tile
}

/**
 * Standalone default palette for notifications — deliberately independent of the control
 * overlay's theme, so toasts look consistent regardless of the user's overlay colors.
 * Every one of these is overridable per-notification from Home Assistant.
 */
internal object NotifColors {
    val background = Color(0xFF1E2228)
    val accent = Color(0xFFE7ECF2)
    val iconChip = Color(0x22FFFFFF)
    val title = Color(0xFFF3F5F9)
    val text = Color(0xFFF3F5F9)
    val subText = Color(0xFFA7AFBC)
}

/** Apply an opacity 0-100 to a color's alpha; values outside that range leave it unchanged. */
internal fun Color.withOpacity(op: Int): Color = if (op in 0..100) copy(alpha = op / 100f) else this

/** Resolve an icon/badge background spec, or null when unset (caller picks a default). */
internal fun bgSpecOrNull(spec: String): Color? = when (spec.lowercase()) {
    "" -> null
    "transparent", "none" -> Color.Transparent
    else -> spec.toColorOrNull()
}

internal fun alignmentFor(position: String): Alignment = when (position.lowercase()) {
    "top-left" -> Alignment.TopStart
    "top-center" -> Alignment.TopCenter
    "bottom-left" -> Alignment.BottomStart
    "bottom-center" -> Alignment.BottomCenter
    "bottom-right" -> Alignment.BottomEnd
    else -> Alignment.TopEnd
}

/**
 * One flash cycle's period in milliseconds. Accepts either the legacy words (slow/medium/fast) or a
 * precise numeric ms value from the slider (clamped to a sane range); lower = faster. "" = medium.
 */
internal fun flashPeriodMs(speed: String): Int = when (val s = speed.trim().lowercase()) {
    "slow" -> 1500
    "fast" -> 450
    "medium", "" -> 850
    else -> s.toIntOrNull()?.coerceIn(120, 6000) ?: 850
}
