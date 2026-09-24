package com.tvassist.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.ui.text.font.FontWeight
import com.tvassist.data.settings.DisplayCorner
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun DisplayPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The dim/clock overlays are drawn by the keep-alive service — make sure it's running.
    fun ensureService() = com.tvassist.overlay.KeepAliveService.start(context)
    PageScaffold("On-screen display", onBack) {
        // ---- Screen dimming ----
        Text("Screen dimming", color = TxtPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "A translucent layer over the TV picture — good for movie night or a panel that's too bright.",
            color = TxtMuted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Off", 15 to "15%", 30 to "30%", 45 to "45%", 60 to "60%", 75 to "75%", 90 to "90%").forEach { (lvl, label) ->
                ChipButton(label, settings.dimLevel == lvl, onClick = { ensureService(); viewModel.setDimLevel(lvl) })
            }
        }

        Spacer(Modifier.height(26.dp))

        // ---- Always-on clock ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Always-on clock", color = TxtPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("A small clock pinned to a screen corner.", color = TxtMuted, fontSize = 13.sp)
            }
            ChipButton(
                if (settings.clockEnabled) "On" else "Off",
                selected = settings.clockEnabled,
                onClick = { ensureService(); viewModel.setClockEnabled(!settings.clockEnabled) },
            )
        }

        if (settings.clockEnabled) {
            Spacer(Modifier.height(18.dp))
            Text("Corner", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    DisplayCorner.TOP_START to "Top left",
                    DisplayCorner.TOP_END to "Top right",
                    DisplayCorner.BOTTOM_START to "Bottom left",
                    DisplayCorner.BOTTOM_END to "Bottom right",
                ).forEach { (corner, label) ->
                    ChipButton(label, DisplayCorner.fromName(settings.clockCorner) == corner, onClick = { viewModel.setClockCorner(corner) })
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Format", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton("12-hour", !settings.clock24Hour, onClick = { viewModel.setClock24Hour(false) })
                ChipButton("24-hour", settings.clock24Hour, onClick = { viewModel.setClock24Hour(true) })
                ChipButton("Seconds", settings.clockSeconds, onClick = { viewModel.setClockSeconds(!settings.clockSeconds) })
            }

            Spacer(Modifier.height(18.dp))
            Text("Size", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(32 to "Small", 44 to "Medium", 60 to "Large", 80 to "Huge").forEach { (sp, label) ->
                    ChipButton(label, settings.clockSize == sp, onClick = { viewModel.setClockSize(sp) })
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            "Dim/clock update live and survive reboots. Home Assistant can also control these by POSTing " +
                "to /set/overlay (dim, clock, corner) on the notification server.",
            color = TxtMuted, fontSize = 12.sp,
        )
    }
}
