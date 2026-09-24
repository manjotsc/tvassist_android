package com.tvassist.data.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
    /** ISO-8601 timestamp of the last update, which HA bumps even when the state is unchanged. */
    val lastUpdated: String? = null,
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

    /**
     * A Home Assistant conversation agent (Assist). It has no on/off — its state is just the last
     * time it was used — so it is neither toggleable nor a button: pressing its tile opens the card
     * where you actually talk to it.
     */
    val isConversation: Boolean get() = domain == "conversation"

    // --- App-defined local cameras (not HA-backed): stream/snapshot URLs live in attributes. ---
    val localStreamUrl: String? get() = attributes.str("ta_stream_url")
    val localSnapshotUrl: String? get() = attributes.str("ta_snapshot_url")
    /** A local camera's smaller stream, for when the hardware decoder is taken; see LocalCamera.lowResUrl. */
    val localLowResUrl: String? get() = attributes.str("ta_low_res_url")?.takeIf { it.isNotBlank() }
    val localPlayer: String get() = attributes.str("ta_player")?.takeIf { it.isNotBlank() } ?: "auto"
    /** Reload the clip when it ends: "rolling clip" cameras that serve a short looped video, not a stream. */
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

    // --- Climate / fan capability bits (values verified against HA's *_entity_feature.ts) ---
    /** climate TARGET_TEMPERATURE|TARGET_TEMPERATURE_RANGE, or water_heater TARGET_TEMPERATURE. */
    val supportsTargetTemperature: Boolean
        get() = when (domain) {
            "climate" -> supportedFeatures and (CLIMATE_TARGET_TEMP or CLIMATE_TARGET_TEMP_RANGE) != 0
            "water_heater" -> supportedFeatures and CLIMATE_TARGET_TEMP != 0
            else -> false
        }
    val supportsClimateFanMode: Boolean
        get() = domain == "climate" && supportedFeatures and CLIMATE_FAN_MODE != 0
    val supportsClimatePresetMode: Boolean
        get() = domain == "climate" && supportedFeatures and CLIMATE_PRESET_MODE != 0
    val supportsClimateSwingMode: Boolean
        get() = domain == "climate" && supportedFeatures and CLIMATE_SWING_MODE != 0
    val supportsClimateSwingHorizontalMode: Boolean
        get() = domain == "climate" && supportedFeatures and CLIMATE_SWING_H_MODE != 0
    val swingModes: List<String> get() = attributes.strList("swing_modes")
    val swingHorizontalModes: List<String> get() = attributes.strList("swing_horizontal_modes")
    val swingHorizontalMode: String? get() = attributes.str("swing_horizontal_mode")
    val swingMode: String? get() = attributes.str("swing_mode")

    /** HumidifierEntityFeature.MODES (1) — the enum's only member. */
    val supportsHumidifierModes: Boolean
        get() = domain == "humidifier" && supportedFeatures and HUMIDIFIER_MODES != 0
    val humidifierModes: List<String> get() = attributes.strList("available_modes")
    val humidifierMode: String? get() = attributes.str("mode")

    /**
     * Water heater operation modes. **No bitmask** — upstream's
     * `supportsWaterHeaterOperationModesCardFeature` checks only the domain, so a water heater with
     * an `operation_list` offers them whatever its `supported_features` says.
     */
    val operationList: List<String> get() = attributes.strList("operation_list")
    val operationMode: String? get() = attributes.str("operation_mode")

    /**
     * A `select` / `input_select`'s options, and the one chosen.
     *
     * Upstream reads the current value from `attributes.option`, the same attribute name it uses
     * for the service payload. A select entity's *state* is its option, so this prefers that and
     * keeps the attribute as a fallback — otherwise nothing is highlighted on an entity that does
     * not happen to publish the attribute.
     */
    val selectOptions: List<String> get() = attributes.strList("options")
    val selectedOption: String? get() = state.takeIf { it.isNotBlank() } ?: attributes.str("option")

    /** LightEntityFeature.EFFECT (4); HA also requires a non-empty effect_list. */
    val supportsLightEffect: Boolean
        get() = domain == "light" && supportedFeatures and LIGHT_EFFECT != 0
    val effectList: List<String> get() = attributes.strList("effect_list")
    val effect: String? get() = attributes.str("effect")
    val supportsFanSetSpeed: Boolean
        get() = domain == "fan" && supportedFeatures and FAN_SET_SPEED != 0
    val supportsFanPresetMode: Boolean
        get() = domain == "fan" && supportedFeatures and FAN_PRESET_MODE != 0

    /**
     * Discrete speed steps, HA's `computeFanSpeeds`: `round(100 / percentage_step) + 1` (the +1 is
     * off), and buttons only up to six — beyond that upstream falls back to a slider. Distinct from
     * the older [fanSpeedCount], which reads `speed_count` and allowed up to twelve buttons.
     */
    val fanSpeedSteps: Int
        get() = percentageStep?.takeIf { it > 0 }?.let { (100.0 / it).roundToInt() + 1 } ?: 0
    val fanUsesSpeedButtons: Boolean get() = fanSpeedSteps in 2..FAN_SPEED_MAX_BUTTONS

    // --- Cover capabilities/values (states are open/closed/opening/closing, never "on") ---
    /**
     * Open-ness for a cover. [isOn] is useless here: a cover never reports the literal state "on",
     * so anything routed through it treated every cover as closed — which is how the app ended up
     * only ever able to open one.
     */
    val isOpen: Boolean get() = !state.equals("closed", true) && !state.equals("closing", true)
    val isCoverMoving: Boolean
        get() = state.equals("opening", true) || state.equals("closing", true)
    /** 0 = shut, 100 = fully open; null when the cover reports no position. */
    val coverPosition: Int? get() = attributes.int("current_position")
    val coverTiltPosition: Int? get() = attributes.int("current_tilt_position")

    /** HA's CoverEntityFeature bitmask; absent means "assume the basics". */
    private val supportedFeatures: Int get() = attributes.int("supported_features") ?: 0
    /**
     * **Deliberately looser than Home Assistant**, which tests the OPEN/CLOSE bits strictly: a
     * cover reporting no `supported_features` at all is assumed to do the basics, so an older
     * integration that omits the attribute still gets controls rather than an empty card.
     */
    val supportsOpenClose: Boolean
        get() = supportedFeatures == 0 || supportedFeatures and (COVER_OPEN or COVER_CLOSE) != 0
    val supportsCoverStop: Boolean get() = supportedFeatures and COVER_STOP != 0
    val supportsCoverPosition: Boolean get() = supportedFeatures and COVER_SET_POSITION != 0
    val supportsCoverTilt: Boolean get() = supportedFeatures and (COVER_OPEN_TILT or COVER_CLOSE_TILT) != 0
    val supportsCoverTiltPosition: Boolean get() = supportedFeatures and COVER_SET_TILT_POSITION != 0

    // --- Fan direction / oscillation ---
    val supportsFanDirection: Boolean
        get() = domain == "fan" && supportedFeatures and FAN_DIRECTION != 0
    val supportsFanOscillate: Boolean
        get() = domain == "fan" && supportedFeatures and FAN_OSCILLATE != 0
    /** HA reports "forward" or "reverse". */
    val fanDirection: String? get() = attributes.str("direction")
    val fanOscillating: Boolean get() = attributes["oscillating"]?.toString() == "true"

    // --- Lock ---
    /** LockEntityFeature.OPEN (1) — the only member of that enum: a latch the lock can release. */
    val supportsLockOpen: Boolean
        get() = domain == "lock" && supportedFeatures and LOCK_OPEN != 0

    // --- Alarm control panel ---
    /**
     * The arm modes this panel offers, in HA's own order, each as the suffix shared by its state
     * (`armed_away`) and its service (`alarm_arm_away`). From AlarmControlPanelEntityFeature.
     */
    val alarmArmModes: List<String>
        get() = if (domain != "alarm_control_panel") {
            emptyList()
        } else {
            ALARM_ARM_MODES.filter { (_, bit) -> supportedFeatures and bit != 0 }.map { it.first }
        }
    /** "number", "text", or null when the panel takes no code at all. */
    val alarmCodeFormat: String? get() = attributes.str("code_format")
    /** Whether arming needs the code too, not only disarming. HA's own default when absent is true. */
    val alarmCodeArmRequired: Boolean get() = attributes.bool("code_arm_required") ?: true
    /** Who last armed or disarmed it, where the integration reports that. */
    val alarmChangedBy: String? get() = attributes.str("changed_by")?.takeIf { it.isNotBlank() }

    // --- Update (firmware, add-ons, HA itself; state `on` means an update is available) ---
    val updateInstalledVersion: String? get() = attributes.str("installed_version")
    val updateLatestVersion: String? get() = attributes.str("latest_version")
    /** The software's own name ("Philips Hue Firmware"), where it differs from the entity's. */
    val updateTitle: String? get() = attributes.str("title")?.takeIf { it.isNotBlank() }
    val updateReleaseSummary: String? get() = attributes.str("release_summary")?.takeIf { it.isNotBlank() }
    /**
     * Whether an install is running. `in_progress` was a percentage *or* a boolean until HA
     * 2024.11 moved the number into `update_percentage`; older integrations still send the number.
     */
    val updateInProgress: Boolean
        get() = attributes.bool("in_progress") ?: (attributes.dbl("in_progress") != null)
    /**
     * Install progress 0–100, which may be fractional (`42.7`); null when the integration reports
     * only that it is busy, not how far.
     */
    val updatePercentage: Double?
        get() = attributes.dbl("update_percentage") ?: attributes.dbl("in_progress")
    /** UpdateEntityFeature.INSTALL — without it the entity only reports, and Install would fail. */
    val supportsUpdateInstall: Boolean
        get() = domain == "update" && supportedFeatures and UPDATE_INSTALL != 0
    /** UpdateEntityFeature.BACKUP — `update.install` accepts `backup: true`. */
    val supportsUpdateBackup: Boolean
        get() = domain == "update" && supportedFeatures and UPDATE_BACKUP != 0

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
    /**
     * The pollutant readings an `air_quality` entity carries, in HA's own declaration order, with
     * absent ones dropped. Returns attribute *keys* rather than labels — the wire format belongs
     * here, the words a person reads belong to the card.
     *
     * The entity's own `state` duplicates `particulate_matter_2_5`, which is why a bare air quality
     * tile reads as an unexplained number.
     */
    val airQualityReadings: List<Pair<String, Double>>
        get() = AIR_QUALITY_KEYS.mapNotNull { key -> attributes.dbl(key)?.let { key to it } }

    // --- Media player (domain "media_player") ---
    //
    // What is playing, how loud, and from where. Every one of these is absent on some player: a
    // speaker has no series title, a receiver has no artwork, a TV reports a source and no volume.
    // Null means "not reported", never "empty" — the card draws a row only when its own feature bit
    // *and* its data are present.
    val mediaTitle: String? get() = attributes.str("media_title")
    val mediaArtist: String? get() = attributes.str("media_artist")
    val mediaAlbumName: String? get() = attributes.str("media_album_name")
    val mediaSeriesTitle: String? get() = attributes.str("media_series_title")
    /** What the player is running — "Spotify", "Netflix". Often the only label a TV gives. */
    val appName: String? get() = attributes.str("app_name")

    /** 0.0-1.0, HA's own scale. The card shows percent; the service takes this. */
    val volumeLevel: Double? get() = attributes.dbl("volume_level")
    val isVolumeMuted: Boolean get() = attributes.bool("is_volume_muted") == true

    val source: String? get() = attributes.str("source")
    val sourceList: List<String> get() = attributes.strList("source_list")
    val soundMode: String? get() = attributes.str("sound_mode")
    val soundModeList: List<String> get() = attributes.strList("sound_mode_list")

    val shuffle: Boolean get() = attributes.bool("shuffle") == true
    /** HA's RepeatMode: "off", "all" or "one". */
    val repeatMode: String? get() = attributes.str("repeat")

    /** Track length in seconds, and the position at the moment HA last said so. */
    val mediaDuration: Double? get() = attributes.dbl("media_duration")
    val mediaPosition: Double? get() = attributes.dbl("media_position")
    /**
     * When [mediaPosition] was taken, as an ISO-8601 instant.
     *
     * A playing track's real position is this plus the wall time since — HA does not stream the
     * position, it publishes a fixed point and expects the client to do the arithmetic. Ignoring
     * this is how a progress bar ends up frozen at the moment the track started.
     */
    val mediaPositionUpdatedAt: String? get() = attributes.str("media_position_updated_at")

    val isPlaying: Boolean get() = state.equals("playing", ignoreCase = true)
    val isPaused: Boolean get() = state.equals("paused", ignoreCase = true)

    // HA MediaPlayerEntityFeature bits, each gating exactly one control.
    val supportsMediaPause: Boolean get() = mediaFeature(MEDIA_PAUSE)
    val supportsMediaPlay: Boolean get() = mediaFeature(MEDIA_PLAY)
    val supportsMediaStop: Boolean get() = mediaFeature(MEDIA_STOP)
    val supportsMediaSeek: Boolean get() = mediaFeature(MEDIA_SEEK)
    val supportsMediaNext: Boolean get() = mediaFeature(MEDIA_NEXT_TRACK)
    val supportsMediaPrevious: Boolean get() = mediaFeature(MEDIA_PREVIOUS_TRACK)
    val supportsVolumeSet: Boolean get() = mediaFeature(MEDIA_VOLUME_SET)
    val supportsVolumeMute: Boolean get() = mediaFeature(MEDIA_VOLUME_MUTE)
    val supportsVolumeStep: Boolean get() = mediaFeature(MEDIA_VOLUME_STEP)
    val supportsSelectSource: Boolean get() = mediaFeature(MEDIA_SELECT_SOURCE)
    val supportsSelectSoundMode: Boolean get() = mediaFeature(MEDIA_SELECT_SOUND_MODE)
    val supportsShuffleSet: Boolean get() = mediaFeature(MEDIA_SHUFFLE_SET)
    val supportsRepeatSet: Boolean get() = mediaFeature(MEDIA_REPEAT_SET)
    val supportsMediaTurnOn: Boolean get() = mediaFeature(MEDIA_TURN_ON)
    val supportsMediaTurnOff: Boolean get() = mediaFeature(MEDIA_TURN_OFF)

    /** Play and pause are separate bits upstream; one transport button needs either. */
    val supportsPlayPause: Boolean get() = supportsMediaPlay || supportsMediaPause

    private fun mediaFeature(bit: Int) = domain == "media_player" && supportedFeatures and bit != 0

    val minTemp: Double get() = attributes.dbl("min_temp") ?: 7.0
    val maxTemp: Double get() = attributes.dbl("max_temp") ?: 35.0
    val targetTempStep: Double get() = attributes.dbl("target_temp_step") ?: 0.5

    companion object {
        val EMPTY_ATTRS = JsonObject(emptyMap())

        // HA CoverEntityFeature bits.
        private const val COVER_OPEN = 1
        private const val COVER_CLOSE = 2
        private const val COVER_SET_POSITION = 4
        private const val COVER_STOP = 8
        private const val COVER_OPEN_TILT = 16
        private const val COVER_CLOSE_TILT = 32
        private const val COVER_SET_TILT_POSITION = 128

        // HA ClimateEntityFeature / FanEntityFeature bits.
        private const val CLIMATE_TARGET_TEMP = 1
        private const val CLIMATE_TARGET_TEMP_RANGE = 2
        private const val CLIMATE_FAN_MODE = 8
        private const val FAN_SET_SPEED = 1
        private const val FAN_PRESET_MODE = 8
        private const val CLIMATE_PRESET_MODE = 16
        private const val CLIMATE_SWING_MODE = 32
        private const val CLIMATE_SWING_H_MODE = 512
        private const val LIGHT_EFFECT = 4
        private const val HUMIDIFIER_MODES = 1
        private const val FAN_OSCILLATE = 2
        private const val FAN_DIRECTION = 4
        private const val LOCK_OPEN = 1
        // HA UpdateEntityFeature bits (SPECIFIC_VERSION 2, PROGRESS 4, RELEASE_NOTES 16 unused).
        private const val UPDATE_INSTALL = 1
        private const val UPDATE_BACKUP = 8
        // HA AlarmControlPanelEntityFeature bits, in the order upstream's more-info lists the modes.
        // TRIGGER (8) is deliberately not here: see AlarmCard.
        private val ALARM_ARM_MODES = listOf(
            "home" to 1, "away" to 2, "night" to 4, "vacation" to 32, "custom_bypass" to 16,
        )

        // HA MediaPlayerEntityFeature bits.
        private const val MEDIA_PAUSE = 1
        private const val MEDIA_SEEK = 2
        private const val MEDIA_VOLUME_SET = 4
        private const val MEDIA_VOLUME_MUTE = 8
        private const val MEDIA_PREVIOUS_TRACK = 16
        private const val MEDIA_NEXT_TRACK = 32
        private const val MEDIA_TURN_ON = 128
        private const val MEDIA_TURN_OFF = 256
        private const val MEDIA_VOLUME_STEP = 1024
        private const val MEDIA_SELECT_SOURCE = 2048
        private const val MEDIA_STOP = 4096
        private const val MEDIA_PLAY = 16384
        private const val MEDIA_SHUFFLE_SET = 32768
        private const val MEDIA_SELECT_SOUND_MODE = 65536
        private const val MEDIA_REPEAT_SET = 262144

        /** Upstream's FAN_SPEED_COUNT_MAX_FOR_BUTTONS. */
        private const val FAN_SPEED_MAX_BUTTONS = 6

        val TOGGLEABLE_DOMAINS = setOf(
            "light", "switch", "fan", "input_boolean", "automation",
            "script", "scene", "media_player", "cover", "lock",
        )

        /**
         * Every pollutant an `air_quality` entity can report, in HA's own order (AirQualityEntity,
         * dev 2026-09-18). `air_quality_index` is deliberately last and is the one without a unit —
         * an index is not a concentration.
         */
        private val AIR_QUALITY_KEYS = listOf(
            "particulate_matter_0_1",
            "particulate_matter_2_5",
            "particulate_matter_10",
            "ozone",
            "carbon_monoxide",
            "carbon_dioxide",
            "sulphur_dioxide",
            "nitrogen_oxide",
            "nitrogen_monoxide",
            "nitrogen_dioxide",
            "volatile_organic_compounds",
            "air_quality_index",
        )

        /** The reading an `air_quality` entity publishes as its state; the card's headline. */
        const val AIR_QUALITY_HEADLINE = "particulate_matter_2_5"

        /** [AIR_QUALITY_HEADLINE] aside, this is the one reading that carries no unit. */
        const val AIR_QUALITY_INDEX = "air_quality_index"

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
            val lastUpdated = obj["last_updated"]?.jsonPrimitive?.contentOrNull
            return Entity(
                entityId = entityId, state = state, friendlyName = friendly,
                attributes = attrs, lastChanged = lastChanged, lastUpdated = lastUpdated,
            )
        }
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.dbl(key: String): Double? = this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
/** HA sends booleans as JSON true/false; null when the key is absent, never false-by-default. */
private fun JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
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
