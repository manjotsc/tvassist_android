package com.tvassist.ui.cards

import com.tvassist.data.ha.Entity
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.cards.map.MapCard
import com.tvassist.ui.cards.map.mapThumbKey
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A map card as `HaRepository` synthesises it — an app object, never a Home Assistant entity. */
class MapCardTest {

    private fun card(id: String = "abc123", zoom: Int = 14, provider: String = "osm") = Entity(
        entityId = "map.ta_$id",
        state = "2",
        friendlyName = "Where is everyone",
        attributes = buildJsonObject {
            put("friendly_name", "Where is everyone")
            put("ta_map", true)
            put("ta_map_zoom", zoom)
            put("ta_map_provider", provider)
        },
    )

    @Test fun `the domain resolves to its own card`() {
        assertEquals(MapCard, cardFor(card()))
    }

    @Test fun `the picker offers Normal, Compact and Full after Auto`() {
        assertEquals(
            listOf(
                OverlayTile.STYLE_AUTO,
                OverlayTile.STYLE_STANDARD,
                OverlayTile.STYLE_COMPACT,
                OverlayTile.STYLE_FULL,
            ),
            stylesFor(card()),
        )
    }

    @Test fun `Auto is the Normal row`() {
        assertEquals(OverlayTile.STYLE_STANDARD, resolveTileStyle(card(), OverlayTile.STYLE_AUTO))
    }

    @Test fun `the picture styles resolve to themselves`() {
        assertEquals(OverlayTile.STYLE_COMPACT, resolveTileStyle(card(), OverlayTile.STYLE_COMPACT))
        assertEquals(OverlayTile.STYLE_FULL, resolveTileStyle(card(), OverlayTile.STYLE_FULL))
    }

    @Test fun `a style the card does not offer falls back to the icon row`() {
        assertEquals(OverlayTile.STYLE_STANDARD, resolveTileStyle(card(), OverlayTile.STYLE_SQUARE))
    }

    @Test fun `two map cards never share a thumbnail`() {
        // The key was once the same literal text for every card, so the second card drew the
        // first card's streets.
        assertNotEquals(mapThumbKey(card("home")), mapThumbKey(card("cabin")))
    }

    @Test fun `changing zoom or provider refetches`() {
        assertNotEquals(mapThumbKey(card(zoom = 14)), mapThumbKey(card(zoom = 16)))
        assertNotEquals(mapThumbKey(card(provider = "osm")), mapThumbKey(card(provider = "google")))
        assertEquals(mapThumbKey(card()), mapThumbKey(card()))
    }
}
