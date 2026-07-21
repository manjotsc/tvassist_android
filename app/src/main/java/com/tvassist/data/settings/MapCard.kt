package com.tvassist.data.settings

import kotlinx.serialization.Serializable

/**
 * A map card defined in the app: a saved multi-entity location map. It surfaces as a synthetic
 * entity ("map.ta_<id>") in the entity pool, so it can be placed in the overlay like any other
 * entity. Opening it shows a fullscreen map centered on `zone.home` plotting every [members]
 * person/device_tracker. Mirrors how [LocalCamera] becomes a synthetic camera entity.
 */
@Serializable
data class MapCard(
    /** Stable slug, used to build the synthetic entity id "map.ta_<id>". */
    val id: String,
    val name: String,
    val members: List<MapCardMember> = emptyList(),
    /** auto / osm / google (see [OverlayTile.MAP_AUTO] etc.). */
    val mapProvider: String = OverlayTile.MAP_AUTO,
    /** Fixed zoom the card centers on `zone.home` at (the D-pad can still nudge it live). */
    val mapZoom: Int = DEFAULT_ZOOM,
    /** Whether the fullscreen map shows the side legend listing each member. */
    val showLegend: Boolean = true,
) {
    companion object {
        const val DEFAULT_ZOOM = 14
    }
}

/** One person/device_tracker on a [MapCard], with the legend details it shows (P_* option keys). */
@Serializable
data class MapCardMember(
    val entityId: String,
    val options: List<String> = OverlayTile.PERSON_DEFAULTS,
)
