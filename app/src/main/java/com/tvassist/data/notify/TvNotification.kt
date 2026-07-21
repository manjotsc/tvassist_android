package com.tvassist.data.notify

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A pushed notification to display as an overlay toast/banner on the TV. */
@Serializable
data class TvNotification(
    val id: String,
    val message: String,
    val title: String = "",
    /** Small grey line above the title, e.g. "Home Assistant". */
    val source: String = "",
    /** Optional 2nd source segment shown after [source] with a "•" separator (e.g. "RestAPI"). */
    val source2: String = "",
    /** Main/large icon: an mdi:/iconify name, an SVG/PNG/JPG URL, or an entity_picture path. */
    val icon: String = "",
    /** Small badge icon shown on the large icon's top-right corner (same formats as [icon]). */
    val smallIcon: String = "",
    /** Large icon size in dp (caps to the text-block height), and small-badge size in dp. */
    val iconSize: Int = 64,
    val smallIconSize: Int = 22,
    /** Border color of the card "#RRGGBB" ("" = no border / theme default). Matches the HA
     *  integration's `border_color`; serialized as "color" for backward compatibility. */
    @SerialName("color") val borderColor: String = "",
    /** Per-element colors ("" = sensible default). */
    val iconColor: String = "",
    val smallIconColor: String = "",
    val titleColor: String = "",
    val sourceColor: String = "",
    val messageColor: String = "",
    /** Card background: "", "transparent", or a hex (#RRGGBB / #AARRGGBB). */
    val backgroundColor: String = "",
    /** Background opacity 0-100 (overrides the color's own alpha); -1 = not set. */
    val backgroundOpacity: Int = -1,
    /** Background behind the large icon / small-icon badge ("" = default). */
    val iconBackground: String = "",
    val smallIconBackground: String = "",
    /** Opacity 0-100 for the icon / small-icon backgrounds (-1 = not set). */
    val iconBackgroundOpacity: Int = -1,
    val smallIconBackgroundOpacity: Int = -1,
    /** Seconds before auto-dismiss; 0 (or less) = persistent until cleared. */
    val durationSec: Int = 8,
    /** One of top-right / top-left / bottom-right / bottom-left / top-center / bottom-center. */
    val position: String = "top-right",
    /** small / medium / large. */
    val size: String = "medium",
    /** Optional image URL to show in the notification. */
    val image: String = "",
    /** Optional HA camera entity id; the overlay fetches a still snapshot for it. */
    val camera: String = "",
    /** Optional HA camera entity id; the overlay plays its live stream as video. */
    val cameraStream: String = "",
    /** Optional media URL — a still image, or a video/stream (rtsp/hls/dash); type auto-detected. */
    val mediaUrl: String = "",
    /** Legacy hint (none/image/video); blank = inferred from the URL. */
    val mediaType: String = "",
    /** Video engine: auto / exoplayer / vlc. */
    val player: String = "auto",
    /** Interactive: let the user press OK to enlarge (fullscreen camera) or BACK to dismiss. */
    val interactive: Boolean = false,
    /**
     * Seconds an opened (enlarged) interactive notification is kept before auto-closing, counted
     * from when its normal duration elapses. -1 = use this TV's default; 0 = stay until BACK.
     */
    val enlargeTimeout: Int = -1,
    /** Attention animation: "" / none / glow / pulse / flash / blink. */
    val flash: String = "",
    /** Color used by the "flash" (color-flash) mode. */
    val flashColor: String = "",
    /** Animation speed: slow / medium / fast. */
    val flashSpeed: String = "medium",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val persistent: Boolean get() = durationSec <= 0
}

/**
 * Process-wide store of active TV notifications; the REST server adds, the overlay renders.
 *
 * Persistent notifications ([TvNotification.persistent], i.e. duration ≤ 0) stay until cleared, so
 * they must survive process restarts and reboots — [load]/[save] persist just the persistent ones
 * to disk (transient toasts are ephemeral and would only reappear stale, so they aren't saved).
 */
class NotificationStore(
    private val load: (suspend () -> List<TvNotification>)? = null,
    private val save: (suspend (List<TvNotification>) -> Unit)? = null,
    // Injectable so unit tests can drive the auto-dismiss / enlarge-timeout timing on a virtual clock.
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _items = MutableStateFlow<List<TvNotification>>(emptyList())
    val items: StateFlow<List<TvNotification>> = _items.asStateFlow()

    // Id of the notification currently shown fullscreen ("enlarged"), or null. Driven by the remote
    // (KeyCaptureService) for interactive notifications; rendered by the notification overlay window.
    private val _enlargedId = MutableStateFlow<String?>(null)
    val enlargedId: StateFlow<String?> = _enlargedId.asStateFlow()

    /** This TV's default enlarge timeout (seconds; 0 = keep an opened notification until BACK). Kept
     *  in sync from settings by KeepAliveService; a notification's own `enlargeTimeout` overrides it. */
    @Volatile var enlargeTimeoutDefaultSec: Int = 0

    /** The most-recent interactive notification (the one the remote acts on), or null. */
    fun activeInteractive(): TvNotification? = _items.value.lastOrNull { it.interactive }

    fun enlarge(id: String) { if (_items.value.any { it.id == id }) _enlargedId.value = id }
    fun collapse() { _enlargedId.value = null }

    init {
        // Restore persistent notifications across process restarts / reboots.
        if (load != null) {
            scope.launch {
                _items.value = runCatching { load.invoke() }.getOrDefault(emptyList())
                    .filter { it.persistent }
            }
        }
        // Auto-close an opened (enlarged) notification after its enlarge timeout, so an interactive
        // one left open doesn't stay fullscreen forever. Counts from when it's opened. Transient ones
        // are then removed by show()'s dismiss coroutine (collapse unblocks it); persistent ones just
        // collapse back to their pinned toast. collectLatest cancels the timer if it's closed/switched.
        scope.launch {
            _enlargedId.collectLatest { id ->
                val n = id?.let { eid -> _items.value.firstOrNull { it.id == eid } } ?: return@collectLatest
                val timeoutSec = if (n.enlargeTimeout >= 0) n.enlargeTimeout else enlargeTimeoutDefaultSec
                if (timeoutSec > 0) {
                    delay(timeoutSec * 1000L)
                    if (_enlargedId.value == id) collapse()
                }
            }
        }
    }

    /** Show (or replace by id) a notification; transient ones auto-dismiss after their duration. */
    fun show(n: TvNotification) {
        _items.update { list -> list.filterNot { it.id == n.id } + n }
        if (!n.persistent) {
            scope.launch {
                delay(n.durationSec.toLong().coerceAtLeast(1) * 1000L)
                // If the user has opened (enlarged) this notification, don't auto-dismiss out from
                // under them — wait until it's closed (BACK → collapse, or the enlarge-timeout watcher
                // in init auto-collapses it), then let it go.
                if (_enlargedId.value == n.id) _enlargedId.first { it != n.id }
                // Only remove if it's still the same instance (wasn't replaced).
                if (_items.value.any { it.id == n.id && it.createdAt == n.createdAt }) remove(n.id)
            }
        }
        persist()
    }

    fun remove(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
        if (_enlargedId.value == id) _enlargedId.value = null
        persist()
    }

    fun clearAll() {
        _items.value = emptyList()
        _enlargedId.value = null
        persist()
    }

    /** Save only the persistent notifications; transient toasts are never persisted. */
    private fun persist() {
        val saver = save ?: return
        scope.launch { runCatching { saver.invoke(_items.value.filter { it.persistent }) } }
    }
}
