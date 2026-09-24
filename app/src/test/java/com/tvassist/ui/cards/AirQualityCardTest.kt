package com.tvassist.ui.cards

import com.tvassist.data.ha.Entity
import com.tvassist.ui.cards.airquality.AirQualityCard
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Demo integration's entity, exactly as Home Assistant reports it. */
class AirQualityCardTest {

    private fun demo() = Entity(
        entityId = "air_quality.demo_air_quality_home",
        // An air_quality entity publishes PM2.5 as its own state.
        state = "14",
        friendlyName = "Demo Air Quality Home",
        attributes = buildJsonObject {
            put("nitrogen_oxide", 100)
            put("particulate_matter_10", 23)
            put("particulate_matter_2_5", 14)
            put("unit_of_measurement", "μg/m³")
            put("attribution", "Powered by Home Assistant")
            put("friendly_name", "Demo Air Quality Home")
        },
    )

    @Test fun `the domain resolves to its own card`() {
        assertEquals(AirQualityCard, cardFor(demo()))
    }

    @Test fun `readings come back in HA's order with absent pollutants dropped`() {
        // Declaration order, not the order the attributes happened to arrive in.
        assertEquals(
            listOf("particulate_matter_2_5", "particulate_matter_10", "nitrogen_oxide"),
            demo().airQualityReadings.map { it.first },
        )
        assertEquals(listOf(14.0, 23.0, 100.0), demo().airQualityReadings.map { it.second })
    }

    @Test fun `the tile status names the number instead of showing a bare state`() {
        // The bug this exists to prevent: the entity's state is "14" and nothing said what of.
        assertEquals("PM2.5 14", AirQualityCard.status(demo(), compact = true))
    }

    @Test fun `the roomy status is blank so the card header cannot repeat the headline`() {
        // The card body shows this reading at 40sp, and EntityControlPanel skips a blank status.
        // Both were drawn at once until now: the same number twice, with the name ellipsised to
        // "Demo Air" to make room for the copy nobody needed.
        assertEquals("", AirQualityCard.status(demo(), compact = false))
    }

    @Test fun `a readout is never highlighted`() {
        // stateActive() answers true for any unrecognised domain, which would leave the tile lit.
        assertTrue("the default would have lit it", stateActive(demo()))
        assertFalse(AirQualityCard.isActive(demo()))
    }

    @Test fun `an entity with no readings degrades to its plain state`() {
        val bare = Entity("air_quality.empty", "unknown", "Empty")
        assertTrue(bare.airQualityReadings.isEmpty())
        assertEquals("Unknown", AirQualityCard.status(bare, compact = true))
    }

    @Test fun `it offers no on-off action so the footer stays out of it`() {
        assertFalse(demo().isToggleable)
        assertFalse(AirQualityCard.ownsToggle(demo()))
    }
}
