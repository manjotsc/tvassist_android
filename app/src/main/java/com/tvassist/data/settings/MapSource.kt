package com.tvassist.data.settings

/**
 * Which map a location screen draws: chosen once in Settings → Maps, overridable per person tile
 * and per map card ([OverlayTile.mapProvider], [MapCard.mapProvider]).
 *
 * The keys are persisted — in the global setting and inside users' saved layouts — so they cannot
 * be renamed. `osm` and `google` predate this list; they are the strings tiles already store.
 *
 * Which *look* a map wears (light/dark, satellite, traffic) is not chosen here: that is the D-pad's
 * job on the map itself, remembered separately per map (`Settings.mapOsmDark` for OpenStreetMap,
 * `mapStyle`/`mapTraffic` for Google).
 *
 * A third map once sat between these — "Google-style", OpenStreetMap repainted Google-like — and
 * was taken out on 2026-11-10 pending a look based on Vela, which needs its author's permission
 * first (Vela is GPL-3.0, this app Apache-2.0). Its key `googlestyle` may still be in a test TV's
 * settings or a saved tile; [resolve] treats any unknown key as unset, so those fall back cleanly.
 */
object MapSource {
    /** OpenFreeMap's own styles, drawn as vector tiles by MapLibre. Free, no key. */
    const val OSM = "osm"

    /** Google's own tiles. Needs the Maps API key; billed by Google. */
    const val GOOGLE = "google"

    /**
     * Google Maps' looks (`Settings.mapStyle`), in the order ▶ steps through them on the map and the
     * Settings chips list them. Values are persisted. "dark" is a roadmap with session styles.
     */
    val GOOGLE_LOOKS = listOf("roadmap" to "Roadmap", "dark" to "Dark", "satellite" to "Satellite")

    /** The Google look ▶ moves to from [current]; anything unknown starts over at roadmap. */
    fun nextGoogleLook(current: String): String {
        val i = GOOGLE_LOOKS.indexOfFirst { it.first == current }
        return GOOGLE_LOOKS[(i + 1) % GOOGLE_LOOKS.size].first
    }

    /** The choices Settings → Maps offers, in order. */
    val CHOICES = listOf(OSM to "OpenStreetMap", GOOGLE to "Google Maps")

    private val KNOWN = setOf(OSM, GOOGLE)

    /** What [override] and [global] ask for, before a missing key is taken into account. */
    private fun wanted(override: String, global: String, hasGoogleKey: Boolean): String = when {
        override in KNOWN -> override
        global in KNOWN -> global
        else -> if (hasGoogleKey) GOOGLE else OSM
    }

    /**
     * The map to draw, given a tile's [override] (`auto` or one of the keys), the [global] choice
     * from Settings (blank on setups from before it existed) and whether a Google key is set.
     *
     * Blank global keeps the old behaviour exactly — Google when a key is set, else OpenStreetMap —
     * so nobody's map changes on upgrade. Google without a key never draws: callers check
     * [missingKey] first and say so on the map. This still answers OSM in that case only so that
     * nothing which forgets to check can start fetching Google tiles with an empty key.
     */
    fun resolve(override: String, global: String, hasGoogleKey: Boolean): String {
        val w = wanted(override, global, hasGoogleKey)
        return if (w == GOOGLE && !hasGoogleKey) OSM else w
    }

    /**
     * True when Google Maps was explicitly chosen — on the tile, or in Settings for an Auto tile —
     * and no key is set. The map then shows "API key required" instead of drawing OpenStreetMap:
     * it used to fall back silently, and a tile set to Google showing OpenStreetMap read as the
     * setting being ignored. A blank global choice never trips this; nobody picked Google there.
     */
    fun missingKey(override: String, global: String, hasGoogleKey: Boolean): Boolean =
        !hasGoogleKey && wanted(override, global, hasGoogleKey) == GOOGLE

    /** What a map shows in place of itself when [missingKey]. */
    const val KEY_REQUIRED_TEXT = "Google Maps API key required"
    const val KEY_REQUIRED_HINT = "Add one in Settings → Security, or set this map to OpenStreetMap."
}
