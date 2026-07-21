package com.tvassist.data.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

/**
 * A single Home Assistant entity in a UI-friendly shape. Marked [Immutable] (every field is a `val`
 * and its values never change for a given instance — an update produces a NEW Entity) so Compose can
 * skip recomposing list rows whose entity instance is unchanged, keeping the entity list smooth.
 */
@Immutable
data class Entity(
    val entityId: String,
    val state: String,
    val friendlyName: String,
    /** Raw HA attributes, kept so domain control cards can read capabilities/values. */
    val attributes: JsonObject = EMPTY_ATTRS,
    /** ISO-8601 timestamp of the entity's last state change, if known. */
    val lastChanged: String? = null,
) {
    /** The part before the dot, e.g. "light" in "light.kitchen". */
    val domain: String get() = entityId.substringBefore('.', "")

    /** Whether this entity can be meaningfully toggled on/off from the UI. */
    val isToggleable: Boolean
        get() = domain in TOGGLEABLE_DOMAINS

    val isOn: Boolean get() = state.equals("on", ignoreCase = true)

    /** A lock entity, and whether it's currently locked (state is "locked"/"unlocked"/…). */
    val isLock: Boolean get() = domain == "lock"
    val isLocked: Boolean get() = state.equals("locked", ignoreCase = true)
    /** Transitional lock states — an action is in flight. */
    val isLockTransitioning: Boolean get() = state.equals("locking", true) || state.equals("unlocking", true)

    /** Stateless press buttons (state is just a last-pressed timestamp). */
    val isButton: Boolean get() = domain == "button" || domain == "input_button"

    /** Location-tracked entities (person / device_tracker). */
    val isPerson: Boolean get() = domain == "person" || domain == "device_tracker"

    // --- App-defined local cameras (not HA-backed): stream/snapshot URLs live in attributes. ---
    val localStreamUrl: String? get() = attributes.str("ta_stream_url")
    val localSnapshotUrl: String? get() = attributes.str("ta_snapshot_url")
    val localPlayer: String get() = attributes.str("ta_player")?.takeIf { it.isNotBlank() } ?: "auto"
    /** Reload the clip when it ends (finite "rolling clip" cameras, e.g. Québec 511). */
    val localRefresh: Boolean get() = attributes.str("ta_refresh")?.toBoolean() ?: false
    /** True for a camera the user created in the app with a direct URL (bypasses HA's HLS). */
    val isLocalCamera: Boolean get() = localStreamUrl != null

    // --- App-defined map cards (not HA-backed): a synthetic "map.ta_<id>" entity plotting people. ---
    /** True for an app-defined multi-entity location map card. */
    val isMapCard: Boolean get() = domain == "map" && attributes.str("ta_map") != null
    /** Fixed zoom the map card centers on `zone.home` at. */
    val mapCardZoom: Int get() = attributes.int("ta_map_zoom") ?: 14
    /** Map source (auto/osm/google) for the card. */
    val mapCardProvider: String get() = attributes.str("ta_map_provider") ?: "auto"
    /** Whether the fullscreen map shows the side legend (defaults to true for older cards). */
    val mapCardShowLegend: Boolean get() = attributes.str("ta_map_legend")?.toBoolean() ?: true
    /** The card's members as (entityId, legend-option keys) pairs. */
    val mapCardMembers: List<Pair<String, List<String>>>
        get() = (attributes["ta_map_members"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["e"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val opts = (o["o"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            id to opts
        } ?: emptyList()
    val latitude: Double? get() = attributes.dbl("latitude")
    val longitude: Double? get() = attributes.dbl("longitude")
    /** Battery %, GPS accuracy (m) and speed reported by a tracker, if present. */
    val batteryLevel: Int? get() = attributes.int("battery_level")
    val gpsAccuracy: Int? get() = attributes.int("gps_accuracy")
    val speed: Double? get() = attributes.dbl("speed")
    /** Relative URL of the entity's picture/avatar, if any. */
    val entityPicture: String? get() = attributes.str("entity_picture")
    /** Home Assistant's own icon for this entity, e.g. "mdi:ceiling-light", if set. */
    val haIcon: String? get() = attributes.str("icon")
    /** HA device class (temperature, humidity, motion, …); drives the default icon when none is set. */
    val deviceClass: String? get() = attributes.str("device_class")
    /** Unit of measurement for a sensor (°C, %, W, …), if any. */
    val unitOfMeasurement: String? get() = attributes.str("unit_of_measurement")

    /** Read an arbitrary attribute as a string (used by entity-bound pills), or null. */
    fun attributeString(name: String): String? = attributes.str(name)

    // Lowercase keys computed once (lazily) and cached, so search filtering and sorting
    // don't re-lowercase thousands of strings on every update/keystroke.
    val nameLower: String by lazy(LazyThreadSafetyMode.PUBLICATION) { friendlyName.lowercase() }
    val idLower: String by lazy(LazyThreadSafetyMode.PUBLICATION) { entityId.lowercase() }

    // --- Light capabilities/values ---
    val supportedColorModes: List<String> get() = attributes.strList("supported_color_modes")
    val brightness255: Int? get() = attributes.int("brightness")
    val brightnessPct: Int?
        get() = brightness255?.let { ((it * 100f / 255f).roundToInt()).coerceIn(0, 100) }
    val supportsBrightness: Boolean
        get() = supportedColorModes.any { it != "onoff" } || brightness255 != null
    val supportsColorTemp: Boolean get() = "color_temp" in supportedColorModes
    val supportsColor: Boolean get() = supportedColorModes.any { it in COLOR_MODES }
    /** Current color as (hue 0–360, saturation 0–100), if the light reports one. */
    val hsColor: Pair<Double, Double>?
        get() {
            val arr = attributes["hs_color"] as? JsonArray ?: return null
            val h = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            val s = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            return if (h != null && s != null) h to s else null
        }
    val colorTempKelvin: Int? get() = attributes.int("color_temp_kelvin")
    val minColorTempKelvin: Int get() = attributes.int("min_color_temp_kelvin") ?: 2000
    val maxColorTempKelvin: Int get() = attributes.int("max_color_temp_kelvin") ?: 6500

    // --- Climate capabilities/values (a climate entity's state is its hvac mode) ---
    val currentTemperature: Double? get() = attributes.dbl("current_temperature")
    val targetTemperature: Double? get() = attributes.dbl("temperature")
    val hvacModes: List<String> get() = attributes.strList("hvac_modes")
    val fanModes: List<String> get() = attributes.strList("fan_modes")
    val fanMode: String? get() = attributes.str("fan_mode")

    // --- Fan entity (domain "fan") speed/preset ---
    val percentage: Int? get() = attributes.int("percentage")
    val percentageStep: Double? get() = attributes.dbl("percentage_step")
    val presetModes: List<String> get() = attributes.strList("preset_modes")
    val presetMode: String? get() = attributes.str("preset_mode")
    /** Number of discrete speeds a fan exposes (from speed_count or percentage_step). */
    val fanSpeedCount: Int
        get() = attributes.int("speed_count")
            ?: percentageStep?.let { if (it > 0) (100.0 / it).roundToInt() else null }
            ?: 0
    val minTemp: Double get() = attributes.dbl("min_temp") ?: 7.0
    val maxTemp: Double get() = attributes.dbl("max_temp") ?: 35.0
    val targetTempStep: Double get() = attributes.dbl("target_temp_step") ?: 0.5

    companion object {
        val EMPTY_ATTRS = JsonObject(emptyMap())

        val TOGGLEABLE_DOMAINS = setOf(
            "light", "switch", "fan", "input_boolean", "automation",
            "script", "scene", "media_player", "cover", "lock",
        )

        // Color-capable light modes (anything beyond on/off + plain color temperature).
        private val COLOR_MODES = setOf("hs", "rgb", "rgbw", "rgbww", "xy", "xyz")

        /** Build an [Entity] from a HA state JSON object (from get_states or state_changed). */
        fun fromStateJson(obj: JsonObject): Entity? {
            val entityId = obj["entity_id"]?.jsonPrimitive?.contentOrNull ?: return null
            val state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val attrs = obj["attributes"]?.jsonObject ?: EMPTY_ATTRS
            val friendly = attrs["friendly_name"]?.jsonPrimitive?.contentOrNull
                ?: entityId.substringAfter('.', entityId)
            val lastChanged = obj["last_changed"]?.jsonPrimitive?.contentOrNull
            return Entity(
                entityId = entityId, state = state, friendlyName = friendly,
                attributes = attrs, lastChanged = lastChanged,
            )
        }
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.dbl(key: String): Double? = this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
private fun JsonObject.strList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

/** High-level connection status surfaced to the UI. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Authenticating : ConnectionState
    data object Connected : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}
