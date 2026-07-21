package com.tvassist.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.tvassist.R
import com.tvassist.TvAssistApp
import com.tvassist.data.settings.OverlayAppearance
import com.tvassist.data.settings.OverlayLayout
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.overlayThemeOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts the Home Assistant control sidebar as a system overlay
 * drawn over whatever app is in the foreground. Driven by [ACTION_SHOW]/[ACTION_HIDE]/
 * [ACTION_TOGGLE] intents (e.g. from the remote key-capture service).
 */
class OverlayService : Service() {

    private val app: TvAssistApp get() = application as TvAssistApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val layout = MutableStateFlow(OverlayLayout())
    private val appearance = MutableStateFlow(OverlayAppearance())
    private val overrides = MutableStateFlow<Map<String, com.tvassist.data.settings.EntityOverride>>(emptyMap())
    // The entity whose control card is open over the bar (null = bar only).
    private val openCardId = MutableStateFlow<String?>(null)
    // The entity whose fullscreen camera/person view pops up over the bar (null = none).
    private val openFullscreenId = MutableStateFlow<String?>(null)
    // True while the bar plays its exit animation, just before the window is removed.
    private val closing = MutableStateFlow(false)
    private val controlActions by lazy { com.tvassist.ui.EntityControlActions(app.haRepository) }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var autoCloseMs = 0L // 0 = never auto-close
    // True once stored settings have been loaded at least once. Until then, showing the bar would
    // render constructor defaults (empty layout + default theme) — the cold-start flash.
    @Volatile
    private var settingsPrimed = false
    // Never dismiss while a fullscreen camera/map is open, even if a stray timer slipped through
    // the scheduling guards — a fullscreen popup is only closed with BACK.
    private val autoCloseRunnable = Runnable { if (openFullscreenId.value == null) hideSidebar() }

    /** (Re)start the inactivity timer that hides the sidebar after [autoCloseMs]. */
    private fun scheduleAutoClose() {
        handler.removeCallbacks(autoCloseRunnable)
        // Never auto-close while a fullscreen camera/map popup is open.
        if (openFullscreenId.value != null) return
        if (autoCloseMs > 0L && overlayView != null) {
            handler.postDelayed(autoCloseRunnable, autoCloseMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // Keep the sidebar's entity selection in sync with stored settings.
        scope.launch {
            app.settingsStore.settings.collect { s ->
                layout.value = s.overlayLayout
                overrides.value = s.entityOverrides
                appearance.value = OverlayAppearance.from(s)
                autoCloseMs = s.autoCloseSeconds * 1000L
                settingsPrimed = true
                scheduleAutoClose() // apply a changed timeout to an open sidebar
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_SHOW -> showSidebar()
            ACTION_HIDE -> hideSidebar()
            ACTION_TOGGLE -> if (overlayView == null) showSidebar() else hideSidebar()
        }
        return START_STICKY
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun showSidebar() {
        if (overlayView != null) return
        if (!canDrawOverlays()) {
            Log.w(TAG, "Overlay permission not granted; cannot show sidebar")
            return
        }
        // Recover a dropped HA connection so the bar opens with live states, not "unavailable" tiles
        // — e.g. after a reboot where the first connect failed before the network was ready. No-op
        // when already connected.
        app.haRepository.ensureConnected()
        closing.value = false // fresh open — play the enter transition, not a stuck exit

        if (settingsPrimed) {
            buildAndAddSidebar()
            return
        }
        // Cold start (app just updated / process recreated): the settings Flow hasn't emitted yet.
        // Seed layout/appearance/overrides from a direct read so the very first frame uses the real
        // theme + layout instead of flashing a default-themed entity-fallback bar for ~1s.
        scope.launch {
            runCatching {
                val s = app.settingsStore.settings.first()
                layout.value = s.overlayLayout
                overrides.value = s.entityOverrides
                appearance.value = OverlayAppearance.from(s)
                autoCloseMs = s.autoCloseSeconds * 1000L
                settingsPrimed = true
            }.onFailure { Log.w(TAG, "settings prime failed; showing with current values", it) }
            // A hide could have arrived while we awaited; only build if still wanted and not already up.
            if (overlayView == null && !closing.value) buildAndAddSidebar()
        }
    }

    private fun buildAndAddSidebar() {
        if (overlayView != null) return
        val owner = OverlayLifecycleOwner().apply { onCreate() }
        val composeView = ComposeView(this).apply {
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    val look by appearance.collectAsStateWithLifecycle()
                    val theme = remember(look) {
                        overlayThemeOf(
                            look.bgColor, look.tileColor, look.accentColor,
                            look.borderColor, look.borderEnabled, look.iconOnColor, look.iconOffColor,
                            look.focusColor,
                        )
                    }
                    CompositionLocalProvider(LocalOverlayTheme provides theme) {
                    SidebarContent(
                        repository = app.haRepository,
                        layout = layout,
                        appearance = appearance,
                        overrides = overrides,
                        openCardId = openCardId,
                        openFullscreenId = openFullscreenId,
                        closing = closing,
                        actions = controlActions,
                        onOpenEntity = { openCardId.value = it.entityId },
                        onLaunchFullscreen = {
                            openFullscreenId.value = it.entityId
                            handler.removeCallbacks(autoCloseRunnable) // don't auto-close while watching
                        },
                        onCloseCard = { openCardId.value = null },
                        onCloseFullscreen = { openFullscreenId.value = null; scheduleAutoClose() },
                    )
                    }
                }
            }
        }

        // Host wrapper handles dismissal robustly: BACK at the window root (Compose can
        // swallow setOnKeyListener) and auto-close when the overlay loses window focus
        // (e.g. the user presses HOME) so it can never get stuck on screen. The ViewTree
        // owners must live on this root view — Compose resolves the recomposer from it.
        val host = OverlayHostLayout(
            context = this,
            // BACK closes a fullscreen popup, then a control card, then the whole sidebar.
            onBack = {
                when {
                    openFullscreenId.value != null -> { openFullscreenId.value = null; scheduleAutoClose() }
                    openCardId.value != null -> openCardId.value = null
                    else -> hideSidebar()
                }
            },
            onLoseFocus = { if (openFullscreenId.value == null) hideSidebar() },
            onInteraction = { scheduleAutoClose() },
        ).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            isFocusable = true
            isFocusableInTouchMode = true
            addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // Full-screen transparent window; the (transparent) area outside the floating card
        // lets the app behind show through, while Compose positions the card per setting.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // Focusable (no FLAG_NOT_FOCUSABLE) so the D-pad can drive the sidebar; HARDWARE
            // ACCELERATED so WebView (maps) and TextureView (camera) render in this window.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )

        // The overlay permission can be revoked between the canDrawOverlays() check and here; a
        // failed addView must not crash the service or orphan the Compose lifecycle owner.
        val added = runCatching { windowManager.addView(host, params) }
            .onFailure { Log.w(TAG, "addView failed", it); owner.onDestroy() }
            .isSuccess
        if (!added) return
        owner.onResume()
        host.requestFocus()
        overlayView = host
        lifecycleOwner = owner
        scheduleAutoClose()
    }

    /**
     * Dismiss the bar. When motion is enabled and only the plain bar is showing, flip [closing] so
     * the panel plays its exit transition, then remove the window after the animation. Any other
     * case (motion off, a card/fullscreen open, or already closing) removes immediately so dismissal
     * stays reliable (BACK / lost focus / auto-close must never leave it stuck).
     */
    private fun hideSidebar() {
        if (overlayView == null) return
        val look = appearance.value
        val animate = look.animStyle != com.tvassist.data.settings.OVERLAY_ANIM_NONE &&
            look.animSpeedMs > 0 &&
            openCardId.value == null && openFullscreenId.value == null &&
            !closing.value
        if (!animate) {
            removeSidebarNow()
            return
        }
        handler.removeCallbacks(autoCloseRunnable)
        closing.value = true
        handler.postDelayed({ removeSidebarNow() }, look.animSpeedMs.toLong() + 60L)
    }

    private fun removeSidebarNow() {
        handler.removeCallbacks(autoCloseRunnable)
        closing.value = false
        openCardId.value = null
        openFullscreenId.value = null
        val view = overlayView ?: return
        runCatching { windowManager.removeView(view) }
            .onFailure { Log.w(TAG, "removeView failed", it) }
        lifecycleOwner?.onDestroy()
        overlayView = null
        lifecycleOwner = null
    }

    override fun onDestroy() {
        hideSidebar()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.overlay_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_channel_name))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_SHOW = "com.tvassist.overlay.SHOW"
        const val ACTION_HIDE = "com.tvassist.overlay.HIDE"
        const val ACTION_TOGGLE = "com.tvassist.overlay.TOGGLE"

        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "tv_assist_overlay"
        private const val NOTIFICATION_ID = 1001

        fun toggle(context: Context) = send(context, ACTION_TOGGLE)
        fun show(context: Context) = send(context, ACTION_SHOW)
        fun hide(context: Context) = send(context, ACTION_HIDE)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, OverlayService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

/**
 * Root container for the overlay window. Reliably handles dismissal that Compose's own
 * key listener can miss:
 *  - BACK at the window root closes the sidebar (Compose can swallow setOnKeyListener).
 *  - Losing window focus (e.g. the user presses HOME) auto-closes it, so it can't get
 *    stuck on screen.
 *  - Any key press is reported as interaction to reset the auto-close timer.
 */
private class OverlayHostLayout(
    context: Context,
    private val onBack: () -> Unit,
    private val onLoseFocus: () -> Unit,
    private val onInteraction: () -> Unit,
) : FrameLayout(context) {

    private var everHadFocus = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onBack()
            return true
        }
        onInteraction()
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            everHadFocus = true
        } else if (everHadFocus) {
            onLoseFocus()
        }
    }
}
