package com.tvassist.ui.entities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import com.tvassist.ui.cards.displayIcon
import com.tvassist.ui.groupEntitiesByCategory
import com.tvassist.ui.cards.entityStatus
import com.tvassist.ui.cards.displayName
import com.tvassist.ui.cards.effectiveOn
import com.tvassist.data.ha.Entity
import com.tvassist.data.settings.EntityOverride
import com.tvassist.ui.AppAccent
import com.tvassist.ui.CardFocusBg
import com.tvassist.ui.EntityIconContent
import com.tvassist.ui.PremiumRow
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary
import com.tvassist.ui.cards.displayIcon
import com.tvassist.ui.groupEntitiesByCategory
import com.tvassist.ui.cards.entityStatus
import com.tvassist.ui.cards.displayName
import com.tvassist.ui.cards.effectiveOn

internal fun categoryLabel(domain: String): String =
    domain.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** Home list: imported entities grouped into collapsible category sections. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CategorizedEntityList(
    entities: List<Entity>,
    overrides: Map<String, com.tvassist.data.settings.EntityOverride>,
    repository: com.tvassist.data.ha.HaRepository,
    onPrimary: (Entity) -> Unit,
    onMore: (Entity) -> Unit,
) {
    val groups = remember(entities) { groupEntitiesByCategory(entities) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        groups.forEach { (domain, list) ->
            val isExpanded = expanded[domain] ?: true
            item(key = "hdr_$domain") {
                CategoryHeader(categoryLabel(domain), list.size, isExpanded) {
                    expanded[domain] = !isExpanded
                }
            }
            if (isExpanded) {
                items(list, key = { it.entityId }) { entity ->
                    ControlRow(entity = entity, override = overrides[entity.entityId], repository = repository, onPrimary = onPrimary, onMore = onMore, resolve = { id -> entities.firstOrNull { it.entityId == id } })
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CategoryHeader(title: String, count: Int, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = CardFocusBg,
            pressedContainerColor = CardFocusBg,
            contentColor = TxtPrimary,
            focusedContentColor = TxtPrimary,
        ),
        // A full-width header must not grow on focus (default focusedScale is 1.1x, which makes the
        // highlight spill past the content margins at the corners); the border/fill are the cue.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.5.dp, AppAccent), shape = RoundedCornerShape(14.dp)),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.ChevronRight,
                    contentDescription = null, tint = TxtMuted, modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 15.sp, color = TxtMuted, fontWeight = FontWeight.SemiBold)
            }
            Text("$count", fontSize = 13.sp, color = TxtMuted)
        }
    }
}

/** A control row on Home: single-press = quick action, long-press = more options (card). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ControlRow(
    entity: Entity,
    override: com.tvassist.data.settings.EntityOverride?,
    repository: com.tvassist.data.ha.HaRepository,
    onPrimary: (Entity) -> Unit,
    onMore: (Entity) -> Unit,
    resolve: (String) -> Entity? = { null },
) {
    PremiumRow(
        icon = displayIcon(entity, override),
        iconContent = { tint -> EntityIconContent(entity, override, tint, repository = repository) },
        title = displayName(entity, override),
        subtitle = entity.entityId,
        onClick = { onPrimary(entity) },
        onLongClick = { onMore(entity) },
        modifier = Modifier.padding(start = 20.dp),
        trailing = {
            Text(
                text = entityStatus(entity, compact = true),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (effectiveOn(entity, override, resolve)) Color(0xFF6FCF7F) else TxtMuted,
            )
        },
    )
}

// ---------------------------------------------------------------------------------------
// Import screen: browse ALL of HA (fetched once), categorized, search + multi-select
// ---------------------------------------------------------------------------------------
