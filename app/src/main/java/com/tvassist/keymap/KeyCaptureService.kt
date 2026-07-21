package com.tvassist.keymap

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.tvassist.TvAssistApp
import com.tvassist.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * System-wide remote key capture. As an accessibility service it can observe button
 * presses while other apps are foregrounded, then classify them as single / double /
 * long press and act on the configured trigger key:
 *
 *  - single press → toggle the control overlay
 *  - double press → show the overlay
 *  - long press   → hide the overlay
 */
class KeyCaptureService : AccessibilityService() {

    private val app: TvAssistApp get() = application as TvAssistApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    /** KeyEvent keycode that triggers the overlay. 0 falls back to [DEFAULT_TRIGGER]. */
    @Volatile
    private var triggerKeyCode: Int = DEFAULT_TRIGGER

    private var lastUpTime = 0L
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        longPressFired = true
        OverlayService.hide(this)
    }
    private val singlePressRunnable = Runnable {
        OverlayService.toggle(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        scope.launch {
            app.settingsStore.settings
                .map { it.triggerKeyCode }
                .collect { code -> triggerKeyCode = if (code != 0) code else DEFAULT_TRIGGER }
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false
        // Interactive-notification control (keys mode): while an interactive notification is showing,
        // OK enlarges it (fullscreen camera) and BACK dismisses/collapses it. Other keys pass through.
        if (handleInteractiveKey(event)) return true
        android.util.Log.d("KeyCaptureService", "onKeyEvent code=${event.keyCode} action=${event.action} trigger=$triggerKeyCode")
        if (event.keyCode != triggerKeyCode) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    longPressFired = false
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
            }

            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (longPressFired) {
                    longPressFired = false
                } else {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastUpTime < DOUBLE_TAP_MS) {
                        handler.removeCallbacks(singlePressRunnable)
                        lastUpTime = 0L
                        OverlayService.show(this) // double press
                    } else {
                        lastUpTime = now
                        handler.postDelayed(singlePressRunnable, DOUBLE_TAP_MS)
                    }
                }
            }
        }
        // Consume the trigger key so it doesn't leak to the foreground app.
        return true
    }

    /**
     * Route OK/BACK to an active interactive notification. Returns true (consuming the key) only when
     * an interactive notification is actually showing — otherwise keys pass through to the app.
     */
    private fun handleInteractiveKey(event: KeyEvent): Boolean {
        val store = app.notificationStore
        val enlarged = store.enlargedId.value != null
        val active = store.activeInteractive()
        if (!enlarged && active == null) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP && !enlarged && active != null) store.enlarge(active.id)
                return true // consume down + up
            }
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (enlarged) store.collapse() else active?.let { store.remove(it.id) }
                }
                return true
            }
            else -> return false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* unused */ }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        return super.onUnbind(intent)
    }

    companion object {
        /** Default trigger when the user hasn't picked one: the MENU button. */
        const val DEFAULT_TRIGGER = KeyEvent.KEYCODE_MENU
        private const val LONG_PRESS_MS = 600L
        private const val DOUBLE_TAP_MS = 300L

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
