package com.tvassist.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.ui.text.font.FontWeight
import com.tvassist.ui.AccentButton
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.PremiumRow
import com.tvassist.ui.TvTextField
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun BackupPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val status by viewModel.backupStatus.collectAsStateWithLifecycle()
    val backups by viewModel.backups.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var location by remember { mutableStateOf(com.tvassist.data.settings.BackupLocation.DOWNLOAD) }
    // Off by default: an included secret is written to Download/USB, so it must be opt-in + encrypted.
    var includeSecrets by remember { mutableStateOf(false) }
    var backupPass by remember { mutableStateOf("") }
    var restorePass by remember { mutableStateOf("") }
    // Which backup row is expanded to show its Restore/Delete actions, and whether Restore is
    // awaiting its confirm tap.
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var confirmingRestore by remember { mutableStateOf(false) }
    // Re-checked on each recomposition (after returning from the grant screen).
    val hasAccess = viewModel.hasAllFilesAccess()

    // (Re)load the backup list on entry and whenever the location (or granted access) changes.
    LaunchedEffect(location, hasAccess) {
        selectedPath = null
        confirmingRestore = false
        if (!location.needsAllFiles || hasAccess) viewModel.refreshBackups(location)
    }
    // Android 10 and below grant Download/USB access via a runtime storage permission (not the
    // Android 11+ "All files access" settings screen).
    val storagePermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.refreshBackups(location)
        else viewModel.showMessage("Storage permission denied — it's needed for Download/USB backups.")
    }

    PageScaffold("Backup & restore", onBack) {
        Text(
            "Saves your connection, entities, overlay layout, cameras, colors, notification & map " +
                "settings and trigger key to a timestamped file (app + TV model + date). App folder " +
                "is wiped on uninstall; Download and USB survive it. Pick any saved backup below to " +
                "restore or delete it.",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Include secrets", color = TxtPrimary, fontSize = 15.sp)
                Text(
                    "HA token, Google Maps key, notification token. Encrypted with a passphrase so the " +
                        "backup file is safe to keep in Download/USB. Off = a share-safe backup.",
                    color = TxtMuted, fontSize = 12.sp,
                )
            }
            ChipButton(
                if (includeSecrets) "On" else "Off",
                selected = includeSecrets,
                onClick = { includeSecrets = !includeSecrets },
            )
        }
        if (includeSecrets) {
            Spacer(Modifier.height(10.dp))
            TvTextField(value = backupPass, onValueChange = { backupPass = it }, placeholder = "Backup passphrase", secret = true)
            Spacer(Modifier.height(4.dp))
            Text(
                "Needed to protect the secrets — you'll enter it again to restore. Keep it somewhere safe; " +
                    "it can't be recovered.",
                color = TxtMuted, fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        AppearanceRow("Location") {
            OptionChips(
                options = listOf(
                    com.tvassist.data.settings.BackupLocation.APP to "App folder",
                    com.tvassist.data.settings.BackupLocation.DOWNLOAD to "Download",
                    com.tvassist.data.settings.BackupLocation.USB to "USB",
                ),
                selected = location,
                onSelect = { location = it },
            )
        }
        Spacer(Modifier.height(6.dp))

        if (location.needsAllFiles && !hasAccess) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Text(
                    "Download/USB needs \"All files access\". Grant it once, then come back.",
                    color = Color(0xFFFFC107), fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                AccentButton("Grant file access", {
                    val pkg = android.net.Uri.parse("package:com.tvassist")
                    val intents = listOf(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
                    )
                    val opened = intents.any { intent ->
                        runCatching {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent); true
                        }.getOrDefault(false)
                    }
                    if (!opened) {
                        viewModel.showMessage("This TV has no All-files-access screen. Grant via adb:  adb shell appops set com.tvassist MANAGE_EXTERNAL_STORAGE allow")
                    }
                })
            } else {
                Text(
                    "Download/USB needs storage access. Grant it once, then come back.",
                    color = Color(0xFFFFC107), fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                AccentButton("Grant storage access", {
                    storagePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                })
            }
        } else {
            AccentButton(
                "Back up now",
                {
                    if (includeSecrets && backupPass.isBlank()) {
                        viewModel.showMessage("Enter a passphrase to protect the included secrets.")
                    } else {
                        viewModel.backupSettings(location, includeSecrets, backupPass)
                    }
                },
                leadingIcon = Icons.Rounded.Backup,
            )
            Spacer(Modifier.height(20.dp))

            Text(
                "Saved backups" + if (backups.isNotEmpty()) " (${backups.size})" else "",
                color = TxtPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            if (backups.isEmpty()) {
                Text(
                    "No backups in ${location.label} yet — tap \"Back up now\" to create one.",
                    color = TxtMuted, fontSize = 12.sp,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    backups.forEach { info ->
                        val isSelected = selectedPath == info.path
                        val rel = android.text.format.DateUtils.getRelativeTimeSpanString(info.timestampMs)
                        val size = android.text.format.Formatter.formatShortFileSize(context, info.sizeBytes)
                        PremiumRow(
                            icon = Icons.Rounded.Backup,
                            title = info.name,
                            subtitle = "$rel · $size",
                            onClick = {
                                selectedPath = if (isSelected) null else info.path
                                confirmingRestore = false
                            },
                        )
                        if (isSelected) {
                            Spacer(Modifier.height(6.dp))
                            if (confirmingRestore) {
                                Text(
                                    "Overwrites all current settings on this TV (and reconnects). " +
                                        "Restore this backup?",
                                    color = Color(0xFFFFC107), fontSize = 12.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                TvTextField(value = restorePass, onValueChange = { restorePass = it }, placeholder = "Passphrase (if secrets are encrypted)", secret = true)
                                Text(
                                    "Leave blank if the backup has no secrets — everything else still restores.",
                                    color = TxtMuted, fontSize = 11.sp,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AccentButton("Confirm restore", {
                                        viewModel.restoreFrom(info, restorePass)
                                        restorePass = ""
                                        selectedPath = null
                                        confirmingRestore = false
                                    })
                                    AccentButton("Cancel", { confirmingRestore = false })
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AccentButton("Restore", { confirmingRestore = true })
                                    AccentButton("Delete", {
                                        viewModel.deleteBackup(info)
                                        selectedPath = null
                                    }, leadingIcon = Icons.Rounded.DeleteOutline)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }

        when (val s = status) {
            is ConnectionViewModel.BackupStatus.Idle -> {}
            is ConnectionViewModel.BackupStatus.Loading -> {
                Spacer(Modifier.height(12.dp))
                Text(s.message, color = TxtMuted, fontSize = 13.sp)
            }
            is ConnectionViewModel.BackupStatus.Message -> {
                Spacer(Modifier.height(12.dp))
                Text(
                    s.text,
                    color = if (s.ok) Color(0xFF4CAF50) else Color(0xFFF44336),
                    fontSize = 13.sp,
                )
            }
        }
    }
}
