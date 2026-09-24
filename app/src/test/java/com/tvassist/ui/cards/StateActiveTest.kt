package com.tvassist.ui.cards

import com.tvassist.data.ha.Entity
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import com.tvassist.ui.cards.lock.LockCard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which entities read as "active" — what tints an icon and highlights a tile.
 *
 * Mirrors Home Assistant's `common/entity/state_active.ts` (dev, 2026-09-15). The default used to
 * be `state == "on"`, a test no enum-stated domain can pass, so covers, thermostats, vacuums and
 * media players could never highlight at all. These pin the inversion.
 */
class StateActiveTest {

    private fun e(id: String, state: String) =
        Entity(entityId = id, state = state, friendlyName = id)

    @Test fun offAndUnknownAndUnavailableAreNeverActive() {
        for (s in listOf("off", "unknown", "unavailable")) {
            assertFalse("$s", stateActive(e("light.k", s)))
        }
    }

    /** The regression that started this: a thermostat's state is its mode, never the word "on". */
    @Test fun aThermostatIsActiveInEveryModeButOff() {
        for (s in listOf("heat", "cool", "dry", "fan_only", "auto", "heat_cool")) {
            assertTrue(s, stateActive(e("climate.ac", s)))
        }
        assertFalse(stateActive(e("climate.ac", "off")))
    }

    @Test fun aCoverIsActiveUnlessClosed() {
        for (s in listOf("open", "opening", "closing")) assertTrue(s, stateActive(e("cover.b", s)))
        assertFalse(stateActive(e("cover.b", "closed")))
    }

    /** Upstream reads "active" as "wants attention", so an unlocked door is the active one. This
     *  app had it the other way round until the port. */
    @Test fun aLockIsActiveWhenUnlocked() {
        assertTrue(stateActive(e("lock.front", "unlocked")))
        assertFalse(stateActive(e("lock.front", "locked")))
    }

    @Test fun idleStatesAreInactiveForTheDomainsThatNameThem() {
        assertFalse(stateActive(e("vacuum.v", "docked")))
        assertTrue(stateActive(e("vacuum.v", "cleaning")))
        assertFalse(stateActive(e("media_player.tv", "standby")))
        assertTrue(stateActive(e("media_player.tv", "playing")))
        assertFalse(stateActive(e("alarm_control_panel.a", "disarmed")))
        assertTrue(stateActive(e("alarm_control_panel.a", "armed_away")))
        assertFalse(stateActive(e("person.p", "not_home")))
        assertTrue(stateActive(e("person.p", "home")))
    }

    /** A camera inverts the usual rule: only streaming or recording counts, not "idle". */
    @Test fun aCameraIsActiveOnlyWhileStreamingOrRecording() {
        assertTrue(stateActive(e("camera.door", "streaming")))
        assertTrue(stateActive(e("camera.door", "recording")))
        assertFalse(stateActive(e("camera.door", "idle")))
    }

    /** `alert` is the one domain where "off" still means active — it means acknowledged. */
    @Test fun anAlertIsInactiveOnlyWhenIdle() {
        assertTrue(stateActive(e("alert.a", "off")))
        assertTrue(stateActive(e("alert.a", "on")))
        assertFalse(stateActive(e("alert.a", "idle")))
    }

    /** Timestamp-stated domains hold the moment they last fired, so any state at all is active —
     *  and they skip the unknown/off guards entirely. */
    @Test fun timestampDomainsAreActiveOnceTheyHaveFired() {
        assertTrue(stateActive(e("scene.movie", "2026-09-15T10:00:00+00:00")))
        assertTrue(stateActive(e("button.restart", "unknown")))
        assertFalse(stateActive(e("scene.movie", "unavailable")))
    }

    /**
     * Colour and activity are different axes, and a lock is where they visibly disagree: upstream
     * calls an *unlocked* lock active (it wants attention) while colouring a *locked* one green
     * (the good news). Both are HA's own `--state-lock-*` tokens.
     */
    @Test fun theLockCardLightsWhenLockedNotWhenUnlocked() {
        // The one deliberate divergence from upstream. stateActive stays the faithful port, so the
        // two disagree on purpose and this pins both halves against a future "fix" of either.
        assertTrue(LockCard.isActive(e("lock.front", "locked")))
        assertFalse(LockCard.isActive(e("lock.front", "unlocked")))
        assertFalse("mid-throw is not locked yet", LockCard.isActive(e("lock.front", "locking")))
        assertTrue("and stateActive still says the opposite", stateActive(e("lock.front", "unlocked")))
    }

    @Test fun aLockIsGreenLockedAndRedUnlocked() {
        assertEquals(Color(0xFF4CAF50), stateTint(e("lock.front", "locked")))
        assertEquals(Color(0xFFF44336), stateTint(e("lock.front", "unlocked")))
        assertEquals(Color(0xFFF44336), stateTint(e("lock.front", "jammed")))
        assertEquals(Color(0xFFFF9800), stateTint(e("lock.front", "unlocking")))
    }

    /** A domain with no state colour of its own falls through to the theme accent. */
    @Test fun anOrdinaryDomainHasNoStateTint() {
        assertNull(stateTint(e("switch.kettle", "on")))
        assertNull(stateTint(e("climate.ac", "off")))
    }

    /** `EntityCard.isActive` must route to it, or a domain card silently keeps the old behaviour. */
    @Test fun theCardRegistryUsesIt() {
        val thermostat = e("climate.ac", "dry")
        assertTrue(cardFor(thermostat).isActive(thermostat))
        val cover = e("cover.blind", "closed")
        assertFalse(cardFor(cover).isActive(cover))
    }
}
