package com.tvassist.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.tvassist.ui.AccentButton
import com.tvassist.ui.AppAccent
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Logs: the switches for the log collector and the two ways to get a log off the TV.
 * No viewer, deliberately — the point is a file for whoever is fixing the bug (see LogCollector).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LogsPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saveStatus by viewModel.logSaveStatus.collectAsStateWithLifecycle()
    PageScaffold("Logs", onBack) {
        Text(
            "Collects what TV Assist logs, so a problem can be reported with the details needed to fix " +
                "it. Nothing leaves the TV unless you download or save it. Tokens, passwords and the " +
                "addresses of your Home Assistant and cameras are replaced with placeholders.",
            color = TxtMuted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))

        ToggleRow(
            "Collect logs",
            "Off stops collecting and deletes what was collected.",
            settings.collectLogs,
        ) { viewModel.setCollectLogs(!settings.collectLogs) }

        if (settings.collectLogs) {
            ToggleRow(
                "Keep logs across restarts",
                if (settings.persistLogs) "Also written to storage (up to 1 MB), so a crash or restart doesn't lose them."
                else "Memory only: the newest 2,000 lines, gone when the app restarts.",
                settings.persistLogs,
            ) { viewModel.setPersistLogs(!settings.persistLogs) }

            // Ticks once, at expiry, so the row turns Off by itself instead of saying "On until"
            // a time already past (logging itself stops on time either way — DiagLog compares).
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(settings.detailedLogsUntil) {
                now = System.currentTimeMillis()
                val wait = settings.detailedLogsUntil - now
                if (wait > 0) {
                    delay(wait + 500)
                    now = System.currentTimeMillis()
                }
            }
            val detailedOn = settings.detailedLogsUntil > now
            ToggleRow(
                "Detailed logging",
                if (detailedOn) {
                    "On until ${SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(settings.detailedLogsUntil))}, " +
                        "then off by itself."
                } else {
                    "Extra detail about camera and notification video, for reporting a playback problem. " +
                        "Turns itself off after 24 hours."
                },
                detailedOn,
            ) { viewModel.setDetailedLogging(!detailedOn) }

            Spacer(Modifier.height(12.dp))
            Text("Get a log", color = TxtPrimary, fontSize = 15.sp)
            Text(
                "Reproduce the problem first, then save or download straight away.",
                color = TxtMuted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            AccentButton("Save to Downloads", { viewModel.saveLogsToDownloads() }, leadingIcon = Icons.Rounded.Download)
            saveStatus?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = if (it.startsWith("Saved")) AppAccent else TxtMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            WebSetupRow(viewModel, "Logs")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ToggleRow(title: String, detail: String, on: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TxtPrimary, fontSize = 15.sp)
            Text(detail, color = TxtMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        ChipButton(if (on) "On" else "Off", selected = on, onClick = onToggle)
    }
    Spacer(Modifier.height(12.dp))
}
