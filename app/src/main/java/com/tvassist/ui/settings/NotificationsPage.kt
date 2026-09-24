package com.tvassist.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.tvassist.TvAssistApp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.ui.text.font.FontWeight
import com.tvassist.ui.AccentButton
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary
import com.tvassist.ui.AppAccent

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun NotificationsPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ip = remember { com.tvassist.data.notify.NotificationServer.localIp() }
    PageScaffold("Notifications", onBack) {
        Text(
            "Let Home Assistant push toast/banner notifications to this TV. Install the tv_assist " +
                "integration (notify.tv_assist), or POST to the URL below from a rest_command.",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable notifications", color = TxtPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            ChipButton(
                if (settings.notificationsEnabled) "On" else "Off",
                selected = settings.notificationsEnabled,
                onClick = {
                    val next = !settings.notificationsEnabled
                    viewModel.setNotificationsEnabled(next)
                    if (settings.copy(notificationsEnabled = next).needsKeepAlive) {
                        com.tvassist.overlay.KeepAliveService.start(context)
                    } else {
                        com.tvassist.overlay.KeepAliveService.stop(context)
                    }
                },
            )
        }
        if (settings.notificationsEnabled && settings.notificationToken.isBlank()) {
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(0x33E55B5B)).border(1.dp, Color(0xFFE55B5B).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Text("No token set — this server is unauthenticated", color = Color(0xFFFF8A8A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    "Any device on your network can push notifications to this TV. Generate a token and add " +
                        "it to your Home Assistant pushes.",
                    color = TxtMuted, fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                AccentButton("Generate token", { viewModel.setNotificationToken(randomToken()) }, leadingIcon = Icons.Rounded.Autorenew)
            }
        }
        if (settings.notificationsEnabled) {
            Spacer(Modifier.height(18.dp))
            Text("Server address", fontSize = 14.sp, color = TxtMuted)
            Spacer(Modifier.height(4.dp))
            Text("http://${ip ?: "<tv-ip>"}:${settings.notificationPort}", color = AppAccent, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Point the tv_assist integration (or a rest_command) at this address. Needs the " +
                    "overlay permission to draw, and \"Run in background\" keeps it listening.",
                color = TxtMuted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(18.dp))
            Text("Default duration", fontSize = 14.sp, color = TxtMuted)
            Spacer(Modifier.height(2.dp))
            Text(
                "Used when a notification doesn't specify one. For a persistent toast, send duration 0 " +
                    "from the Home Assistant service call.",
                fontSize = 12.sp, color = TxtMuted,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4 to "4s", 6 to "6s", 8 to "8s", 12 to "12s", 20 to "20s", 30 to "30s").forEach { (secs, label) ->
                    ChipButton(label, settings.notificationDefaultDuration == secs, onClick = { viewModel.setNotificationDefaultDuration(secs) })
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Interactive hold", fontSize = 14.sp, color = TxtMuted)
            Spacer(Modifier.height(2.dp))
            Text(
                "When an interactive notification is opened (OK to enlarge), how long to keep it before " +
                    "auto-closing. \"Until BACK\" keeps it open until the remote dismisses it. A push can " +
                    "override this with enlarge_timeout.",
                fontSize = 12.sp, color = TxtMuted,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Until BACK", 30 to "30s", 60 to "1m", 120 to "2m", 300 to "5m").forEach { (secs, label) ->
                    ChipButton(label, settings.interactiveEnlargeTimeout == secs, onClick = { viewModel.setInteractiveEnlargeTimeout(secs) })
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Access token", fontSize = 14.sp, color = TxtMuted)
            Spacer(Modifier.height(2.dp))
            Text(
                "The token that secures pushes to this TV now lives in Settings → Security. " +
                    "When set, pushes must include ?token=… (or an X-Token header).",
                fontSize = 12.sp, color = TxtMuted,
            )
            Spacer(Modifier.height(18.dp))
            AccentButton(
                "Test notification",
                {
                    (context.applicationContext as TvAssistApp).notificationStore.show(
                        com.tvassist.data.notify.TvNotification(
                            id = "test",
                            message = "Notifications are working!",
                            title = "TV Assist",
                            icon = "mdi:check-circle",
                            durationSec = settings.notificationDefaultDuration,
                        ),
                    )
                },
                leadingIcon = Icons.Rounded.Notifications,
            )
        }
    }
}
