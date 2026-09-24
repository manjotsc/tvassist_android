package com.tvassist.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.ui.text.font.FontWeight
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.AccentButton
import com.tvassist.ui.CardBg
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.IconBtn
import com.tvassist.ui.layouteditor.LayoutEntityPicker
import com.tvassist.ui.TvTextField
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MapsPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val entities by viewModel.entities.collectAsStateWithLifecycle()
    val hasKey = settings.googleMapsApiKey.isNotBlank()
    val mapCards = settings.mapCards
    fun nameOf(id: String) = entities.firstOrNull { it.entityId == id }?.friendlyName ?: id

    // The map card being added/edited (null = collapsed) and whether the member picker is showing.
    var draft by remember { mutableStateOf<com.tvassist.data.settings.MapCard?>(null) }
    var pickingMember by remember { mutableStateOf(false) }

    val d = draft
    if (pickingMember && d != null) {
        BackHandler { pickingMember = false }
        LayoutEntityPicker(
            viewModel = viewModel,
            filter = { it.isPerson },
            title = "Add people to map card",
            onAdd = { ids ->
                val fresh = ids.filter { id -> d.members.none { it.entityId == id } }
                    .map { com.tvassist.data.settings.MapCardMember(it) }
                draft = d.copy(members = d.members + fresh)
                pickingMember = false
            },
            onBack = { pickingMember = false },
        )
        return
    }

    PageScaffold("Maps", onBack) {
        // ---- Map cards: saved multi-entity location maps ----
        Text("Map cards", fontSize = 16.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "A map card plots several people/device-trackers on one map, centered on your home zone. " +
                "Each card shows up as an entity you can add to the overlay like any other.",
            color = TxtMuted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))

        mapCards.forEach { card ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(card.name.ifBlank { "(unnamed)" }, color = TxtPrimary, fontSize = 15.sp, maxLines = 1)
                    Text(
                        if (card.members.size == 1) "1 person" else "${card.members.size} people",
                        color = TxtMuted, fontSize = 12.sp, maxLines = 1,
                    )
                }
                ChipButton("Edit", selected = d?.id == card.id, onClick = { draft = card })
                Spacer(Modifier.width(8.dp))
                ChipButton("Delete", selected = false, onClick = {
                    viewModel.deleteMapCard(card.id)
                    if (d?.id == card.id) draft = null
                })
            }
        }

        Spacer(Modifier.height(12.dp))
        if (d == null) {
            AccentButton(
                "Add map card",
                {
                    draft = com.tvassist.data.settings.MapCard(
                        id = "map_" + System.currentTimeMillis().toString(36),
                        name = "",
                    )
                },
                leadingIcon = Icons.Rounded.Add,
            )
        } else {
            MapCardEditor(
                draft = d,
                isNew = mapCards.none { it.id == d.id },
                nameOf = ::nameOf,
                onChange = { draft = it },
                onAddMember = { pickingMember = true },
                onSave = {
                    if (d.name.isNotBlank() && d.members.isNotEmpty()) {
                        viewModel.saveMapCard(d)
                        draft = null
                    }
                },
                onCancel = { draft = null },
            )
        }

        Spacer(Modifier.height(28.dp))

        // ---- Map source (global tiles config) ----
        Text("Map source", fontSize = 16.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Applies to all person/map tiles. Leave the key blank for free OpenStreetMap; add a Google " +
                "Maps Platform API key for Google tiles + live traffic (Google bills per map load).",
            color = TxtMuted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasKey) "✓ Key set — using Google tiles" else "No key — using OpenStreetMap",
            color = if (hasKey) Color(0xFF6FCF7F) else TxtMuted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))

        WebSetupRow(viewModel, "Maps")
        Spacer(Modifier.height(18.dp))

        Text("API key", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(2.dp))
        Text(
            if (settings.googleMapsApiKey.isNotBlank()) {
                "A key is set (using Google tiles). Manage it in Settings → Security."
            } else {
                "No key set (using OpenStreetMap). Add a Google Maps key in Settings → Security for Google tiles."
            },
            color = TxtMuted, fontSize = 12.sp,
        )

        if (hasKey) {
            Spacer(Modifier.height(18.dp))
            Text("Map style", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("roadmap" to "Roadmap", "satellite" to "Satellite").forEach { (value, label) ->
                    ChipButton(label, settings.mapStyle == value, onClick = { viewModel.setMapStyle(value) })
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Live traffic overlay", color = TxtPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                ChipButton(
                    if (settings.mapTraffic) "On" else "Off",
                    selected = settings.mapTraffic,
                    onClick = { viewModel.setMapTraffic(!settings.mapTraffic) },
                )
            }
        }
    }
}

/** Add/edit form for a single [com.tvassist.data.settings.MapCard]. */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MapCardEditor(
    draft: com.tvassist.data.settings.MapCard,
    isNew: Boolean,
    nameOf: (String) -> String,
    onChange: (com.tvassist.data.settings.MapCard) -> Unit,
    onAddMember: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).padding(12.dp)) {
        Text(if (isNew) "New map card" else "Edit map card", color = TxtPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("Name", color = TxtMuted, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        TvTextField(draft.name, { onChange(draft.copy(name = it)) }, "Family")
        Spacer(Modifier.height(12.dp))

        Text("Zoom", color = TxtMuted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Rounded.Remove, "Zoom out", dense = true) { onChange(draft.copy(mapZoom = (draft.mapZoom - 1).coerceIn(3, 20))) }
            Spacer(Modifier.width(10.dp))
            Text("${draft.mapZoom}", fontSize = 15.sp, color = Color.White)
            Spacer(Modifier.width(10.dp))
            IconBtn(Icons.Rounded.Add, "Zoom in", dense = true) { onChange(draft.copy(mapZoom = (draft.mapZoom + 1).coerceIn(3, 20))) }
        }
        Spacer(Modifier.height(12.dp))

        Text("Map source", color = TxtMuted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OverlayTile.MAP_PROVIDERS.forEach { (key, label) ->
                ChipButton(label, draft.mapProvider == key, onClick = { onChange(draft.copy(mapProvider = key)) }, dense = true)
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Legend", color = TxtPrimary, fontSize = 14.sp)
                Text("Side list of members on the map", color = TxtMuted, fontSize = 12.sp)
            }
            ChipButton(
                if (draft.showLegend) "On" else "Off",
                selected = draft.showLegend,
                onClick = { onChange(draft.copy(showLegend = !draft.showLegend)) },
            )
        }
        Spacer(Modifier.height(14.dp))

        Text("People / devices on this map", color = TxtMuted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            draft.members.forEachIndexed { i, m ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF14181E)).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(nameOf(m.entityId), fontSize = 13.sp, color = TxtPrimary, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
                        IconBtn(Icons.Rounded.Close, "Remove", dense = true) {
                            onChange(draft.copy(members = draft.members.filterIndexed { idx, _ -> idx != i }))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Legend shows", fontSize = 11.sp, color = TxtMuted)
                    Spacer(Modifier.height(5.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OverlayTile.PERSON_OPTIONS_ALL.forEach { (key, label) ->
                            val on = key in m.options
                            ChipButton(label, selected = on, dense = true, onClick = {
                                val nextOpts = if (on) m.options - key else m.options + key
                                onChange(draft.copy(members = draft.members.mapIndexed { idx, mm -> if (idx == i) mm.copy(options = nextOpts) else mm }))
                            })
                        }
                    }
                }
            }
            ChipButton("+ Add person", selected = true, onClick = onAddMember, dense = true)
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentButton("Save", onSave, leadingIcon = Icons.Rounded.Add)
            Spacer(Modifier.width(10.dp))
            ChipButton("Cancel", selected = false, onClick = onCancel)
        }
    }
}
