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

    // Conversation agents only: a card you type into and a card that opens listening are two
    // genuinely different actions, and which one a press should do is a matter of taste.
    const val ASSIST_TALK = "assist_talk"
    const val ASSIST_TYPE = "assist_type"

    val ALL = listOf(DEFAULT, TOGGLE, MORE, TURN_ON, TURN_OFF, RUN, NONE)

    /**
     * The actions worth offering for [entityId]'s domain. A conversation agent cannot be toggled or
     * turned on, so those chips are noise there; everything else keeps the full list.
     */
    fun forEntityId(entityId: String): List<String> =
        if (entityId.startsWith("conversation.")) {
            listOf(DEFAULT, ASSIST_TALK, ASSIST_TYPE, NONE)
        } else {
            ALL
        }

    fun label(action: String): String = when (action) {
        DEFAULT -> "Default"
        TOGGLE -> "Toggle"
        MORE -> "Controls"
        TURN_ON -> "Turn on"
        TURN_OFF -> "Turn off"
        RUN -> "Run"
        NONE -> "Nothing"
        ASSIST_TALK -> "Talk (mic)"
        ASSIST_TYPE -> "Type"
        else -> action
    }
}
