package com.tvassist.ui

import com.tvassist.data.ha.Entity
import com.tvassist.data.settings.EntityOverride
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for entity icon resolution — the logic behind "icons match Home Assistant".
 * Guards the device_class fix (temperature/humidity/… sensors) and the domain-default MDI fallback.
 */
class EntityIconResolveTest {

    private fun entity(id: String, state: String = "on", deviceClass: String? = null, icon: String? = null) =
        Entity(
            entityId = id,
            state = state,
            friendlyName = id,
            attributes = buildJsonObject {
                if (deviceClass != null) put("device_class", deviceClass)
                if (icon != null) put("icon", icon)
            },
        )

    // ---- device_class defaults (the reported bug) ----

    @Test fun temperatureSensorUsesThermometer() {
        assertEquals("mdi:thermometer", deviceClassIconifyName(entity("sensor.rm4_temp", deviceClass = "temperature")))
    }

    @Test fun humiditySensorUsesWaterPercent() {
        assertEquals("mdi:water-percent", deviceClassIconifyName(entity("sensor.bedroom_hum", deviceClass = "humidity")))
    }

    @Test fun binarySensorMotionSwitchesIconByState() {
        assertEquals("mdi:motion-sensor", deviceClassIconifyName(entity("binary_sensor.hall", "on", "motion")))
        assertEquals("mdi:motion-sensor-off", deviceClassIconifyName(entity("binary_sensor.hall", "off", "motion")))
    }

    @Test fun sensorWithoutDeviceClassHasNoDeviceClassIcon() {
        assertNull(deviceClassIconifyName(entity("sensor.plain")))
    }

    // ---- domain defaults (fidelity beyond sensors) ----

    @Test fun lightDomainUsesLightbulb() {
        assertEquals("mdi:lightbulb", domainIconifyName(entity("light.kitchen")))
    }

    @Test fun lockDomainSwitchesIconByState() {
        assertEquals("mdi:lock", domainIconifyName(entity("lock.door", "locked")))
        assertEquals("mdi:lock-open-variant", domainIconifyName(entity("lock.door", "unlocked")))
    }

    @Test fun unknownDomainHasNoDomainIcon() {
        assertNull(domainIconifyName(entity("weirddomain.thing")))
    }

    // ---- resolveIconifyName priority: override > HA icon > device_class > domain ----

    @Test fun explicitHaIconWinsOverDeviceClass() {
        val e = entity("sensor.temp", deviceClass = "temperature", icon = "mdi:fire")
        assertEquals("mdi:fire", resolveIconifyName(e, null))
    }

    @Test fun overrideIconWinsOverEverything() {
        val e = entity("sensor.temp", deviceClass = "temperature", icon = "mdi:fire")
        assertEquals("mdi:snowflake", resolveIconifyName(e, EntityOverride("sensor.temp", icon = "mdi:snowflake")))
    }

    @Test fun deviceClassUsedWhenNoExplicitIcon() {
        assertEquals("mdi:thermometer", resolveIconifyName(entity("sensor.temp", deviceClass = "temperature"), null))
    }

    @Test fun domainUsedWhenNoIconOrDeviceClass() {
        assertEquals("mdi:lightbulb", resolveIconifyName(entity("light.kitchen"), null))
    }

    @Test fun legacyCuratedOverrideKeyFallsBackToVector() {
        // A non-":" override is a legacy curated key → handled as a Material vector, not an Iconify name.
        assertNull(resolveIconifyName(entity("light.kitchen"), EntityOverride("light.kitchen", icon = "lightbulb")))
    }
}
