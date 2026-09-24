package com.tvassist.ui.entities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.font.FontWeight
import com.tvassist.data.ha.ConnectionState
import com.tvassist.data.ha.Entity
import com.tvassist.ui.AppAccent
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.EntityIconContent
import com.tvassist.ui.PremiumIconButton
import com.tvassist.ui.PremiumRow
import com.tvassist.ui.TvTextField
import com.tvassist.ui.domainIcon

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ImportScreen(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val all by viewModel.importerAll.collectAsStateWithLifecycle()
    val loading by viewModel.importerLoading.collectAsStateWithLifecycle()
    val query by viewModel.importSearch.collectAsStateWithLifecycle()
    val groups by viewModel.importerGroups.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val imported = settings.importedEntityIds.toSet()

    // Load the full catalogue once we're connected (and reload on reconnect).
    LaunchedEffect(connection) {
        if (connection is ConnectionState.Connected) viewModel.loadImporter()
    }

    // Categories are collapsed by default so we render only ~a dozen headers, not every
    // entity. A search auto-expands every matching category so results stay visible.
    val searching = query.isNotBlank()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Column(modifier = Modifier.fillMaxSize().padding(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconButton(Icons.Rounded.ChevronLeft, "Done", onBack)
            Spacer(Modifier.width(16.dp))
            Text("Import entities", fontSize = 28.sp, color = Color.White)
            Spacer(Modifier.width(16.dp))
            Text("${imported.size} imported", fontSize = 14.sp, color = Color(0xFFFFC107))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Pick a category to expand it, or search by name.",
            fontSize = 13.sp,
            color = Color(0xFF999999),
        )
        Spacer(Modifier.height(12.dp))
        TvTextField(value = query, onValueChange = viewModel::setImportSearch, placeholder = "Search all entities…")
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Text("Loading entities from Home Assistant…", color = Color(0xFFBBBBBB), fontSize = 16.sp)
            all.isEmpty() -> Text("No entities found (is Home Assistant connected?).", color = Color(0xFFBBBBBB), fontSize = 16.sp)
            groups.isEmpty() -> Text("No entities match \"$query\".", color = Color(0xFFBBBBBB), fontSize = 16.sp)
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groups.forEach { (domain, list) ->
                    // While searching, force every matching category open.
                    val isExpanded = searching || (expanded[domain] ?: false)
                    item(key = "hdr_$domain") {
                        CategoryHeader(categoryLabel(domain), list.size, isExpanded) {
                            expanded[domain] = !isExpanded
                        }
                    }
                    if (isExpanded) {
                        items(list, key = { it.entityId }) { entity ->
                            ImportRow(
                                entity = entity,
                                isImported = entity.entityId in imported,
                                repository = viewModel.repository,
                                onToggle = { viewModel.toggleImport(entity.entityId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ImportRow(
    entity: Entity,
    isImported: Boolean,
    repository: com.tvassist.data.ha.HaRepository,
    onToggle: () -> Unit,
) {
    PremiumRow(
        icon = domainIcon(entity),
        iconContent = { tint -> EntityIconContent(entity, null, tint, repository = repository) },
        title = entity.friendlyName,
        subtitle = entity.entityId,
        onClick = onToggle,
        modifier = Modifier.padding(start = 20.dp),
        trailing = {
            Text(
                text = if (isImported) "✓ Imported" else "Add",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (isImported) Color(0xFF6FCF7F) else AppAccent,
            )
        },
    )
}

// ---------------------------------------------------------------------------------------
// Customize entities: per-entity name, icon, single/long press action
// ---------------------------------------------------------------------------------------
