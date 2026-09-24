package com.tvassist.ui.cards.cover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.tvassist.data.ha.Entity
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.cards.AdjustableSliderRow
import com.tvassist.ui.cards.ModeIconButton
import com.tvassist.ui.cards.thenKeepInRow
import kotlin.math.roundToInt
import com.tvassist.ui.cap
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.cards.EntityCard
import com.tvassist.ui.cards.EntityControlActions
import kotlinx.serialization.json.JsonPrimitive

/**
 * Blinds, shutters, curtains, garage doors and gates.
 *
 * A cover is the one common domain whose state is not on/off — HA reports open/closed/opening/
 * closing — so it must never be routed through [Entity.isOn], which is false for every cover and
 * silently made "toggle" mean "open" in both directions.
 *
 * It has no `Controls` of its own: its rows come from the feature registry, so the same three
 * serve the tile and the full card. That is the pattern to follow for new domains.
 */
internal object CoverCard : EntityCard {
    override val domains = setOf("cover")

    /**
     * Icon tap, Compact, Full — three rungs of how much of the cover the tile carries.
     *
     * - **Icon tap** is the button: a press opens or closes, and nothing is drawn but the glyph.
     * - **Compact** keeps the labelled row and puts open and close on the end of it, so the common
     *   pair costs no extra height. A sidebar scrolls vertically, so height is the scarce dimension
     *   and a rung that spends a whole line on two buttons is a poor trade.
     * - **Full** adds a second line carrying up / stop / down, which is the whole of what a cover
     *   can be told to do from a tile. Stop is the reason it exists — it is the one action you want
     *   mid-travel and the one Compact has no room for.
     *
     * Position and tilt stay on the card: a slider needs width the tile does not have, and a cover
     * that reports neither would leave Full identical to Compact plus a button.
     *
     * `Auto` is untouched and stays the labelled row, so no existing tile changes appearance.
     */
    override fun variants(e: Entity) = listOf(
        OverlayTile.STYLE_ICON,
        OverlayTile.STYLE_COMPACT,
        OverlayTile.STYLE_FULL,
    )

    /** Open and Close are the on/off control; a generic Toggle underneath would repeat them. */
    override fun ownsToggle(e: Entity): Boolean = true

    // "Open · 45%" only while partly open: a fully open or shut cover reads better without it.
    override fun status(e: Entity, compact: Boolean): String {
        val pos = e.coverPosition
        return if (pos != null && pos in 1..99) "${cap(e.state)} · $pos%" else cap(e.state)
    }

    /**
     * Up / Stop / Down as icons, plus a position slider where the cover reports one.
     *
     * **This is new.** Before the card registry existed, covers had no controls of their own and
     * fell through to the generic body — which offered "Turn on", driven by [Entity.isOn], and that
     * is false for every cover that ever existed. The result was a cover that could be opened and
     * never closed. Stop earns the middle, where a mis-aim lands on it rather than on the opposite
     * direction.
     *
     * Arrows rather than the words Open / Stop / Close: they need no translation, they match the
     * segmented rows the climate and fan cards already use, and they end a collision where the card
     * showed two buttons both reading "Close" that did opposite things — shut the blind, and
     * dismiss the card.
     */
    @Composable
    override fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) {
        // trapHorizontal: on the card nothing sits left or right of this row, so keeping Left and
        // Right inside it costs nothing. On a *tile* it would cost the neighbouring tiles — see
        // [DirectionRow].
        DirectionRow(e, actions, firstFocus, withStop = e.supportsCoverStop, trapHorizontal = true)
        if (e.supportsCoverPosition) {
            Spacer(Modifier.height(10.dp))
            AdjustableSliderRow(
                label = "Position",
                value = (e.coverPosition ?: 0).toDouble(),
                min = 0.0, max = 100.0, step = 5.0,
                valueLabel = { "${it.roundToInt()}%" },
                onCommit = { actions.setCoverPosition(e, it.roundToInt()) },
                resetKey = e.entityId,
            )
        }
        if (e.supportsCoverTiltPosition) {
            Spacer(Modifier.height(10.dp))
            AdjustableSliderRow(
                label = "Tilt",
                value = (e.coverTiltPosition ?: 0).toDouble(),
                min = 0.0, max = 100.0, step = 5.0,
                valueLabel = { "${it.roundToInt()}%" },
                onCommit = { actions.setCoverTiltPosition(e, it.roundToInt()) },
                resetKey = e.entityId,
            )
        }
    }

    /** Compact: the pair you actually press, on the header's own line. */
    @Composable
    override fun TileTrailing(e: Entity, actions: EntityControlActions, style: String) {
        if (style != OverlayTile.STYLE_COMPACT) return
        ModeIconButton(
            icon = Icons.Rounded.ArrowUpward,
            selected = e.state.equals("opening", ignoreCase = true),
            onClick = { actions.openCover(e) },
        )
        ModeIconButton(
            icon = Icons.Rounded.ArrowDownward,
            selected = e.state.equals("closing", ignoreCase = true),
            onClick = { actions.closeCover(e) },
        )
    }

    /** Full: a second line with the complete set, Stop included. */
    @Composable
    override fun TileControls(e: Entity, actions: EntityControlActions, style: String) {
        if (style != OverlayTile.STYLE_FULL) return
        DirectionRow(e, actions, null, withStop = e.supportsCoverStop, trapHorizontal = false)
    }
}

/**
 * Up / Stop / Down, shared by the full card and the Full tile rung.
 *
 * Built here rather than through `ModeIconRow`: these are momentary actions, not a mode being
 * chosen, so there is no label above them and nothing is permanently selected. The highlight
 * follows what the cover is *doing* instead — which is the only feedback a blind travelling between
 * two positions gives you at all.
 *
 * [trapHorizontal] is the one difference between the two callers, and it is not cosmetic. On the
 * card, holding Left and Right inside the row is right: nothing else is horizontally adjacent. On a
 * tile in a multi-column row it is the focus trap that made a Normal light row swallow the key and
 * strand the tiles beside it, so the tile passes false and lets the D-pad leave.
 */
@Composable
private fun DirectionRow(
    e: Entity,
    actions: EntityControlActions,
    firstFocus: FocusRequester?,
    withStop: Boolean,
    trapHorizontal: Boolean,
) {
    val th = LocalOverlayTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(th.segmentBg)
            .padding(6.dp),
        // Content-sized and centred, not weighted: a cover has two or three actions where a
        // thermostat has five, and stretching that few across the full width turns aimable
        // buttons into bars. The climate and fan rows size to content for the same reason.
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        ModeIconButton(
            icon = Icons.Rounded.ArrowUpward,
            selected = e.state.equals("opening", ignoreCase = true),
            onClick = { actions.openCover(e) },
            modifier = (firstFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                .then(Modifier.thenKeepInRow(trapHorizontal, isFirst = true, isLast = false)),
        )
        if (withStop) {
            ModeIconButton(
                icon = Icons.Rounded.Stop,
                selected = false,
                onClick = { actions.stopCover(e) },
                modifier = Modifier.thenKeepInRow(trapHorizontal, isFirst = false, isLast = false),
            )
        }
        ModeIconButton(
            icon = Icons.Rounded.ArrowDownward,
            selected = e.state.equals("closing", ignoreCase = true),
            onClick = { actions.closeCover(e) },
            modifier = Modifier.thenKeepInRow(trapHorizontal, isFirst = false, isLast = true),
        )
    }
}

// --- Service calls -------------------------------------------------------------------------

internal fun EntityControlActions.openCover(e: Entity) =
    repository.callService("cover", "open_cover", e.entityId)

internal fun EntityControlActions.closeCover(e: Entity) =
    repository.callService("cover", "close_cover", e.entityId)

/** Only offered where `supportsCoverStop`; a cover without it rejects the call. */
internal fun EntityControlActions.stopCover(e: Entity) =
    repository.callService("cover", "stop_cover", e.entityId)

/** 0 is shut and 100 is fully open, which is HA's convention and the inverse of some hardware. */
internal fun EntityControlActions.setCoverPosition(e: Entity, pct: Int) = repository.callService(
    "cover", "set_cover_position", e.entityId,
    mapOf("position" to JsonPrimitive(pct.coerceIn(0, 100))),
)

internal fun EntityControlActions.setCoverTiltPosition(e: Entity, pct: Int) = repository.callService(
    "cover", "set_cover_tilt_position", e.entityId,
    mapOf("tilt_position" to JsonPrimitive(pct.coerceIn(0, 100))),
)
