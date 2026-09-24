package com.tvassist.ui.cards.climate

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.hvacModeIcon
import com.tvassist.ui.cards.EntityControlActions
import com.tvassist.ui.cards.FanSpeedGlyph
import com.tvassist.ui.cards.ModeContentButton
import com.tvassist.ui.cards.thenKeepInRow

/** A climate fan-mode row: auto/off get an icon, real speeds get ascending [SpeedBars]. */
@Composable
fun FanModeRow(
    entity: Entity,
    actions: EntityControlActions,
    activeColor: Color? = null,
    /** Upstream's `fan_modes` filter; null means every mode the thermostat reports. */
    modes: List<String>? = null,
    /**
     * Whether Left on the first item and Right on the last stay inside the row.
     *
     * True on a control card, where nothing sits beside the row and holding the keys in is the
     * kindness. **False on a sidebar tile**: there the keys belong to the tiles left and right, and
     * swallowing them strands every one of them — the same trap the Normal light row had.
     */
    trapHorizontal: Boolean = true,
) {
    val modes = modes?.let { w -> entity.fanModes.filter { it in w } } ?: entity.fanModes
    if (modes.isEmpty()) return
    val special = setOf("auto", "off")
    val speeds = modes.filter { it.lowercase() !in special }
    Column {
        Text("Fan", color = LocalOverlayTheme.current.subText, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(LocalOverlayTheme.current.segmentBg)
                .horizontalScroll(rememberScrollState()).padding(6.dp),
        ) {
            modes.forEachIndexed { i, mode ->
                val sel = mode.equals(entity.fanMode, ignoreCase = true)
                val keys = Modifier.padding(horizontal = 3.dp)
                    .thenKeepInRow(trapHorizontal, i == 0, i == modes.lastIndex)
                ModeContentButton(sel, { actions.setFanMode(entity, mode) }, keys, activeColor = activeColor) { c ->
                    if (mode.lowercase() in special) {
                        Icon(hvacModeIcon(mode), contentDescription = mode, tint = c, modifier = Modifier.size(22.dp))
                    } else {
                        FanSpeedGlyph(speeds.indexOf(mode) + 1, speeds.size, c)
                    }
                }
            }
        }
    }
}

