package com.tvassist.ui.settings

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.tvassist.overlay.OverlayService
import com.tvassist.ui.AccentButton
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.TxtPrimary
import com.tvassist.ui.isKeyCaptureEnabled
import com.tvassist.ui.openAccessibilitySettings
import com.tvassist.ui.openOverlaySettings

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PermissionsPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    PageScaffold("Permissions", onBack) {
        Text(
            "The overlay permission lets the control sidebar draw over other apps; key capture " +
                "lets the remote button open it from anywhere. \"Open sidebar\" shows it now.",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        PermissionsRow()

        Spacer(Modifier.height(26.dp))
        Text("Run in background", fontSize = 18.sp, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            "Keeps the Home Assistant connection warm so the overlay opens instantly with live " +
                "states — even after an app update or reboot. Recommended on (TVs are always powered).",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep alive", color = TxtPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            ChipButton(
                if (settings.keepAlive) "On" else "Off",
                selected = settings.keepAlive,
                onClick = {
                    val next = !settings.keepAlive
                    viewModel.setKeepAlive(next)
                    // Asks the settings this toggle is about to produce, not this one switch: the
                    // notification server, the dim/clock overlays and the voice bar all live in the
                    // same service, and turning keep-alive off used to take every one of them down.
                    if (settings.copy(keepAlive = next).needsKeepAlive) {
                        com.tvassist.overlay.KeepAliveService.start(context)
                    } else {
                        com.tvassist.overlay.KeepAliveService.stop(context)
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PermissionsRow() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-check the permissions whenever we resume (e.g. returning from system settings).
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasKeyCapture by remember { mutableStateOf(isKeyCaptureEnabled(context)) }
    var hasMic by remember { mutableStateOf(com.tvassist.data.assist.hasRecordPermission(context)) }
    // RECORD_AUDIO is a runtime grant, and a Service (the overlay) cannot ask for one — so the
    // request lives here, in the Activity, and the Assist card points the user at this screen.
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> hasMic = granted }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = Settings.canDrawOverlays(context)
                hasKeyCapture = isKeyCaptureEnabled(context)
                hasMic = com.tvassist.data.assist.hasRecordPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChipButton(
                label = if (hasOverlay) "✓ Overlay allowed" else "Grant overlay permission",
                selected = hasOverlay,
                onClick = { openOverlaySettings(context) },
            )
            ChipButton(
                label = if (hasKeyCapture) "✓ Key capture on" else "Enable key capture",
                selected = hasKeyCapture,
                onClick = { openAccessibilitySettings(context) },
            )
            ChipButton(
                label = if (hasMic) "✓ Microphone allowed" else "Allow microphone",
                selected = hasMic,
                onClick = { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) },
            )
            AccentButton("Open sidebar", {
                if (hasOverlay) {
                    OverlayService.toggle(context)
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "Grant overlay permission first to open the sidebar",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    openOverlaySettings(context)
                }
            })
        }
        if (!hasOverlay) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Overlay permission is required to draw the sidebar over other apps.",
                color = Color(0xFFFFB74D),
                fontSize = 12.sp,
            )
        }
    }
}
