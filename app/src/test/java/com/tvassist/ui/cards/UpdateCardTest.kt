package com.tvassist.ui.cards

import com.tvassist.data.ha.Entity
import com.tvassist.ui.cards.update.UpdateCard
import com.tvassist.ui.cards.update.canInstall
import com.tvassist.ui.cards.update.formatPercent
import com.tvassist.ui.cards.update.updateStatus
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shaped like the Demo integration's update entities. */
class UpdateCardTest {

    private fun update(state: String, attrs: JsonObject) =
        Entity("update.demo_update_with_decimal_progress", state, "Update with Decimal Progress", attrs)

    private val installing = update(
        "on",
        buildJsonObject {
            put("installed_version", "1.93.3")
            put("latest_version", "1.94.2")
            put("in_progress", true)
            put("update_percentage", 42.7)
            put("supported_features", 5)
        },
    )

    @Test fun `the domain resolves to its own card`() {
        assertEquals(UpdateCard, cardFor(installing))
    }

    @Test fun `decimal progress keeps its decimal`() {
        assertTrue(installing.updateInProgress)
        assertEquals(42.7, installing.updatePercentage!!, 0.0001)
        assertEquals("Installing 42.7%", updateStatus(installing, compact = true))
    }

    @Test fun `a whole percent prints without one`() {
        assertEquals("50%", formatPercent(50.0))
        assertEquals("0.5%", formatPercent(0.5))
    }

    @Test fun `the pre-2024_11 shape, a number in in_progress, still reads as progress`() {
        val old = update("on", buildJsonObject { put("in_progress", 30) })
        assertTrue(old.updateInProgress)
        assertEquals(30.0, old.updatePercentage!!, 0.0001)
    }

    @Test fun `busy without a figure says so rather than inventing one`() {
        val busy = update("on", buildJsonObject { put("in_progress", true) })
        assertNull(busy.updatePercentage)
        assertEquals("Installing…", updateStatus(busy, compact = true))
    }

    @Test fun `available and up to date read as such`() {
        val available = update("on", buildJsonObject {
            put("installed_version", "1.0.0"); put("latest_version", "1.0.1"); put("in_progress", false)
        })
        assertFalse(available.updateInProgress)
        assertEquals("1.0.1 available", updateStatus(available, compact = true))
        assertEquals("Update available · 1.0.1", updateStatus(available, compact = false))
        val current = update("off", buildJsonObject { put("installed_version", "1.0.1") })
        assertEquals("Up to date", updateStatus(current, compact = true))
        assertEquals("Up to date · 1.0.1", updateStatus(current, compact = false))
    }

    @Test fun `install and backup follow supported_features`() {
        assertTrue(installing.supportsUpdateInstall) // 5 = INSTALL | PROGRESS
        assertFalse(installing.supportsUpdateBackup)
        val both = update("on", buildJsonObject { put("supported_features", 1 or 8) })
        assertTrue(both.supportsUpdateBackup)
    }

    @Test fun `lit when an update is waiting, dark when current`() {
        assertTrue(UpdateCard.isActive(installing))
        assertFalse(UpdateCard.isActive(update("off", buildJsonObject {})))
    }

    @Test fun `HA's explicit null percentage while busy is no figure, not zero`() {
        // What a current HA actually sends mid-install from an integration without PROGRESS.
        val busy = update("on", buildJsonObject { put("in_progress", true); put("update_percentage", JsonNull) })
        assertTrue(busy.updateInProgress)
        assertNull(busy.updatePercentage)
    }

    @Test fun `idle reads as idle in both shapes`() {
        assertFalse(update("on", buildJsonObject { put("in_progress", false); put("update_percentage", JsonNull) }).updateInProgress)
        assertFalse(update("on", buildJsonObject {}).updateInProgress)
    }

    @Test fun `nearly done never prints as done`() {
        assertEquals("99.9%", formatPercent(99.96))
        assertEquals("99.9%", formatPercent(99.95))
        assertEquals("100%", formatPercent(100.0))
        assertEquals("42.3%", formatPercent(42.25))
    }

    @Test fun `Install is offered only when it can work`() {
        val available = { features: Int, busy: Boolean ->
            update("on", buildJsonObject { put("supported_features", features); put("in_progress", busy) })
        }
        assertTrue(canInstall(available(1, false)))
        // The demo's "Update No Install": reports only, so the button would just fail.
        assertFalse(canInstall(available(0, false)))
        assertFalse(canInstall(available(4, false))) // PROGRESS without INSTALL
        assertFalse(canInstall(available(5, true))) // already installing
        assertFalse(canInstall(update("off", buildJsonObject { put("supported_features", 1) })))
    }
}
