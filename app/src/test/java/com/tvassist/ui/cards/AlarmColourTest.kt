package com.tvassist.ui.cards

import androidx.compose.ui.graphics.Color
import com.tvassist.data.ha.Entity
import com.tvassist.ui.alarmStateColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alarm panel's colours. Pinned because disarmed deliberately differs from a lock's unlocked
 * red, and "make it match the lock" is exactly the tidy-up that would undo it.
 */
class AlarmColourTest {

    private val green = Color(0xFF4CAF50)
    private val orange = Color(0xFFFF9800)
    private val red = Color(0xFFF44336)
    private val purple = Color(0xFFAB47BC)

    private fun panel(state: String) = Entity("alarm_control_panel.security", state, "Security")

    @Test fun `every armed mode is green`() {
        listOf("armed_home", "armed_away", "armed_night", "armed_vacation", "armed_custom_bypass")
            .forEach { assertEquals(it, green, alarmStateColor(it)) }
    }

    @Test fun `the transitions are orange`() {
        listOf("arming", "pending", "disarming").forEach { assertEquals(it, orange, alarmStateColor(it)) }
    }

    @Test fun `only triggered is red`() {
        assertEquals(red, alarmStateColor("triggered"))
        assertNotEquals(red, alarmStateColor("disarmed"))
    }

    @Test fun `disarmed is purple`() {
        assertEquals(purple, alarmStateColor("disarmed"))
    }

    @Test fun `the tile tint uses it, and a disarmed panel is not lit`() {
        assertEquals(purple, stateTint(panel("disarmed")))
        assertEquals(green, stateTint(panel("armed_away")))
        // Lit when armed, as a lock is lit when locked, so the tile and the green icon agree.
        assertTrue(cardFor(panel("armed_away")).isActive(panel("armed_away")))
        assertEquals(false, cardFor(panel("disarmed")).isActive(panel("disarmed")))
    }
}
