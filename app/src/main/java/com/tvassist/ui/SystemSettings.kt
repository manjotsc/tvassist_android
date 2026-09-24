package com.tvassist.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.rounded.Settings
import com.tvassist.keymap.KeyCaptureService

internal fun openOverlaySettings(context: android.content.Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

internal fun openAccessibilitySettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * True if our remote key-capture accessibility service is enabled. Combines several
 * signals because no single one is reliable across Android TV builds:
 *  1. Our own [KeyCaptureService.isRunning] flag (set when the service actually binds).
 *  2. The AccessibilityManager's list of enabled services.
 *  3. The raw secure setting string (some TV builds return null from #2).
 */
internal fun isKeyCaptureEnabled(context: android.content.Context): Boolean {
    if (KeyCaptureService.isRunning) return true

    val pkg = context.packageName
    val cls = KeyCaptureService::class.java.name

    val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
        as? android.view.accessibility.AccessibilityManager
    val inManagerList = am?.getEnabledAccessibilityServiceList(
        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
    )?.any { info ->
        val si = info.resolveInfo?.serviceInfo
        si?.packageName == pkg && si.name == cls
    } ?: false
    if (inManagerList) return true

    val flat = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return flat.split(':').mapNotNull { android.content.ComponentName.unflattenFromString(it) }
        .any { it.packageName == pkg && it.className == cls }
}
