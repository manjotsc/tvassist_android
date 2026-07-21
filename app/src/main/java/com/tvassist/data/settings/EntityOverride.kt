package com.tvassist.data.settings

import kotlinx.serialization.Serializable

/**
 * Per-entity user customization, applied wherever the entity appears (Home + overlay).
 * Blank fields fall back to the Home Assistant defaults.
 */
@Serializable
data class EntityOverride(
    val entityId: String,
    /** Custom display name ("" = HA friendly_name). */
    val name: String = "",
    /** Custom icon key from the curated set ("" = domain default). */
    val icon: String = "",
    /** Action for a single press (see [PressAction]). */
    val singlePress: String = PressAction.DEFAULT,
    /** Action for a long press. */
    val longPress: String = PressAction.DEFAULT,
    /** How the tile's on/off highlight is decided (see [DisplayState]). */
    val displayState: String = DisplayState.AUTO,
    /** For [DisplayState.MIRROR]: the entity whose state drives this tile's on/off highlight. */
    val mirrorEntityId: String = "",
    /**
     * For [DisplayState.MIRROR]: if set, the tile is "on" when the mirror entity's numeric state is
     * >= this value (e.g. watts). If null, it follows the mirror entity's plain on/off state.
     */
    val mirrorThreshold: Double? = null,
)

/** How an entity's on/off highlight is determined (useful for stateless buttons / IR controls). */
object DisplayState {
    const val AUTO = "auto"      // follow Home Assistant's state
    const val ON = "on"          // always highlighted as on
    const val OFF = "off"        // always shown as off
    const val MIRROR = "mirror"  // follow another entity's state (optionally via a numeric threshold)

    val ALL = listOf(AUTO, ON, OFF, MIRROR)

    fun label(value: String): String = when (value) {
        AUTO -> "Auto (HA)"
        ON -> "Always on"
        OFF -> "Always off"
        MIRROR -> "Mirror entity"
        else -> value
    }
}

/** Assignable press actions for an entity tile/row. */
object PressAction {
    const val DEFAULT = "default"   // toggle-or-open on single, open card on long
    const val TOGGLE = "toggle"
    const val MORE = "more"         // open the control card
    const val TURN_ON = "turn_on"
    const val TURN_OFF = "turn_off"
    const val RUN = "run"           // activate (scene/script/turn_on)
    const val NONE = "none"

    val ALL = listOf(DEFAULT, TOGGLE, MORE, TURN_ON, TURN_OFF, RUN, NONE)

    fun label(action: String): String = when (action) {
        DEFAULT -> "Default"
        TOGGLE -> "Toggle"
        MORE -> "Controls"
        TURN_ON -> "Turn on"
        TURN_OFF -> "Turn off"
        RUN -> "Run"
        NONE -> "Nothing"
        else -> action
    }
}
