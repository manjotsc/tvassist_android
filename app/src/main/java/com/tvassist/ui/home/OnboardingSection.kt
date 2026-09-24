package com.tvassist.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Link
import androidx.compose.ui.text.font.FontWeight
import com.tvassist.data.ha.ConnectionState
import com.tvassist.ui.AccentButton
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ConnectionStatusLine
import com.tvassist.ui.TvTextField
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary
import com.tvassist.ui.WebOnboarding

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun OnboardingSection(
    initialUrl: String,
    initialToken: String,
    connection: ConnectionState,
    webOnboarding: WebOnboarding,
    initialVerifySsl: Boolean,
    onConnect: (String, String, Boolean) -> Unit,
    onStartWeb: () -> Unit,
    onStopWeb: () -> Unit,
) {
    // Web mode is driven by the actual server state so the UI always reflects whether
    // the onboarding server is running (started here or via a re-enable elsewhere).
    var manualMode by remember { mutableStateOf(false) }

    when {
        webOnboarding !is WebOnboarding.Off -> WebPanel(
            state = webOnboarding,
            connection = connection,
            onCancel = onStopWeb,
        )

        manualMode -> Column(modifier = Modifier.width(720.dp)) {
            SetupSection(
                initialUrl = initialUrl,
                initialToken = initialToken,
                initialVerifySsl = initialVerifySsl,
                onConnect = onConnect,
            )
            Spacer(Modifier.height(12.dp))
            AccentButton("Back", { manualMode = false }, leadingIcon = Icons.Rounded.ChevronLeft)
        }

        else -> Column(modifier = Modifier.width(820.dp)) {
            Text("How do you want to connect?", color = TxtPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentButton("Enter on this TV", { manualMode = true })
                AccentButton("Use web setup", onStartWeb)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Typing on a TV remote is painful — \"Use web setup\" opens a page you can reach " +
                    "from a browser on your phone or laptop to enter your URL and token.",
                color = Color(0xFF999999),
                fontSize = 13.sp,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun WebPanel(
    state: WebOnboarding,
    connection: ConnectionState,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.width(820.dp)) {
        Text("Connect from your phone", color = Color.White, fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        when (state) {
            is WebOnboarding.Running -> {
                Text("On your phone or laptop, open this in a browser:", color = Color(0xFFBBBBBB), fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Text(state.address, color = Color(0xFF8AB4F8), fontSize = 30.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "The browser will ask for the PIN shown at the bottom-left of this screen. Then " +
                        "enter your Home Assistant URL and long-lived token and tap Connect. Turn this " +
                        "off with Cancel when you're done.",
                    color = Color(0xFF999999),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(16.dp))
                ConnectionStatusLine(connection)
            }

            is WebOnboarding.Error -> Text("Couldn't start: ${state.reason}", color = Color(0xFFF44336), fontSize = 16.sp)
            WebOnboarding.Off -> Text("Starting…", color = Color(0xFFBBBBBB), fontSize = 16.sp)
        }
        Spacer(Modifier.height(20.dp))
        AccentButton("Cancel", onCancel)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SetupSection(
    initialUrl: String,
    initialToken: String,
    initialVerifySsl: Boolean,
    onConnect: (String, String, Boolean) -> Unit,
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var token by remember(initialToken) { mutableStateOf(initialToken) }
    var verify by remember(initialVerifySsl) { mutableStateOf(initialVerifySsl) }

    Column(modifier = Modifier.width(720.dp)) {
        Text("Home Assistant URL", color = Color(0xFFBBBBBB), fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        TvTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = "http://homeassistant.local:8123",
        )
        Spacer(Modifier.height(16.dp))
        Text("Long-lived access token", color = Color(0xFFBBBBBB), fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        TvTextField(
            value = token,
            onValueChange = { token = it },
            placeholder = "Paste token from HA profile",
            secret = true,
        )
        // Always shown. Hiding it until the URL starts with https made it undiscoverable: someone
        // whose https connection is failing on a self-signed cert can't tell the option exists, and
        // a URL typed without a scheme (localhost:8123) never matched at all.
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Verify certificate", color = Color(0xFFBBBBBB), fontSize = 14.sp)
                Text(
                    "Turn off to accept a self-signed certificate. Applies only to https on your " +
                        "local network — a public address always requires a valid certificate.",
                    color = TxtMuted, fontSize = 12.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            ChipButton(if (verify) "On" else "Off", selected = verify, onClick = { verify = !verify })
        }
        Spacer(Modifier.height(20.dp))
        AccentButton("Connect", { onConnect(url, token, verify) }, leadingIcon = Icons.Rounded.Link)
    }
}
