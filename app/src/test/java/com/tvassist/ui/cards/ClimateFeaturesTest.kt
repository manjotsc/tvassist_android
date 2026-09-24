package com.tvassist.ui.cards

import com.tvassist.data.ha.Entity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bits and attributes behind the climate card's Preset and Swing rows.
 *
 * Both were absent from the card for as long as it existed while `Entity` had exposed them all
 * along — a thermostat with an Eco preset simply had no way to reach it. These pin the two halves
 * the rows are guarded by, because a row drawn on the strength of the wrong bit is a row of
 * buttons that call a service the thermostat rejects.
 */
class ClimateFeaturesTest {

    private fun thermostat(
        features: Int,
        presets: List<String> = emptyList(),
        swing: List<String> = emptyList(),
        swingH: List<String> = emptyList(),
    ) = Entity(
        entityId = "climate.ac",
        state = "cool",
        friendlyName = "AC",
        attributes = buildJsonObject {
            put("supported_features", features)
            if (presets.isNotEmpty()) {
                put("preset_modes", JsonArray(presets.map { JsonPrimitive(it) }))
                put("preset_mode", presets.first())
            }
            if (swing.isNotEmpty()) {
                put("swing_modes", JsonArray(swing.map { JsonPrimitive(it) }))
                put("swing_mode", swing.first())
            }
            if (swingH.isNotEmpty()) {
                put("swing_horizontal_modes", JsonArray(swingH.map { JsonPrimitive(it) }))
                put("swing_horizontal_mode", swingH.first())
            }
        },
    )

    /** ClimateEntityFeature: PRESET_MODE 16, SWING_MODE 32, SWING_HORIZONTAL_MODE 512. */
    @Test fun eachRowAnswersToItsOwnBit() {
        val preset = thermostat(16, presets = listOf("eco", "away"))
        assertTrue(preset.supportsClimatePresetMode)
        assertFalse(preset.supportsClimateSwingMode)
        assertFalse(preset.supportsClimateSwingHorizontalMode)

        val swing = thermostat(32, swing = listOf("off", "vertical"))
        assertFalse(swing.supportsClimatePresetMode)
        assertTrue(swing.supportsClimateSwingMode)

        val both = thermostat(32 or 512, swing = listOf("off"), swingH = listOf("off", "both"))
        assertTrue(both.supportsClimateSwingMode)
        assertTrue(both.supportsClimateSwingHorizontalMode)
    }

    @Test fun theListsAndCurrentValuesComeBackAsHaSentThem() {
        val e = thermostat(
            16 or 32 or 512,
            presets = listOf("eco", "away", "boost"),
            swing = listOf("off", "vertical"),
            swingH = listOf("off", "both"),
        )
        assertEquals(listOf("eco", "away", "boost"), e.presetModes)
        assertEquals("eco", e.presetMode)
        assertEquals(listOf("off", "vertical"), e.swingModes)
        assertEquals("off", e.swingMode)
        assertEquals(listOf("off", "both"), e.swingHorizontalModes)
        assertEquals("off", e.swingHorizontalMode)
    }

    /**
     * The case the rows are guarded against twice. A thermostat may advertise a feature and publish
     * no options for it, and a label over an empty strip is worse than no row — so the card tests
     * the bit *and* [ModeTextRow] returns early on an empty list.
     */
    @Test fun aFeatureBitWithNoOptionsIsPossible() {
        val e = thermostat(16 or 32)
        assertTrue(e.supportsClimatePresetMode)
        assertTrue(e.presetModes.isEmpty())
        assertTrue(e.supportsClimateSwingMode)
        assertTrue(e.swingModes.isEmpty())
    }

    /** The bits are independent: a thermostat with none of them draws none of the rows. */
    @Test fun aPlainThermostatOffersNeither() {
        val e = thermostat(1)
        assertFalse(e.supportsClimatePresetMode)
        assertFalse(e.supportsClimateSwingMode)
        assertFalse(e.supportsClimateSwingHorizontalMode)
    }

    /** Preset lives on `Entity`, not on either card — a fan reads the same two attributes. */
    @Test fun aFanReadsThePresetAttributesTheSameWay() {
        val fan = Entity(
            entityId = "fan.ceiling",
            state = "on",
            friendlyName = "Fan",
            attributes = buildJsonObject {
                put("preset_modes", JsonArray(listOf("auto", "smart").map { JsonPrimitive(it) }))
                put("preset_mode", "smart")
            },
        )
        assertEquals(listOf("auto", "smart"), fan.presetModes)
        assertEquals("smart", fan.presetMode)
    }
}
