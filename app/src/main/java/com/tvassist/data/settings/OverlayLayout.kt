package com.tvassist.data.settings

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A user-defined overlay layout: an ordered list of [OverlayRow]s. Each row is either a
 * header (section label) or a set of entity [OverlayTile]s laid out in N columns.
 */
@Serializable
data class OverlayLayout(
    val rows: List<OverlayRow> = emptyList(),
    /**
     * Which vocabulary the tiles' `style` strings are written in — see [STYLE_VERSION].
     *
     * Defaults to the current version so a layout built in code is taken at its word. A *stored*
     * layout without the key predates it, and that is decided on the raw JSON by [decodeStored],
     * never by this default: kotlinx fills an absent key with the default, which would make every
     * 1.1.5 layout look current.
     */
    val styleVersion: Int = STYLE_VERSION,
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    private fun fromLegacyStyles(): OverlayLayout = copy(
        rows = rows.map { row ->
            row.copy(tiles = row.tiles.map { it.copy(style = legacyStyle(it.entityId, it.style)) })
        },
        styleVersion = STYLE_VERSION,
    )

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
        /**
         * 1: `compact` and `full` are rungs of a card's control ladder.
         *
         * Before it (1.1.5 and earlier) they were both a plain row — `full` added a read-only
         * brightness bar on a light, `compact` did not — and the picker offered them to every
         * entity. Read in today's meaning, a 1.1.5 light saved as Full turns into a tall tile with
         * every slider inlined, which nobody chose.
         */
        const val STYLE_VERSION = 1
        private const val STYLE_VERSION_KEY = "styleVersion"

        /**
         * A layout as stored — in preferences or inside a backup — translated into today's styles.
         *
         * The translation is applied on every read until the layout is next written, rather than
         * written back once from a start-up hook. The sidebar must draw correctly after an upgrade
         * that never opens the app (`MY_PACKAGE_REPLACED` restarts the services, not the activity),
         * and a pure read cannot half-apply.
         */
        fun decodeStored(json: Json, element: JsonElement): OverlayLayout {
            val layout = json.decodeFromJsonElement(serializer(), element)
            val version = (element as? JsonObject)?.get(STYLE_VERSION_KEY)?.jsonPrimitive?.intOrNull ?: 0
            return if (version >= STYLE_VERSION) layout else layout.fromLegacyStyles()
        }

        /**
         * What a 1.1.5 `compact`/`full` tile looked like, in today's vocabulary: a plain row.
         *
         * Keyed on the domain in the entity id, because this runs before Home Assistant has sent
         * anything. Light and map cards have a real `Normal` rung (the row, with a light's level);
         * a thermostat has no plain row any more, so it takes Compact — its header with −/+,
         * the nearest thing. Everything else goes to Auto, which *is* the plain row for them.
         *
         * Only those two styles move. Every other value is kept exactly as saved, including ones
         * the entity's card does not offer today: the renderer already resolves those, and keeping
         * the stored value means a card that later gains that rung picks the user's choice back up.
         */
        internal fun legacyStyle(entityId: String, style: String): String {
            if (style != OverlayTile.STYLE_COMPACT && style != OverlayTile.STYLE_FULL) return style
            return when (entityId.substringBefore('.')) {
                "light", "map" -> OverlayTile.STYLE_STANDARD
                "climate" -> OverlayTile.STYLE_COMPACT
                else -> OverlayTile.STYLE_AUTO
            }
        }

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
    /** Tiles per line for an entity row; clamped to 1..MAX_COLUMNS (12) by the editor and renderer. */
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
        /**
         * A tile's style is **how much of the entity's control surface the tile itself carries** —
         * not how much text it shows. Standard is a plain row; Compact adds the one control that
         * matters most; Full inlines the lot. A card offers only the rungs it can actually fill,
         * so a switch or a sensor shows Standard alone.
         */
        const val STYLE_AUTO = "auto"            // the domain's own recommendation
        // Labelled "Normal": the plain row, and what Auto renders as for a light.
        const val STYLE_STANDARD = "standard"    // icon + name + level, no controls
        const val STYLE_COMPACT = "compact"      // the domain's controls, condensed
        const val STYLE_FULL = "full"            // every control the domain has, in full
        const val STYLE_ICON = "icon"            // a square, icon only, press to toggle
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
        /**
         * The rungs of the control ladder — the styles that draw a card's [EntityCard.TileControls]
         * under the tile header, as opposed to a plain row or a domain's own look.
         *
         * A set rather than a list of `when` branches because enumerating them by hand is exactly
         * how `Standard` came to render as a plain row: it was the plain row when the dispatch was
         * written, gained brightness and warmth a day later, and the branch was never revisited.
         */
        val CONTROL_STYLES = setOf(STYLE_COMPACT, STYLE_FULL)

        val CYCLE = listOf(
            STYLE_AUTO, STYLE_ICON, STYLE_STANDARD, STYLE_COMPACT, STYLE_FULL,
            STYLE_SQUARE, STYLE_CLIMATE, STYLE_ACTION,
        )

        /**
         * A style this build actually knows, or [STYLE_AUTO].
         *
         * A saved layout can name a style that no longer exists — the ported HA tile card wrote
         * `"tile"` before it was parked in `experiments/`, and a layout written by a newer build
         * can do the same. Unknown fell through to the `else` branch of the render `when`, so a tile
         * silently drew as Full while its chip read a bare lowercase "tile". Auto is the honest
         * answer: let the domain decide, exactly as an unconfigured tile does.
         */
        fun known(style: String): String = if (style in CYCLE) style else STYLE_AUTO

        fun label(style: String): String = when (known(style)) {
            STYLE_AUTO -> "Auto"
            STYLE_ICON -> "Icon tap"
            STYLE_STANDARD -> "Normal"
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
