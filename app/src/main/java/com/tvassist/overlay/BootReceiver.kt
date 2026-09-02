package com.tvassist.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tvassist.TvAssistApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts the keep-alive service after a reboot — and after an app update
 * (ACTION_MY_PACKAGE_REPLACED) — so the overlay/notification server are ready without the user
 * having to open the app first.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        // Read the toggle off the main thread (DataStore is async); finish() releases the receiver.
        val pending = goAsync()
        val app = context.applicationContext as TvAssistApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = runCatching { app.settingsStore.settings.first() }.getOrNull()
                // A settings read that failed starts it anyway: coming back from a reboot with no
                // overlay is worse than running a service this TV did not need.
                if (s == null || s.needsKeepAlive) {
                    runCatching { KeepAliveService.start(context) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
