package com.tvassist.data.settings

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * A user-defined overlay layout: an ordered list of [OverlayRow]s. Each row is either a
 * header (section label) or a set of entity [OverlayTile]s laid out in N columns.
 */
@Serializable
data class OverlayLayout(val rows: List<OverlayRow> = emptyList()) {
    val isEmpty: Boolean get() = rows.isEmpty()

    /** Every entity id referenced by the layout, in order (deduped). */
    fun entityIds(): List<String> =
        rows.flatMap { r -> r.tiles.map { it.entityId } }.distinct()

    /** Copy with [entityIds] appended as tiles on row [rowIndex] (out-of-range index → unchanged). */
    fun withTilesAdded(rowIndex: Int, entityIds: List<String>): OverlayLayout =
        copy(rows = rows.mapIndexed { idx, r ->
            if (idx == rowIndex) r.copy(tiles = r.tiles + entityIds.map { OverlayTile(it) }) else r
        })

    /** Copy with [entityIds] appended as header pills on row [rowIndex], skipping ids already present
     *  on the row or repeated within [entityIds]. */
    fun withPillsAdded(rowIndex: Int, entityIds: List<String>): OverlayLayout =
        copy(rows = rows.mapIndexed { idx, r ->
            if (idx != rowIndex) r
            else {
                val seen = r.pills.mapTo(mutableSetOf()) { it.entityId }
                r.copy(pills = r.pills + entityIds.filter { seen.add(it) }.map { OverlayPill(it) })
            }
        })

    companion object {
        /** Seed a simple one-column layout from a flat ordered entity list. */
        fun fromFlat(ids: List<String>): OverlayLayout = OverlayLayout(
            rows = if (ids.isEmpty()) {
                emptyList()
            } else {
                listOf(OverlayRow(columns = 1, tiles = ids.map { OverlayTile(it) }))
            },
        )
    }
}

@Serializable
data class OverlayRow(
    /** Optional section header text ("" = none). */
    val title: String = "",
    /** Tiles per line for an entity row (1–3). */
    val columns: Int = 1,
    val type: String = TYPE_ENTITIES,
    val tiles: List<OverlayTile> = emptyList(),
    /** Header rows only: live pills (e.g. temperature/humidity) shown on the header. */
    val pills: List<OverlayPill> = emptyList(),
) {
    val isHeader: Boolean get() = type == TYPE_HEADER

    companion object {
        const val TYPE_ENTITIES = "entities"
        const val TYPE_HEADER = "header"
    }
}

@Serializable
data class OverlayTile(
    val entityId: String,
    /** How the tile renders; see the STYLE_* constants. */
    val style: String = STYLE_AUTO,
    /** Hide the entity name on this tile (icon + status only). */
    val hideName: Boolean = false,
    /** Hide the status/state line on this tile. */
    val hideStatus: Boolean = false,
    /** Hide the icon on this tile (text only). */
    val hideIcon: Boolean = false,
    /** For person/device_tracker tiles: which things the map popup shows (see P_* keys). */
    val personOptions: List<String> = PERSON_DEFAULTS,
    /** For person/device_tracker tiles: which map source to use (see MAP_* keys). */
    val mapProvider: String = MAP_AUTO,
) {
    companion object {
        const val STYLE_AUTO = "auto"        // pick by domain (light → slider, etc.)
        const val STYLE_COMPACT = "compact"  // small icon + state
        const val STYLE_FULL = "full"        // full-width tile
        const val STYLE_SQUARE = "square"    // square (camera/scene)
        const val STYLE_CLIMATE = "climate"  // inline climate controls
        const val STYLE_ACTION = "action"    // fire a scene/script/turn_on

        // Person-map options (what the fullscreen map popup shows / does).
        const val P_ZONE = "zone"        // colored dot + zone/state
        const val P_BATTERY = "battery"  // battery %, GPS accuracy
        const val P_SPEED = "speed"      // current speed
        const val P_DISTANCE = "distance" // distance from home zone
        const val P_UPDATED = "updated"  // live "updated Xs ago" ticker
        const val P_LIVE = "live"        // nudge HA every ~15s for a fresh fix
        const val P_TRAIL = "trail"      // fading breadcrumb trail of recent positions

        // Per-tile map source. AUTO follows the global setting (Google if a key is set, else OSM);
        // OSM / GOOGLE force that source (GOOGLE needs a key, else falls back to OSM).
        const val MAP_AUTO = "auto"
        const val MAP_OSM = "osm"
        const val MAP_GOOGLE = "google"
        val MAP_PROVIDERS = listOf(MAP_AUTO to "Auto", MAP_OSM to "OpenStreetMap", MAP_GOOGLE to "Google")

        /** Default person-map options applied to a new person tile. */
        val PERSON_DEFAULTS = listOf(P_ZONE, P_BATTERY, P_SPEED, P_UPDATED, P_LIVE)

        /** All person-map options as (key, label), in editor order. */
        val PERSON_OPTIONS_ALL = listOf(
            P_ZONE to "Zone",
            P_BATTERY to "Battery/GPS",
            P_SPEED to "Speed",
            P_DISTANCE to "Distance",
            P_UPDATED to "Updated",
            P_LIVE to "Live refresh",
            P_TRAIL to "Trail",
        )

        /** Styles offered in the editor, in cycle order. */
        val CYCLE = listOf(STYLE_AUTO, STYLE_COMPACT, STYLE_FULL, STYLE_SQUARE, STYLE_CLIMATE, STYLE_ACTION)

        fun label(style: String): String = when (style) {
            STYLE_AUTO -> "Auto"
            STYLE_COMPACT -> "Compact"
            STYLE_FULL -> "Full"
            STYLE_SQUARE -> "Square"
            STYLE_CLIMATE -> "Climate card"
            STYLE_ACTION -> "Action"
            else -> style
        }
    }
}

@Serializable
private data class PillObj(
    val entityId: String,
    val showIcon: Boolean = true,
    val showName: Boolean = false,
    val showState: Boolean = true,
    val iconColor: Int = 0,
)

/**
 * A header pill: an entity plus which fields it shows. Has a tolerant serializer that also
 * reads the legacy form where a pill was just a bare entity-id string, so older saved layouts
 * keep working.
 */
@Serializable(with = OverlayPillSerializer::class)
data class OverlayPill(
    val entityId: String,
    val showIcon: Boolean = true,
    val showName: Boolean = false,
    val showState: Boolean = true,
    /** ARGB tint for the pill's icon; 0 = use the theme's default (subtext) color. */
    val iconColor: Int = 0,
)

object OverlayPillSerializer : KSerializer<OverlayPill> {
    private val delegate = PillObj.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): OverlayPill {
        val input = decoder as JsonDecoder
        return when (val el = input.decodeJsonElement()) {
            is JsonPrimitive -> OverlayPill(el.content) // legacy: bare entity id
            else -> input.json.decodeFromJsonElement(delegate, el).let {
                OverlayPill(it.entityId, it.showIcon, it.showName, it.showState, it.iconColor)
            }
        }
    }
    override fun serialize(encoder: Encoder, value: OverlayPill) {
        val output = encoder as JsonEncoder
        output.encodeJsonElement(
            output.json.encodeToJsonElement(
                delegate,
                PillObj(value.entityId, value.showIcon, value.showName, value.showState, value.iconColor),
            ),
        )
    }
}
