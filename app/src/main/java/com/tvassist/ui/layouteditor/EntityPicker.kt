package com.tvassist.ui.layouteditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Icon
import com.tvassist.ui.groupEntitiesByCategory
import com.tvassist.data.ha.Entity
import com.tvassist.ui.AccentButton
import com.tvassist.ui.AppAccent
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.EntityIconContent
import com.tvassist.ui.PremiumIconButton
import com.tvassist.ui.PremiumRow
import com.tvassist.ui.TvTextField
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary
import com.tvassist.ui.domainIcon
import com.tvassist.ui.entities.CategoryHeader
import com.tvassist.ui.entities.categoryLabel

/**
 * Picks one or more entities from the IMPORTED pool (the overlay is built from entities you've
 * already imported on Home). Multi-select: tapping a row toggles it; the "Add (N)" button commits
 * every selection at once via [onAdd]. Searchable within the pool.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LayoutEntityPicker(
    viewModel: ConnectionViewModel,
    onAdd: (List<String>) -> Unit,
    onBack: () -> Unit,
    filter: ((Entity) -> Boolean)? = null,
    title: String = "Add entities to row",
    // Multi-select accumulates picks behind an "Add (N)" button; single-select commits on tap
    // (used where exactly one entity makes sense, e.g. choosing a mirror source).
    multiSelect: Boolean = true,
) {
    val entities by viewModel.entities.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val searching = query.isNotBlank()
    val groups = remember(entities, query, filter) {
        val q = query.trim().lowercase()
        val pool = if (filter != null) entities.filter(filter) else entities
        val filtered = if (q.isEmpty()) pool else pool.filter { it.nameLower.contains(q) || it.idLower.contains(q) }
        groupEntitiesByCategory(filtered)
    }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // Ids picked so far (order preserved so they're added in the order tapped).
    val selected = remember { mutableStateListOf<String>() }

    Column(modifier = Modifier.fillMaxSize().padding(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconButton(Icons.Rounded.ChevronLeft, "Back", onBack)
            Spacer(Modifier.width(16.dp))
            Text(title, fontSize = 28.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (multiSelect && selected.isNotEmpty()) {
                AccentButton("Add (${selected.size})", { onAdd(selected.toList()) }, leadingIcon = Icons.Rounded.Add)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (multiSelect) "Tap entities to select several, then press Add. Import more from the Home screen."
            else "Choose an entity. Import more from the Home screen.",
            fontSize = 13.sp, color = TxtMuted,
        )
        Spacer(Modifier.height(12.dp))
        TvTextField(value = query, onValueChange = { query = it }, placeholder = "Search imported entities…")
        Spacer(Modifier.height(12.dp))
        when {
            entities.isEmpty() -> Text(
                "No imported entities yet — import some from the Home screen first.",
                color = Color(0xFFBBBBBB), fontSize = 16.sp,
            )
            groups.isEmpty() -> Text("No imported entity matches \"$query\".", color = Color(0xFFBBBBBB), fontSize = 16.sp)
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groups.forEach { (domain, list) ->
                    val isExpanded = searching || (expanded[domain] ?: true)
                    item(key = "h_$domain") {
                        CategoryHeader(categoryLabel(domain), list.size, isExpanded) {
                            expanded[domain] = !isExpanded
                        }
                    }
                    if (isExpanded) {
                        items(list, key = { it.entityId }) { entity ->
                            val isSel = entity.entityId in selected
                            PremiumRow(
                                icon = domainIcon(entity),
                                iconContent = { tint -> EntityIconContent(entity, null, tint, repository = viewModel.repository) },
                                title = entity.friendlyName,
                                subtitle = entity.entityId,
                                onClick = {
                                    if (!multiSelect) {
                                        onAdd(listOf(entity.entityId))
                                    } else if (isSel) {
                                        selected.remove(entity.entityId)
                                    } else {
                                        selected.add(entity.entityId)
                                    }
                                },
                                modifier = Modifier.padding(start = 20.dp),
                                trailing = {
                                    when {
                                        !multiSelect -> Icon(Icons.Rounded.Add, contentDescription = "Add", tint = AppAccent, modifier = Modifier.size(20.dp))
                                        isSel -> Icon(Icons.Rounded.CheckCircle, contentDescription = "Selected", tint = AppAccent, modifier = Modifier.size(22.dp))
                                        else -> Icon(Icons.Rounded.RadioButtonUnchecked, contentDescription = "Tap to select", tint = TxtMuted, modifier = Modifier.size(22.dp))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Settings screen: trigger key capture + backup & restore
// ---------------------------------------------------------------------------------------
