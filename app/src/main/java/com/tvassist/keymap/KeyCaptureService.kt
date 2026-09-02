package com.tvassist.keymap

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.tvassist.BuildConfig
import android.view.accessibility.AccessibilityEvent
import com.tvassist.TvAssistApp
import com.tvassist.data.assist.VoiceController
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

    /** KeyEvent keycode that opens Assist with the mic live. 0 = unset (no mic key). */
    @Volatile
    private var micKeyCode: Int = 0

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
        scope.launch {
            app.settingsStore.settings
                .map { it.micKeyCode }
                .collect { code -> micKeyCode = code }
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false
        // Debug-gated: this fires for EVERY remote press system-wide, so it has no business in a
        // release build. It earns its place in debug because it is the only way to answer "did the
        // key reach us at all" — the question that comes up whenever a trigger appears dead, and
        // the one that cost an evening after this line was deleted outright rather than gated.
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "KeyCaptureService",
                "onKeyEvent code=${event.keyCode} action=${event.action} " +
                    "trigger=$triggerKeyCode mic=$micKeyCode",
            )
        }
        // Interactive-notification control (keys mode): while an interactive notification is showing,
        // OK enlarges it (fullscreen camera) and BACK dismisses/collapses it. Other keys pass through.
        if (handleInteractiveKey(event)) return true

        // The voice bar's window is not focusable, so OK and BACK can only reach it through here —
        // the same arrangement as the interactive notification above. Checked first so a bar that
        // is already up answers to the remote's ordinary buttons.
        if (app.voice.isActive && handleVoiceKey(event)) return true

        // Mic key: one press opens Assist and starts listening; pressing it again finishes the
        // utterance, or asks a follow-up once an answer is up. Checked before the overlay trigger
        // so binding both to the same button still gets you the more specific action.
        if (micKeyCode != 0 && event.keyCode == micKeyCode) {
            if (event.action == KeyEvent.ACTION_UP) app.voice.trigger()
            return true
        }

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
    /**
     * OK finishes the utterance — or dismisses a finished one, see [VoiceController.confirm], which
     * decides what it means in the phase the exchange is actually in. BACK abandons it; every other
     * key passes through to the app.
     */
    private fun handleVoiceKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) app.voice.confirm()
                return true // consume down + up
            }
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP) app.voice.cancel()
                return true
            }
        }
        return false
    }

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
