package com.tvassist.ui.cards.climate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.ui.EntityIconContent
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.cap
import com.tvassist.ui.domainIcon
import com.tvassist.ui.fmt
import com.tvassist.ui.hvacModeColor
import com.tvassist.ui.hvacModeIcon
import com.tvassist.ui.cards.EntityControlActions
import com.tvassist.ui.cards.HaIconChip
import com.tvassist.ui.cards.ModeIconRow

/** A compact inline climate tile: header (opens full card) + Mode/Fan icon rows. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun InlineClimateTile(
    entity: Entity,
    actions: EntityControlActions,
    onOpen: (Entity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val th = LocalOverlayTheme.current
    val on = entity.state != "off"
    val active = hvacModeColor(entity.state) // HA-style mode color (dry=amber, cool=blue, …)
    Column(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(th.tile).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            onClick = { onOpen(entity) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = th.tileFocused,
                contentColor = th.text,
                focusedContentColor = th.text,
            ),
            // No scale: this header is full-width inside the climate card, so scaling would
            // bulge it past the panel edges. The focus border is the highlight.
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(2.dp, th.focus), shape = RoundedCornerShape(12.dp)),
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HaIconChip(
                    icon = domainIcon(entity),
                    on = on,
                    tint = if (on) active else null,
                    size = 36,
                    iconContent = { tint -> EntityIconContent(entity, null, tint, 19, repository = actions.repository) },
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(entity.friendlyName, fontSize = 14.sp, color = th.text, maxLines = 1)
                    Text(
                        text = entity.currentTemperature?.let { "${cap(entity.state)} · ${fmt(it)}°" } ?: cap(entity.state),
                        fontSize = 11.sp,
                        color = th.subText,
                        maxLines = 1,
                    )
                }
            }
        }
        if (entity.hvacModes.isNotEmpty()) {
            ModeIconRow(
                label = "Mode",
                items = entity.hvacModes.map { it to hvacModeIcon(it) },
                selected = entity.state,
                onSelect = { actions.setHvacMode(entity, it) },
                activeColor = if (on) active else null,
                // A tile shares its line with other tiles; see ModeIconRow's own note.
                trapHorizontal = false,
            )
        }
        if (entity.fanModes.isNotEmpty()) {
            FanModeRow(entity, actions, activeColor = if (on) active else null, trapHorizontal = false)
        }
    }
}

