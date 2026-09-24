package com.tvassist.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import com.tvassist.ui.AppAccent
import com.tvassist.ui.CardBg
import com.tvassist.ui.CardFocusBg
import com.tvassist.ui.ChipDim
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.Route
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary

/** Settings landing page: a list of categories, each opening its own page. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsHub(viewModel: ConnectionViewModel, onOpen: (Route) -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 44.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", fontSize = 34.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(18.dp))
        HubItem(Icons.Rounded.Link, "Connection", "Home Assistant URL & token, web onboarding") { onOpen(Route.Connection) }
        HubItem(Icons.Rounded.Security, "Permissions", "Overlay draw & remote key-capture access") { onOpen(Route.Permissions) }
        HubItem(Icons.Rounded.Key, "Security", "Access tokens that secure this TV") { onOpen(Route.Security) }
        HubItem(Icons.Rounded.SettingsRemote, "Triggers & keys", "The remote button that opens the overlay") { onOpen(Route.Triggers) }
        HubItem(Icons.Rounded.Tune, "Appearance & timing", "Overlay position, style and auto-close") { onOpen(Route.Appearance) }
        HubItem(Icons.Rounded.Notifications, "Notifications", "Let Home Assistant push toasts to this TV") { onOpen(Route.Notifications) }
        HubItem(Icons.AutoMirrored.Rounded.VolumeUp, "Audio & announcements", "TTS/sound volume, ducking and language for this TV") { onOpen(Route.Audio) }
        HubItem(Icons.Rounded.Schedule, "On-screen display", "Always-on clock and screen dimming overlay") { onOpen(Route.Display) }
        HubItem(Icons.Rounded.Videocam, "Cameras", "Add direct-URL cameras (RTSP/HTTP) that start instantly") { onOpen(Route.Cameras) }
        HubItem(Icons.Rounded.Map, "Maps", "Multi-entity map cards + Google Maps source") { onOpen(Route.Maps) }
        HubItem(Icons.Rounded.Backup, "Backup & restore", "Save or restore your settings to a file") { onOpen(Route.Backup) }
        HubItem(Icons.Rounded.Info, "About", "Version & info") { onOpen(Route.About) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HubItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CardBg,
            focusedContainerColor = CardFocusBg,
            pressedContainerColor = CardFocusBg,
            contentColor = TxtPrimary,
            focusedContentColor = TxtPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.5.dp, AppAccent), shape = RoundedCornerShape(20.dp)),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(46.dp).clip(CircleShape)
                    .background(if (focused) AppAccent.copy(alpha = 0.18f) else ChipDim),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, contentDescription = null,
                    tint = if (focused) AppAccent else Color(0xFFB6C0CC),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, color = TxtPrimary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 13.sp, color = TxtMuted)
            }
            Icon(
                Icons.Rounded.ChevronRight, contentDescription = null,
                tint = if (focused) AppAccent else TxtMuted, modifier = Modifier.size(22.dp),
            )
        }
    }
}
