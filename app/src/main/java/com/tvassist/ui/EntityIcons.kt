package com.tvassist.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Blinds
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cyclone
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.tvassist.data.ha.Entity

/** Material icon that best represents an entity's domain (Home-Assistant-like glyphs). */
fun domainIcon(entity: Entity): ImageVector = when (entity.domain) {
    "light" -> Icons.Rounded.Lightbulb
    "switch", "input_boolean" -> Icons.Rounded.ToggleOn
    "climate" -> Icons.Rounded.DeviceThermostat
    "camera" -> Icons.Rounded.Videocam
    "fan" -> Icons.Rounded.Air
    "lock" -> if (entity.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen
    "media_player" -> Icons.Rounded.PlayCircle
    "cover" -> Icons.Rounded.Blinds
    "script" -> Icons.Rounded.Bolt
    "automation" -> Icons.Rounded.SmartToy
    "button", "input_button" -> Icons.Rounded.TouchApp
    "person", "device_tracker" -> Icons.Rounded.Person
    "map" -> Icons.Rounded.Map
    "sensor", "binary_sensor" -> Icons.Rounded.Sensors
    else -> Icons.Rounded.Power
}

/**
 * The MDI icon Home Assistant's frontend shows for an entity based on its `device_class` when no
 * explicit icon is set — e.g. a temperature sensor is mdi:thermometer, humidity is mdi:water-percent.
 * Mirrors HA's built-in defaults so sensors match HA without the user hand-picking icons. Returns an
 * "mdi:…" name (rendered faithfully via the Iconify/Coil path), or null when there's no default.
 * Binary sensors and covers use different icons for their on/off (open/closed) state, like HA.
 */
fun deviceClassIconifyName(entity: Entity): String? {
    val dc = entity.deviceClass ?: return null
    val on = entity.isOn
    return when (entity.domain) {
        "sensor" -> when (dc) {
            "temperature" -> "mdi:thermometer"
            "humidity", "moisture" -> "mdi:water-percent"
            "pressure", "atmospheric_pressure" -> "mdi:gauge"
            "battery" -> "mdi:battery"
            "power", "reactive_power", "apparent_power" -> "mdi:flash"
            "power_factor" -> "mdi:angle-acute"
            "energy", "energy_storage" -> "mdi:lightning-bolt"
            "current" -> "mdi:current-ac"
            "voltage" -> "mdi:sine-wave"
            "frequency" -> "mdi:sine-wave"
            "illuminance" -> "mdi:brightness-5"
            "signal_strength" -> "mdi:wifi"
            "carbon_dioxide" -> "mdi:molecule-co2"
            "carbon_monoxide" -> "mdi:molecule-co"
            "pm1", "pm25", "pm10", "volatile_organic_compounds",
            "nitrogen_dioxide", "nitrogen_monoxide", "nitrous_oxide",
            "sulphur_dioxide", "ozone" -> "mdi:molecule"
            "aqi" -> "mdi:air-filter"
            "gas" -> "mdi:meter-gas"
            "water" -> "mdi:water"
            "timestamp" -> "mdi:clock"
            "date" -> "mdi:calendar"
            "duration" -> "mdi:timer-outline"
            "distance" -> "mdi:arrow-left-right"
            "speed", "wind_speed" -> "mdi:speedometer"
            "precipitation", "precipitation_intensity" -> "mdi:weather-rainy"
            "data_rate" -> "mdi:transmission-tower"
            "data_size" -> "mdi:database"
            "monetary" -> "mdi:cash"
            "weight" -> "mdi:weight"
            "sound_pressure" -> "mdi:ear-hearing"
            "irradiance" -> "mdi:sun-wireless"
            else -> null
        }
        "binary_sensor" -> when (dc) {
            "motion", "moving" -> if (on) "mdi:motion-sensor" else "mdi:motion-sensor-off"
            "door" -> if (on) "mdi:door-open" else "mdi:door-closed"
            "garage_door" -> if (on) "mdi:garage-open" else "mdi:garage"
            "window", "opening" -> if (on) "mdi:window-open" else "mdi:window-closed"
            "moisture" -> if (on) "mdi:water-alert" else "mdi:water-off"
            "smoke" -> if (on) "mdi:smoke-detector-alert" else "mdi:smoke-detector-variant"
            "gas", "carbon_monoxide" -> if (on) "mdi:smoke-detector-alert" else "mdi:smoke-detector-variant-off"
            "lock" -> if (on) "mdi:lock-open" else "mdi:lock"
            "presence", "occupancy" -> if (on) "mdi:home" else "mdi:home-outline"
            "connectivity" -> if (on) "mdi:check-network-outline" else "mdi:close-network-outline"
            "battery" -> if (on) "mdi:battery-alert" else "mdi:battery"
            "problem", "safety" -> if (on) "mdi:alert-circle" else "mdi:check-circle"
            "power", "plug" -> if (on) "mdi:power-plug" else "mdi:power-plug-off"
            "vibration" -> if (on) "mdi:vibrate" else "mdi:crop-portrait"
            "sound" -> if (on) "mdi:music-note" else "mdi:music-note-off"
            "update" -> if (on) "mdi:package-up" else "mdi:package"
            "running" -> if (on) "mdi:play" else "mdi:stop"
            "tamper" -> if (on) "mdi:shield-alert" else "mdi:shield-check"
            "light" -> if (on) "mdi:brightness-7" else "mdi:brightness-5"
            "cold" -> if (on) "mdi:snowflake" else "mdi:thermometer"
            "heat" -> if (on) "mdi:fire" else "mdi:thermometer"
            else -> null
        }
        "cover" -> when (dc) {
            "garage" -> if (on) "mdi:garage-open" else "mdi:garage"
            "door", "gate" -> if (on) "mdi:door-open" else "mdi:door-closed"
            "window" -> if (on) "mdi:window-open" else "mdi:window-closed"
            "blind", "shade", "shutter", "curtain", "awning" -> if (on) "mdi:blinds-open" else "mdi:blinds"
            else -> null
        }
        "switch" -> when (dc) {
            "outlet" -> "mdi:power-socket"
            else -> null
        }
        else -> null
    }
}

/**
 * Home Assistant's default MDI icon for an entity's DOMAIN (used when it has no explicit icon and no
 * device_class match) — e.g. light → mdi:lightbulb, switch → mdi:toggle-switch-variant. Rendered via
 * the Iconify path so lights/switches/fans/locks/etc. match HA, not just sensors. A wrong or missing
 * name simply falls through to the Material glyph, so this can't break rendering. Some domains vary
 * their icon by state (lock/binary_sensor), matching HA.
 */
fun domainIconifyName(entity: Entity): String? = when (entity.domain) {
    "light" -> "mdi:lightbulb"
    "switch", "input_boolean" -> "mdi:toggle-switch-variant"
    "climate" -> "mdi:thermostat"
    "fan" -> "mdi:fan"
    "camera" -> "mdi:video"
    "lock" -> if (entity.isLocked) "mdi:lock" else "mdi:lock-open-variant"
    "media_player" -> "mdi:cast"
    "cover" -> "mdi:window-shutter"
    "script" -> "mdi:script-text"
    "automation" -> "mdi:robot"
    "scene" -> "mdi:palette"
    "button", "input_button" -> "mdi:gesture-tap-button"
    "person" -> "mdi:account"
    "device_tracker" -> "mdi:account"
    "vacuum" -> "mdi:robot-vacuum"
    "weather" -> "mdi:weather-partly-cloudy"
    "select", "input_select" -> "mdi:format-list-bulleted"
    "number", "input_number" -> "mdi:ray-vertex"
    "sensor" -> "mdi:eye"
    "binary_sensor" -> if (entity.isOn) "mdi:radiobox-marked" else "mdi:radiobox-blank"
    else -> null
}

/** Home-Assistant-style color for a climate HVAC mode (heat=orange, cool=blue, dry=amber, …). */
fun hvacModeColor(mode: String): Color = when (mode.lowercase()) {
    "heat" -> Color(0xFFFF8100)
    "cool" -> Color(0xFF2B9AF9)
    "dry" -> Color(0xFFF5A623)
    "fan_only" -> Color(0xFF00BCD4)
    "auto", "heat_cool" -> Color(0xFF4CAF50)
    else -> Color(0xFFF39C12)
}

/** Icon for a climate HVAC mode (its value is also the entity state). */
fun hvacModeIcon(mode: String): ImageVector = when (mode.lowercase()) {
    "off" -> Icons.Rounded.PowerSettingsNew
    "heat" -> Icons.Rounded.Whatshot
    "cool" -> Icons.Rounded.AcUnit
    "dry" -> Icons.Rounded.WaterDrop
    "fan_only" -> Icons.Rounded.Air
    "auto", "heat_cool" -> Icons.Rounded.Autorenew
    else -> Icons.Rounded.DeviceThermostat
}

/** Icon for a climate fan mode; falls back to a generic fan glyph. */
fun fanModeIcon(mode: String): ImageVector = when (mode.lowercase()) {
    "auto" -> Icons.Rounded.Autorenew
    "low", "quiet", "silent" -> Icons.Rounded.Air
    "medium", "mid" -> Icons.Rounded.Speed
    "high", "turbo", "focus" -> Icons.Rounded.Cyclone
    else -> Icons.Rounded.Air
}

/** A no-icon fallback used where an [ImageVector] is required but unknown. */
val UnknownIcon: ImageVector = Icons.Rounded.HelpOutline

/** Curated icons the user can assign to an entity (key, label, vector). */
val CUSTOM_ICONS: List<Triple<String, String, ImageVector>> = listOf(
    Triple("lightbulb", "Light", Icons.Rounded.Lightbulb),
    Triple("fan", "Fan", Icons.Rounded.Air),
    Triple("ac", "AC", Icons.Rounded.AcUnit),
    Triple("heat", "Heat", Icons.Rounded.Whatshot),
    Triple("thermostat", "Thermostat", Icons.Rounded.DeviceThermostat),
    Triple("lock", "Lock", Icons.Rounded.Lock),
    Triple("tv", "TV", Icons.Rounded.Tv),
    Triple("speaker", "Speaker", Icons.Rounded.Speaker),
    Triple("media", "Media", Icons.Rounded.PlayCircle),
    Triple("camera", "Camera", Icons.Rounded.Videocam),
    Triple("blinds", "Blinds", Icons.Rounded.Blinds),
    Triple("power", "Power", Icons.Rounded.Power),
    Triple("plug", "Plug", Icons.Rounded.PowerSettingsNew),
    Triple("switch", "Switch", Icons.Rounded.ToggleOn),
    Triple("scene", "Scene", Icons.Rounded.Bolt),
    Triple("sensor", "Sensor", Icons.Rounded.Sensors),
    Triple("star", "Star", Icons.Rounded.Star),
    Triple("home", "Home", Icons.Rounded.Home),
)

/** Resolve a custom icon key to its vector, or null if unknown/blank. */
fun iconForKey(key: String): ImageVector? =
    CUSTOM_ICONS.firstOrNull { it.first == key }?.third
