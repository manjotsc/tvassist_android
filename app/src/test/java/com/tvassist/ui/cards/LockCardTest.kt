package com.tvassist.ui.cards

import com.tvassist.data.ha.Entity
import com.tvassist.ui.cards.lock.LockCard
import com.tvassist.ui.lockStateColor
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock card's segmented pair, which shows a state as well as offering two actions.
 *
 * Its selection is deliberately *absent* mid-throw and when jammed. Filling Lock while a bolt is
 * still travelling — or stuck — claims a state the door has not reached, on the one control where
 * being wrong matters.
 */
class LockCardTest {

    private fun lock(state: String) = Entity("lock.front", state, "Front Door")

    @Test fun onlySettledStatesSelectAnOption() {
        assertTrue(lock("locked").isLocked)
        assertFalse(lock("unlocked").isLocked)
        // The two the card maps to "neither".
        assertTrue(lock("locking").isLockTransitioning)
        assertTrue(lock("unlocking").isLockTransitioning)
        assertFalse(lock("jammed").isLockTransitioning)
        assertFalse(lock("jammed").isLocked)
    }

    @Test fun theFillColourIsTheStateColour() {
        assertEquals(Color(0xFF4CAF50), lockStateColor("locked"))
        assertEquals(Color(0xFFF44336), lockStateColor("unlocked"))
        assertEquals(Color(0xFFF44336), lockStateColor("jammed"))
        assertEquals(Color(0xFFFF9800), lockStateColor("locking"))
    }

    /** The tile follows the bolt, not upstream's "wants attention". See [LockCard.isActive]. */
    @Test fun theTileLightsWhenLocked() {
        assertTrue(LockCard.isActive(lock("locked")))
        assertFalse(LockCard.isActive(lock("unlocked")))
        assertFalse(LockCard.isActive(lock("locking")))
    }

    /** Lock and Unlock are the card's own control; the footer must not add a second toggle. */
    @Test fun theCardOwnsItsOnOff() {
        assertTrue(LockCard.ownsToggle(lock("locked")))
    }
}
