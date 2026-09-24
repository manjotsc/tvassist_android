package com.tvassist.ui.cards.light

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.tvassist.data.settings.OverlayTile
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.cards.AdjustableSliderRow
import com.tvassist.ui.cards.ColorSliderRow
import com.tvassist.ui.cards.EntityControlActions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import com.tvassist.ui.cards.EntityCard
import com.tvassist.ui.cards.pctStatus

/**
 * The card body, and the top rung of the tile ladder.
 *
 * Split into named rows below so a tile can take a prefix of them — brightness for `Compact`,
 * brightness and warmth for `Standard`, all of it for `Full` — without either surface restating a
 * slider. A capability added here therefore reaches both.
 */
@Composable
internal fun LightControls(
    entity: Entity,
    actions: EntityControlActions,
    firstFocus: FocusRequester? = null,
) {
    BrightnessRow(entity, actions, firstFocus)
    WarmthRow(entity, actions, if (entity.supportsBrightness) null else firstFocus)
    ColorRows(entity, actions)
}

@Composable
private fun BrightnessRow(
    entity: Entity,
    actions: EntityControlActions,
    firstFocus: FocusRequester?,
) {
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
}

@Composable
private fun WarmthRow(
    entity: Entity,
    actions: EntityControlActions,
    firstFocus: FocusRequester?,
) {
    if (entity.supportsColorTemp) {
        val min = entity.minColorTempKelvin
        val max = entity.maxColorTempKelvin
        val step = ((max - min) / 15).coerceAtLeast(50)
        val k = entity.colorTempKelvin ?: ((min + max) / 2)
        ColorSliderRow(
            label = "Warmth",
            value = k.toDouble(),
            min = min.toDouble(), max = max.toDouble(), step = step.toDouble(),
            // Low kelvin is the warm end, so the ramp runs amber -> neutral -> daylight blue, left
            // to right, matching what the bulb will actually do. Same reasoning as the thermostat:
            // a fill bar empties at whichever end of the range the user happens to want.
            track = WARMTH_TRACK,
            valueLabel = { "${it.roundToInt()}K" },
            onCommit = { actions.setColorTempKelvin(entity, it.roundToInt()) },
            resetKey = entity.entityId,
            focusRequester = firstFocus,
        )
    }
}

/**
 * Hue on its own, so `Compact` can offer colour without the preview swatch and the saturation row
 * that [ColorRows] adds. Saturation is read from the bulb rather than remembered here: the compact
 * rung does not change it, and a hue move must not silently reset it.
 */
@Composable
private fun HueRow(entity: Entity, actions: EntityControlActions) {
    if (!entity.supportsColor) return
    var hue by remember(entity.entityId) { mutableDoubleStateOf(entity.hsColor?.first ?: 30.0) }
    HueRow(entity, actions, hue, sat = entity.hsColor?.second ?: 100.0) { hue = it }
}

/**
 * The hue slider, with the hue owned by the caller. [ColorRows] must share one hue between this row,
 * its preview swatch and its saturation row: when each kept its own, a saturation change sent the
 * hue the card had opened with and snapped the bulb back to its old colour.
 */
@Composable
private fun HueRow(
    entity: Entity,
    actions: EntityControlActions,
    hue: Double,
    sat: Double,
    onHue: (Double) -> Unit,
) {
    ColorSliderRow(
        label = "Color",
        value = hue, min = 0.0, max = 360.0, step = 6.0,
        track = Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) }),
        valueLabel = { "${it.roundToInt()}°" },
        onCommit = { onHue(it); actions.setHsColor(entity, it, sat) },
        resetKey = entity.entityId,
    )
}

@Composable
private fun ColorRows(entity: Entity, actions: EntityControlActions) {
    val th = LocalOverlayTheme.current
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
        HueRow(entity, actions, hue, sat) { hue = it }
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

// --- Service calls -------------------------------------------------------------------------

internal fun EntityControlActions.setBrightnessPct(e: Entity, pct: Int) = repository.callService(
    "light", "turn_on", e.entityId,
    mapOf("brightness_pct" to JsonPrimitive(pct.coerceIn(1, 100))),
)

internal fun EntityControlActions.setColorTempKelvin(e: Entity, kelvin: Int) = repository.callService(
    "light", "turn_on", e.entityId,
    mapOf("color_temp_kelvin" to JsonPrimitive(kelvin)),
)

/** Set a light's color via hue (0-360) and saturation (0-100). */
internal fun EntityControlActions.setHsColor(e: Entity, hue: Double, sat: Double) = repository.callService(
    "light", "turn_on", e.entityId,
    mapOf(
        "hs_color" to JsonArray(
            listOf(JsonPrimitive(hue.coerceIn(0.0, 360.0)), JsonPrimitive(sat.coerceIn(0.0, 100.0))),
        ) as JsonElement,
    ),
)

/** A Normal tile's live brightness, plus the modifier that makes Left/Right change it. */
internal class InlineBrightness(val pct: Int, val modifier: Modifier)

/**
 * Brightness on the `Normal` row, adjustable in place.
 *
 * The bar there was a read-only [com.tvassist.ui.cards.TrackBar] — it showed the level and did
 * nothing, which reads as a broken slider rather than as a status. Left and Right now move it,
 * accelerating on a held key and committing on the same 160ms debounce a slider row uses, so one
 * press is one command rather than one per step.
 *
 * **[adjustable] is false wherever the tile has horizontal neighbours**, and that is not a
 * preference. A focused row consumed Left and Right unconditionally, and Up/Down only moves between
 * *lines* — so in a multi-column row the tiles beside this one became unreachable, and the key that
 * should have moved to them silently changed a light's brightness instead. Consuming the key costs
 * nothing when there is nowhere sideways to go, and costs the whole row when there is.
 */
@Composable
internal fun rememberInlineBrightness(
    e: Entity,
    actions: EntityControlActions,
    adjustable: Boolean = true,
): InlineBrightness {
    val reported = (e.brightnessPct ?: 0).toDouble()
    var local by remember(e.entityId) { mutableDoubleStateOf(reported) }
    var focused by remember(e.entityId) { mutableStateOf(false) }
    // Home Assistant's echo wins whenever the user is not the one turning the dial.
    LaunchedEffect(reported) { if (!focused) local = reported }
    LaunchedEffect(local) {
        if (abs(local - reported) > 0.001) {
            delay(160)
            actions.setBrightnessPct(e, local.roundToInt())
        }
    }
    val modifier = Modifier
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { ev ->
            // Never consume where the tile has neighbours: the key belongs to focus movement there.
            if (!adjustable || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val mult = when {
                ev.nativeKeyEvent.repeatCount >= 14 -> 5.0
                ev.nativeKeyEvent.repeatCount >= 6 -> 3.0
                else -> 1.0
            }
            when (ev.key) {
                Key.DirectionLeft -> { local = (local - 5.0 * mult).coerceIn(1.0, 100.0); true }
                Key.DirectionRight -> { local = (local + 5.0 * mult).coerceIn(1.0, 100.0); true }
                else -> false
            }
        }
    return InlineBrightness(local.roundToInt(), modifier)
}

/**
 * The colour the bulb is actually showing, for tinting its icon — null when it is off, or when it
 * reports neither a colour nor a colour temperature.
 *
 * A lamp set to deep amber and one set to daylight are the same grey dot otherwise, which is most
 * of what makes a row of lights hard to read at a glance.
 */
internal fun bulbTint(e: Entity): Color? {
    if (!e.isOn) return null
    e.hsColor?.let { (h, s) ->
        return Color.hsv(h.toFloat(), (s / 100.0).toFloat().coerceIn(0f, 1f), 1f)
    }
    return e.colorTempKelvin?.let(::kelvinTint)
}

/** Piecewise interpolation over [WARMTH_TRACK]'s own stops, so icon and slider agree. */
private fun kelvinTint(k: Int): Color {
    val stops = listOf(
        2000 to Color(0xFFFF9329),
        2700 to Color(0xFFFFC98F),
        4000 to Color(0xFFFFEFDF),
        5500 to Color(0xFFF2F6FF),
        6500 to Color(0xFFC9DCFF),
    )
    if (k <= stops.first().first) return stops.first().second
    if (k >= stops.last().first) return stops.last().second
    for (i in 0 until stops.lastIndex) {
        val (k0, c0) = stops[i]
        val (k1, c1) = stops[i + 1]
        if (k in k0..k1) return lerp(c0, c1, (k - k0).toFloat() / (k1 - k0))
    }
    return stops.last().second
}

/** Roughly what a bulb looks like at each kelvin, warmest (lowest K) first. */
private val WARMTH_TRACK = Brush.horizontalGradient(
    listOf(
        Color(0xFFFF9329), // ~2000K
        Color(0xFFFFC98F), // ~2700K
        Color(0xFFFFEFDF), // ~4000K
        Color(0xFFF2F6FF), // ~5500K
        Color(0xFFC9DCFF), // ~6500K
    ),
)

/** The `light` domain: brightness, colour temperature and hue/saturation. */
internal object LightCard : EntityCard {
    override val domains = setOf("light")

    /**
     * The rungs this bulb can fill. `Auto` is not among them — it is the plain row every light tile
     * renders as today, and [autoStyle] leaves it that way so existing layouts are unchanged.
     *
     * A rung that would draw the same thing as the one below it is not offered: a dimmer-only bulb
     * has no warmth to add, so `Standard` would be `Compact` and only `Compact` appears.
     */
    /**
     * Normal is the plain row — a colour-tinted icon, the name, and the brightness level — and it
     * is what [autoStyle] draws, so Auto and Normal are the same tile. Compact carries the bulb's
     * controls condensed; Full carries all of them at full size. A bulb with nothing to control is
     * offered neither.
     */
    override fun variants(e: Entity): List<String> = buildList {
        // Icon tap suits any bulb — it is on/off and nothing else.
        add(OverlayTile.STYLE_ICON)
        add(OverlayTile.STYLE_STANDARD)
        if (e.supportsBrightness || e.supportsColorTemp || e.supportsColor) {
            add(OverlayTile.STYLE_COMPACT)
            add(OverlayTile.STYLE_FULL)
        }
    }

    /** Auto is Normal. */
    override fun autoStyle(e: Entity) = OverlayTile.STYLE_STANDARD

    /**
     * Brightness, then warmth, then colour — each rung is the one below it plus a row.
     *
     * Built from the same three rows [LightControls] uses, so the tile and the card can never drift
     * apart. The overlay is the only caller: the card that hold-for-more opens does not change with
     * a tile's style.
     */
    @Composable
    override fun TileControls(e: Entity, actions: EntityControlActions, style: String) {
        when (style) {
            // Brightness, warmth, colour — the three that matter, without the colour preview
            // swatch or the saturation row, which is what keeps it a rung short of Full.
            OverlayTile.STYLE_COMPACT -> {
                BrightnessRow(e, actions, null)
                WarmthRow(e, actions, null)
                HueRow(e, actions)
            }
            OverlayTile.STYLE_FULL -> LightControls(e, actions)
        }
    }

    override fun status(e: Entity, compact: Boolean) = pctStatus(e.isOn, e.brightnessPct, compact)

    @Composable
    override fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) =
        LightControls(e, actions, firstFocus)
}

