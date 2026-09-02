package com.tvassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * Wraps the HA service calls a control card needs, so the card itself stays UI-only and
 * works from both the Home screen and the overlay (each builds one from the repository).
 */
class EntityControlActions(private val repo: HaRepository) {
    /** The backing repository, e.g. to fetch an entity_picture (person avatar) for the card icon. */
    val repository: HaRepository get() = repo

    fun toggle(e: Entity) = repo.toggle(e)

    fun setBrightnessPct(e: Entity, pct: Int) = repo.callService(
        "light", "turn_on", e.entityId,
        mapOf("brightness_pct" to JsonPrimitive(pct.coerceIn(1, 100))),
    )

    fun setColorTempKelvin(e: Entity, kelvin: Int) = repo.callService(
        "light", "turn_on", e.entityId,
        mapOf("color_temp_kelvin" to JsonPrimitive(kelvin)),
    )

    fun setRgbColor(e: Entity, rgb: Triple<Int, Int, Int>) = repo.callService(
        "light", "turn_on", e.entityId,
        mapOf(
            "rgb_color" to JsonArray(
                listOf(JsonPrimitive(rgb.first), JsonPrimitive(rgb.second), JsonPrimitive(rgb.third)),
            ) as JsonElement,
        ),
    )

    /** Set a light's color via hue (0–360) and saturation (0–100). */
    fun setHsColor(e: Entity, hue: Double, sat: Double) = repo.callService(
        "light", "turn_on", e.entityId,
        mapOf(
            "hs_color" to JsonArray(
                listOf(JsonPrimitive(hue.coerceIn(0.0, 360.0)), JsonPrimitive(sat.coerceIn(0.0, 100.0))),
            ) as JsonElement,
        ),
    )

    fun setClimateTemp(e: Entity, temp: Double) = repo.callService(
        "climate", "set_temperature", e.entityId,
        mapOf("temperature" to JsonPrimitive(temp)),
    )

    fun setHvacMode(e: Entity, mode: String) = repo.callService(
        "climate", "set_hvac_mode", e.entityId,
        mapOf("hvac_mode" to JsonPrimitive(mode)),
    )

    fun setFanMode(e: Entity, mode: String) = repo.callService(
        "climate", "set_fan_mode", e.entityId,
        mapOf("fan_mode" to JsonPrimitive(mode)),
    )

    /** Set a fan entity's speed as a percentage (0–100). */
    fun setFanPercentage(e: Entity, pct: Int) = repo.callService(
        "fan", "set_percentage", e.entityId,
        mapOf("percentage" to JsonPrimitive(pct.coerceIn(0, 100))),
    )

    /** Set a fan entity's preset mode (e.g. Sleep, Nature). */
    fun setFanPreset(e: Entity, preset: String) = repo.callService(
        "fan", "set_preset_mode", e.entityId,
        mapOf("preset_mode" to JsonPrimitive(preset)),
    )

    fun turnOn(e: Entity) = repo.callService("homeassistant", "turn_on", e.entityId)
    fun turnOff(e: Entity) = repo.callService("homeassistant", "turn_off", e.entityId)

    /** Fire a stateless button via <domain>.press. */
    fun press(e: Entity) = repo.callService(e.domain, "press", e.entityId)

    /** Activate: press for buttons, otherwise turn_on (scenes/scripts/etc.). */
    fun run(e: Entity) =
        if (e.isButton) press(e) else repo.callService(e.domain, "turn_on", e.entityId)
}

private val SWATCHES = listOf(
    Triple(255, 70, 70), Triple(255, 150, 40), Triple(255, 220, 60), Triple(70, 220, 90),
    Triple(60, 210, 220), Triple(70, 120, 255), Triple(180, 90, 240), Triple(255, 240, 220),
)

/**
 * A Home-Assistant-style control card for a single entity, rendered over a dim scrim.
 * Dispatches to a domain-specific layout (light/climate) with a generic on/off fallback.
 * D-pad: Up/Down move between rows, Left/Right adjust a focused slider, OK selects a mode.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EntityControlCard(
    entity: Entity,
    actions: EntityControlActions,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(entity.entityId) { runCatching { firstFocus.requestFocus() } }

    // The card must follow the user's palette: the rows it contains (AdjustableSliderRow etc.) are
    // themed, so a hardcoded dark card left light sliders sitting on a dark panel under a light theme.
    val th = LocalOverlayTheme.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Scrim stays dark-on-light and light-on-dark so the card lifts off whatever is behind it.
            .background(if (th.background.luminance() > 0.5f) Color(0x66000000) else Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                // The Assist transcript needs more room to read than a stack of sliders does.
                .widthIn(min = 360.dp, max = if (entity.isConversation) 560.dp else 440.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(th.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // A locked door reads as the "active/secure" (green) state, like on for other domains.
            val activeGreen = if (entity.isLock) entity.isLocked else entity.isOn
            // Header: icon chip + name + status.
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaIconChip(
                    icon = domainIcon(entity),
                    on = activeGreen || entity.domain == "climate",
                    tint = if (entity.domain == "climate" && entity.state != "off") hvacModeColor(entity.state) else null,
                    size = 40,
                    iconContent = { tint -> EntityIconContent(entity, null, tint, 21, repository = actions.repository) },
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(entity.friendlyName, color = th.text, fontSize = 17.sp, maxLines = 1)
                    Text(entity.entityId, color = th.subText, fontSize = 11.sp, maxLines = 1)
                }
                Text(
                    text = headerStatus(entity),
                    // Green stays semantic (on/locked); the inactive case follows the palette.
                    color = if (activeGreen) Color(0xFF6FCF7F) else th.subText,
                    fontSize = 14.sp,
                )
            }

            when (entity.domain) {
                "light" -> LightControls(entity, actions, firstFocus)
                "climate" -> ClimateControls(entity, actions, firstFocus)
                "fan" -> FanSpeedControls(entity, actions, firstFocus)
                "conversation" -> ConversationControls(entity, actions, firstFocus)
                else -> GenericControls(entity, actions, firstFocus)
            }

            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Locks get their explicit Lock/Unlock button inside GenericControls; skip the
                // generic "Toggle" here to avoid a second, vaguely-labelled action.
                if ((entity.isToggleable && !entity.isLock) || entity.domain == "light") {
                    AccentButton("Toggle", { actions.toggle(entity) })
                }
                AccentButton("Close", onDismiss)
            }
        }
    }
}

private fun headerStatus(e: Entity): String = when {
    e.isButton -> ""
    // A conversation entity's state is the timestamp it was last used — meaningless in a header.
    e.isConversation -> "Assist"
    e.domain == "climate" -> e.currentTemperature?.let { "${cap(e.state)} · ${fmt(it)}°" } ?: cap(e.state)
    e.domain == "light" -> if (e.isOn) e.brightnessPct?.let { "on · $it%" } ?: "on" else "off"
    e.domain == "fan" -> if (e.isOn) e.percentage?.let { "on · $it%" } ?: "on" else "off"
    else -> cap(e.state)
}

@Composable
private fun LightControls(entity: Entity, actions: EntityControlActions, firstFocus: FocusRequester) {
    val th = LocalOverlayTheme.current
    if (entity.supportsBrightness) {
        AdjustableSliderRow(
            label = "Brightness",
            value = (entity.brightnessPct ?: 0).toDouble(),
            min = 1.0, max = 100.0, step = 5.0,
            valueLabel = { "${it.roundToInt()}%" },
            onCommit = { actions.setBrightnessPct(entity, it.roundToInt()) },
            resetKey = entity.entityId,
            focusRequester = firstFocus,
        )
    }
    if (entity.supportsColorTemp) {
        val min = entity.minColorTempKelvin
        val max = entity.maxColorTempKelvin
        val step = ((max - min) / 15).coerceAtLeast(50)
        val k = entity.colorTempKelvin ?: ((min + max) / 2)
        AdjustableSliderRow(
            label = "Warmth",
            value = k.toDouble(),
            min = min.toDouble(), max = max.toDouble(), step = step.toDouble(),
            valueLabel = { "${it.roundToInt()}K" },
            onCommit = { actions.setColorTempKelvin(entity, it.roundToInt()) },
            resetKey = entity.entityId,
            focusRequester = if (entity.supportsBrightness) null else firstFocus,
        )
    }
    if (entity.supportsColor) {
        var hue by remember(entity.entityId) { mutableDoubleStateOf(entity.hsColor?.first ?: 30.0) }
        var sat by remember(entity.entityId) { mutableDoubleStateOf(entity.hsColor?.second ?: 100.0) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Color", color = th.subText, fontSize = 13.sp, modifier = Modifier.weight(1f))
            // Live preview of the chosen color.
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .background(Color.hsv(hue.toFloat(), (sat / 100.0).toFloat(), 1f)),
            )
        }
        Spacer(Modifier.height(8.dp))
        ColorSliderRow(
            label = "Hue",
            value = hue, min = 0.0, max = 360.0, step = 6.0,
            track = Brush.horizontalGradient(
                (0..6).map { Color.hsv(it * 60f, 1f, 1f) },
            ),
            valueLabel = { "${it.roundToInt()}°" },
            onCommit = { hue = it; actions.setHsColor(entity, it, sat) },
            resetKey = entity.entityId,
        )
        Spacer(Modifier.height(8.dp))
        ColorSliderRow(
            label = "Saturation",
            value = sat, min = 0.0, max = 100.0, step = 5.0,
            track = Brush.horizontalGradient(
                listOf(Color.hsv(hue.toFloat(), 0f, 1f), Color.hsv(hue.toFloat(), 1f, 1f)),
            ),
            valueLabel = { "${it.roundToInt()}%" },
            onCommit = { sat = it; actions.setHsColor(entity, hue, it) },
            resetKey = entity.entityId,
        )
    }
}

@Composable
private fun ClimateControls(entity: Entity, actions: EntityControlActions, firstFocus: FocusRequester) {
    val step = entity.targetTempStep
    val target = entity.targetTemperature ?: entity.currentTemperature ?: entity.minTemp
    AdjustableSliderRow(
        label = "Target",
        value = target,
        min = entity.minTemp, max = entity.maxTemp, step = step,
        valueLabel = { "${fmt(it)}°" },
        onCommit = { actions.setClimateTemp(entity, it) },
        resetKey = entity.entityId,
        focusRequester = firstFocus,
    )
    val active = if (entity.state != "off") hvacModeColor(entity.state) else null
    if (entity.hvacModes.isNotEmpty()) {
        ModeIconRow(
            label = "Mode",
            items = entity.hvacModes.map { it to hvacModeIcon(it) },
            selected = entity.state,
            onSelect = { actions.setHvacMode(entity, it) },
            activeColor = active,
        )
    }
    if (entity.fanModes.isNotEmpty()) {
        FanModeRow(entity, actions, activeColor = active)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GenericControls(entity: Entity, actions: EntityControlActions, firstFocus: FocusRequester) {
    val th = LocalOverlayTheme.current
    if (entity.isButton) {
        AccentButton(
            label = "Press",
            onClick = { actions.press(entity) },
            modifier = Modifier.focusRequester(firstFocus),
        )
        return
    }
    if (entity.isLock) {
        Text("State: ${cap(entity.state)}", color = th.text, fontSize = 15.sp)
        // Explicit action labelled by what the press will DO (toggle picks lock vs unlock by state).
        AccentButton(
            label = if (entity.isLocked) "Unlock" else "Lock",
            onClick = { actions.toggle(entity) },
            modifier = Modifier.focusRequester(firstFocus),
        )
        return
    }
    Text("State: ${cap(entity.state)}", color = Color(0xFFCBD2DA), fontSize = 15.sp)
    if (entity.isToggleable) {
        AccentButton(
            label = if (entity.isOn) "Turn off" else "Turn on",
            onClick = { actions.toggle(entity) },
            modifier = Modifier.focusRequester(firstFocus),
        )
    }
}

internal fun fmt(v: Double): String =
    if (v == v.roundToInt().toDouble()) v.roundToInt().toString()
    // Explicit locale → a stable dot decimal, matching HA's convention (not "22,5" in some locales).
    else String.format(java.util.Locale.US, "%.1f", v)

internal fun cap(s: String): String =
    s.replace('_', ' ').replaceFirstChar { it.uppercase() }
