package com.tvassist.ui.cards.generic

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.ui.AccentButton
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.cap
import com.tvassist.ui.cards.EntityControlActions
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.cards.EntityCard

/**
 * The fallback card body: a state line, plus a turn on/off action for anything toggleable.
 *
 * Buttons and locks used to be `if` branches at the top of this function, in an order that was
 * load-bearing — a lock *is* toggleable, so falling through here would label it "Turn on" rather
 * than "Unlock". They are registered domains now, so the registry routes them and this function
 * never sees one.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun GenericControls(entity: Entity, actions: EntityControlActions, firstFocus: FocusRequester) {
    if (entity.isToggleable) {
        // No "State:" line here: the card header already shows the status, and on a 320dp column
        // that repetition was most of the card.
        AccentButton(
            label = if (entity.isOn) "Turn off" else "Turn on",
            onClick = { actions.toggle(entity) },
            modifier = Modifier.focusRequester(firstFocus),
        )
    } else {
        // Kept for everything that cannot be acted on — a sensor, a binary_sensor — where it is
        // the only thing the body has to say and the card would otherwise be a name and a Close
        // button. th.subText, not a hardcoded grey: a light palette made that pale-on-white.
        Text(
            "State: ${cap(entity.state)}",
            color = LocalOverlayTheme.current.subText,
            fontSize = 15.sp,
        )
    }
}

/** Domains whose whole purpose is to be fired once — the only ones an `Action` tile suits. */
private val RUNNABLE = setOf("script", "scene", "automation")

/** The fallback for any domain no other card claims: a state line and a turn on/off action. */
internal object GenericCard : EntityCard {
    override val domains = emptySet<String>()

    /**
     * "Run" only for the things that are run. Scripts, scenes and automations all land on this
     * card, and so do sensors — offering every one of them an `Action` tile was most of why the
     * style picker felt arbitrary.
     */
    override fun autoStyle(e: Entity): String =
        if (e.domain in RUNNABLE) OverlayTile.STYLE_ACTION else OverlayTile.STYLE_AUTO

    /** [GenericControls] draws Turn on/off for exactly the entities the footer would have. */
    override fun ownsToggle(e: Entity): Boolean = e.isToggleable

    @Composable
    override fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) =
        GenericControls(e, actions, firstFocus)
}
