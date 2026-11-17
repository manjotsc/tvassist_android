package com.tvassist.ui.maps

import org.maplibre.android.maps.Style

/**
 * The styles the vector engine ([VectorMap]) can draw: every vector map in Settings → Maps, light
 * and dark. A look is only a style — the camera, D-pad, overlay window and marker projection are
 * the same engine for all of them — so adding one (a Vela-based look is planned) is one entry here
 * plus its `MapSource` key.
 *
 * Which map is chosen in Settings (`MapSource`); light/dark is ▶ on the map, remembered per map.
 * Google Maps itself is not here: it is a different engine (Google's own tiles), and its looks —
 * roadmap, dark, satellite, traffic — are Google's, set through its tile session.
 */
internal enum class VectorLook {
    /** OpenFreeMap `liberty`, as served. Chosen over `positron`, whose pale greys wash out from a couch. */
    OSM_LIGHT,

    /** OpenFreeMap `dark`, matching the dark chrome every map screen draws over it. */
    OSM_DARK;

    /** The MapLibre style for this look. Suspending so a look that must be fetched or built first can be added. */
    @Suppress("RedundantSuspendModifier")
    suspend fun style(): Style.Builder = when (this) {
        OSM_LIGHT -> Style.Builder().fromUri(LIBERTY_STYLE_URL)
        OSM_DARK -> Style.Builder().fromUri(DARK_STYLE_URL)
    }

    companion object {
        /** The look for the vector map [source] (a `MapSource` key) drawn [dark] or light. */
        @Suppress("UNUSED_PARAMETER")
        fun of(source: String, dark: Boolean): VectorLook = if (dark) OSM_DARK else OSM_LIGHT
    }
}

private const val LIBERTY_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
