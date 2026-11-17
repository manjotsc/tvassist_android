package com.tvassist.ui.maps

import com.tvassist.data.settings.MapSource
import com.tvassist.data.settings.OverlayTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSourceTest {

    /** Persisted in settings and inside saved layouts; renaming one silently resets a user's choice. */
    @Test
    fun keysAreStable() {
        assertEquals(listOf("osm", "google"), MapSource.CHOICES.map { it.first })
    }

    /** Setups from before the choice existed keep exactly the map they had. */
    @Test
    fun blankGlobalKeepsTheOldRule() {
        assertEquals(MapSource.GOOGLE, MapSource.resolve("auto", "", hasGoogleKey = true))
        assertEquals(MapSource.OSM, MapSource.resolve("auto", "", hasGoogleKey = false))
    }

    @Test
    fun autoFollowsSettingsAndATileOverrideWins() {
        assertEquals(MapSource.OSM, MapSource.resolve("auto", MapSource.OSM, hasGoogleKey = true))
        assertEquals(MapSource.OSM, MapSource.resolve(MapSource.OSM, MapSource.GOOGLE, hasGoogleKey = true))
        assertEquals(MapSource.GOOGLE, MapSource.resolve(MapSource.GOOGLE, MapSource.OSM, hasGoogleKey = true))
    }

    /** Google-style was removed; a test TV or saved tile still holding its key falls back cleanly. */
    @Test
    fun theRemovedGoogleStyleKeyReadsAsUnset() {
        assertEquals(MapSource.OSM, MapSource.resolve("googlestyle", "", hasGoogleKey = false))
        assertEquals(MapSource.GOOGLE, MapSource.resolve("auto", "googlestyle", hasGoogleKey = true))
    }

    /**
     * Google cannot draw without a key, and must not be asked to: [MapSource.resolve] still answers
     * OSM there, so a caller that forgets [MapSource.missingKey] fetches no Google tiles with an
     * empty key. What the user *sees* is decided by missingKey, below.
     */
    @Test
    fun resolveNeverAnswersGoogleWithoutAKey() {
        assertEquals(MapSource.OSM, MapSource.resolve("auto", MapSource.GOOGLE, hasGoogleKey = false))
        assertEquals(MapSource.OSM, MapSource.resolve(MapSource.GOOGLE, MapSource.OSM, hasGoogleKey = false))
    }

    /**
     * A tile set to Google with no key used to draw OpenStreetMap silently, which read as the
     * tile's setting being ignored. Now the map says "API key required" — whether Google was
     * chosen on the tile or in Settings for an Auto tile.
     */
    @Test
    fun googleChosenWithoutAKeyIsReportedNotSwapped() {
        assertTrue(MapSource.missingKey(MapSource.GOOGLE, MapSource.OSM, hasGoogleKey = false))
        assertTrue(MapSource.missingKey("auto", MapSource.GOOGLE, hasGoogleKey = false))
    }

    @Test
    fun noKeyIsMissingWhenGoogleWasNotChosen() {
        // A key makes Google drawable.
        assertFalse(MapSource.missingKey(MapSource.GOOGLE, MapSource.OSM, hasGoogleKey = true))
        // The tile overrides a global Google with OpenStreetMap.
        assertFalse(MapSource.missingKey(MapSource.OSM, MapSource.GOOGLE, hasGoogleKey = false))
        // Older setups with no choice at all: nobody picked Google, so OpenStreetMap is right.
        assertFalse(MapSource.missingKey("auto", "", hasGoogleKey = false))
        // The removed Google-style key reads as unset, not as Google.
        assertFalse(MapSource.missingKey("googlestyle", "", hasGoogleKey = false))
    }
}

class VectorLookTest {

    @Test
    fun openStreetMapHasALightAndADark() {
        assertEquals(VectorLook.OSM_DARK, VectorLook.of(MapSource.OSM, dark = true))
        assertEquals(VectorLook.OSM_LIGHT, VectorLook.of(MapSource.OSM, dark = false))
    }
}

/** The options and texts the map UI shows, so a change to one is a deliberate edit here. */
class MapUiTextTest {

    /** The chips in the person-tile and map-card editors: Auto, then every map Settings offers. */
    @Test
    fun tileAndCardMapChoices() {
        assertEquals(
            listOf("auto" to "Auto", "osm" to "OpenStreetMap", "google" to "Google Maps"),
            OverlayTile.MAP_PROVIDERS,
        )
    }

    @Test
    fun googleLooksAndTheirOrder() {
        assertEquals(listOf("roadmap", "dark", "satellite"), MapSource.GOOGLE_LOOKS.map { it.first })
        assertEquals("dark", MapSource.nextGoogleLook("roadmap"))
        assertEquals("satellite", MapSource.nextGoogleLook("dark"))
        assertEquals("roadmap", MapSource.nextGoogleLook("satellite"))
        // A value from an older build, or garbage, starts the cycle over rather than sticking.
        assertEquals("roadmap", MapSource.nextGoogleLook("terrain"))
    }

    @Test
    fun openStreetMapHintOffersOnlyLightAndDark() {
        assertEquals("▲▼ zoom · ▶ dark · BACK to close", mapKeyHint(vector = true, dark = true, googleLook = "roadmap", traffic = true))
        assertEquals("▲▼ zoom · ▶ light · BACK to close", mapKeyHint(vector = true, dark = false, googleLook = "roadmap", traffic = false))
    }

    @Test
    fun googleHintNamesTheLookAndTraffic() {
        assertEquals(
            "▲▼ zoom · ▶ satellite · ◀ traffic on · BACK to close",
            mapKeyHint(vector = false, dark = true, googleLook = "satellite", traffic = true),
        )
    }
}
