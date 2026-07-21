package com.tvassist.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.tvassist.R
import com.tvassist.TvAssistApp
import com.tvassist.data.notify.NotificationServer
import com.tvassist.data.settings.OverlayAppearance
import com.tvassist.data.settings.OverlayDisplay
import com.tvassist.ui.FixedPillsOverlay
import com.tvassist.ui.NotificationOverlay
import com.tvassist.ui.OverlayDisplays
import com.tvassist.ui.overlayThemeOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the app process (and its HA WebSocket connection) resident so the control overlay is
 * instantly ready, and — when enabled — hosts the notification REST server + the non-focusable
 * notification overlay window that displays pushed toasts/banners.
 */
class KeepAliveService : Service() {
    private val app get() = application as TvAssistApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val appearance = MutableStateFlow(OverlayAppearance())
    private val displayFlow = MutableStateFlow(OverlayDisplay())

    private lateinit var windowManager: WindowManager
    private var notifServer: NotificationServer? = null
    private var notifServerPort = 0
    // Created on the first /speak (so devices that never use TTS don't init an engine).
    private var tts: com.tvassist.data.notify.TtsManager? = null
    private fun ttsEngine(): com.tvassist.data.notify.TtsManager =
        tts ?: com.tvassist.data.notify.TtsManager(this).also { tts = it }

    // Created on the first /play.
    private var sound: com.tvassist.data.notify.SoundPlayer? = null
    private fun soundPlayer(): com.tvassist.data.notify.SoundPlayer =
        sound ?: com.tvassist.data.notify.SoundPlayer(this).also { sound = it }

    /** Duck mode resolved from the per-call `duck` field falling back to this TV's default. */
    private fun resolveDuck(field: String?): String = when (field) {
        "false" -> "off"
        "true" -> if (announceDuckMode == "off") "duck" else announceDuckMode
        else -> announceDuckMode
    }

    /** Repeat resolved from a per-call field ("once"/"loop") falling back to this TV's default. */
    private fun resolveRepeat(field: String?, default: String): Boolean =
        (field?.takeIf { it.isNotBlank() } ?: default).equals("loop", ignoreCase = true)

    // A persistent notification (duration ≤ 0) never expires on its own, so cap its looping audio
    // so it can't sound forever; transient toasts stop when the store removes them.
    private val persistentAudioCapMs = 60_000L

    /**
     * Run [stop] when the notification identified by the lifecycle fields (see NotificationServer)
     * leaves the store — whether it auto-expires, is dismissed, replaced, or cleared — or when the
     * persistent safety cap elapses. Ties looping sound / repeating speech to the toast's visible life.
     */
    private fun bindToNotification(f: Map<String, String>, stop: () -> Unit) {
        val id = f["notif_id"] ?: return
        val created = f["notif_created"]?.toLongOrNull()
        val durationSec = f["notif_duration"]?.toIntOrNull() ?: 0
        // Transient/timed: rely on the store removing it (with slack); pinned-forever: hard-cap.
        val capMs = if (durationSec <= 0) persistentAudioCapMs else durationSec * 1000L + 5_000L
        val isPill = f["notif_kind"] == "pill"
        scope.launch {
            withTimeoutOrNull(capMs) {
                // Wait until this exact instance leaves its store (a pill lives in the fixed store).
                if (isPill) {
                    app.fixedStore.items.first { list ->
                        list.none { it.id == id && (created == null || it.createdAt == created) }
                    }
                } else {
                    app.notificationStore.items.first { list ->
                        list.none { it.id == id && (created == null || it.createdAt == created) }
                    }
                }
            }
            stop()
        }
    }
    @Volatile private var defaultDuration = 8
    @Volatile private var notifToken = ""
    @Volatile private var announceEnabled = true
    @Volatile private var announceVolume = 100
    @Volatile private var announceDuckMode = "duck"
    @Volatile private var announceLanguage = ""
    @Volatile private var announceSpeakMode = "both"
    @Volatile private var announceSoundRepeat = "once"
    @Volatile private var announceSpeakRepeat = "once"
    @Volatile private var announceRepeatGap = 2
    @Volatile private var ttsWarmed = false
    private var notifView: View? = null
    private var notifOwner: OverlayLifecycleOwner? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        app.haRepository // ensure the connection is created and auto-connects
        // Watchdog: if the connection is down (e.g. the first connect after a reboot failed before
        // the network was ready, and the WS-level retry didn't recover), reconnect. Keeps the
        // overlay + notification pushes backed by live HA state without opening the app. No-op when
        // already connected.
        scope.launch {
            while (true) {
                delay(60_000)
                app.haRepository.ensureConnected()
            }
        }
        // Keep entities that a live pill binds to tracked, so they update even if not imported.
        scope.launch {
            app.fixedStore.items
                .map { pills -> pills.mapNotNull { it.entity.takeIf(String::isNotBlank) }.toSet() }
                .distinctUntilChanged()
                .collect { app.haRepository.setExtraTrackedIds(it) }
        }
        scope.launch {
            app.settingsStore.settings.collect {
                appearance.value = OverlayAppearance.from(it)
                defaultDuration = it.notificationDefaultDuration
                app.notificationStore.enlargeTimeoutDefaultSec = it.interactiveEnlargeTimeout
                notifToken = it.notificationToken
                announceEnabled = it.announceEnabled
                announceVolume = it.announceVolume
                announceDuckMode = it.announceDuckMode
                announceLanguage = it.announceLanguage
                announceSpeakMode = it.announceSpeakMode
                announceSoundRepeat = it.announceSoundRepeat
                announceSpeakRepeat = it.announceSpeakRepeat
                announceRepeatGap = it.announceRepeatGap
                // Warm the TTS voice model once, ahead of any notification, so the first spoken one
                // isn't delayed ~5s by Google TTS lazily loading its voice on first use.
                if (it.announceEnabled && !ttsWarmed) {
                    ttsWarmed = true
                    runCatching { ttsEngine().warmUp() }
                }
                displayFlow.value = OverlayDisplay.from(it)
            }
        }
        scope.launch {
            app.settingsStore.settings
                .map { Triple(it.notificationsEnabled, it.notificationPort, OverlayDisplay.from(it).active) }
                .distinctUntilChanged()
                .collect { (notifEnabled, port, displayActive) ->
                    if (notifEnabled) ensureServer(port) else stopServer()
                    // The overlay window hosts both pushed notifications and the dim/clock displays.
                    if (notifEnabled || displayActive) ensureNotifWindow() else removeNotifWindow()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every caller uses startForegroundService(), so a redelivered start on an already-running
        // service still owes the system a startForeground() call within a few seconds — otherwise
        // the platform kills us with RemoteServiceException. onCreate only runs on fresh creation.
        startForeground(NOTIFICATION_ID, buildNotification())
        app.haRepository
        return START_STICKY
    }

    private fun ensureServer(port: Int) {
        if (notifServer == null || notifServerPort != port) {
            notifServer?.stop()
            notifServer = NotificationServer(
                port,
                app.notificationStore,
                defaultDuration = { defaultDuration },
                applyOverlay = { fields -> scope.launch { app.settingsStore.applyOverlayCommand(fields) } },
                fixedStore = app.fixedStore,
                token = { notifToken },
                speakMode = { announceSpeakMode },
                speak = { utterances, f ->
                    // Per-call HA values win; otherwise fall back to this TV's audio defaults. The
                    // master switch can't be overridden per call.
                    if (announceEnabled) {
                        // Repeat only makes sense bound to a notification (something that expires).
                        val repeat = resolveRepeat(f["speak_repeat"], announceSpeakRepeat) && f["notif_id"] != null
                        val gapMs = (f["speak_repeat_gap"]?.toIntOrNull() ?: announceRepeatGap).coerceIn(0, 60) * 1000L
                        val token = ttsEngine().speak(
                            utterances = utterances,
                            language = f["language"]?.takeIf { it.isNotBlank() } ?: announceLanguage,
                            volume = f["volume"]?.toIntOrNull() ?: announceVolume,
                            duckMode = resolveDuck(f["duck"]),
                            interrupt = f["interrupt"] != "false",
                            repeat = repeat,
                            repeatGapMs = gapMs,
                        )
                        if (repeat) bindToNotification(f) { ttsEngine().stop(token) }
                    }
                },
                playSound = { f ->
                    if (announceEnabled) {
                        val loop = resolveRepeat(f["sound_repeat"], announceSoundRepeat) && f["notif_id"] != null
                        val token = soundPlayer().play(
                            url = f["url"]?.takeIf { it.isNotBlank() } ?: f["sound"].orEmpty(),
                            volume = f["volume"]?.toIntOrNull() ?: announceVolume,
                            duckMode = resolveDuck(f["duck"]),
                            loop = loop,
                        )
                        if (loop) bindToNotification(f) { soundPlayer().stop(token) }
                    }
                },
            ).also { it.start() }
            notifServerPort = port
        }
    }

    private fun stopServer() {
        notifServer?.stop(); notifServer = null; notifServerPort = 0
    }

    private var notifWindowJob: kotlinx.coroutines.Job? = null

    /**
     * Add the notification window, retrying for a short while. At cold boot the overlay permission
     * (and the display) can be unavailable for the first few seconds — [Settings.canDrawOverlays]
     * returns false / addView fails — and the settings flow won't re-emit to trigger another try, so
     * without this the window would never appear until the app or overlay is opened.
     */
    private fun ensureNotifWindow() {
        if (notifView != null) return
        if (notifWindowJob?.isActive == true) return
        notifWindowJob = scope.launch {
            repeat(30) {
                addNotifWindow()
                if (notifView != null) return@launch
                delay(1000)
            }
        }
    }

    private fun addNotifWindow() {
        if (notifView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        val owner = OverlayLifecycleOwner().apply { onCreate() }
        val composeView = ComposeView(this).apply {
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    val items by app.notificationStore.items.collectAsState()
                    val enlargedId by app.notificationStore.enlargedId.collectAsState()
                    val look by appearance.collectAsState()
                    val theme = remember(look) {
                        overlayThemeOf(
                            look.bgColor, look.tileColor, look.accentColor, look.borderColor,
                            look.borderEnabled, look.iconOnColor, look.iconOffColor, look.focusColor,
                        )
                    }
                    val disp by displayFlow.collectAsState()
                    val pills by app.fixedStore.items.collectAsState()
                    OverlayDisplays(disp)
                    FixedPillsOverlay(pills, app.haRepository)
                    NotificationOverlay(items, app.haRepository, theme)
                    com.tvassist.ui.NotificationEnlarged(items, enlargedId, app.haRepository)
                }
            }
        }
        val root = FrameLayout(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            addView(
                composeView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        // Non-focusable, pass-through layer that never steals focus or blocks the app behind it.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        runCatching {
            windowManager.addView(root, params)
            owner.onResume()
            notifView = root
            notifOwner = owner
        }.onFailure { Log.w(TAG, "addNotifWindow failed", it) }
    }

    private fun removeNotifWindow() {
        notifWindowJob?.cancel()
        notifWindowJob = null
        notifView?.let { v -> runCatching { windowManager.removeView(v) } }
        notifOwner?.onDestroy()
        notifView = null
        notifOwner = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TV Assist background", NotificationManager.IMPORTANCE_MIN),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Ready in the background")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopServer()
        removeNotifWindow()
        tts?.shutdown()
        sound?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "tv_assist_keepalive"
        private const val NOTIFICATION_ID = 1002
        private const val TAG = "KeepAliveService"

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
