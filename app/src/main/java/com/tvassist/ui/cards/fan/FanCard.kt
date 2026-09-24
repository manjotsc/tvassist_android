package com.tvassist.ui.cards.fan

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import com.tvassist.ui.cards.ModeIconButton
import com.tvassist.ui.cards.thenKeepInRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.tvassist.ui.cards.ModeTextRow
import com.tvassist.data.ha.Entity
import kotlin.math.roundToInt
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.hvacModeIcon
import com.tvassist.ui.cards.AdjustableSliderRow
import com.tvassist.ui.cards.EntityControlActions
import com.tvassist.ui.cards.FanSpeedGlyph
import com.tvassist.ui.cards.ModeContentButton
import kotlinx.serialization.json.JsonPrimitive
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.cards.EntityCard
import com.tvassist.ui.cards.pctStatus

// --- Service calls -------------------------------------------------------------------------

/** Set a fan entity's speed as a percentage (0-100). */
internal fun EntityControlActions.setFanPercentage(e: Entity, pct: Int) = repository.callService(
    "fan", "set_percentage", e.entityId,
    mapOf("percentage" to JsonPrimitive(pct.coerceIn(0, 100))),
)

/** Set a fan entity's preset mode (e.g. Sleep, Nature). */
internal fun EntityControlActions.setFanPreset(e: Entity, preset: String) = repository.callService(
    "fan", "set_preset_mode", e.entityId,
    mapOf("preset_mode" to JsonPrimitive(preset)),
)

/** The `fan` domain — discrete speeds or a percentage, plus preset modes. */
internal object FanCard : EntityCard {
    override val domains = setOf("fan")

    /**
     * Icon tap, and nothing else.
     *
     * The tile is a button: one press turns it on or off, hold opens the card. Nothing
     * about this domain
     * belongs on a sidebar tile beyond that, so there is no Compact or Full rung to offer — those
     * carry a domain's controls, and speed and preset are a row of buttons that will not fit one.
     *
     * `Auto` is untouched and stays the labelled row, so no existing tile changes appearance.
     */
    override fun variants(e: Entity) = listOf(
        OverlayTile.STYLE_ICON,
        OverlayTile.STYLE_COMPACT,
        OverlayTile.STYLE_FULL,
    )

    /** Compact: one step of speed either way, on the header's own line. */
    @Composable
    override fun TileTrailing(e: Entity, actions: EntityControlActions, style: String) {
        if (style != OverlayTile.STYLE_COMPACT) return
        // A fan with discrete speeds steps by one of them; one with a free percentage steps by
        // whatever it says its own step is. Either way the arithmetic stays in percent, which is
        // what `set_percentage` takes and what `fanSpeedCount` is a division of.
        val count = e.fanSpeedCount
        val step = if (count in 1..12) 100.0 / count else (e.percentageStep ?: 10.0)
        if (count !in 1..12 && e.percentage == null) return
        val now = (e.percentage ?: 0).toDouble()
        ModeIconButton(
            icon = Icons.Rounded.Remove,
            selected = false,
            onClick = { actions.setFanPercentage(e, (now - step).roundToInt().coerceIn(0, 100)) },
        )
        ModeIconButton(
            icon = Icons.Rounded.Add,
            selected = false,
            onClick = { actions.setFanPercentage(e, (now + step).roundToInt().coerceIn(0, 100)) },
        )
    }

    /** Full: the card's own body, so the two can never describe different fans. */
    @Composable
    override fun TileControls(e: Entity, actions: EntityControlActions, style: String) {
        if (style != OverlayTile.STYLE_FULL) return
        FanControls(e, actions, null, trapHorizontal = false)
    }

    /**
     * Only when [Controls] actually draws something. The speed row carries its own Off button and
     * the percentage slider reaches 0, but a fan that reports neither renders an empty body — and
     * suppressing the footer Toggle there would leave no way to turn it off at all.
     */
    override fun ownsToggle(e: Entity): Boolean = e.fanSpeedCount in 1..12 || e.percentage != null

    override fun status(e: Entity, compact: Boolean) = pctStatus(e.isOn, e.percentage, compact)

    /**
     * Discrete speeds where the fan has a small number of them, a percentage slider otherwise.
     *
     * The split is what a remote wants: with four speeds, four buttons are one press each, where a
     * slider is a held direction and a guess. Twelve is the cut-off — past that the buttons stop
     * being individually aimable and the slider is the better tool.
     */
    @Composable
    override fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) =
        FanControls(e, actions, firstFocus, trapHorizontal = true)
}

/**
 * Speed and preset — the card's body and the Full tile rung both.
 *
 * [trapHorizontal] is the only difference between the two callers: on the card Left and Right have
 * nowhere else to go, on a tile they belong to the tiles beside it. See `ModeIconRow`.
 */
@Composable
private fun FanControls(
    e: Entity,
    actions: EntityControlActions,
    firstFocus: FocusRequester?,
    trapHorizontal: Boolean,
) {
    val count = e.fanSpeedCount
    val curPct = e.percentage ?: 0
    val curLevel = if (count > 0) Math.round(curPct * count / 100.0).toInt() else 0
    // One child, not seven. Every parent this lands in spaces its own children out — the control
    // card by 9dp, a Full tile by 8dp — so a label, a spacer and a row emitted side by side had
    // those gaps inserted *between* them, turning 6dp of intended breathing room into 24dp, twice
    // over. Sections space themselves here instead, and each row owns its label.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (count in 1..12) {
            LabelledRow("Speed") {
                // Off is part of the same row: on a remote, reaching for a separate control to
                // stop the fan is a worse journey than one more button in the line you are on.
                ModeContentButton(
                    selected = !e.isOn,
                    onClick = { actions.turnOff(e) },
                    modifier = Modifier.padding(horizontal = 3.dp)
                        .thenKeepInRow(trapHorizontal, isFirst = true, isLast = false)
                        .then(firstFocus?.let { Modifier.focusRequester(it) } ?: Modifier),
                ) { c ->
                    Icon(hvacModeIcon("off"), contentDescription = "Off", tint = c, modifier = Modifier.size(22.dp))
                }
                for (lvl in 1..count) {
                    val pct = Math.round(lvl * 100.0 / count).toInt()
                    ModeContentButton(
                        selected = e.isOn && curLevel == lvl,
                        onClick = { actions.setFanPercentage(e, pct) },
                        modifier = Modifier.padding(horizontal = 3.dp)
                            .thenKeepInRow(trapHorizontal, isFirst = false, isLast = lvl == count),
                    ) { c -> FanSpeedGlyph(lvl, count, c) }
                }
            }
        } else if (e.percentage != null) {
            AdjustableSliderRow(
                label = "Speed", value = curPct.toDouble(), min = 0.0, max = 100.0,
                step = e.percentageStep ?: 10.0, valueLabel = { "${it.roundToInt()}%" },
                onCommit = { actions.setFanPercentage(e, it.roundToInt()) },
                resetKey = e.entityId, focusRequester = firstFocus,
            )
        }
        // The same row a thermostat's presets draw, so the two cannot drift apart. Capitalising
        // for display lives in there: HA reports these lowercase, and a row reading
        // "auto smart sleep on" looks like raw data rather than four things you can press.
        ModeTextRow(
            label = "Preset",
            items = e.presetModes,
            selected = e.presetMode,
            onSelect = { actions.setFanPreset(e, it) },
            trapHorizontal = trapHorizontal,
        )
    }
}

/**
 * A labelled segmented row — the shape `ModeIconRow` already has, for the two rows a fan builds by
 * hand because their buttons are speed glyphs and preset names rather than plain icons.
 *
 * A `Column`, so it is a single child of whatever it lands in. See the note in [FanControls].
 */
@Composable
private fun LabelledRow(label: String, content: @Composable RowScope.() -> Unit) {
    val th = LocalOverlayTheme.current
    Column {
        Text(label, color = th.subText, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(th.segmentBg)
                .horizontalScroll(rememberScrollState()).padding(6.dp),
            content = content,
        )
    }
}
