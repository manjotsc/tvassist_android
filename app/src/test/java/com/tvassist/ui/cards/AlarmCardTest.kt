package com.tvassist.ui.cards

import com.tvassist.data.ha.Entity
import com.tvassist.ui.cards.alarm.AlarmCard
import com.tvassist.ui.cards.alarm.DISARM
import com.tvassist.ui.cards.alarm.alarmService
import com.tvassist.ui.cards.alarm.alarmServiceData
import com.tvassist.ui.cards.alarm.needsCode
import com.tvassist.ui.cards.alarm.selectedFor
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Demo integration's `alarm_control_panel.security`, attributes as Home Assistant sends them. */
class AlarmCardTest {

    private fun panel(
        state: String = "disarmed",
        features: Int = 63,
        codeFormat: String? = "number",
        armRequired: Boolean? = true,
    ) = Entity(
        entityId = "alarm_control_panel.security",
        state = state,
        friendlyName = "Security",
        attributes = buildJsonObject {
            if (codeFormat != null) put("code_format", codeFormat) else put("code_format", JsonNull)
            put("changed_by", JsonNull)
            if (armRequired != null) put("code_arm_required", armRequired)
            put("supported_features", features)
            put("friendly_name", "Security")
        },
    )

    @Test fun `the domain resolves to its own card`() {
        assertEquals(AlarmCard, cardFor(panel()))
    }

    @Test fun `63 offers every arm mode in upstream's order, and never trigger`() {
        assertEquals(listOf("home", "away", "night", "vacation", "custom_bypass"), panel().alarmArmModes)
    }

    @Test fun `only the supported modes are offered`() {
        // ARM_HOME | ARM_AWAY | TRIGGER — the common two-mode panel.
        assertEquals(listOf("home", "away"), panel(features = 1 or 2 or 8).alarmArmModes)
    }

    @Test fun `the state fills its own segment and nothing mid-transition`() {
        assertEquals(DISARM, selectedFor("disarmed"))
        assertEquals("away", selectedFor("armed_away"))
        assertEquals("custom_bypass", selectedFor("armed_custom_bypass"))
        assertNull(selectedFor("arming"))
        assertNull(selectedFor("pending"))
        assertNull(selectedFor("triggered"))
    }

    @Test fun `a code is asked for exactly when HA wants one`() {
        assertTrue(needsCode(panel(), DISARM))
        assertTrue(needsCode(panel(), "away"))
        // Arming without a code: disarming still needs it.
        assertFalse(needsCode(panel(armRequired = false), "away"))
        assertTrue(needsCode(panel(armRequired = false), DISARM))
        // No code format means no code at all.
        assertFalse(needsCode(panel(codeFormat = null), DISARM))
        // HA's default when the attribute is missing is that arming needs it.
        assertTrue(needsCode(panel(armRequired = null), "home"))
    }

    @Test fun `the card header names who changed it, the tile does not`() {
        val e = Entity(
            "alarm_control_panel.security", "armed_away", "Security",
            buildJsonObject { put("changed_by", "Keypad 1") },
        )
        assertEquals("Armed away · by Keypad 1", AlarmCard.status(e, compact = false))
        assertEquals("Armed away", AlarmCard.status(e, compact = true))
    }

    @Test fun `each feature bit names its own mode`() {
        // 63 switches every bit on at once, so on its own it could not tell VACATION (32) from
        // CUSTOM_BYPASS (16) — swapping the two would still produce all five in the right order.
        assertEquals(listOf("home"), panel(features = 1).alarmArmModes)
        assertEquals(listOf("away"), panel(features = 2).alarmArmModes)
        assertEquals(listOf("night"), panel(features = 4).alarmArmModes)
        assertEquals(emptyList<String>(), panel(features = 8).alarmArmModes) // TRIGGER only
        assertEquals(listOf("custom_bypass"), panel(features = 16).alarmArmModes)
        assertEquals(listOf("vacation"), panel(features = 32).alarmArmModes)
    }

    @Test fun `another domain never offers arm modes`() {
        val light = Entity("light.x", "on", "X", buildJsonObject { put("supported_features", 63) })
        assertEquals(emptyList<String>(), light.alarmArmModes)
    }

    @Test fun `every mode maps to HA's service name`() {
        assertEquals("alarm_disarm", alarmService(DISARM))
        assertEquals("alarm_arm_home", alarmService("home"))
        assertEquals("alarm_arm_away", alarmService("away"))
        assertEquals("alarm_arm_night", alarmService("night"))
        assertEquals("alarm_arm_vacation", alarmService("vacation"))
        assertEquals("alarm_arm_custom_bypass", alarmService("custom_bypass"))
    }

    @Test fun `every mode the panel offers round-trips through its armed state`() {
        // The row's ids are the suffix shared by state and service; if they ever diverged, the
        // mode you just armed would stop being filled.
        panel().alarmArmModes.forEach { assertEquals(it, selectedFor("armed_$it")) }
    }

    @Test fun `the code is sent only when there is one`() {
        assertNull(alarmServiceData(null))
        assertEquals("1234", alarmServiceData("1234")!!["code"]!!.content)
        // A PIN is a string: a leading zero must survive.
        assertEquals("0042", alarmServiceData("0042")!!["code"]!!.content)
        assertTrue(alarmServiceData("0042")!!["code"]!!.isString)
    }

    @Test fun `a blank changed_by adds nothing to the header`() {
        val e = Entity(
            "alarm_control_panel.security", "disarmed", "Security",
            buildJsonObject { put("changed_by", "") },
        )
        assertEquals("Disarmed", AlarmCard.status(e, compact = false))
    }
}
