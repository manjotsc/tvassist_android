package com.tvassist.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import com.tvassist.ui.AccentButton
import com.tvassist.ui.AppAccent
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.TvTextField
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary

/** A random alphanumeric access token for the notification endpoint. 24 chars so the masked display
 * (first-2 + last-4 shown) still leaves a strong, un-guessable hidden part. */
internal fun randomToken(length: Int = 24): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return buildString { repeat(length) { append(alphabet.random()) } }
}

/** All access tokens that secure this TV, in one place. Stored encrypted, shown masked (write-only). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SecurityPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ip = remember { com.tvassist.data.notify.NotificationServer.localIp() }
    PageScaffold("Security", onBack) {
        Text(
            "Access tokens and keys that secure this TV — all encrypted at rest and shown masked. " +
                "Enter or regenerate them here.",
            color = TxtMuted, fontSize = 13.sp,
        )

        // --- TLS verification for the HA connection (reconnects on change) ---
        Spacer(Modifier.height(20.dp))
        Text("Verify HA certificate", fontSize = 14.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "Turn off to connect to a Home Assistant using a self-signed certificate. For safety this " +
                "only applies when the server is on your local network — a public address always " +
                "requires a valid certificate, and map tiles and icons are never affected.",
            fontSize = 12.sp, color = TxtMuted,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (settings.verifySsl) "Certificates are verified" else "Verification off for local HA",
                color = TxtPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f),
            )
            ChipButton(
                if (settings.verifySsl) "On" else "Off",
                selected = settings.verifySsl,
                onClick = { viewModel.setVerifySsl(!settings.verifySsl) },
            )
        }

        // --- Home Assistant long-lived token (mirrors Settings → Connection; changing it reconnects) ---
        Spacer(Modifier.height(20.dp))
        Text("Home Assistant token", fontSize = 14.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            if (settings.baseUrl.isBlank()) {
                "Set up the connection first (Settings → Connection); then you can rotate the token here."
            } else {
                "Long-lived access token for ${settings.baseUrl}. Changing it reconnects to Home Assistant."
            },
            fontSize = 12.sp, color = TxtMuted,
        )
        if (settings.baseUrl.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            var haTok by remember { mutableStateOf(settings.token) }
            TvTextField(value = haTok, onValueChange = { haTok = it }, placeholder = "paste token from HA profile", secret = true)
            LaunchedEffect(haTok) {
                if (haTok == settings.token || haTok.isBlank()) return@LaunchedEffect
                delay(600)
                viewModel.saveAndConnect(settings.baseUrl, haTok)
            }
        }

        // --- Notification / push token ---
        Spacer(Modifier.height(22.dp))
        Text("Notification / push token", fontSize = 14.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "Secures pushes to this TV (notify, speak, play sound). Leave blank for no auth. When set, " +
                "pushes must include ?token=… (or an X-Token header) — e.g. " +
                "http://${ip ?: "<tv-ip>"}:${settings.notificationPort}/notify?token=…",
            fontSize = 12.sp, color = TxtMuted,
        )
        Spacer(Modifier.height(8.dp))
        // Shown once right after Generate; debounced write, seeded once.
        var tok by remember { mutableStateOf(settings.notificationToken) }
        var justGenerated by remember { mutableStateOf<String?>(null) }
        TvTextField(value = tok, onValueChange = { tok = it; justGenerated = null }, placeholder = "no token", secret = true)
        LaunchedEffect(tok) {
            if (tok == settings.notificationToken) return@LaunchedEffect
            delay(400)
            viewModel.setNotificationToken(tok)
        }
        justGenerated?.let { g ->
            Spacer(Modifier.height(8.dp))
            Text("New token — copy it into Home Assistant now (it won't be shown again):", color = AppAccent, fontSize = 12.sp)
            Text(g, color = TxtPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentButton("Generate token", { val t = randomToken(); tok = t; justGenerated = t }, leadingIcon = Icons.Rounded.Autorenew)
            if (tok.isNotEmpty()) ChipButton("Clear", selected = false, onClick = { tok = ""; justGenerated = null })
        }

        // --- Google Maps key (moved here from Settings → Maps) ---
        Spacer(Modifier.height(22.dp))
        Text("Google Maps key", fontSize = 14.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "Optional — enables Google map tiles for person maps (blank = free OpenStreetMap). Enable the " +
                "\"Map Tiles API\" on the key; restricting it to Android apps is recommended.",
            fontSize = 12.sp, color = TxtMuted,
        )
        Spacer(Modifier.height(6.dp))
        var mapsKey by remember { mutableStateOf(settings.googleMapsApiKey) }
        TvTextField(value = mapsKey, onValueChange = { mapsKey = it }, placeholder = "Google Maps API key (optional)", secret = true)
        LaunchedEffect(mapsKey) {
            if (mapsKey == settings.googleMapsApiKey) return@LaunchedEffect
            delay(500)
            viewModel.setGoogleMapsApiKey(mapsKey)
        }
    }
}
