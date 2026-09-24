package com.tvassist.data.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.1.5 wrote `compact` and `full` meaning "a plain row"; today they are rungs of a card's control
 * ladder. These pin the translation, and — as important — that nothing else is touched, since a
 * start-up pass once reset every style a card did not offer to Auto and saved it.
 */
class LegacyStyleMigrationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun tile(id: String, style: String) = """{"entityId":"$id","style":"$style"}"""

    /** A layout as 1.1.5 stored it: no `styleVersion` key at all. */
    private fun legacy(vararg tiles: String) =
        json.parseToJsonElement("""{"rows":[{"columns":2,"tiles":[${tiles.joinToString(",")}]}]}""")

    private fun styles(layout: OverlayLayout) = layout.rows.single().tiles.map { it.style }

    @Test fun compactAndFullBecomeTheRowTheyUsedToBe() {
        val out = OverlayLayout.decodeStored(
            json,
            legacy(
                tile("light.kitchen", "full"),
                tile("light.hall", "compact"),
                tile("switch.fan", "full"),
                tile("sensor.temp", "compact"),
                tile("climate.lounge", "full"),
                tile("map.ta_family", "full"),
                tile("camera.door", "compact"),
            ),
        )
        assertEquals(
            listOf("standard", "standard", "auto", "auto", "compact", "standard", "auto"),
            styles(out),
        )
        assertEquals(OverlayLayout.STYLE_VERSION, out.styleVersion)
    }

    @Test fun everyOtherStyleIsKeptAsSaved() {
        // Square on a scene and Climate on a light are not offered today. They must survive in
        // storage anyway: the renderer resolves them, and deleting them is the bug this replaced.
        val out = OverlayLayout.decodeStored(
            json,
            legacy(
                tile("scene.movie", "square"),
                tile("script.bed", "action"),
                tile("light.desk", "climate"),
                tile("camera.door", "auto"),
                tile("light.x", "tile"),
            ),
        )
        assertEquals(listOf("square", "action", "climate", "auto", "tile"), styles(out))
    }

    @Test fun aCurrentLayoutIsNotTranslatedAgain() {
        // Otherwise a light set to Full in this build would be knocked back to Normal on every read.
        val current = OverlayLayout(
            rows = listOf(OverlayRow(tiles = listOf(OverlayTile("light.kitchen", OverlayTile.STYLE_FULL)))),
        )
        val roundTrip = OverlayLayout.decodeStored(
            json,
            json.encodeToJsonElement(OverlayLayout.serializer(), current),
        )
        assertEquals(listOf("full"), styles(roundTrip))
    }

    @Test fun aTranslatedLayoutIsStampedSoTheNextWriteSticks() {
        val once = OverlayLayout.decodeStored(json, legacy(tile("light.kitchen", "full")))
        val twice = OverlayLayout.decodeStored(json, json.encodeToJsonElement(OverlayLayout.serializer(), once))
        assertEquals(once, twice)
    }
}
