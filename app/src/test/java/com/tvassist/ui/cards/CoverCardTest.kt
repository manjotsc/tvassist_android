package com.tvassist.ui.cards

import com.tvassist.data.ha.Entity
import com.tvassist.ui.cards.cover.CoverCard
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cover's state is open/closed/opening/closing — never the literal "on".
 *
 * Everything routed through `Entity.isOn` therefore treated every cover as closed, which is how
 * "toggle" came to mean "open" in both directions and why a cover tile never highlighted. These
 * assertions exist so that cannot come back quietly.
 */
class CoverCardTest {

    private fun cover(state: String, position: Int? = null, features: Int? = null) =
        Entity(
            entityId = "cover.blind",
            state = state,
            friendlyName = "Blind",
            attributes = buildJsonObject {
                if (position != null) put("current_position", position)
                if (features != null) put("supported_features", features)
            },
        )

    // ---- the bug ----

    @Test fun isOnIsUselessForCoversWhichIsWhyIsOpenExists() {
        // Every one of these is "not closed", yet none reports the state "on".
        listOf("open", "opening").forEach { assertFalse(cover(it).isOn) }
        assertTrue(cover("open").isOpen)
        assertTrue(cover("opening").isOpen)
        assertFalse(cover("closed").isOpen)
        assertFalse(cover("closing").isOpen)
    }

    @Test fun anOpenCoverReadsAsActive() {
        assertTrue(CoverCard.isActive(cover("open")))
        assertFalse(CoverCard.isActive(cover("closed")))
    }

    // ---- status ----

    @Test fun statusIsTheCapitalisedState() {
        assertEquals("Open", CoverCard.status(cover("open"), compact = true))
        assertEquals("Closed", CoverCard.status(cover("closed"), compact = true))
        assertEquals("Opening", CoverCard.status(cover("opening"), compact = false))
    }

    @Test fun positionIsShownOnlyWhilePartlyOpen() {
        assertEquals("Open · 45%", CoverCard.status(cover("open", position = 45), compact = true))
        // Fully open or shut reads better without the redundant number.
        assertEquals("Open", CoverCard.status(cover("open", position = 100), compact = true))
        assertEquals("Closed", CoverCard.status(cover("closed", position = 0), compact = true))
    }

    // ---- CoverEntityFeature bitmask ----

    @Test fun capabilitiesComeFromTheSupportedFeaturesBits() {
        val basic = cover("open", features = 1 or 2)             // OPEN | CLOSE
        assertTrue(basic.supportsOpenClose)
        assertFalse(basic.supportsCoverStop)
        assertFalse(basic.supportsCoverPosition)
        assertFalse(basic.supportsCoverTilt)

        val full = cover("open", features = 1 or 2 or 4 or 8 or 16 or 32)
        assertTrue(full.supportsCoverStop)
        assertTrue(full.supportsCoverPosition)
        assertTrue(full.supportsCoverTilt)
    }

    @Test fun aCoverThatReportsNoFeaturesClaimsNoOptionalOnes() {
        val bare = cover("open")
        assertFalse(bare.supportsCoverStop)
        assertFalse(bare.supportsCoverPosition)
    }

    @Test fun movingIsDistinctFromOpen() {
        assertTrue(cover("opening").isCoverMoving)
        assertTrue(cover("closing").isCoverMoving)
        assertFalse(cover("open").isCoverMoving)
    }
}
