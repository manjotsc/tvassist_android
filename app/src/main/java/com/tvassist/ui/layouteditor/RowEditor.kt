package com.tvassist.ui.layouteditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import com.tvassist.data.settings.OverlayRow
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.AppAccent
import com.tvassist.ui.CardBg
import com.tvassist.ui.CardFocusBg
import com.tvassist.ui.ChipDim
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ColorControlRow
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.IconBtn
import com.tvassist.ui.TvTextField
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary
import com.tvassist.ui.entities.PILL_ICON_SWATCHES

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RowEditor(
    index: Int,
    row: OverlayRow,
    nameOf: (String) -> String,
    /** The chip text for a tile: the style it actually renders as, not always the stored one. */
    styleLabelOf: (OverlayTile) -> String,
    isPersonOf: (String) -> Boolean,
    /** HA-tile feature picker: (type, label) for whatever this entity supports. */
    iconOf: (String) -> ImageVector,
    viewModel: ConnectionViewModel,
    onAddEntity: () -> Unit,
    onAddPill: () -> Unit,
    onRemovePill: (Int) -> Unit,
    onTogglePill: (Int, String) -> Unit,
    onSetPillColor: (Int, Int) -> Unit,
) {
    // Which pill's icon-color editor is expanded (ColorControlRow keys off its label).
    var pillColorExpanded by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF20262D)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.isHeader) {
                CardIdentity(Icons.Rounded.TextFields, "Header", "Title and optional pills")
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    TvTextField(row.title, { viewModel.setRowTitle(index, it) }, "Section title")
                }
            } else {
                CardIdentity(Icons.Rounded.Layers, "Row ${index + 1}", "Main content rows")
                Spacer(Modifier.weight(1f))
                Text("Columns", fontSize = 11.sp, color = Color(0xFFAAAAAA))
                Spacer(Modifier.width(6.dp))
                IconBtn(Icons.Rounded.Remove, "Fewer columns", dense = true) { viewModel.setRowColumns(index, row.columns - 1) }
                Spacer(Modifier.width(8.dp))
                Text("${row.columns}", fontSize = 15.sp, color = Color.White)
                Spacer(Modifier.width(8.dp))
                IconBtn(Icons.Rounded.Add, "More columns", dense = true) { viewModel.setRowColumns(index, row.columns + 1) }
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.width(8.dp))
            IconBtn(Icons.Rounded.KeyboardArrowUp, "Move row up", dense = true) { viewModel.moveLayoutRow(index, up = true) }
            Spacer(Modifier.width(4.dp))
            IconBtn(Icons.Rounded.KeyboardArrowDown, "Move row down", dense = true) { viewModel.moveLayoutRow(index, up = false) }
            Spacer(Modifier.width(4.dp))
            IconBtn(Icons.Rounded.DeleteOutline, "Delete row", dense = true) { viewModel.removeLayoutRow(index) }
        }

        if (row.isHeader) {
            // Live pills (temperature/humidity/any sensor) shown on the header; each pill
            // chooses whether to show its icon, name and state.
            Spacer(Modifier.height(8.dp))
            Text("Pills", fontSize = 11.sp, color = TxtMuted)
            Spacer(Modifier.height(5.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                row.pills.forEachIndexed { pi, pill ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBg).padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(nameOf(pill.entityId), fontSize = 12.sp, color = TxtPrimary, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
                            IconBtn(Icons.Rounded.Close, "Remove pill", dense = true) { onRemovePill(pi) }
                        }
                        Spacer(Modifier.height(5.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChipButton("Icon", selected = pill.showIcon, onClick = { onTogglePill(pi, "icon") }, dense = true)
                            ChipButton("Name", selected = pill.showName, onClick = { onTogglePill(pi, "name") }, dense = true)
                            ChipButton("State", selected = pill.showState, onClick = { onTogglePill(pi, "state") }, dense = true)
                        }
                        // Icon tint (only relevant when the icon is shown). "Default" clears it.
                        if (pill.showIcon) {
                            Spacer(Modifier.height(8.dp))
                            ColorControlRow(
                                label = "Icon color ${pi + 1}",
                                argb = if (pill.iconColor != 0) pill.iconColor else 0xFF8A94A3.toInt(),
                                swatches = PILL_ICON_SWATCHES,
                                expandedKey = pillColorExpanded,
                                onExpand = { pillColorExpanded = it },
                                onChange = { onSetPillColor(pi, it) },
                                isUnset = pill.iconColor == 0,
                                onReset = { onSetPillColor(pi, 0) },
                            )
                        }
                    }
                }
                ChipButton("+ Add pill", selected = true, onClick = onAddPill, dense = true)
            }
        }

        if (!row.isHeader) {
            Spacer(Modifier.height(10.dp))
            val cols = row.columns.coerceIn(1, 12)
            // Tiles flow left-to-right into the column grid; a trailing "+" cell (-1)
            // shows where the next entity will land. Reorder with the ‹ › chevrons.
            val slots = row.tiles.indices.toList() + (-1)
            slots.chunked(cols).forEach { line ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    line.forEach { slot ->
                        if (slot == -1) {
                            AddCell(Modifier.weight(1f), onAddEntity)
                        } else {
                            TileCell(
                                modifier = Modifier.weight(1f),
                                name = nameOf(row.tiles[slot].entityId),
                                icon = iconOf(row.tiles[slot].entityId),
                                styleLabel = styleLabelOf(row.tiles[slot]),
                                showName = !row.tiles[slot].hideName,
                                showStatus = !row.tiles[slot].hideStatus,
                                showIcon = !row.tiles[slot].hideIcon,
                                isPerson = isPersonOf(row.tiles[slot].entityId),
                                personOptions = row.tiles[slot].personOptions,
                                mapProvider = row.tiles[slot].mapProvider,
                                onCycleStyle = { viewModel.cycleTileStyle(index, slot) },
                                onToggleName = { viewModel.toggleTileName(index, slot) },
                                onToggleStatus = { viewModel.toggleTileStatus(index, slot) },
                                onToggleIcon = { viewModel.toggleTileIcon(index, slot) },
                                onTogglePersonOption = { key -> viewModel.toggleTilePersonOption(index, slot, key) },
                                onSetMapProvider = { p -> viewModel.setTileMapProvider(index, slot, p) },
                                onEarlier = { viewModel.moveTile(index, slot, up = true) },
                                onLater = { viewModel.moveTile(index, slot, up = false) },
                                onRemove = { viewModel.removeTile(index, slot) },
                            )
                        }
                    }
                    repeat(cols - line.size) { Box(Modifier.weight(1f)) {} }
                }
            }
        }
    }
}

/**
 * A section heading inside [TileCell].
 *
 * The cell had grown to six groups of chips separated by nothing but a 11sp grey word, so the eye
 * had no way to tell where "Features" stopped and "Features position" began — it read as one wall
 * of pills. A rule above the label does the separating; the label itself only names the group.
 */
@Composable
private fun CellSection(title: String, first: Boolean = false) {
    if (!first) {
        Spacer(Modifier.height(12.dp))
        HairLine()
        Spacer(Modifier.height(10.dp))
    }
    Text(title, fontSize = 11.sp, color = TxtMuted, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
}

/** The 1dp rule that separates sections and columns. One definition so they cannot drift apart. */
@Composable
private fun HairLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(TxtMuted.copy(alpha = 0.18f)))
}

/**
 * The rounded, tinted icon square that fronts a card — the entity's own glyph for a tile, a
 * section glyph for a row.
 *
 * It is what makes a list of cards scannable without reading any of them: a bulb, a thermostat and
 * a camera are told apart at a glance where three identical text rows are not.
 */
@Composable
private fun IconBadge(icon: ImageVector, tint: Color = AppAccent, size: Int = 34) {
    Box(
        modifier = Modifier.size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** A card's identity line: badge, then a title with a quieter subtitle under it. */
@Composable
private fun CardIdentity(icon: ImageVector, title: String, subtitle: String, tint: Color = AppAccent) {
    IconBadge(icon, tint)
    Spacer(Modifier.width(10.dp))
    Column {
        Text(title, fontSize = 14.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (subtitle.isNotBlank()) {
            Text(subtitle, fontSize = 11.sp, color = TxtMuted, maxLines = 1)
        }
    }
}

/**
 * A "pick exactly one" group, drawn on its own recessed strip.
 *
 * Multi-select toggles (Icon/Name/Status) and single-select options (Bottom/Inline) were the same
 * pill in the same row, so nothing said that turning one on turns another off. The strip is the
 * cue, and it costs no extra focus stops on the remote.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SegmentedChips(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(ChipDim.copy(alpha = 0.5f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** The chip grouping the cell uses, so spacing cannot drift between its sections. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** One filled cell in the row grid: entity name + style chip + reorder/remove. */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TileCell(
    modifier: Modifier,
    name: String,
    icon: ImageVector,
    styleLabel: String,
    showName: Boolean,
    showStatus: Boolean,
    showIcon: Boolean,
    isPerson: Boolean,
    personOptions: List<String>,
    mapProvider: String,
    onCycleStyle: () -> Unit,
    onToggleName: () -> Unit,
    onToggleStatus: () -> Unit,
    onToggleIcon: () -> Unit,
    onTogglePersonOption: (String) -> Unit,
    onSetMapProvider: (String) -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onRemove: () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(CardBg).padding(10.dp),
    ) {
        // When the cell is narrow (many columns), stack the style chip above the
        // reorder/remove buttons so the ✕ is never clipped off the right edge.
        val narrow = maxWidth < 250.dp
        Column {
            val controls: @Composable RowScope.() -> Unit = {
                IconBtn(Icons.Rounded.ChevronLeft, "Move earlier", dense = true, onClick = onEarlier)
                Spacer(Modifier.width(4.dp))
                IconBtn(Icons.Rounded.ChevronRight, "Move later", dense = true, onClick = onLater)
                Spacer(Modifier.width(4.dp))
                IconBtn(Icons.Rounded.Close, "Remove", dense = true, onClick = onRemove)
            }
            // Identity first: the entity's own glyph, its name, and the style it renders as. The
            // style stays a button rather than the static pill it looks like — pressing it still
            // cycles the tile style, which is the only way to change it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, size = 30)
                Spacer(Modifier.width(9.dp))
                Text(
                    name,
                    fontSize = 13.sp,
                    color = TxtPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = if (narrow) Modifier.weight(1f) else Modifier,
                )
                if (!narrow) {
                    Spacer(Modifier.width(8.dp))
                    ChipButton(styleLabel, selected = false, onClick = onCycleStyle, dense = true)
                    Spacer(Modifier.weight(1f))
                    controls()
                }
            }
            if (narrow) {
                Spacer(Modifier.height(6.dp))
                ChipButton(styleLabel, selected = false, onClick = onCycleStyle, dense = true)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, content = controls)
            }

            Spacer(Modifier.height(10.dp))
            HairLine()
            Spacer(Modifier.height(10.dp))

            // Per-tile visibility: accent = shown, dim = hidden.
            Text("Fields", fontSize = 11.sp, color = TxtMuted, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            ChipRow {
                ChipButton("Icon", selected = showIcon, onClick = onToggleIcon, dense = true)
                ChipButton("Name", selected = showName, onClick = onToggleName, dense = true)
                ChipButton("Status", selected = showStatus, onClick = onToggleStatus, dense = true)
            }

            // Person tiles: what the fullscreen map popup shows, and which map to draw it on.
            if (isPerson) {
                CellSection("Map shows")
                ChipRow {
                    OverlayTile.PERSON_OPTIONS_ALL.forEach { (key, label) ->
                        ChipButton(label, selected = key in personOptions, onClick = { onTogglePersonOption(key) }, dense = true)
                    }
                }
                CellSection("Map source")
                SegmentedChips {
                    OverlayTile.MAP_PROVIDERS.forEach { (key, label) ->
                        ChipButton(label, selected = key == mapProvider, onClick = { onSetMapProvider(key) }, dense = true)
                    }
                }
            }
        }
    }
}

/** An empty grid cell that adds an entity into the next open position. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AddCell(modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        // Subtle scale so a focused cell doesn't overlap its grid neighbours (default is 1.1x).
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF14181E),
            focusedContainerColor = CardFocusBg,
            pressedContainerColor = CardFocusBg,
            contentColor = TxtMuted,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.5.dp, AppAccent), shape = RoundedCornerShape(14.dp)),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "Add entity", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add", fontWeight = FontWeight.Medium)
        }
    }
}
