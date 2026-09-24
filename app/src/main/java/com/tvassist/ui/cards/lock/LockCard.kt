package com.tvassist.ui.cards.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import com.tvassist.ui.cards.ModeTextRow
import com.tvassist.ui.lockStateColor
import com.tvassist.data.ha.Entity
import com.tvassist.ui.AccentButton
import com.tvassist.ui.cards.EntityControlActions
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.cards.EntityCard

/** The `lock` domain. Status is the plain capitalised state — Locked / Unlocked / Jammed. */
internal object LockCard : EntityCard {
    override val domains = setOf("lock")

    /**
     * Lit when **locked** — a deliberate departure from [com.tvassist.ui.cards.stateActive].
     *
     * Upstream reads "active" as "wants your attention", so it calls an *unlocked* door the active
     * one and a thrown bolt inactive. That is coherent for a dashboard full of mixed domains and
     * backwards for a sidebar tile, where lit reads as "this is on, this is done" — a dark tile for
     * a locked door says the opposite of what the door is doing.
     *
     * It also put the tile at odds with its own colour: [com.tvassist.ui.cards.stateTint] paints a
     * locked door green and an unlocked one red (HA's own `--state-lock-*` tokens, which we keep),
     * so the tile was drawing a green glyph on an unlit tile and a red one on a lit tile.
     *
     * `stateActive` itself is untouched and stays a faithful port; only this domain overrides it.
     */
    override fun isActive(e: Entity): Boolean = e.isLocked

    /**
     * Icon tap, and nothing else.
     *
     * The tile is a button: one press locks or unlocks it, hold opens the card. Nothing
     * about this domain
     * belongs on a sidebar tile beyond that, so there is no Compact or Full rung to offer — those
     * carry a domain's controls, and a lock has nothing to adjust, only a state to flip.
     *
     * `Auto` is untouched and stays the labelled row, so no existing tile changes appearance.
     */
    override fun variants(e: Entity) = listOf(OverlayTile.STYLE_ICON)

    /** Lock and Unlock are the control; see [EntityCard.ownsToggle]. */
    override fun ownsToggle(e: Entity): Boolean = true

    /**
     * Two explicit options rather than one toggle, with the current one filled.
     *
     * A door is the one control where pressing the wrong thing has consequences, and a single
     * button labelled by what it will *do* can be read a moment too late — the label changes under
     * you as the lock reports back. Lock is always lock.
     *
     * They were two accent buttons, which drew both as *the* primary action and so said nothing
     * about the state the door was actually in. As a segmented pair the current state is filled in
     * its own colour — green bolted, red open — the language every other card here uses. Mid-throw
     * and jammed select **neither**, which is the honest answer while a motor is running or a bolt
     * is stuck. What the two presses do is unchanged.
     */
    @Composable
    override fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) {
        // No "State:" line and no label over the row: the card header two rows up already spells
        // the state out, and on a 320dp column that repetition is most of the card.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeTextRow(
                label = "",
                items = listOf(LOCK, UNLOCK),
                selected = when {
                    e.isLocked -> LOCK
                    e.isLockTransitioning || e.state.equals("jammed", ignoreCase = true) -> null
                    else -> UNLOCK
                },
                onSelect = { if (it == LOCK) actions.lockDoor(e) else actions.unlockDoor(e) },
                activeColor = lockStateColor(e.state),
                firstFocus = firstFocus,
            )
            if (e.supportsLockOpen) {
                // Releasing the latch is a different act from unlocking — a door that swings open
                // by itself — so it stays a button of its own rather than a third segment.
                AccentButton(label = "Open door", onClick = { actions.openDoor(e) })
            }
        }
    }
}

/** HA's own service names, which [ModeTextRow] capitalises into the two labels. */
private const val LOCK = "lock"
private const val UNLOCK = "unlock"

// --- Service calls -------------------------------------------------------------------------

internal fun EntityControlActions.lockDoor(e: Entity) =
    repository.callService("lock", "lock", e.entityId)

internal fun EntityControlActions.unlockDoor(e: Entity) =
    repository.callService("lock", "unlock", e.entityId)

/** Release the latch. Distinct from unlocking, and gated on LockEntityFeature.OPEN. */
internal fun EntityControlActions.openDoor(e: Entity) =
    repository.callService("lock", "open", e.entityId)
