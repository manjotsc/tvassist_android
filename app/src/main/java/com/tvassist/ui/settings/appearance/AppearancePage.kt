package com.tvassist.ui.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import com.tvassist.ui.cards.ColorSliderRow
import com.tvassist.overlay.OverlayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.tvassist.ui.AppAccent
import com.tvassist.ui.CardBg
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ChipDim
import com.tvassist.ui.ColorControlRow
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.PremiumIconButton
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary
import com.tvassist.ui.settings.AppearanceRow
import com.tvassist.ui.settings.OptionChips

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AppearancePage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pos = com.tvassist.data.settings.OverlayPosition.fromName(settings.overlayPosition)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PremiumIconButton(Icons.Rounded.ChevronLeft, "Back", onBack)
                Spacer(Modifier.width(14.dp))
                Text("Appearance & timing", fontSize = 23.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                // Pop the real overlay over this screen for a few seconds so changes can be judged live.
                ChipButton("Preview", selected = false, dense = true, onClick = {
                    com.tvassist.overlay.OverlayService.show(context)
                    scope.launch { delay(4500); com.tvassist.overlay.OverlayService.hide(context) }
                })
                Spacer(Modifier.width(8.dp))
                ChipButton("Reset", selected = false, dense = true, onClick = { viewModel.resetAppearance() })
            }
            Spacer(Modifier.height(16.dp))

            val controls = @Composable { AppearanceControls(settings, viewModel) }
            val preview = @Composable { OverlayPreviewPane(settings, pos) }

            // Studio layout: the preview docks where the overlay actually docks (kept compact).
            when (pos) {
                com.tvassist.data.settings.OverlayPosition.RIGHT ->
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { controls() }
                        Box(Modifier.width(212.dp).fillMaxHeight()) { preview() }
                    }
                com.tvassist.data.settings.OverlayPosition.LEFT ->
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(Modifier.width(212.dp).fillMaxHeight()) { preview() }
                        Box(Modifier.weight(1f).fillMaxHeight()) { controls() }
                    }
                com.tvassist.data.settings.OverlayPosition.TOP ->
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.fillMaxWidth().height(120.dp)) { preview() }
                        Box(Modifier.fillMaxWidth().weight(1f)) { controls() }
                    }
                com.tvassist.data.settings.OverlayPosition.BOTTOM ->
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.fillMaxWidth().weight(1f)) { controls() }
                        Box(Modifier.fillMaxWidth().height(120.dp)) { preview() }
                    }
            }
        }
    }
}

/** The compact, scrollable left/side controls pane of the appearance studio. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceControls(
    settings: com.tvassist.data.settings.Settings,
    viewModel: ConnectionViewModel,
) {
    var advancedExpanded by remember { mutableStateOf(false) }
    var expandedColor by remember { mutableStateOf<String?>(null) }
    val onExpand: (String?) -> Unit = { expandedColor = it }
    Column(
        Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard("Theme") {
            Row(
                // Pad inside the scroll so the first/last card's focus scale + selection border
                // aren't clipped flush against the scroll viewport edges.
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 7.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                THEME_PRESETS.forEach { preset ->
                    val selected = settings.overlayBgColor == preset.bg.toInt() &&
                        settings.overlayTileColor == preset.tile.toInt() &&
                        settings.overlayAccentColor == preset.accent.toInt()
                    ThemeCard(preset, selected) {
                        viewModel.applyOverlayColors(
                            preset.bg.toInt(), preset.tile.toInt(), preset.accent.toInt(),
                            preset.border.toInt(), preset.borderOn, preset.iconOn.toInt(), preset.iconOff.toInt(),
                            preset.focus.toInt(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            ColorGroupLabel("Accent")
            ColorControlRow("Accent", settings.overlayAccentColor, ACCENT_SWATCHES, expandedColor, onExpand, viewModel::setOverlayAccentColor)
        }

        SectionCard("Advanced colors") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Fine-tune every color individually.",
                    color = TxtMuted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                ChipButton(if (advancedExpanded) "Hide" else "Customize", selected = advancedExpanded, onClick = { advancedExpanded = !advancedExpanded }, dense = true)
            }
            if (advancedExpanded) {
                Spacer(Modifier.height(16.dp))
                ColorGroupLabel("Panel")
                ColorControlRow("Background", settings.overlayBgColor, BG_SWATCHES, expandedColor, onExpand, viewModel::setOverlayBgColor)
                Spacer(Modifier.height(2.dp))
                ColorControlRow("Tile", settings.overlayTileColor, TILE_SWATCHES, expandedColor, onExpand, viewModel::setOverlayTileColor)
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Panel border", color = TxtPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    ChipButton(if (settings.overlayBorderEnabled) "On" else "Off", selected = settings.overlayBorderEnabled, onClick = { viewModel.setOverlayBorderEnabled(!settings.overlayBorderEnabled) }, dense = true)
                }
                if (settings.overlayBorderEnabled) {
                    Spacer(Modifier.height(2.dp))
                    ColorControlRow("Border color", settings.overlayBorderColor, BORDER_SWATCHES, expandedColor, onExpand, viewModel::setOverlayBorderColor)
                }
                Spacer(Modifier.height(16.dp))
                ColorGroupLabel("Icons")
                ColorControlRow("Icon · on", settings.overlayIconOnColor, ICON_ON_SWATCHES, expandedColor, onExpand, viewModel::setOverlayIconOnColor)
                Spacer(Modifier.height(2.dp))
                ColorControlRow("Icon · off", settings.overlayIconOffColor, ICON_OFF_SWATCHES, expandedColor, onExpand, viewModel::setOverlayIconOffColor)
                Spacer(Modifier.height(16.dp))
                ColorGroupLabel("Focus")
                ColorControlRow("Highlight", settings.overlayFocusColor, ACCENT_SWATCHES, expandedColor, onExpand, viewModel::setOverlayFocusColor)
            }
        }
        SectionCard("Shape & position") {
            AppearanceRow("Position") {
                OptionChips(
                    options = listOf(
                        com.tvassist.data.settings.OverlayPosition.RIGHT to "Right",
                        com.tvassist.data.settings.OverlayPosition.LEFT to "Left",
                    ),
                    selected = com.tvassist.data.settings.OverlayPosition.fromName(settings.overlayPosition),
                    onSelect = viewModel::setOverlayPosition,
                )
            }
            Spacer(Modifier.height(12.dp))
            val sliderTrack = Brush.horizontalGradient(listOf(ChipDim, AppAccent))
            ColorSliderRow(
                "Size", settings.overlaySizeScale.toDouble(), 50.0, 130.0, 5.0, sliderTrack,
                { "${it.roundToInt()}%" }, { viewModel.setOverlaySizeScale(it.roundToInt()) }, resetKey = "size",
            )
            Spacer(Modifier.height(8.dp))
            ColorSliderRow(
                "Corners", settings.overlayCornerRadius.toDouble(), 0.0, 40.0, 2.0, sliderTrack,
                { "${it.roundToInt()} dp" }, { viewModel.setOverlayCornerRadius(it.roundToInt()) }, resetKey = "corner",
            )
            Spacer(Modifier.height(8.dp))
            ColorSliderRow(
                "Margin", settings.overlayMargin.toDouble(), 8.0, 72.0, 4.0, sliderTrack,
                { "${it.roundToInt()} dp" }, { viewModel.setOverlayMargin(it.roundToInt()) }, resetKey = "margin",
            )
            Spacer(Modifier.height(8.dp))
            ColorSliderRow(
                "Opacity", settings.overlayOpacity.toDouble(), 20.0, 100.0, 5.0, sliderTrack,
                { "${it.roundToInt()}%" }, { viewModel.setOverlayOpacity(it.roundToInt()) }, resetKey = "opacity",
            )
        }
        SectionCard("Motion") {
            AppearanceRow("Style") {
                OptionChips(
                    options = listOf(
                        com.tvassist.data.settings.OVERLAY_ANIM_SLIDE to "Slide",
                        com.tvassist.data.settings.OVERLAY_ANIM_FADE to "Fade",
                        com.tvassist.data.settings.OVERLAY_ANIM_NONE to "None",
                    ),
                    selected = settings.overlayAnimStyle,
                    onSelect = viewModel::setOverlayAnimStyle,
                )
            }
            if (settings.overlayAnimStyle != com.tvassist.data.settings.OVERLAY_ANIM_NONE) {
                Spacer(Modifier.height(12.dp))
                ColorSliderRow(
                    "Speed", settings.overlayAnimSpeedMs.toDouble(), 120.0, 500.0, 20.0,
                    Brush.horizontalGradient(listOf(ChipDim, AppAccent)),
                    { "${it.roundToInt()} ms" }, { viewModel.setOverlayAnimSpeedMs(it.roundToInt()) }, resetKey = "speed",
                )
            }
        }
        SectionCard("Timing") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Never", 5 to "5s", 10 to "10s", 15 to "15s", 30 to "30s", 60 to "60s").forEach { (secs, label) ->
                    ChipButton(label, settings.autoCloseSeconds == secs, onClick = { viewModel.setAutoCloseSeconds(secs) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** A sub-group divider inside the Colors card ("Panel" / "Icons" / "Focus") — label + hairline. */
@Composable
internal fun ColorGroupLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = AppAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.07f)))
    }
    Spacer(Modifier.height(10.dp))
}

/** A titled rounded section container for the settings pages. */
@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg).padding(14.dp),
    ) {
        Text(title.uppercase(), color = TxtMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(11.dp))
        content()
    }
}
