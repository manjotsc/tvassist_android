package com.tvassist.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.provider.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Icon
import com.tvassist.ui.ChipButton
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.isKeyCaptureEnabled
import com.tvassist.ui.openAccessibilitySettings
import com.tvassist.ui.openOverlaySettings

/**
 * A prominent banner surfacing conditions that silently break the app so the user notices without
 * digging into settings — the overlay permission and the key-capture accessibility service, both
 * of which Android can revoke on its own (key capture is disabled on every app update), plus the
 * microphone grant, which is simply never given until someone asks for it. Re-checked on resume;
 * renders nothing when everything's healthy.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HealthWarnings() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasKeyCapture by remember { mutableStateOf(isKeyCaptureEnabled(context)) }
    // NEEDS_PERMISSION, not a bare permission check: it is returned only when this device HAS a
    // way to listen and the grant is what is missing. On hardware with no app-openable mic and no
    // recogniser there is nothing a grant would fix, and a warning there would be permanent noise.
    var micNeeded by remember {
        mutableStateOf(
            com.tvassist.data.assist.voiceBackendFor(context) ==
                com.tvassist.data.assist.VoiceBackend.NEEDS_PERMISSION,
        )
    }
    // A runtime grant cannot be requested from a Service, and unlike the other two this one is
    // asked for in-app rather than by sending the user to a settings screen.
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> micNeeded = !granted }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = Settings.canDrawOverlays(context)
                hasKeyCapture = isKeyCaptureEnabled(context)
                micNeeded = com.tvassist.data.assist.voiceBackendFor(context) ==
                    com.tvassist.data.assist.VoiceBackend.NEEDS_PERMISSION
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (hasOverlay && hasKeyCapture && !micNeeded) return

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color(0x33FF6E6E))
            .border(1.dp, Color(0xFFFF8A80), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Color(0xFFFFC078), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Attention needed", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!hasOverlay) {
            WarningLine(
                "Overlay permission is off",
                "The sidebar can't appear over other apps.",
            ) { openOverlaySettings(context) }
        }
        if (!hasKeyCapture) {
            WarningLine(
                "Key capture is off",
                "Your trigger button won't open the overlay. Android turns this off after each app update.",
            ) { openAccessibilitySettings(context) }
        }
        if (micNeeded) {
            WarningLine(
                "Microphone access is off",
                "The voice key can't listen. The sidebar and notifications still work.",
            ) { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
        }
    }
    Spacer(Modifier.height(16.dp))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun WarningLine(title: String, detail: String, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xFFFFE0E0), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = TxtMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        ChipButton("Fix", selected = false, onClick = onFix)
    }
}
