package com.tvassist.ui.entities

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Icon
import com.tvassist.ui.groupEntitiesByCategory
import com.tvassist.ui.cards.displayIcon
import com.tvassist.ui.cards.displayName
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.EntityIconContent
import com.tvassist.ui.PremiumIconButton
import com.tvassist.ui.PremiumRow
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary
import com.tvassist.ui.cards.displayIcon
import com.tvassist.ui.cards.displayName

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CustomizeScreen(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val entities by viewModel.entities.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val overrides = settings.entityOverrides
    var editingId by remember { mutableStateOf<String?>(null) }

    val editing = editingId?.let { id -> entities.firstOrNull { it.entityId == id } }
    if (editing != null) {
        BackHandler { editingId = null }
        EntityEditorScreen(
            entity = editing,
            override = overrides[editing.entityId],
            allEntities = entities,
            viewModel = viewModel,
            onSave = { viewModel.setEntityOverride(it) },
            onBack = { editingId = null },
        )
        return
    }

    val groups = remember(entities) { groupEntitiesByCategory(entities) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    Column(modifier = Modifier.fillMaxSize().padding(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconButton(Icons.Rounded.ChevronLeft, "Back", onBack)
            Spacer(Modifier.width(16.dp))
            Text("Customize entities", fontSize = 28.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Text("Edit an entity's name, icon and what a press does.", fontSize = 13.sp, color = TxtMuted)
        Spacer(Modifier.height(12.dp))
        if (entities.isEmpty()) {
            Text("No imported entities yet — import some from the Home screen first.",
                color = Color(0xFFBBBBBB), fontSize = 16.sp)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { (domain, list) ->
                    val isExpanded = expanded[domain] ?: true
                    item(key = "h_$domain") {
                        CategoryHeader(categoryLabel(domain), list.size, isExpanded) {
                            expanded[domain] = !isExpanded
                        }
                    }
                    if (isExpanded) {
                        items(list, key = { it.entityId }) { e ->
                            val ov = overrides[e.entityId]
                            PremiumRow(
                                icon = displayIcon(e, ov),
                                iconContent = { tint -> EntityIconContent(e, ov, tint, repository = viewModel.repository) },
                                title = displayName(e, ov),
                                subtitle = e.entityId,
                                onClick = { editingId = e.entityId },
                                modifier = Modifier.padding(start = 20.dp),
                                trailing = {
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TxtMuted, modifier = Modifier.size(20.dp))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
