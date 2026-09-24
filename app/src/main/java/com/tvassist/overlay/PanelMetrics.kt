package com.tvassist.overlay

import android.view.Gravity
import com.tvassist.data.settings.OverlayAppearance
import com.tvassist.data.settings.OverlayPosition

/**
 * The floating panel's size and placement, shared by both overlay backends.
 *
 * The overlay *window* is fullscreen and transparent; the panel is a card drawn inside it. The
 * native sidebar positions that card with Compose, the Home Assistant page with layout params — so
 * without one definition of the numbers the two backends drift apart, and switching between them
 * silently resizes the overlay. That is exactly the kind of duplicate this codebase has been bitten
 * by before.
 */
internal object PanelMetrics {

    /** Down the side: a column narrow enough to leave the picture visible beside it. */
    const val WIDTH_VERTICAL_DP = 320

    /** Along the top or bottom: a wide, short bar instead. */
    const val WIDTH_HORIZONTAL_DP = 720

    /** The panel never grows past this, however much it holds; it scrolls instead. */
    const val MAX_HEIGHT_DP = 620

    fun widthDp(position: OverlayPosition): Int =
        if (position.isVertical) WIDTH_VERTICAL_DP else WIDTH_HORIZONTAL_DP

    /**
     * How the user's "size" setting scales the panel.
     *
     * The native side applies it by boosting Compose's density, which scales dp *and* sp together;
     * a WebView has no density to boost, so it scales the box and lets the page reflow into it.
     * The two are not identical — text inside the dashboard keeps its own size — but the panel
     * they occupy is the same, which is what matters when switching backends.
     */
    fun sizeFactor(look: OverlayAppearance): Float = look.sizeScale.coerceIn(50, 200) / 100f

    /**
     * The panel's size in pixels, clamped to what the screen actually leaves after the margins.
     *
     * The native side never needs this: its panel is a wrap-content column with
     * `heightIn(max = MAX_HEIGHT_DP)` inside a box that is already padded by the margin, so the
     * layout clamps it for free. A view laid out with explicit `LayoutParams` has no such parent
     * doing the work — given 620dp at 2x density on a 1080p panel it will happily ask for 1240px
     * and touch both edges of the screen, which is what the HA-page backend did at first.
     */
    fun panelSizePx(
        look: OverlayAppearance,
        density: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
    ): Pair<Int, Int> {
        val scale = sizeFactor(look)
        val margin = (look.marginDp * density).toInt()
        val wanted = { dp: Int -> (dp * density * scale).toInt() }
        return Pair(
            wanted(widthDp(look.position)).coerceAtMost(screenWidthPx - margin * 2),
            wanted(MAX_HEIGHT_DP).coerceAtMost(screenHeightPx - margin * 2),
        )
    }

    /** Where the panel sits inside the fullscreen window, as a view [Gravity]. */
    fun gravity(position: OverlayPosition): Int = when (position) {
        OverlayPosition.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
        OverlayPosition.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
        OverlayPosition.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        OverlayPosition.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }
}
