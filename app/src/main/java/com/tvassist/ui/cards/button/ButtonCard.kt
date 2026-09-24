package com.tvassist.ui.cards.button

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.tvassist.data.ha.Entity
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.AccentButton
import com.tvassist.ui.cards.EntityControlActions
import com.tvassist.ui.cards.EntityCard

/** A stateless button's card body: nothing to report, one thing to do. */
@Composable
internal fun ButtonControls(entity: Entity, actions: EntityControlActions, firstFocus: FocusRequester) {
    AccentButton(
        label = "Press",
        onClick = { actions.press(entity) },
        modifier = Modifier.focusRequester(firstFocus),
    )
}

/** Stateless buttons. Their state is the time they were last pressed, so they show no status. */
internal object ButtonCard : EntityCard {
    override val domains = setOf("button", "input_button")

    /** A button *is* an action. */
    override fun autoStyle(e: Entity) = OverlayTile.STYLE_ACTION

    override fun status(e: Entity, compact: Boolean) = ""

    @Composable
    override fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) =
        ButtonControls(e, actions, firstFocus)
}
