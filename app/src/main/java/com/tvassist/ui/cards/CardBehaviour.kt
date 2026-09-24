package com.tvassist.ui.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.tvassist.ui.hvacModeColor
import com.tvassist.ui.alarmStateColor
import com.tvassist.ui.lockStateColor
import androidx.compose.ui.graphics.vector.ImageVector
import android.util.Log
import com.tvassist.BuildConfig
import com.tvassist.data.ha.Entity
import com.tvassist.ui.cards.light.bulbTint
import com.tvassist.data.settings.EntityOverride
import com.tvassist.data.settings.PressAction
import com.tvassist.ui.domainIcon
import com.tvassist.ui.iconForKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Display name for an entity, honoring a custom override name. */
fun displayName(entity: Entity, override: EntityOverride?): String =
    override?.name?.takeIf { it.isNotBlank() } ?: entity.friendlyName

/**
 * Whether the tile should highlight as "on", honoring a custom display-state override.
 * For [DisplayState.MIRROR], [lookup] resolves the source entity by id (pass one from the current
 * entity list); with a numeric threshold set, "on" means the source's value is >= the threshold.
 */
fun effectiveOn(
    entity: Entity,
    override: EntityOverride?,
    lookup: ((String) -> Entity?)? = null,
): Boolean = when (override?.displayState) {
    com.tvassist.data.settings.DisplayState.ON -> true
    com.tvassist.data.settings.DisplayState.OFF -> false
    com.tvassist.data.settings.DisplayState.MIRROR -> {
        val src = override.mirrorEntityId.takeIf { it.isNotBlank() }?.let { lookup?.invoke(it) }
        val thr = override.mirrorThreshold
        when {
            src == null -> entity.isOn
            thr != null -> (src.state.toDoubleOrNull() ?: 0.0) >= thr
            else -> src.isOn
        }
    }
    // Each domain decides what "active" means for it — locked for a lock, open for a cover.
    else -> cardFor(entity).isActive(entity)
}

/**
 * The colour an entity's icon takes from *what it is doing*, or null when its domain has no such
 * colour and the theme accent should stand in.
 *
 * Home Assistant colours a thermostat by hvac mode — blue cooling, orange heating, amber drying —
 * and this is the third place that mapping was about to be written out by hand: the full control
 * card and the classic climate tile already had it, and the HA tile had been shipped without it,
 * which is why a thermostat looked identical in `dry` and in `off`.
 */
fun stateTint(e: Entity): Color? = when {
    e.domain == "climate" && e.state != "off" -> hvacModeColor(e.state)
    // A lamp on deep amber and one on daylight are otherwise the same grey dot, which is most of
    // what makes a row of lights unreadable at a glance. Null when off, so it dims like the rest.
    e.domain == "light" -> bulbTint(e)
    // A lock is the one domain where the colour carries the meaning rather than decorating it —
    // green bolted, red open — so it applies in every state, including the ones `stateActive`
    // calls inactive.
    e.domain == "lock" -> lockStateColor(e.state)
    // Same idea as a lock, in every state; see [alarmStateColor] for why disarmed is not red.
    e.domain == "alarm_control_panel" -> alarmStateColor(e.state)
    else -> null
}

/** Display icon for an entity, honoring a custom override icon. */
fun displayIcon(entity: Entity, override: EntityOverride?): ImageVector =
    override?.icon?.takeIf { it.isNotBlank() }?.let { iconForKey(it) } ?: domainIcon(entity)

/** How long a press is held back while waiting to see whether a second one follows. */
const val DOUBLE_TAP_MS = 300L

/**
 * Wraps a tile's press so two quick presses run [onDouble] instead of [onSingle] — Home Assistant's
 * `double_tap_action`.
 *
 * **Pass a null [onDouble] whenever the entity has no double-press action, which is the default.**
 * Recognising a second press means the first cannot fire until the window has passed, and 300 ms
 * of nothing happening after pressing a light is very obvious on a remote. With null this returns
 * [onSingle] itself and no press is ever delayed; upstream makes the same trade, arming its
 * `actionHandler`'s `hasDoubleClick` only when a double-tap action is configured.
 *
 * Every hook is remembered unconditionally rather than behind an early return, because the null-ness
 * of [onDouble] changes the moment the user edits the entity — an early return would change the
 * slot count of a live composition.
 *
 * The pending press rides on the caller's composition scope, so a tile that leaves the tree inside
 * the window (the sidebar auto-closing on the press) drops it. That is the same thing the press
 * closing the sidebar would have done anyway.
 */
@Composable
fun rememberPress(onSingle: () -> Unit, onDouble: (() -> Unit)?): () -> Unit {
    val scope = rememberCoroutineScope()
    val single by rememberUpdatedState(onSingle)
    val double by rememberUpdatedState(onDouble)
    val pending = remember { mutableStateOf<Job?>(null) }
    return remember {
        {
            val onTwo = double
            val waiting = pending.value
            when {
                onTwo == null -> single()
                waiting != null -> {
                    waiting.cancel()
                    pending.value = null
                    onTwo()
                }
                else -> pending.value = scope.launch {
                    delay(DOUBLE_TAP_MS)
                    pending.value = null
                    single()
                }
            }
        }
    }
}

/**
 * Runs the configured press [action] for [entity]. [openCard] opens the control card;
 * `single` selects the default behaviour for [PressAction.DEFAULT].
 */
fun performPress(
    action: String,
    entity: Entity,
    actions: EntityControlActions,
    openCard: (Entity) -> Unit,
    single: Boolean,
    /** Opens a conversation agent's card with the mic already live. Defaults to a plain open, so a
     *  caller with no voice route degrades to the typing card rather than doing nothing. */
    openVoice: (Entity) -> Unit = openCard,
) {
    // Debug builds only. One physical hold must produce exactly one line: single=false. Two lines
    // for one press — a single=true immediately followed by a single=false — is the tile firing its
    // click *and* its long-click, which is the bug this is here to prove or disprove.
    if (BuildConfig.DEBUG) {
        Log.d("CardPress", "press ${entity.entityId} action=$action single=$single")
    }
    when (action) {
        PressAction.DEFAULT ->
            if (single) {
                when {
                    // Asked before `isToggleable`, which a media player passes — see [pressOpens].
                    cardFor(entity).pressOpens(entity) -> openCard(entity)
                    entity.isButton -> actions.press(entity)
                    entity.isToggleable -> actions.toggle(entity)
                    else -> openCard(entity)
                }
            } else if (entity.isConversation) {
                // Hold on an agent goes straight to the mic: it is the fastest way to talk without
                // moving your hand, and the plain card is still one short press away.
                openVoice(entity)
            } else {
                openCard(entity)
            }
        PressAction.TOGGLE -> actions.toggle(entity)
        PressAction.MORE -> openCard(entity)
        PressAction.TURN_ON -> actions.turnOn(entity)
        PressAction.TURN_OFF -> actions.turnOff(entity)
        PressAction.RUN -> actions.run(entity)
        PressAction.ASSIST_TALK -> openVoice(entity)
        PressAction.ASSIST_TYPE -> openCard(entity)
        PressAction.NONE -> {}
    }
}

