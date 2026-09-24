package com.tvassist.ui.layouteditor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.domainIcon
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.ui.text.font.FontWeight
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.cards.shownTileStyle
import com.tvassist.overlay.OverlayService
import com.tvassist.ui.AccentButton
import com.tvassist.ui.AddButton
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.PremiumIconButton
import com.tvassist.ui.TxtPrimary

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LayoutEditorScreen(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val entities by viewModel.entities.collectAsStateWithLifecycle()
    val layout = settings.overlayLayout
    val context = LocalContext.current

    var pickerForRow by remember { mutableStateOf<Int?>(null) }
    if (pickerForRow != null) {
        BackHandler { pickerForRow = null }
        LayoutEntityPicker(
            viewModel = viewModel,
            onAdd = { ids ->
                pickerForRow?.let { r -> viewModel.addTilesToRow(r, ids) }
                pickerForRow = null
            },
            onBack = { pickerForRow = null },
        )
        return
    }

    var pillPickerForRow by remember { mutableStateOf<Int?>(null) }
    if (pillPickerForRow != null) {
        BackHandler { pillPickerForRow = null }
        LayoutEntityPicker(
            viewModel = viewModel,
            title = "Add pills to header",
            onAdd = { ids ->
                pillPickerForRow?.let { r -> viewModel.addHeaderPills(r, ids) }
                pillPickerForRow = null
            },
            onBack = { pillPickerForRow = null },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconButton(Icons.Rounded.ChevronLeft, "Back", onBack)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Overlay Layout", fontSize = 26.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    "Build your overlay from sections, entity rows and tiles.",
                    fontSize = 13.sp,
                    color = TxtMuted,
                )
            }
            AccentButton("Preview", { OverlayService.show(context) })
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AddButton("Entity row") { viewModel.addLayoutRow(header = false) }
            AddButton("Header") { viewModel.addLayoutRow(header = true) }
        }
        Spacer(Modifier.height(16.dp))

        if (layout.rows.isEmpty()) {
            Text(
                "No rows yet. Add an entity row, then add entities to it. " +
                    "Press Preview to see the overlay.",
                color = Color(0xFFBBBBBB),
                fontSize = 15.sp,
            )
        } else {
            layout.rows.forEachIndexed { i, row ->
                RowEditor(
                    index = i,
                    row = row,
                    nameOf = { id -> entities.firstOrNull { it.entityId == id }?.friendlyName ?: id },
                    styleLabelOf = { tile ->
                        OverlayTile.label(shownTileStyle(entities.firstOrNull { it.entityId == tile.entityId }, tile.style))
                    },
                    isPersonOf = { id -> entities.firstOrNull { it.entityId == id }?.isPerson == true },
                    // The tile's own glyph, so a list of cards is scannable without reading it.
                    iconOf = { id ->
                        entities.firstOrNull { it.entityId == id }?.let { domainIcon(it) }
                            ?: Icons.Rounded.Widgets
                    },
                    viewModel = viewModel,
                    onAddEntity = { pickerForRow = i },
                    onAddPill = { pillPickerForRow = i },
                    onRemovePill = { p -> viewModel.removeHeaderPill(i, p) },
                    onTogglePill = { p, field -> viewModel.togglePillField(i, p, field) },
                    onSetPillColor = { p, color -> viewModel.setPillIconColor(i, p, color) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
