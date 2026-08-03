package com.tvassist.ui

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for the palette derived from the user's chosen colours.
 *
 * Every light-theme bug this app has had came from here or from something ignoring it: white text
 * on white tiles, translucent tiles letting the app behind bleed through, unreadable sub-text. Each
 * is a property that can be asserted, so a regression fails the build instead of needing a
 * screenshot to spot.
 */
class OverlayThemeTest {

    private val darkTile = 0xFF2A2F37.toInt()
    private val lightTile = 0xFFF2F4F7.toInt()
    private val accent = 0xFFF39C12.toInt()
    private val bg = 0xFF12161B.toInt()

    private fun dark() = overlayThemeOf(bg, darkTile, accent)
    private fun light() = overlayThemeOf(0xFFFFFFFF.toInt(), lightTile, accent)

    /** Rough perceptual gap; 0.3 comfortably separates "readable" from "washed out". */
    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) =
        abs(a.luminance() - b.luminance())

    // --- text must be readable on the tile it sits on -------------------------------------------

    @Test fun `light tile gets dark text`() {
        assertTrue("text should be dark on a light tile", light().text.luminance() < 0.5f)
    }

    @Test fun `dark tile gets light text`() {
        assertTrue("text should be light on a dark tile", dark().text.luminance() > 0.5f)
    }

    @Test fun `primary text contrasts with its tile in both themes`() {
        assertTrue(contrast(light().text, light().tile) > 0.3f)
        assertTrue(contrast(dark().text, dark().tile) > 0.3f)
    }

    @Test fun `sub-text still contrasts with its tile in both themes`() {
        // Weaker than primary by design, but it must not wash out — this is the failure that made
        // slider labels invisible on light themes.
        assertTrue(contrast(light().subText, light().tile) > 0.15f)
        assertTrue(contrast(dark().subText, dark().tile) > 0.15f)
    }

    // --- opacity -------------------------------------------------------------------------------

    @Test fun `light tiles are fully opaque`() {
        // At 92% the dark app behind the overlay bled through every white tile, showing ghosted
        // text from whatever was underneath.
        assertEquals(1f, light().tile.alpha, 0.001f)
    }

    @Test fun `dark tiles keep the translucent glass look`() {
        assertTrue("dark tiles should stay slightly translucent", dark().tile.alpha < 1f)
    }

    // --- structural ----------------------------------------------------------------------------

    @Test fun `accent is passed through untouched in both themes`() {
        val expected = androidx.compose.ui.graphics.Color(accent)
        assertEquals(expected, dark().accent)
        assertEquals(expected, light().accent)
    }

    @Test fun `focused tile differs from the resting tile`() {
        // Focus is the only cue a TV user gets while moving with the D-pad.
        assertTrue(dark().tileFocused != dark().tile)
        assertTrue(light().tileFocused != light().tile)
    }

    @Test fun `segments differ from the tile so controls are visible`() {
        assertTrue(light().segmentBg != light().tile)
        assertTrue(dark().segmentBg != dark().tile)
    }
}
