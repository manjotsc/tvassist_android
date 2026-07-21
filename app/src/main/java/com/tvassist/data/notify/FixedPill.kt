package com.tvassist.data.notify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * A small persistent "pill" widget pinned to a screen corner (icon + optional text). Several line
 * up in a row, like a status bar — distinct from transient toast [TvNotification]s.
 */
@Serializable
data class FixedPill(
    val id: String,
    /** mdi:/Iconify name, an SVG/PNG/JPG URL, or an entity_picture (avatar). */
    val icon: String = "",
    /** Optional short text, e.g. "88%" ("" = icon-only). */
    val message: String = "",
    /** circle / rounded / rectangular. */
    val shape: String = "rounded",
    val iconColor: String = "",
    /** Background behind the icon ("", "transparent", or a color); "" = none. */
    val iconBackground: String = "",
    /** Opacity 0-100 for the icon background; -1 = not set (use the color's own alpha). */
    val iconBackgroundOpacity: Int = -1,
    val messageColor: String = "",
    val borderColor: String = "",
    val backgroundColor: String = "",
    /** Opacity 0-100 for the pill background (overrides the color's own alpha); -1 = not set. */
    val backgroundOpacity: Int = -1,
    /** top-right / top-left / bottom-right / bottom-left / top-center / bottom-center. */
    val position: String = "top-right",
    /** Absolute epoch-ms to auto-remove at; 0 = never. */
    val expiresAt: Long = 0L,
    // --- Live entity binding (all optional; set from the service call, rendered by the app) ---
    /** HA entity id to bind: value/icon/color update live from its state. "" = a static pill. */
    val entity: String = "",
    /** Attribute to display instead of the state (e.g. "battery_level"); "" = state. */
    val attribute: String = "",
    /** Static label shown before the live value (e.g. "Bedroom" → "Bedroom 22°"). */
    val label: String = "",
    // --- Flash / attention (optional; set a color to pulse that element — icon & border independent) ---
    /** Border flash color — set to pulse the border ("" = no border flash). */
    val flashBorderColor: String = "",
    /** Icon flash color — set to pulse the icon ("" = no icon flash). */
    val flashIconColor: String = "",
    /** Border flash style: pulse (smooth) / blink (hard on-off) / glow (sustained bright). */
    val flashBorderType: String = "pulse",
    /** Icon flash style: pulse / blink / glow. */
    val flashIconType: String = "pulse",
    /** Icon flash tempo (slow/medium/fast or ms per cycle); "" = medium (850 ms). */
    val flashIconSpeed: String = "",
    /** Border flash tempo (slow/medium/fast or ms per cycle); "" = medium (850 ms). */
    val flashBorderSpeed: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Process-wide store of pinned pills; the REST server adds/removes, the overlay renders.
 *
 * Pills are pinned until explicitly cleared, so they must survive process restarts and reboots:
 * [load]/[save] persist the list to disk. Expired pills are pruned on restore.
 */
class FixedNotificationStore(
    private val load: (suspend () -> List<FixedPill>)? = null,
    private val save: (suspend (List<FixedPill>) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _items = MutableStateFlow<List<FixedPill>>(emptyList())
    val items: StateFlow<List<FixedPill>> = _items.asStateFlow()

    init {
        // Restore pinned pills across process restarts / reboots; drop any that expired while the
        // TV was off, and re-arm the auto-remove timers for the rest.
        if (load != null) {
            scope.launch {
                val now = System.currentTimeMillis()
                val restored = runCatching { load.invoke() }.getOrDefault(emptyList())
                    .filter { it.expiresAt == 0L || it.expiresAt > now }
                _items.value = restored
                restored.forEach { armExpiry(it) }
                persist() // rewrite the pruned list so expired pills don't linger in storage
            }
        }
    }

    /** Add or replace a pill by id; auto-removes at [FixedPill.expiresAt] when set. */
    fun show(pill: FixedPill) {
        // Keep insertion order: replace in place if the id already exists, else append.
        _items.update { list ->
            val existing = list.indexOfFirst { it.id == pill.id }
            if (existing >= 0) list.toMutableList().also { it[existing] = pill } else list + pill
        }
        armExpiry(pill)
        persist()
    }

    fun remove(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun clearAll() {
        _items.value = emptyList()
        persist()
    }

    private fun armExpiry(pill: FixedPill) {
        if (pill.expiresAt > 0L) {
            scope.launch {
                delay((pill.expiresAt - System.currentTimeMillis()).coerceAtLeast(1L))
                if (_items.value.any { it.id == pill.id && it.createdAt == pill.createdAt }) remove(pill.id)
            }
        }
    }

    private fun persist() {
        val saver = save ?: return
        scope.launch { runCatching { saver.invoke(_items.value) } }
    }
}
