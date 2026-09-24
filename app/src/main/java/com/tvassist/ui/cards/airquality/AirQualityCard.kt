package com.tvassist.ui.cards.airquality

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.tvassist.data.settings.OverlayTile
import com.tvassist.data.ha.Entity
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.cards.EntityCard
import com.tvassist.ui.cards.EntityControlActions
import com.tvassist.ui.fmt

/**
 * A pollutant's attribute key as something a person reads.
 *
 * Words rather than formulae for the nitrogen and sulphur compounds: subscripts do not survive a
 * plain [Text] and "NO2" read as a product name in review. The particulates keep their familiar
 * shorthand, which is what everyone actually calls them.
 */
private fun label(key: String): String = when (key) {
    "particulate_matter_0_1" -> "PM1"
    "particulate_matter_2_5" -> "PM2.5"
    "particulate_matter_10" -> "PM10"
    "ozone" -> "Ozone"
    "carbon_monoxide" -> "Carbon monoxide"
    "carbon_dioxide" -> "Carbon dioxide"
    "sulphur_dioxide" -> "Sulphur dioxide"
    "nitrogen_oxide" -> "Nitrogen oxide"
    "nitrogen_monoxide" -> "Nitrogen monoxide"
    "nitrogen_dioxide" -> "Nitrogen dioxide"
    "volatile_organic_compounds" -> "VOC"
    "air_quality_index" -> "Air quality index"
    else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** The unit for one reading. An index is a number on a scale, not a concentration. */
private fun unitFor(key: String, entity: Entity): String =
    if (key == Entity.AIR_QUALITY_INDEX) "" else entity.unitOfMeasurement.orEmpty()

/**
 * The `air_quality` domain — the first card in this app that is a **readout rather than a control**.
 *
 * Nothing here can be actuated, which is why it declares no actions and leaves the footer to Done
 * alone. Readings are shown exactly as Home Assistant sent them, with no good/moderate/poor
 * colouring: these attributes are concentrations rather than an index, and turning a concentration
 * into a verdict needs a published standard — WHO 2021, US EPA and the EEA disagree with each
 * other, per pollutant. Reporting the number and letting the reader judge is the honest option, and
 * there is no threshold table to drift out of date.
 *
 * Note this domain is Home Assistant's legacy shape. Most integrations now publish one `sensor` per
 * pollutant with a `pm25` / `nitrogen_dioxide` / `aqi` device class — which this app already draws
 * icons for (see `deviceClassIconifyName`). The Demo integration is the one still producing these.
 */
internal object AirQualityCard : EntityCard {
    override val domains = setOf("air_quality")

    /**
     * Never highlighted. [com.tvassist.ui.cards.stateActive] answers `true` for any domain it does
     * not recognise, which would leave an air quality tile permanently lit as though it were
     * switched on. A readout has nothing to be on.
     */
    override fun isActive(e: Entity): Boolean = false

    /**
     * Full, and nothing else.
     *
     * No Icon tap and no Compact: a readout cannot be actuated, so a square button would be a
     * button that does nothing, and Compact's trailing slot is for controls this domain has none
     * of. Full is the one rung that means something here — the pollutants the row can only hint at,
     * listed on the tile instead of behind a hold.
     *
     * `Auto` stays the plain row, so no air quality tile anybody already has changes.
     */
    override fun variants(e: Entity) = listOf(OverlayTile.STYLE_FULL)

    /**
     * Full: the readings, without the 40sp headline the card leads with.
     *
     * The tile's own status line already carries that number — `status(compact = true)` is
     * "PM2.5 14" — so repeating it at headline size would say the same thing twice in a space the
     * sidebar cannot spare.
     */
    @Composable
    override fun TileControls(e: Entity, actions: EntityControlActions, style: String) {
        if (style != OverlayTile.STYLE_FULL) return
        // Minus the headline, exactly as the card does: the tile's own status line is already
        // "PM2.5 14", and listing it again two rows below says the same number twice.
        val readings = e.airQualityReadings
        val headline = readings.firstOrNull { it.first == Entity.AIR_QUALITY_HEADLINE }
        // Not focusable here: the tile's header is already a focus stop, so the sidebar can scroll
        // past this. On the card it must be focusable, for the reason given in [ReadingsList].
        ReadingsList(e, readings.filter { it.first != headline?.first }, focusRequester = null)
    }

    /**
     * Names the number. An `air_quality` entity publishes PM2.5 as its own state, so the default
     * status line rendered a bare "14" with nothing to say what had been measured.
     */
    override fun status(e: Entity, compact: Boolean): String {
        // Blank in the roomy case on purpose: the card's own headline is this number, and the
        // control-card header skips a blank status rather than printing it a second time.
        if (!compact) return ""
        val pm25 = e.airQualityReadings.firstOrNull { it.first == Entity.AIR_QUALITY_HEADLINE }
            ?: return com.tvassist.ui.cap(e.state)
        return "PM2.5 ${fmt(pm25.second)}"
    }

    @Composable
    override fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) {
        val th = LocalOverlayTheme.current
        val readings = e.airQualityReadings
        val headline = readings.firstOrNull { it.first == Entity.AIR_QUALITY_HEADLINE }
        // Everything except whatever became the headline. An entity with no PM2.5 still lists all
        // it has rather than dropping a reading or inventing a headline it never reported.
        val rest = readings.filter { it.first != headline?.first }

        if (headline != null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The unit rides along at text size rather than headline size: the number is the
                // reading and the unit a footnote to it, and at 40sp the two together overran the
                // 320dp column.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmt(headline.second), color = th.text, fontSize = 40.sp)
                    e.unitOfMeasurement?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            it,
                            color = th.subText,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(bottom = 7.dp),
                        )
                    }
                }
                Text(label(headline.first), color = th.subText, fontSize = 13.sp)
            }
        }

        ReadingsList(e, rest, firstFocus)

        if (readings.isEmpty()) {
            Text("No readings reported.", color = th.subText, fontSize = 14.sp)
        }
    }
}

/**
 * The pollutant rows, shared by the control card and the Full tile rung.
 *
 * One tile holding the readings, the same surface a slider row sits on, so it reads as part of the
 * set rather than as loose text.
 *
 * [focusRequester] non-null also makes it **focusable**, which it needs on the card despite having
 * nothing to activate: on a TV `verticalScroll` is driven entirely by focus movement, and with Done
 * as the only focusable there was nowhere to move *from*, so a card taller than its slot could not
 * be scrolled at all. It takes the card's opening focus too, which beats starting on Done — the
 * reading is what you opened the card to see. On a tile the header is already a focus stop, so one
 * here would only add a step to cross the sidebar.
 */
@Composable
private fun ReadingsList(
    e: Entity,
    readings: List<Pair<String, Double>>,
    focusRequester: FocusRequester?,
) {
    if (readings.isEmpty()) return
    val th = LocalOverlayTheme.current
    var focused by remember(e.entityId) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // Same focused/unfocused pair an AdjustableSliderRow uses, so a focused readout looks
            // like every other focused row rather than inventing a highlight.
            .background(if (focused) th.tileFocused else th.tile)
            .then(
                if (focusRequester != null) {
                    Modifier
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        readings.forEach { (key, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label(key),
                    color = th.subText,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${fmt(value)} ${unitFor(key, e)}".trim(),
                    color = th.text,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
