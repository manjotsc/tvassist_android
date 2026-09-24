package com.tvassist.ui.entities

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.ui.text.font.FontWeight
import com.tvassist.ui.cards.displayIcon
import com.tvassist.ui.cards.displayName
import com.tvassist.data.ha.Entity
import com.tvassist.data.settings.EntityOverride
import com.tvassist.data.settings.PressAction
import com.tvassist.ui.cards.DOUBLE_TAP_MS
import kotlinx.coroutines.delay
import com.tvassist.ui.AccentButton
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.EntityIconContent
import com.tvassist.ui.PremiumIconButton
import com.tvassist.ui.PremiumRow
import com.tvassist.ui.TvTextField
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary
import com.tvassist.ui.layouteditor.LayoutEntityPicker

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun EntityEditorScreen(
    entity: Entity,
    override: EntityOverride?,
    allEntities: List<Entity>,
    viewModel: ConnectionViewModel,
    onSave: (EntityOverride) -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember(entity.entityId) { mutableStateOf(override ?: EntityOverride(entity.entityId)) }
    // Persist is debounced so typing a name doesn't re-serialize + write DataStore on every
    // keystroke (which also re-emits settings and recomposes subscribers). `draft` stays the live
    // source for the preview; `saved` tracks what's on disk so we don't write no-op duplicates.
    var saved by remember(entity.entityId) { mutableStateOf(override ?: EntityOverride(entity.entityId)) }
    val latestSave by rememberUpdatedState(onSave)
    fun update(next: EntityOverride) { draft = next }
    LaunchedEffect(draft) {
        if (draft == saved) return@LaunchedEffect
        delay(350)
        latestSave(draft)
        saved = draft
    }
    // Flush an in-flight edit if the user leaves before the debounce fires.
    DisposableEffect(entity.entityId) {
        onDispose { if (draft != saved) latestSave(draft) }
    }

    // Picking the "mirror" source entity takes over the screen (reuses the layout picker).
    var mirrorPicker by remember { mutableStateOf(false) }
    if (mirrorPicker) {
        BackHandler { mirrorPicker = false }
        LayoutEntityPicker(
            viewModel = viewModel,
            multiSelect = false,
            title = "Choose mirror source",
            onAdd = { ids -> ids.firstOrNull()?.let { update(draft.copy(mirrorEntityId = it)) }; mirrorPicker = false },
            onBack = { mirrorPicker = false },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconButton(Icons.Rounded.ChevronLeft, "Back", onBack)
            Spacer(Modifier.width(16.dp))
            Text("Edit entity", fontSize = 26.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(18.dp))

        // Live preview of how the entity will look.
        PremiumRow(
            icon = displayIcon(entity, draft),
            iconContent = { tint -> EntityIconContent(entity, draft, tint, repository = viewModel.repository) },
            title = displayName(entity, draft),
            subtitle = entity.entityId,
            onClick = {},
            trailing = { Text(entity.state, fontSize = 13.sp, color = TxtMuted) },
        )
        Spacer(Modifier.height(24.dp))

        Text("Name", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(6.dp))
        TvTextField(value = draft.name, onValueChange = { update(draft.copy(name = it)) }, placeholder = entity.friendlyName)
        Spacer(Modifier.height(22.dp))

        Text("Icon", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(8.dp))
        IconPickerSection(entity, draft, viewModel.repository) { update(draft.copy(icon = it)) }
        Spacer(Modifier.height(22.dp))

        Text("Single press", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PressAction.forEntityId(entity.entityId).forEach { a ->
                ChipButton(PressAction.label(a), draft.singlePress == a, onClick = { update(draft.copy(singlePress = a)) })
            }
        }
        Spacer(Modifier.height(18.dp))

        Text("Long press", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PressAction.forEntityId(entity.entityId).forEach { a ->
                ChipButton(PressAction.label(a), draft.longPress == a, onClick = { update(draft.copy(longPress = a)) })
            }
        }
        Spacer(Modifier.height(18.dp))

        Text("Double press", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(2.dp))
        // Worth saying out loud: this is the one press setting that costs something to leave set,
        // because a single press cannot fire until the tile knows no second one is coming.
        Text(
            "Set this and single presses on this entity's tiles wait ${DOUBLE_TAP_MS}ms first.",
            fontSize = 11.sp,
            color = TxtMuted,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // "Nothing" is the default here rather than "Default", so it leads.
            (listOf(PressAction.NONE) + PressAction.forEntityId(entity.entityId).filter { it != PressAction.NONE })
                .forEach { a ->
                    ChipButton(PressAction.label(a), draft.doublePress == a, onClick = { update(draft.copy(doublePress = a)) })
                }
        }
        Spacer(Modifier.height(18.dp))

        Text("Highlight state", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(2.dp))
        Text(
            "How the tile shows on/off. Use Always on/off for stateless buttons (input_button, IR, scripts).",
            fontSize = 12.sp, color = TxtMuted,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.tvassist.data.settings.DisplayState.ALL.forEach { d ->
                ChipButton(com.tvassist.data.settings.DisplayState.label(d), draft.displayState == d, onClick = { update(draft.copy(displayState = d)) })
            }
        }
        if (draft.displayState == com.tvassist.data.settings.DisplayState.MIRROR) {
            Spacer(Modifier.height(14.dp))
            Text("Mirror source", fontSize = 14.sp, color = TxtMuted)
            Spacer(Modifier.height(2.dp))
            Text("This tile's on/off follows the chosen entity.", fontSize = 12.sp, color = TxtMuted)
            Spacer(Modifier.height(8.dp))
            val srcName = draft.mirrorEntityId.takeIf { it.isNotBlank() }
                ?.let { id -> allEntities.firstOrNull { it.entityId == id }?.friendlyName ?: id }
            AccentButton(srcName ?: "Choose entity…", { mirrorPicker = true })
            Spacer(Modifier.height(14.dp))
            Text("On threshold (optional)", fontSize = 14.sp, color = TxtMuted)
            Spacer(Modifier.height(2.dp))
            Text(
                "Blank = follow the source's on/off. A number = on when the source's value is at least " +
                    "this (e.g. watts for a power sensor).",
                fontSize = 12.sp, color = TxtMuted,
            )
            Spacer(Modifier.height(8.dp))
            var thrText by remember(entity.entityId, draft.mirrorEntityId) {
                mutableStateOf(draft.mirrorThreshold?.let { thresholdText(it) } ?: "")
            }
            TvTextField(
                value = thrText,
                onValueChange = { t -> thrText = t; update(draft.copy(mirrorThreshold = t.trim().toDoubleOrNull())) },
                placeholder = "e.g. 5",
            )
        }
        Spacer(Modifier.height(26.dp))
        AccentButton("Reset to defaults", { update(EntityOverride(entity.entityId)) }, leadingIcon = Icons.Rounded.Refresh)
    }
}

/** Format a mirror threshold for the editor field: drop the trailing ".0" for whole numbers. */
internal fun thresholdText(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

/** Preset icon tints offered for header pills (white, muted, accent, amber, green, red, cyan, purple). */
internal val PILL_ICON_SWATCHES = listOf(
    0xFFFFFFFF.toInt(), 0xFF8A94A3.toInt(), 0xFF5C7CFA.toInt(), 0xFFF39C12.toInt(),
    0xFF6FCF7F.toInt(), 0xFFE55B5B.toInt(), 0xFF00BCD4.toInt(), 0xFFB06FE0.toInt(),
)
