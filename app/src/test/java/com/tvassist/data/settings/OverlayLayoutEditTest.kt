package com.tvassist.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the batched layout edits behind the "adding multiple pills/tiles only kept one"
 * fix. The atomic store write is what prevents the race; these guard the transform logic itself.
 */
class OverlayLayoutEditTest {

    private fun layout(vararg rows: OverlayRow) = OverlayLayout(rows.toList())

    @Test fun addsAllTilesInOnePass() {
        val out = layout(OverlayRow()).withTilesAdded(0, listOf("light.a", "light.b", "light.c"))
        assertEquals(listOf("light.a", "light.b", "light.c"), out.rows[0].tiles.map { it.entityId })
    }

    @Test fun addsAllPillsInOnePass() {
        val out = layout(OverlayRow(type = OverlayRow.TYPE_HEADER))
            .withPillsAdded(0, listOf("sensor.temp", "sensor.hum"))
        assertEquals(listOf("sensor.temp", "sensor.hum"), out.rows[0].pills.map { it.entityId })
    }

    @Test fun pillsDedupeAgainstExistingAndWithinBatch() {
        val start = layout(OverlayRow(type = OverlayRow.TYPE_HEADER, pills = listOf(OverlayPill("sensor.temp"))))
        val out = start.withPillsAdded(0, listOf("sensor.temp", "sensor.hum", "sensor.hum"))
        assertEquals(listOf("sensor.temp", "sensor.hum"), out.rows[0].pills.map { it.entityId })
    }

    @Test fun outOfRangeRowLeavesLayoutUnchanged() {
        val start = layout(OverlayRow())
        assertEquals(start, start.withTilesAdded(5, listOf("light.x")))
    }

    @Test fun addingToOneRowDoesNotTouchOthers() {
        val out = layout(OverlayRow(), OverlayRow()).withTilesAdded(1, listOf("light.a"))
        assertEquals(emptyList<String>(), out.rows[0].tiles.map { it.entityId })
        assertEquals(listOf("light.a"), out.rows[1].tiles.map { it.entityId })
    }
}
