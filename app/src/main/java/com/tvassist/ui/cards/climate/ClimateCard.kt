package com.tvassist.ui.cards.climate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import com.tvassist.ui.cards.ModeIconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import com.tvassist.ui.cards.ModeTextRow
import com.tvassist.data.ha.Entity
import com.tvassist.ui.fmt
import com.tvassist.ui.hvacModeColor
import com.tvassist.ui.hvacModeIcon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.tvassist.ui.cards.ColorSliderRow
import com.tvassist.ui.cards.EntityControlActions
import com.tvassist.ui.cards.ModeIconRow
import kotlinx.serialization.json.JsonPrimitive
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.cap
import com.tvassist.ui.cards.EntityCard

// --- Service calls (shared with InlineTile.kt and FanModeRow.kt in this package) -------------

internal fun EntityControlActions.setClimateTemp(e: Entity, temp: Double) = repository.callService(
    "climate", "set_temperature", e.entityId,
    mapOf("temperature" to JsonPrimitive(temp)),
)

internal fun EntityControlActions.setHvacMode(e: Entity, mode: String) = repository.callService(
    "climate", "set_hvac_mode", e.entityId,
    mapOf("hvac_mode" to JsonPrimitive(mode)),
)

/** A thermostat's own fan speed — not the `fan` domain, which has its own card. */
internal fun EntityControlActions.setFanMode(e: Entity, mode: String) = repository.callService(
    "climate", "set_fan_mode", e.entityId,
    mapOf("fan_mode" to JsonPrimitive(mode)),
)

/** Eco, Away, Comfort, Boost — whatever the integration decided to publish. */
internal fun EntityControlActions.setClimatePreset(e: Entity, mode: String) = repository.callService(
    "climate", "set_preset_mode", e.entityId,
    mapOf("preset_mode" to JsonPrimitive(mode)),
)

/** Louvre position: HA's `swing_mode`, typically off/vertical/horizontal/both. */
internal fun EntityControlActions.setSwingMode(e: Entity, mode: String) = repository.callService(
    "climate", "set_swing_mode", e.entityId,
    mapOf("swing_mode" to JsonPrimitive(mode)),
)

/**
 * The second swing axis, added in HA 2024.12 as a service of its own rather than a value of
 * `swing_mode` — a thermostat can report both, and they are set independently.
 */
internal fun EntityControlActions.setSwingHorizontalMode(e: Entity, mode: String) =
    repository.callService(
        "climate", "set_swing_horizontal_mode", e.entityId,
        mapOf("swing_horizontal_mode" to JsonPrimitive(mode)),
    )

/**
 * Cold to hot, anchored at both ends by the same [hvacModeColor] tokens the Mode row below uses —
 * so the blue of the snowflake button and the blue end of this track are the one colour.
 *
 * A gradient rather than a fill, because a fill is the wrong shape for a temperature. The old row
 * drew `(target - min_temp) / (max_temp - min_temp)`, which is a perfectly correct **0%** for a
 * thermostat asked for 16° when HA reports `min_temp: 16` — an empty bar that reads as broken.
 * Brightness at 0% means off; 16° does not mean off, and a bar that can empty says it does.
 * This track is always full; the thumb is what moves.
 *
 * No white in the ramp: the thumb is white, and it disappears into a pale stop.
 */
private val TEMP_TRACK = Brush.horizontalGradient(
    listOf(
        Color(0xFF2B9AF9), // HA --state-climate-cool
        Color(0xFF00BCD4),
        Color(0xFFFFD54F),
        Color(0xFFFF8100), // HA --state-climate-heat
        Color(0xFFE53935),
    ),
)

/** The `climate` domain. Its tile default is the inline card, which is why it has a style of its own. */
internal object ClimateCard : EntityCard {
    override val domains = setOf("climate")
    /** Not on the control ladder yet; its Auto is the inline thermostat it has always been. */
    override fun autoStyle(e: Entity) = OverlayTile.STYLE_CLIMATE

    /**
     * Compact and Full. **No Icon tap** — `climate` is not a toggleable domain, so a press would
     * fall through to opening the card, and a square button whose only action is the one hold
     * already does is a button that lies about itself.
     *
     * - **Compact** is the target temperature and the two keys that change it, on the header's own
     *   line. It is the one thing anyone reaches for, and it costs no height at all.
     * - **Full** is the card's whole body inline: target slider, hvac modes, fan modes.
     *
     * `Auto` stays [OverlayTile.STYLE_CLIMATE] — the mode and fan rows without a temperature
     * control — so no thermostat tile anybody already has changes appearance.
     */
    override fun variants(e: Entity) = listOf(OverlayTile.STYLE_COMPACT, OverlayTile.STYLE_FULL)

    /** Compact: −/+ against [Entity.targetTempStep], clamped to the thermostat's own range. */
    @Composable
    override fun TileTrailing(e: Entity, actions: EntityControlActions, style: String) {
        if (style != OverlayTile.STYLE_COMPACT || !e.supportsTargetTemperature) return
        val now = e.targetTemperature ?: e.currentTemperature ?: e.minTemp
        val step = e.targetTempStep
        val tint = if (e.state != "off") hvacModeColor(e.state) else null
        ModeIconButton(
            icon = Icons.Rounded.Remove,
            selected = false,
            onClick = { actions.setClimateTemp(e, (now - step).coerceIn(e.minTemp, e.maxTemp)) },
            activeColor = tint,
        )
        ModeIconButton(
            icon = Icons.Rounded.Add,
            selected = false,
            onClick = { actions.setClimateTemp(e, (now + step).coerceIn(e.minTemp, e.maxTemp)) },
            activeColor = tint,
        )
    }

    /** Full: the card's own body, so the two can never describe different thermostats. */
    @Composable
    override fun TileControls(e: Entity, actions: EntityControlActions, style: String) {
        if (style != OverlayTile.STYLE_FULL) return
        ClimateControls(e, actions, null, trapHorizontal = false)
    }

    // A thermostat's state IS its hvac mode, so the mode belongs in the status at every size.
    override fun status(e: Entity, compact: Boolean) =
        e.currentTemperature?.let { "${cap(e.state)} · ${fmt(it)}°" } ?: cap(e.state)

    @Composable
    override fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) =
        ClimateControls(e, actions, firstFocus, trapHorizontal = true)
}

/**
 * Target temperature, hvac modes, fan modes — the card's body and the Full tile rung both.
 *
 * [trapHorizontal] is the only difference between the two callers: on the card Left and Right have
 * nowhere else to go, on a tile they belong to the tiles beside it. See `ModeIconRow`.
 */
@Composable
private fun ClimateControls(
    e: Entity,
    actions: EntityControlActions,
    firstFocus: FocusRequester?,
    trapHorizontal: Boolean,
) {
    // The mode colour runs through the whole card: HA tints a thermostat by what it is doing, and
    // `off` has no colour of its own.
    val active = if (e.state != "off") hvacModeColor(e.state) else null
    // One child, so the gaps between sections are this number and not whichever spacing the parent
    // happens to use — 9dp inside the control card, 8dp inside a Full tile. Climate never showed
    // the fan card's doubled-gap problem because every row here is already a self-contained Column,
    // but it did mean the same thermostat was spaced two different ways in the two places it draws.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ColorSliderRow(
            label = "Target",
            value = e.targetTemperature ?: e.currentTemperature ?: e.minTemp,
            min = e.minTemp, max = e.maxTemp, step = e.targetTempStep,
            track = TEMP_TRACK,
            valueLabel = { "${fmt(it)}°" },
            onCommit = { actions.setClimateTemp(e, it) },
            resetKey = e.entityId,
            focusRequester = firstFocus,
        )
        if (e.hvacModes.isNotEmpty()) {
            ModeIconRow(
                label = "Mode",
                items = e.hvacModes.map { it to hvacModeIcon(it) },
                selected = e.state,
                onSelect = { actions.setHvacMode(e, it) },
                activeColor = active,
                trapHorizontal = trapHorizontal,
            )
        }
        if (e.fanModes.isNotEmpty()) {
            FanModeRow(e, actions, activeColor = active, trapHorizontal = trapHorizontal)
        }
        // Each guarded by its own supported-features bit *and* a non-empty list. A thermostat can
        // advertise the feature and publish nothing — the Demo one does for horizontal swing — and
        // a labelled empty strip is worse than no row at all.
        if (e.supportsClimatePresetMode) {
            ModeTextRow(
                label = "Preset",
                items = e.presetModes,
                selected = e.presetMode,
                onSelect = { actions.setClimatePreset(e, it) },
                activeColor = active,
                trapHorizontal = trapHorizontal,
            )
        }
        if (e.supportsClimateSwingMode) {
            // "Swing" alone while a second axis is also on screen says nothing about which one.
            val label = if (e.supportsClimateSwingHorizontalMode) "Swing (vertical)" else "Swing"
            ModeTextRow(
                label = label,
                items = e.swingModes,
                selected = e.swingMode,
                onSelect = { actions.setSwingMode(e, it) },
                activeColor = active,
                trapHorizontal = trapHorizontal,
            )
        }
        if (e.supportsClimateSwingHorizontalMode) {
            ModeTextRow(
                label = "Swing (horizontal)",
                items = e.swingHorizontalModes,
                selected = e.swingHorizontalMode,
                onSelect = { actions.setSwingHorizontalMode(e, it) },
                activeColor = active,
                trapHorizontal = trapHorizontal,
            )
        }
    }
}
