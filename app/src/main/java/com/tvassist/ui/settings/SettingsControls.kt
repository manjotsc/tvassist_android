package com.tvassist.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.tvassist.ui.AppAccent
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary
import com.tvassist.ui.WebOnboarding

/** A labelled row of selectable chips used in the overlay-appearance settings. */
@Composable
internal fun AppearanceRow(label: String, chips: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Color(0xFFBBBBBB),
            fontSize = 14.sp,
            modifier = Modifier.width(140.dp),
        )
        chips()
    }
    Spacer(Modifier.height(10.dp))
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun <T> OptionChips(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            ChipButton(label = label, selected = value == selected, onClick = { onSelect(value) })
        }
    }
}

/**
 * Toggle row for the shared Web setup console. All setup pages (Connection/Cameras/Maps) show this;
 * they all drive the one server. [section] just tailors the hint. PIN shows in the nav rail.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun WebSetupRow(viewModel: ConnectionViewModel, section: String) {
    val onboarding by viewModel.webOnboarding.collectAsStateWithLifecycle()
    val addr = (onboarding as? WebOnboarding.Running)?.address
    val running = addr != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Web setup", color = TxtPrimary, fontSize = 15.sp)
            Text(
                if (running) "Open $addr in a browser, enter the PIN (TV, bottom-left), then tap $section."
                else "Turn on a PIN-protected web console to configure $section (and more) from a browser.",
                color = if (running) AppAccent else TxtMuted, fontSize = 12.sp,
            )
        }
        ChipButton(
            if (running) "On" else "Off",
            selected = running,
            onClick = { if (running) viewModel.stopWebOnboarding() else viewModel.startWebOnboarding() },
        )
    }
}
