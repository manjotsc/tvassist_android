package com.tvassist.ui.entities

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import com.tvassist.data.ha.Entity
import com.tvassist.data.settings.EntityOverride
import kotlinx.coroutines.delay
import com.tvassist.ui.AppAccent
import com.tvassist.ui.CardFocusBg
import com.tvassist.ui.ChipDim
import com.tvassist.ui.EntityIconContent
import com.tvassist.ui.IconStore
import com.tvassist.ui.IconifyIcon
import com.tvassist.ui.TvTextField
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary

/**
 * Searchable icon picker for the entity editor. "Default" uses Home Assistant's own icon (or a
 * domain guess); typing searches the Iconify catalog (MDI first, like HA) and renders results
 * as tinted PNGs. The chosen icon is stored as an Iconify name, e.g. "mdi:ceiling-light".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconPickerSection(
    entity: Entity,
    draft: EntityOverride,
    repository: com.tvassist.data.ha.HaRepository,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        delay(300)
        results = IconStore.search(query)
        searching = false
    }

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "Default" → fall back to HA's icon / domain guess (no override icon).
        IconPickerCell(selected = draft.icon.isBlank(), onClick = { onPick("") }) { tint ->
            EntityIconContent(entity, EntityOverride(entity.entityId), tint, 26, repository = repository)
        }
        Text("Default", fontSize = 12.sp, color = TxtMuted)
        // Keep the currently-chosen custom icon visible even if it's not in the latest results.
        if (draft.icon.contains(':')) {
            IconPickerCell(selected = true, onClick = {}) { tint -> IconifyIcon(draft.icon, tint, 26) {} }
        }
    }
    Spacer(Modifier.height(10.dp))
    TvTextField(value = query, onValueChange = { query = it }, placeholder = "Search icons (e.g. ceiling light)…")
    Spacer(Modifier.height(10.dp))
    when {
        searching -> Text("Searching…", fontSize = 13.sp, color = TxtMuted)
        query.isNotBlank() && results.isEmpty() -> Text("No icons match \"$query\".", fontSize = 13.sp, color = TxtMuted)
        results.isNotEmpty() -> FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            results.take(60).forEach { name ->
                IconPickerCell(selected = draft.icon == name, onClick = { onPick(name) }) { tint ->
                    IconifyIcon(name, tint, 26) {}
                }
            }
        }
        else -> Text(
            "Type to search thousands of icons (Home Assistant MDI + more).",
            fontSize = 12.sp, color = TxtMuted,
        )
    }
}

/** A selectable icon cell: renders [content] tinted, accent-filled when selected. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun IconPickerCell(selected: Boolean, onClick: () -> Unit, content: @Composable (tint: Color) -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AppAccent else ChipDim,
            focusedContainerColor = if (selected) AppAccent else CardFocusBg,
            pressedContainerColor = if (selected) AppAccent else CardFocusBg,
            contentColor = if (selected) Color.White else TxtPrimary,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.5.dp, AppAccent), shape = RoundedCornerShape(12.dp)),
        ),
    ) {
        Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
            content(if (selected) Color.White else TxtPrimary)
        }
    }
}

// ---------------------------------------------------------------------------------------
// Overlay layout editor: rows → columns → tiles, with a live Preview
// ---------------------------------------------------------------------------------------
