package com.tvassist.ui.cards

import com.tvassist.data.ha.Entity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [entityStatus], which replaced three hand-written copies that had drifted apart.
 *
 * Two of the differences were bugs and are asserted here so they cannot come back: the Home list
 * dropped a thermostat's hvac mode, and it printed raw Home Assistant states without capitalising
 * them. The third difference was intentional and is also pinned — `compact` is what a tile or a
 * list row gets, the long form is what the card header has room for.
 */
class CardStatusTest {

    private fun entity(id: String, state: String = "on", attrs: Map<String, Any> = emptyMap()) =
        Entity(
            entityId = id,
            state = state,
            friendlyName = id,
            attributes = buildJsonObject {
                attrs.forEach { (k, v) -> when (v) { is Int -> put(k, v); is Double -> put(k, v); else -> put(k, v.toString()) } }
            },
        )

    // ---- the two bugs the merge fixed ----

    @Test fun climateKeepsItsHvacModeInBothForms() {
        val e = entity("climate.ac", "dry", mapOf("current_temperature" to 19.0))
        assertEquals("Dry · 19°", entityStatus(e, compact = true))
        assertEquals("Dry · 19°", entityStatus(e, compact = false))
    }

    @Test fun rawStatesAreCapitalised() {
        assertEquals("Unavailable", entityStatus(entity("sensor.x", "unavailable"), compact = true))
        assertEquals("Heat cool", entityStatus(entity("water_heater.x", "heat_cool"), compact = true))
    }

    // ---- the difference that was intentional ----

    @Test fun lightIsShortOnATileAndSpeltOutOnTheCard() {
        val on = entity("light.k", "on", mapOf("brightness" to 43))   // 43/255 -> 17%
        assertEquals("17%", entityStatus(on, compact = true))
        assertEquals("on · 17%", entityStatus(on, compact = false))
    }

    @Test fun lightWithoutBrightnessFallsBackToOnOff() {
        val on = entity("light.k", "on")
        val off = entity("light.k", "off")
        assertEquals("On", entityStatus(on, compact = true))
        assertEquals("on", entityStatus(on, compact = false))
        assertEquals("Off", entityStatus(off, compact = true))
        assertEquals("off", entityStatus(off, compact = false))
    }

    @Test fun fanUsesTheSameShapeAsLight() {
        val e = entity("fan.x", "on", mapOf("percentage" to 40))
        assertEquals("40%", entityStatus(e, compact = true))
        assertEquals("on · 40%", entityStatus(e, compact = false))
    }

    // ---- cases all three copies already agreed on ----

    @Test fun buttonHasNoStatus() {
        assertEquals("", entityStatus(entity("button.doorbell", "unknown"), compact = true))
    }

    @Test fun conversationShowsAssistNotItsTimestamp() {
        val e = entity("conversation.home_assistant", "2026-09-14T10:00:00+00:00")
        assertEquals("Assist", entityStatus(e, compact = true))
        assertEquals("Assist", entityStatus(e, compact = false))
    }

    @Test fun switchAndLockUseTheCapitalisedState() {
        assertEquals("On", entityStatus(entity("switch.x", "on"), compact = true))
        assertEquals("Locked", entityStatus(entity("lock.door", "locked"), compact = true))
        assertEquals("Jammed", entityStatus(entity("lock.door", "jammed"), compact = false))
    }
}
