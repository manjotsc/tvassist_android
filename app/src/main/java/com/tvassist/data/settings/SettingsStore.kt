package com.tvassist.data.settings

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.tvassist.data.notify.FixedPill
import com.tvassist.data.notify.TvNotification
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** Serializable snapshot of all settings, used for backup/restore. */
@Serializable
data class SettingsBackup(
    val version: Int = 1,
    val baseUrl: String = "",
    val token: String = "",
    /** Verify the HA TLS certificate. Off allows a self-signed cert on a private (LAN) host. */
    val verifySsl: Boolean = true,
    val importedEntityIds: List<String> = emptyList(),
    val sidebarEntityIds: List<String> = emptyList(),
    val overlayLayout: OverlayLayout = OverlayLayout(),
    val entityOverrides: Map<String, EntityOverride> = emptyMap(),
    val triggerKeyCode: Int = 0,
    val micKeyCode: Int = 0,
    val assistMicId: String = "",
    val assistPipelineId: String = "",
    val autoCloseSeconds: Int = DEFAULT_AUTO_CLOSE_SECONDS,
    val overlayPosition: String = "RIGHT",
    val overlayCornerRadius: Int = 22,
    val overlayMargin: Int = 32,
    val overlayOpacity: Int = 95,
    val overlayBgColor: Int = DEFAULT_OVERLAY_BG,
    val overlayTileColor: Int = DEFAULT_OVERLAY_TILE,
    val overlayAccentColor: Int = DEFAULT_OVERLAY_ACCENT,
    val overlayBorderColor: Int = DEFAULT_OVERLAY_ACCENT,
    val overlayBorderEnabled: Boolean = true,
    val overlayIconOnColor: Int = DEFAULT_OVERLAY_ICON_ON,
    val overlayIconOffColor: Int = DEFAULT_OVERLAY_ICON_OFF,
    val overlayFocusColor: Int = DEFAULT_OVERLAY_ACCENT,
    val overlaySizeScale: Int = DEFAULT_OVERLAY_SIZE,
    val overlayAnimStyle: String = DEFAULT_OVERLAY_ANIM,
    val overlayAnimSpeedMs: Int = DEFAULT_OVERLAY_ANIM_MS,
    val dimLevel: Int = 0,
    val clockEnabled: Boolean = false,
    val clockCorner: String = "TOP_END",
    val clockColor: Int = 0,
    val clockSeconds: Boolean = false,
    val clock24Hour: Boolean = false,
    val clockSize: Int = 44,
    val localCameras: List<LocalCamera> = emptyList(),
    val mapCards: List<MapCard> = emptyList(),
    // Service + notification config (added later; old backups fall back to these defaults).
    val keepAlive: Boolean = true,
    val notificationsEnabled: Boolean = false,
    val notificationPort: Int = 8455,
    val notificationDefaultDuration: Int = 8,
    val interactiveEnlargeTimeout: Int = 0,
    val notificationToken: String = "",
    val streamPlayer: String = "auto",
    val googleMapsApiKey: String = "",
    val mapStyle: String = "roadmap",
    val mapTraffic: Boolean = false,
    // Audio & announcements (TTS + sound files); per-TV defaults, overridable per HA call.
    val announceEnabled: Boolean = true,
    val announceVolume: Int = 100,
    val announceDuckMode: String = "duck",
    val announceLanguage: String = "",
    val announceSpeakMode: String = "both",
    val announceSoundRepeat: String = "once",
    val announceSpeakRepeat: String = "once",
    val announceRepeatGap: Int = 2,
)

// Default overlay colors (ARGB) for fresh installs — match the original hardcoded palette.
const val DEFAULT_OVERLAY_BG = 0xFF12161B.toInt()
const val DEFAULT_OVERLAY_TILE = 0xFF2A2F37.toInt()
const val DEFAULT_OVERLAY_ACCENT = 0xFFF39C12.toInt()
const val DEFAULT_OVERLAY_ICON_ON = 0xFFEAEDF0.toInt()
const val DEFAULT_OVERLAY_ICON_OFF = 0xFF9AA3AE.toInt()

// Overlay bar size (percent of the base layout) and open/close motion defaults.
const val DEFAULT_OVERLAY_SIZE = 100
const val OVERLAY_ANIM_SLIDE = "slide"
const val OVERLAY_ANIM_FADE = "fade"
const val OVERLAY_ANIM_NONE = "none"
const val DEFAULT_OVERLAY_ANIM = OVERLAY_ANIM_SLIDE
const val DEFAULT_OVERLAY_ANIM_MS = 220

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tv_assist_settings")

/**
 * Credentials (HA URL + long-lived token) live in their own DataStore so this one file can be
 * excluded from Android auto-backup while every other setting still backs up to Google. Installs
 * from before the split kept these in [dataStore]; the migration copies them across on first read.
 */
private val Context.credentialsStore: DataStore<Preferences> by preferencesDataStore(
    name = "tv_assist_credentials",
    produceMigrations = { ctx ->
        val urlKey = stringPreferencesKey("base_url")
        val tokenKey = stringPreferencesKey("token")
        listOf(object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                currentData[urlKey] == null && currentData[tokenKey] == null
            override suspend fun migrate(currentData: Preferences): Preferences {
                val legacy = ctx.dataStore.data.first()
                val url = legacy[urlKey]
                val tok = legacy[tokenKey]
                if (url == null && tok == null) return currentData
                return currentData.toMutablePreferences().apply {
                    url?.let { this[urlKey] = it }
                    tok?.let { this[tokenKey] = it }
                }
            }
            override suspend fun cleanUp() {}
        })
    },
)

/** Persisted connection + key-mapping configuration. */
data class Settings(
    val baseUrl: String = "",
    val token: String = "",
    /**
     * Verify the Home Assistant TLS certificate. Turning this off lets a self-signed / private-CA
     * cert connect, but only when the host resolves entirely into private address space — see
     * [com.tvassist.data.ha.InsecureTls]. Ignored for `http://` and for public hosts.
     */
    val verifySsl: Boolean = true,
    /** Curated entities the app tracks/loads (the imported pool). */
    val importedEntityIds: List<String> = emptyList(),
    /** Legacy flat list of overlay entities; migrated into [overlayLayout]. */
    val sidebarEntityIds: List<String> = emptyList(),
    /** Structured overlay layout (rows/columns/tiles). */
    val overlayLayout: OverlayLayout = OverlayLayout(),
    /** Per-entity customizations (name/icon/press actions), keyed by entity id. */
    val entityOverrides: Map<String, EntityOverride> = emptyMap(),
    /** KeyEvent keycode that opens the overlay (0 = unset). */
    val triggerKeyCode: Int = 0,
    /** KeyEvent keycode that opens Assist and starts listening (0 = unset). */
    val micKeyCode: Int = 0,
    /**
     * Which microphone Assist listens with: blank = auto, "recognizer" = the TV's own recogniser
     * (the only route to a remote's mic), or a "dev:<type>:<name>" audio input. See
     * [com.tvassist.data.assist.listMicChoices].
     */
    val assistMicId: String = "",
    /**
     * Which Assist pipeline transcribes speech. Blank means HA's preferred one — which is also the
     * one that fails when speech-to-text is only configured on some *other* pipeline, so this
     * exists to reach that one. See [com.tvassist.data.ha.AssistPipeline].
     */
    val assistPipelineId: String = "",
    /** Seconds of inactivity before the overlay auto-closes (0 = never). */
    val autoCloseSeconds: Int = DEFAULT_AUTO_CLOSE_SECONDS,
    /** Where the floating overlay docks (enum name). */
    val overlayPosition: String = OverlayPosition.RIGHT.name,
    /** Corner radius of the floating bar, in dp (0 = square). */
    val overlayCornerRadius: Int = 22,
    /** Margin the bar floats from the screen edges, in dp. */
    val overlayMargin: Int = 32,
    /** Background opacity of the bar, 0-100. */
    val overlayOpacity: Int = 95,
    /** Overlay panel background color (ARGB). */
    val overlayBgColor: Int = DEFAULT_OVERLAY_BG,
    /** Overlay tile color (ARGB). */
    val overlayTileColor: Int = DEFAULT_OVERLAY_TILE,
    /** Overlay accent/highlight color (ARGB). */
    val overlayAccentColor: Int = DEFAULT_OVERLAY_ACCENT,
    /** Overlay panel hairline border color (ARGB) and whether it's drawn. */
    val overlayBorderColor: Int = DEFAULT_OVERLAY_ACCENT,
    val overlayBorderEnabled: Boolean = true,
    /** Entity icon tint when on/active and when off/inactive (ARGB). */
    val overlayIconOnColor: Int = DEFAULT_OVERLAY_ICON_ON,
    val overlayIconOffColor: Int = DEFAULT_OVERLAY_ICON_OFF,
    /** Highlight color of the focused tile as you navigate the overlay (ARGB). */
    val overlayFocusColor: Int = DEFAULT_OVERLAY_ACCENT,
    /** Overlay bar size as a percent of the base layout (100 = default). */
    val overlaySizeScale: Int = DEFAULT_OVERLAY_SIZE,
    /** Open/close motion style: "slide" / "fade" / "none". */
    val overlayAnimStyle: String = DEFAULT_OVERLAY_ANIM,
    /** Open/close motion duration in milliseconds. */
    val overlayAnimSpeedMs: Int = DEFAULT_OVERLAY_ANIM_MS,
    /** Keep a foreground service alive so the overlay/connection stay warm in the background. */
    val keepAlive: Boolean = true,
    /** Run the REST notification server so Home Assistant can push toasts to the TV. */
    val notificationsEnabled: Boolean = false,
    /** Port the notification REST server listens on. */
    val notificationPort: Int = 8455,
    /** Default seconds a notification stays when the push doesn't specify a duration (0 = persistent). */
    val notificationDefaultDuration: Int = 8,
    /** Seconds an opened (enlarged) interactive notification is kept before auto-closing; 0 = stay
     *  until BACK. A push can override per notification with `enlarge_timeout`. */
    val interactiveEnlargeTimeout: Int = 0,
    /** Optional shared secret for the notify server; blank = no auth (accept any LAN request). */
    val notificationToken: String = "",
    /** Default video engine for streams: auto / exoplayer / vlc. */
    val streamPlayer: String = "auto",
    /** Google Maps Platform API key for the person map (blank = free OpenStreetMap tiles). */
    val googleMapsApiKey: String = "",
    /** Google map style when a key is set: roadmap / satellite. */
    val mapStyle: String = "roadmap",
    /** Overlay live traffic on the person map (Google only). */
    val mapTraffic: Boolean = false,
    /** Master switch for TTS/sound announcements on this TV. */
    val announceEnabled: Boolean = true,
    /** Default announcement volume 0-100 (relative), when a push doesn't specify one. */
    val announceVolume: Int = 100,
    /** How the current TV audio reacts while announcing: "off" / "duck" / "pause". */
    val announceDuckMode: String = "duck",
    /** Default TTS language (BCP-47, e.g. "en-US"); blank = device default. */
    val announceLanguage: String = "",
    /**
     * How a notification's title and message are voiced when `speak` is on: "both" = one utterance
     * "Title. Message"; "separate" = title then message as two utterances; "message"/"title" = only
     * that field. A push can override this per announcement with `speak_mode`.
     */
    val announceSpeakMode: String = "both",
    /**
     * Whether a notification's sound file plays once or loops until the notification leaves the
     * screen: "once" / "loop". A push can override with `sound_repeat`.
     */
    val announceSoundRepeat: String = "once",
    /**
     * Whether a spoken notification is read once or repeats until the notification leaves the
     * screen: "once" / "loop". A push can override with `speak_repeat`.
     */
    val announceSpeakRepeat: String = "once",
    /** Seconds of pause between repeats of a repeating spoken announcement. A push can override with
     * `speak_repeat_gap`. */
    val announceRepeatGap: Int = 2,
    /** App-defined cameras (direct stream URLs, bypassing HA's HLS). */
    val localCameras: List<LocalCamera> = emptyList(),
    /** App-defined multi-entity location maps; each surfaces as a synthetic "map.ta_<id>" entity. */
    val mapCards: List<MapCard> = emptyList(),
    /** Ambiance dimming layer opacity over the TV picture, 0-95 (0 = off). */
    val dimLevel: Int = 0,
    /** Always-on corner clock overlay. */
    val clockEnabled: Boolean = false,
    /** Which screen corner the clock sits in (DisplayCorner enum name). */
    val clockCorner: String = DisplayCorner.TOP_END.name,
    /** Clock text color (ARGB); 0 = auto (white). */
    val clockColor: Int = 0,
    /** Show seconds on the clock. */
    val clockSeconds: Boolean = false,
    /** Use 24-hour time instead of 12-hour with AM/PM. */
    val clock24Hour: Boolean = false,
    /** Clock text size in sp. */
    val clockSize: Int = 44,
) {
    /**
     * Whether anything still needs [com.tvassist.overlay.KeepAliveService] running.
     *
     * One predicate rather than four copies of the same boolean chain, because the copies had
     * already drifted apart and the two toggles were the ones that were wrong: turning keep-alive
     * off stopped the service even when notifications, a dim/clock display or a bound mic key still
     * depended on it, taking the notification server and the voice bar down with it.
     *
     * The mic key belongs here for the same reason it belongs in the boot condition — the voice bar
     * is drawn by that service's overlay window and by nothing else, so stopping it does not degrade
     * voice, it makes the button do nothing at all, silently.
     */
    val needsKeepAlive: Boolean
        get() = keepAlive || notificationsEnabled || dimLevel > 0 || clockEnabled || micKeyCode != 0
}

/** A screen corner for the clock / fixed overlays. */
enum class DisplayCorner {
    TOP_START, TOP_END, BOTTOM_START, BOTTOM_END;

    companion object {
        fun fromName(name: String?): DisplayCorner = entries.firstOrNull { it.name == name } ?: TOP_END

        /** Accepts both our enum names and TvOverlay's hot-corner strings. */
        fun fromAny(raw: String?): DisplayCorner = when (raw?.trim()?.lowercase()) {
            "top_start", "top-start", "top_left", "top-left", "topleft" -> TOP_START
            "top_end", "top-end", "top_right", "top-right", "topright" -> TOP_END
            "bottom_start", "bottom-start", "bottom_left", "bottom-left", "bottomleft" -> BOTTOM_START
            "bottom_end", "bottom-end", "bottom_right", "bottom-right", "bottomright" -> BOTTOM_END
            else -> fromName(raw)
        }
    }
}

/** Resolved on-screen display overlays (dimming + clock) shown by the keep-alive window. */
data class OverlayDisplay(
    val dimLevel: Int = 0,
    val clockEnabled: Boolean = false,
    val clockCorner: DisplayCorner = DisplayCorner.TOP_END,
    val clockColor: Int = 0,
    val clockSeconds: Boolean = false,
    val clock24Hour: Boolean = false,
    val clockSize: Int = 44,
) {
    /** Whether the overlay window needs to exist for these settings. */
    val active: Boolean get() = dimLevel > 0 || clockEnabled

    companion object {
        fun from(s: Settings) = OverlayDisplay(
            dimLevel = s.dimLevel.coerceIn(0, 95),
            clockEnabled = s.clockEnabled,
            clockCorner = DisplayCorner.fromName(s.clockCorner),
            clockColor = s.clockColor,
            clockSeconds = s.clockSeconds,
            clock24Hour = s.clock24Hour,
            clockSize = s.clockSize,
        )
    }
}

/** Default overlay auto-close timeout for fresh installs (0 = never). */
const val DEFAULT_AUTO_CLOSE_SECONDS = 10

/** Where the floating control overlay docks. Side positions are vertical; top/bottom horizontal. */
enum class OverlayPosition {
    RIGHT, LEFT, BOTTOM, TOP;

    val isVertical: Boolean get() = this == RIGHT || this == LEFT

    companion object {
        fun fromName(name: String?): OverlayPosition =
            entries.firstOrNull { it.name == name } ?: RIGHT
    }
}

/** Resolved visual appearance for the floating overlay bar. */
data class OverlayAppearance(
    val position: OverlayPosition = OverlayPosition.RIGHT,
    val cornerRadiusDp: Int = 22,
    val marginDp: Int = 32,
    val opacityPercent: Int = 95,
    val bgColor: Int = DEFAULT_OVERLAY_BG,
    val tileColor: Int = DEFAULT_OVERLAY_TILE,
    val accentColor: Int = DEFAULT_OVERLAY_ACCENT,
    val borderColor: Int = DEFAULT_OVERLAY_ACCENT,
    val borderEnabled: Boolean = true,
    val iconOnColor: Int = DEFAULT_OVERLAY_ICON_ON,
    val iconOffColor: Int = DEFAULT_OVERLAY_ICON_OFF,
    val focusColor: Int = DEFAULT_OVERLAY_ACCENT,
    val sizeScale: Int = DEFAULT_OVERLAY_SIZE,
    val animStyle: String = DEFAULT_OVERLAY_ANIM,
    val animSpeedMs: Int = DEFAULT_OVERLAY_ANIM_MS,
) {
    companion object {
        fun from(s: Settings) = OverlayAppearance(
            position = OverlayPosition.fromName(s.overlayPosition),
            cornerRadiusDp = s.overlayCornerRadius,
            marginDp = s.overlayMargin,
            opacityPercent = s.overlayOpacity,
            bgColor = s.overlayBgColor,
            tileColor = s.overlayTileColor,
            accentColor = s.overlayAccentColor,
            borderColor = s.overlayBorderColor,
            borderEnabled = s.overlayBorderEnabled,
            iconOnColor = s.overlayIconOnColor,
            iconOffColor = s.overlayIconOffColor,
            focusColor = s.overlayFocusColor,
            sizeScale = s.overlaySizeScale,
            animStyle = s.overlayAnimStyle,
            animSpeedMs = s.overlayAnimSpeedMs,
        )
    }
}

/** Where settings backups are written/read. APP = private folder; others survive uninstall. */
enum class BackupLocation {
    APP, DOWNLOAD, USB;

    val label: String get() = when (this) { APP -> "App"; DOWNLOAD -> "Download"; USB -> "USB" }
    /**
     * Whether this location needs a storage grant from the user. Download and USB both go through
     * MediaStore on Android 10+ (Q), which needs no permission for the app's own files, so only the
     * pre-Q (legacy external storage) path still requires a grant.
     */
    val needsAllFiles: Boolean get() = when (this) {
        APP -> false
        DOWNLOAD, USB -> android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q
    }
}

/** One backup file on disk, surfaced to the restore picker. [path] is a stable id. */
data class BackupInfo(
    val path: String,
    val name: String,
    val timestampMs: Long,
    val sizeBytes: Long,
    val location: BackupLocation,
)

class SettingsStore(private val context: Context) {

    /**
     * True if the app can read/write public storage (Download/USB). Android 11+ needs the
     * "All files access" grant (MANAGE_EXTERNAL_STORAGE); Android 10 and below need the legacy
     * WRITE_EXTERNAL_STORAGE runtime permission (with requestLegacyExternalStorage in the manifest).
     */
    fun hasAllFilesAccess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }


    val settings: Flow<Settings> = combine(
        context.dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e },
        context.credentialsStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e },
    ) { prefs, creds ->
            Settings(
                // Credentials come from their own (backup-excluded) store; fall back to the legacy
                // location so installs from before the split keep working until they're migrated.
                baseUrl = creds[KEY_BASE_URL] ?: prefs[KEY_BASE_URL] ?: "",
                token = SecretCrypto.decrypt(creds[KEY_TOKEN] ?: prefs[KEY_TOKEN] ?: ""),
                verifySsl = prefs[KEY_VERIFY_SSL] ?: true,
                importedEntityIds = readImportedIds(prefs),
                sidebarEntityIds = readSidebarIds(prefs),
                overlayLayout = readLayout(prefs),
                entityOverrides = readOverrides(prefs),
                triggerKeyCode = prefs[KEY_TRIGGER] ?: 0,
                micKeyCode = prefs[KEY_MIC] ?: 0,

                assistMicId = prefs[KEY_ASSIST_MIC] ?: "",
                assistPipelineId = prefs[KEY_ASSIST_PIPELINE] ?: "",
                autoCloseSeconds = prefs[KEY_AUTO_CLOSE] ?: DEFAULT_AUTO_CLOSE_SECONDS,
                overlayPosition = prefs[KEY_OVERLAY_POSITION] ?: OverlayPosition.RIGHT.name,
                overlayCornerRadius = prefs[KEY_OVERLAY_CORNER] ?: 22,
                overlayMargin = prefs[KEY_OVERLAY_MARGIN] ?: 32,
                overlayOpacity = prefs[KEY_OVERLAY_OPACITY] ?: 95,
                overlayBgColor = prefs[KEY_OVERLAY_BG] ?: DEFAULT_OVERLAY_BG,
                overlayTileColor = prefs[KEY_OVERLAY_TILE] ?: DEFAULT_OVERLAY_TILE,
                overlayAccentColor = prefs[KEY_OVERLAY_ACCENT] ?: DEFAULT_OVERLAY_ACCENT,
                overlayBorderColor = prefs[KEY_OVERLAY_BORDER] ?: DEFAULT_OVERLAY_ACCENT,
                overlayBorderEnabled = prefs[KEY_OVERLAY_BORDER_ON] ?: true,
                overlayIconOnColor = prefs[KEY_OVERLAY_ICON_ON] ?: DEFAULT_OVERLAY_ICON_ON,
                overlayIconOffColor = prefs[KEY_OVERLAY_ICON_OFF] ?: DEFAULT_OVERLAY_ICON_OFF,
                overlayFocusColor = prefs[KEY_OVERLAY_FOCUS] ?: DEFAULT_OVERLAY_ACCENT,
                overlaySizeScale = prefs[KEY_OVERLAY_SIZE] ?: DEFAULT_OVERLAY_SIZE,
                overlayAnimStyle = prefs[KEY_OVERLAY_ANIM] ?: DEFAULT_OVERLAY_ANIM,
                overlayAnimSpeedMs = prefs[KEY_OVERLAY_ANIM_MS] ?: DEFAULT_OVERLAY_ANIM_MS,
                keepAlive = prefs[KEY_KEEP_ALIVE] ?: true,
                notificationsEnabled = prefs[KEY_NOTIFY_ENABLED] ?: false,
                notificationPort = prefs[KEY_NOTIFY_PORT] ?: 8455,
                notificationDefaultDuration = prefs[KEY_NOTIFY_DURATION] ?: 8,
                interactiveEnlargeTimeout = prefs[KEY_ENLARGE_TIMEOUT] ?: 0,
                notificationToken = SecretCrypto.decrypt(prefs[KEY_NOTIFY_TOKEN] ?: ""),
                streamPlayer = prefs[KEY_STREAM_PLAYER] ?: "auto",
                googleMapsApiKey = SecretCrypto.decrypt(prefs[KEY_MAPS_KEY] ?: ""),
                mapStyle = prefs[KEY_MAP_STYLE] ?: "roadmap",
                mapTraffic = prefs[KEY_MAP_TRAFFIC] ?: false,
                announceEnabled = prefs[KEY_ANNOUNCE_ENABLED] ?: true,
                announceVolume = prefs[KEY_ANNOUNCE_VOLUME] ?: 100,
                announceDuckMode = prefs[KEY_ANNOUNCE_DUCK] ?: "duck",
                announceLanguage = prefs[KEY_ANNOUNCE_LANG] ?: "",
                announceSpeakMode = prefs[KEY_ANNOUNCE_SPEAK_MODE] ?: "both",
                announceSoundRepeat = prefs[KEY_ANNOUNCE_SOUND_REPEAT] ?: "once",
                announceSpeakRepeat = prefs[KEY_ANNOUNCE_SPEAK_REPEAT] ?: "once",
                announceRepeatGap = prefs[KEY_ANNOUNCE_REPEAT_GAP] ?: 2,
                localCameras = readLocalCameras(prefs),
                mapCards = readMapCards(prefs),
                dimLevel = prefs[KEY_DIM_LEVEL] ?: 0,
                clockEnabled = prefs[KEY_CLOCK_ON] ?: false,
                clockCorner = prefs[KEY_CLOCK_CORNER] ?: DisplayCorner.TOP_END.name,
                clockColor = prefs[KEY_CLOCK_COLOR] ?: 0,
                clockSeconds = prefs[KEY_CLOCK_SECONDS] ?: false,
                clock24Hour = prefs[KEY_CLOCK_24H] ?: false,
                clockSize = prefs[KEY_CLOCK_SIZE] ?: 44,
            )
        }

    /** Reads the ordered sidebar list, falling back to (and migrating from) the old set. */
    private fun readSidebarIds(prefs: Preferences): List<String> {
        prefs[KEY_SIDEBAR_ORDERED]?.let {
            return runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }
                .getOrDefault(emptyList())
        }
        return prefs[KEY_SIDEBAR_SET]?.toList() ?: emptyList()
    }

    /** Imported pool; seeds from the sidebar list for users upgrading from before import existed. */
    private fun readImportedIds(prefs: Preferences): List<String> {
        prefs[KEY_IMPORTED]?.let {
            return runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }
                .getOrDefault(emptyList())
        }
        return readSidebarIds(prefs) // migration: existing sidebar entities become imported
    }

    /** Reads the overlay layout, migrating from the legacy flat sidebar list if absent. */
    private fun readLayout(prefs: Preferences): OverlayLayout {
        prefs[KEY_OVERLAY_LAYOUT]?.let {
            return runCatching { json.decodeFromString(OverlayLayout.serializer(), it) }
                .getOrElse { OverlayLayout.fromFlat(readSidebarIds(prefs)) }
        }
        return OverlayLayout.fromFlat(readSidebarIds(prefs))
    }

    suspend fun setOverlayLayout(layout: OverlayLayout) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_LAYOUT] = json.encodeToString(OverlayLayout.serializer(), layout)
        }
    }

    /**
     * Atomically read-modify-write the overlay layout. [transform] runs INSIDE the DataStore edit, so
     * it always sees the freshest persisted layout and rapid successive edits compose instead of
     * racing (unlike reading the async settings flow, applying, then writing — where the second edit
     * reads stale state and clobbers the first). Same atomicity as [toggleImportedEntity].
     */
    suspend fun updateOverlayLayout(transform: (OverlayLayout) -> OverlayLayout) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_LAYOUT] =
                json.encodeToString(OverlayLayout.serializer(), transform(readLayout(prefs)))
        }
    }

    private val overridesSerializer = MapSerializer(String.serializer(), EntityOverride.serializer())
    private val localCamerasSerializer = ListSerializer(LocalCamera.serializer())
    private val mapCardsSerializer = ListSerializer(MapCard.serializer())
    private val fixedPillsSerializer = ListSerializer(FixedPill.serializer())
    private val notificationsSerializer = ListSerializer(TvNotification.serializer())

    /** Read the persisted pinned pills (survive reboots), or empty. */
    suspend fun readFixedPills(): List<FixedPill> {
        val raw = context.dataStore.data.first()[KEY_FIXED_PILLS] ?: return emptyList()
        return runCatching { json.decodeFromString(fixedPillsSerializer, raw) }.getOrDefault(emptyList())
    }

    /** Replace the persisted pinned-pill list. */
    suspend fun writeFixedPills(pills: List<FixedPill>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FIXED_PILLS] = json.encodeToString(fixedPillsSerializer, pills)
        }
    }

    /** Read the persisted persistent (duration-0) notifications, or empty. */
    suspend fun readPersistentNotifications(): List<TvNotification> {
        val raw = context.dataStore.data.first()[KEY_NOTIFICATIONS] ?: return emptyList()
        return runCatching { json.decodeFromString(notificationsSerializer, raw) }.getOrDefault(emptyList())
    }

    /** Replace the persisted persistent-notification list. */
    suspend fun writePersistentNotifications(items: List<TvNotification>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NOTIFICATIONS] = json.encodeToString(notificationsSerializer, items)
        }
    }

    private fun readLocalCameras(prefs: Preferences): List<LocalCamera> {
        val raw = prefs[KEY_LOCAL_CAMERAS] ?: return emptyList()
        return runCatching { json.decodeFromString(localCamerasSerializer, raw) }.getOrDefault(emptyList())
    }

    private fun readMapCards(prefs: Preferences): List<MapCard> {
        val raw = prefs[KEY_MAP_CARDS] ?: return emptyList()
        return runCatching { json.decodeFromString(mapCardsSerializer, raw) }.getOrDefault(emptyList())
    }

    /** Add or update a map card by id (atomic). */
    suspend fun saveMapCard(card: MapCard) {
        context.dataStore.edit { prefs ->
            val list = readMapCards(prefs).toMutableList()
            val i = list.indexOfFirst { it.id == card.id }
            if (i >= 0) list[i] = card else list.add(card)
            prefs[KEY_MAP_CARDS] = json.encodeToString(mapCardsSerializer, list)
        }
    }

    suspend fun deleteMapCard(id: String) {
        context.dataStore.edit { prefs ->
            val list = readMapCards(prefs).filterNot { it.id == id }
            prefs[KEY_MAP_CARDS] = json.encodeToString(mapCardsSerializer, list)
        }
    }

    /** Replace the whole local-camera list. */
    suspend fun setLocalCameras(cameras: List<LocalCamera>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LOCAL_CAMERAS] = json.encodeToString(localCamerasSerializer, cameras)
        }
    }

    /** Add or update a local camera by id (atomic). */
    suspend fun saveLocalCamera(camera: LocalCamera) {
        context.dataStore.edit { prefs ->
            val list = readLocalCameras(prefs).toMutableList()
            val i = list.indexOfFirst { it.id == camera.id }
            if (i >= 0) list[i] = camera else list.add(camera)
            prefs[KEY_LOCAL_CAMERAS] = json.encodeToString(localCamerasSerializer, list)
        }
    }

    suspend fun deleteLocalCamera(id: String) {
        context.dataStore.edit { prefs ->
            val list = readLocalCameras(prefs).filterNot { it.id == id }
            prefs[KEY_LOCAL_CAMERAS] = json.encodeToString(localCamerasSerializer, list)
        }
    }

    private fun readOverrides(prefs: Preferences): Map<String, EntityOverride> {
        val raw = prefs[KEY_OVERRIDES] ?: return emptyMap()
        return runCatching { json.decodeFromString(overridesSerializer, raw) }.getOrDefault(emptyMap())
    }

    /** Stores (or, if all fields are default, removes) the override for one entity. */
    suspend fun setEntityOverride(override: EntityOverride) {
        context.dataStore.edit { prefs ->
            val map = readOverrides(prefs).toMutableMap()
            val isEmpty = override.name.isBlank() && override.icon.isBlank() &&
                override.singlePress == PressAction.DEFAULT && override.longPress == PressAction.DEFAULT &&
                override.displayState == DisplayState.AUTO
            if (isEmpty) map.remove(override.entityId) else map[override.entityId] = override
            prefs[KEY_OVERRIDES] = json.encodeToString(overridesSerializer, map)
        }
    }

    suspend fun setImportedEntities(entityIds: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_IMPORTED] = json.encodeToString(ListSerializer(String.serializer()), entityIds)
        }
    }

    /** Atomically add an entity to the imported pool if absent (a no-op if already there). Checking
     *  inside the edit avoids a stale-read race that could otherwise toggle an already-imported entity off. */
    suspend fun ensureImportedEntity(entityId: String) {
        context.dataStore.edit { prefs ->
            val imported = readImportedIds(prefs)
            if (entityId !in imported) {
                prefs[KEY_IMPORTED] =
                    json.encodeToString(ListSerializer(String.serializer()), imported + entityId)
            }
        }
    }

    /** Atomically add/remove an entity from the imported pool (and drop it from the overlay
     *  selection when removed). Atomic so rapid taps can't race. */
    suspend fun toggleImportedEntity(entityId: String) {
        context.dataStore.edit { prefs ->
            val imported = readImportedIds(prefs).toMutableList()
            if (imported.remove(entityId)) {
                val sidebar = readSidebarIds(prefs).filterNot { it == entityId }
                prefs[KEY_SIDEBAR_ORDERED] =
                    json.encodeToString(ListSerializer(String.serializer()), sidebar)
            } else {
                imported.add(entityId)
            }
            prefs[KEY_IMPORTED] =
                json.encodeToString(ListSerializer(String.serializer()), imported)
        }
    }

    suspend fun setConnection(baseUrl: String, token: String) {
        context.credentialsStore.edit { creds ->
            creds[KEY_BASE_URL] = baseUrl
            creds[KEY_TOKEN] = SecretCrypto.encrypt(token)
        }
        // Drop any legacy plaintext copy from the backed-up settings store.
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_BASE_URL)
            prefs.remove(KEY_TOKEN)
        }
    }

    /**
     * Verify the HA TLS certificate. Stored in the plain settings store (not credentials) — it's a
     * preference, not a secret — so it rides along in backup/restore.
     */
    suspend fun setVerifySsl(on: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_VERIFY_SSL] = on }
    }

    suspend fun setTriggerKeyCode(keyCode: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_TRIGGER] = keyCode }
    }

    suspend fun setMicKeyCode(keyCode: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_MIC] = keyCode }
    }



    suspend fun setAssistMicId(micKey: String) {
        context.dataStore.edit { prefs -> prefs[KEY_ASSIST_MIC] = micKey }
    }

    suspend fun setAssistPipelineId(pipelineId: String) {
        context.dataStore.edit { prefs -> prefs[KEY_ASSIST_PIPELINE] = pipelineId }
    }

    suspend fun setAutoCloseSeconds(seconds: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTO_CLOSE] = seconds }
    }

    suspend fun setOverlayPosition(position: OverlayPosition) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_POSITION] = position.name }
    }

    suspend fun setOverlayCornerRadius(dp: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_CORNER] = dp }
    }

    suspend fun setOverlayMargin(dp: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_MARGIN] = dp }
    }

    suspend fun setOverlayOpacity(percent: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_OPACITY] = percent }
    }

    suspend fun setOverlaySizeScale(percent: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_SIZE] = percent }
    }

    suspend fun setOverlayAnimStyle(style: String) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_ANIM] = style }
    }

    suspend fun setOverlayAnimSpeedMs(ms: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_ANIM_MS] = ms }
    }

    /** Reset every overlay appearance/motion setting (shape, size, colors, motion) to defaults. */
    suspend fun resetAppearance() {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_POSITION] = OverlayPosition.RIGHT.name
            prefs[KEY_OVERLAY_CORNER] = 22
            prefs[KEY_OVERLAY_MARGIN] = 32
            prefs[KEY_OVERLAY_OPACITY] = 95
            prefs[KEY_OVERLAY_SIZE] = DEFAULT_OVERLAY_SIZE
            prefs[KEY_OVERLAY_ANIM] = DEFAULT_OVERLAY_ANIM
            prefs[KEY_OVERLAY_ANIM_MS] = DEFAULT_OVERLAY_ANIM_MS
            prefs[KEY_OVERLAY_BG] = DEFAULT_OVERLAY_BG
            prefs[KEY_OVERLAY_TILE] = DEFAULT_OVERLAY_TILE
            prefs[KEY_OVERLAY_ACCENT] = DEFAULT_OVERLAY_ACCENT
            prefs[KEY_OVERLAY_BORDER] = DEFAULT_OVERLAY_ACCENT
            prefs[KEY_OVERLAY_BORDER_ON] = true
            prefs[KEY_OVERLAY_ICON_ON] = DEFAULT_OVERLAY_ICON_ON
            prefs[KEY_OVERLAY_ICON_OFF] = DEFAULT_OVERLAY_ICON_OFF
            prefs[KEY_OVERLAY_FOCUS] = DEFAULT_OVERLAY_ACCENT
        }
    }

    suspend fun setOverlayBgColor(argb: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_BG] = argb }
    }

    suspend fun setOverlayTileColor(argb: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_TILE] = argb }
    }

    suspend fun setOverlayAccentColor(argb: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_ACCENT] = argb }
    }

    suspend fun setOverlayBorderColor(argb: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_BORDER] = argb }
    }

    suspend fun setOverlayBorderEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_BORDER_ON] = enabled }
    }

    suspend fun setOverlayIconOnColor(argb: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_ICON_ON] = argb }
    }

    suspend fun setOverlayIconOffColor(argb: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_ICON_OFF] = argb }
    }

    suspend fun setOverlayFocusColor(argb: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_OVERLAY_FOCUS] = argb }
    }

    suspend fun setKeepAlive(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_KEEP_ALIVE] = enabled }
    }

    suspend fun setAnnounceEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_ANNOUNCE_ENABLED] = enabled }
    }

    suspend fun setAnnounceVolume(volume: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_ANNOUNCE_VOLUME] = volume.coerceIn(0, 100) }
    }

    suspend fun setAnnounceDuckMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[KEY_ANNOUNCE_DUCK] = mode }
    }

    suspend fun setAnnounceLanguage(language: String) {
        context.dataStore.edit { prefs -> prefs[KEY_ANNOUNCE_LANG] = language.trim() }
    }

    suspend fun setAnnounceSpeakMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[KEY_ANNOUNCE_SPEAK_MODE] = mode }
    }

    suspend fun setAnnounceSoundRepeat(mode: String) {
        context.dataStore.edit { prefs -> prefs[KEY_ANNOUNCE_SOUND_REPEAT] = mode }
    }

    suspend fun setAnnounceSpeakRepeat(mode: String) {
        context.dataStore.edit { prefs -> prefs[KEY_ANNOUNCE_SPEAK_REPEAT] = mode }
    }

    suspend fun setAnnounceRepeatGap(seconds: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_ANNOUNCE_REPEAT_GAP] = seconds.coerceIn(0, 60) }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_NOTIFY_ENABLED] = enabled }
    }

    suspend fun setNotificationDefaultDuration(seconds: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_NOTIFY_DURATION] = seconds }
    }

    suspend fun setInteractiveEnlargeTimeout(seconds: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_ENLARGE_TIMEOUT] = seconds.coerceIn(0, 3600) }
    }

    suspend fun setNotificationToken(token: String) {
        context.dataStore.edit { prefs -> prefs[KEY_NOTIFY_TOKEN] = SecretCrypto.encrypt(token.trim()) }
    }

    suspend fun setGoogleMapsApiKey(key: String) {
        context.dataStore.edit { prefs -> prefs[KEY_MAPS_KEY] = SecretCrypto.encrypt(key.trim()) }
    }

    suspend fun setMapStyle(style: String) {
        context.dataStore.edit { prefs -> prefs[KEY_MAP_STYLE] = style }
    }

    suspend fun setMapTraffic(on: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_MAP_TRAFFIC] = on }
    }

    suspend fun setStreamPlayer(player: String) {
        context.dataStore.edit { prefs -> prefs[KEY_STREAM_PLAYER] = player }
    }

    suspend fun setDimLevel(level: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_DIM_LEVEL] = level.coerceIn(0, 95) }
    }

    suspend fun setClockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_CLOCK_ON] = enabled }
    }

    suspend fun setClockCorner(corner: DisplayCorner) {
        context.dataStore.edit { prefs -> prefs[KEY_CLOCK_CORNER] = corner.name }
    }

    suspend fun setClockColor(argb: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_CLOCK_COLOR] = argb }
    }

    suspend fun setClockSeconds(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_CLOCK_SECONDS] = enabled }
    }

    suspend fun setClock24Hour(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_CLOCK_24H] = enabled }
    }

    suspend fun setClockSize(sp: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_CLOCK_SIZE] = sp.coerceIn(16, 120) }
    }

    /** Applies a remote `/set/overlay` push (TvOverlay-compatible field names) in one write. */
    suspend fun applyOverlayCommand(fields: Map<String, String>) {
        context.dataStore.edit { prefs ->
            (fields["dim"] ?: fields["overlayVisibility"])?.toIntOrNull()?.let {
                prefs[KEY_DIM_LEVEL] = it.coerceIn(0, 95)
            }
            (fields["clock"] ?: fields["clockOverlayVisibility"])?.let { raw ->
                val on = raw.toBooleanStrictOrNull() ?: (raw.toIntOrNull()?.let { it > 0 })
                if (on != null) prefs[KEY_CLOCK_ON] = on
            }
            (fields["corner"] ?: fields["hotCorner"])?.let {
                prefs[KEY_CLOCK_CORNER] = DisplayCorner.fromAny(it).name
            }
        }
    }

    /** Apply a whole color theme at once (one atomic write). */
    suspend fun applyOverlayColors(
        bg: Int, tile: Int, accent: Int, border: Int, borderOn: Boolean, iconOn: Int, iconOff: Int, focus: Int,
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_BG] = bg
            prefs[KEY_OVERLAY_TILE] = tile
            prefs[KEY_OVERLAY_ACCENT] = accent
            prefs[KEY_OVERLAY_BORDER] = border
            prefs[KEY_OVERLAY_BORDER_ON] = borderOn
            prefs[KEY_OVERLAY_ICON_ON] = iconOn
            prefs[KEY_OVERLAY_ICON_OFF] = iconOff
            prefs[KEY_OVERLAY_FOCUS] = focus
        }
    }

    /** True on Android 10+ (Q), where Download backups go through MediaStore instead of raw files. */
    private val usesMediaStore: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q

    /** Directory for file-based backups at [location] (APP always; DOWNLOAD only pre-Q; USB). */
    private fun backupDir(location: BackupLocation): File? = when (location) {
        BackupLocation.APP -> context.getExternalFilesDir(null)
        BackupLocation.DOWNLOAD ->
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        BackupLocation.USB -> usbRoot()
    }

    // --- MediaStore-backed backups (Android 10+) ---------------------------------------------------
    // The public Downloads folder (and a USB drive) need "All files access" for raw File I/O on
    // Android 11+, and that grant screen is broken on some TVs. MediaStore lets us write/list/read/
    // delete the app's OWN backup files there with no permission at all. Both DOWNLOAD (internal
    // Downloads) and USB (a removable volume's Downloads) go through here; "paths" are content:// URIs.

    /**
     * The MediaStore Downloads collection backing [location], or null if unavailable (APP, or no USB).
     *
     * Only reached behind [usesMediaStore]; the annotation states that contract explicitly, since
     * lint can't follow a guard expressed as a property getter and otherwise reports a false NewApi.
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private fun mediaStoreCollection(location: BackupLocation): Uri? = when (location) {
        BackupLocation.DOWNLOAD -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        BackupLocation.USB -> removableVolumeName()?.let { MediaStore.Downloads.getContentUri(it) }
        BackupLocation.APP -> null
    }

    /** Name of the first removable (USB/SD) MediaStore volume, or null if none is mounted. */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private fun removableVolumeName(): String? =
        MediaStore.getExternalVolumeNames(context)
            .firstOrNull { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }

    /** Writes a backup into a MediaStore Downloads [collection]; returns its content:// URI. */
    private fun exportToMediaStore(text: String, collection: Uri): String {
        val resolver = context.contentResolver
        // IS_PENDING hides the row while we write, and clearing it finalises the entry so MediaStore
        // populates SIZE and DATE_MODIFIED (otherwise the list shows "0 B" / epoch-0 right after write).
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, newBackupName())
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create the backup file")
        try {
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw IOException("Could not open the backup file for writing")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // don't leave a half-written pending row behind
            throw e
        }
        return uri.toString()
    }

    /** Lists the app's own backup files in a MediaStore [collection], newest first. */
    private fun mediaStoreBackups(collection: Uri, location: BackupLocation): List<BackupInfo> {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.DATE_ADDED,
            MediaStore.Downloads.SIZE,
        )
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("$BACKUP_PREFIX%.json")
        val out = mutableListOf<BackupInfo>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            val dateAddedCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                // Both dates are epoch SECONDS; fall back to DATE_ADDED if MODIFIED isn't populated yet.
                val secs = c.getLong(dateCol).takeIf { it > 0 } ?: c.getLong(dateAddedCol)
                out += BackupInfo(
                    path = uri.toString(),
                    name = c.getString(nameCol),
                    timestampMs = secs * 1000L,
                    sizeBytes = c.getLong(sizeCol),
                    location = location,
                )
            }
        }
        return out.sortedByDescending { it.timestampMs }
    }

    /** Newest existing backup at [location] (files are named "tv-assist-<model>-<ts>.json"). */
    private fun latestBackupFile(location: BackupLocation): File? =
        backupFiles(location).maxByOrNull { it.lastModified() }

    /** All backup files at [location] (name "tv-assist-<model>-<ts>.json"), unordered. */
    private fun backupFiles(location: BackupLocation): List<File> =
        backupDir(location)
            ?.listFiles { f -> f.isFile && f.name.startsWith(BACKUP_PREFIX) && f.name.endsWith(".json") }
            ?.toList()
            ?: emptyList()

    /** Backups at [location], newest first, for the restore picker. Blocking file I/O. */
    suspend fun listBackups(location: BackupLocation): List<BackupInfo> = withContext(Dispatchers.IO) {
        if (usesMediaStore && location != BackupLocation.APP) {
            val collection = mediaStoreCollection(location) ?: return@withContext emptyList()
            return@withContext mediaStoreBackups(collection, location)
        }
        backupFiles(location)
            .sortedByDescending { it.lastModified() }
            .map { BackupInfo(it.absolutePath, it.name, it.lastModified(), it.length(), location) }
    }

    /** Deletes the backup at [path]. Returns true if it was removed. Blocking I/O. */
    suspend fun deleteBackup(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (path.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(path), null, null) > 0
            } else {
                File(path).takeIf { it.isFile }?.delete() == true
            }
        }.getOrDefault(false)
    }

    /** Filename = app name + TV model + timestamp, e.g. tv-assist-BRAVIA_4K_UR3-20260705_143000.json */
    private fun newBackupName(): String {
        val model = android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "tv" }
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        return "$BACKUP_PREFIX$model-$ts.json"
    }

    /** First removable/USB volume root, if any (Android TVs mount these under /storage). */
    fun usbRoot(): File? = runCatching {
        File("/storage").listFiles()?.firstOrNull {
            it.isDirectory && it.canRead() && it.name !in setOf("emulated", "self", "sdcard0")
        }
    }.getOrNull()

    /** App-private file holding the setup console's persisted self-signed TLS keystore. */
    fun tlsKeystoreFile(): File = File(context.filesDir, "tv_setup_cert.p12")

    /**
     * Writes settings to a timestamped file at [location]; [includeSecrets] keeps HA token + API keys.
     * Returns the written file's absolute path, or a failure describing why it couldn't be written.
     */
    suspend fun exportBackup(
        location: BackupLocation,
        includeSecrets: Boolean,
        passphrase: String = "",
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val text = json.encodeToString(SettingsBackup.serializer(), buildBackup(includeSecrets, passphrase))
            // Android 10+ writes Download/USB via MediaStore (no permission needed).
            if (usesMediaStore && location != BackupLocation.APP) {
                val collection = mediaStoreCollection(location)
                    ?: return@withContext Result.failure(IOException("No ${location.label} location available"))
                return@withContext runCatching { exportToMediaStore(text, collection) }
            }
            // File/USB enumeration + write are blocking; keep them off the (Main) caller thread.
            val dir = backupDir(location)
                ?: return@withContext Result.failure(IOException("No ${location.label} location available"))
            runCatching {
                dir.mkdirs()
                val file = File(dir, newBackupName())
                file.writeText(text)
                file.absolutePath
            }
        }

    /** The backup as JSON text (for the web console to download), without writing a file. */
    suspend fun exportBackupText(includeSecrets: Boolean, passphrase: String = ""): String =
        json.encodeToString(SettingsBackup.serializer(), buildBackup(includeSecrets, passphrase))

    /** Suggested filename for a downloaded backup (app + TV model + timestamp). */
    fun backupFileName(): String = newBackupName()

    private suspend fun buildBackup(includeSecrets: Boolean, passphrase: String): SettingsBackup {
        val s = settings.first()
        // A secret is dropped when not included; encrypted with the passphrase when one is given; only
        // left plaintext if the caller opts into secrets without a passphrase (the UI requires one).
        fun secret(value: String): String = when {
            !includeSecrets -> ""
            passphrase.isNotEmpty() -> BackupCrypto.encrypt(value, passphrase)
            else -> value
        }
        return SettingsBackup(
            baseUrl = s.baseUrl, token = secret(s.token), verifySsl = s.verifySsl,
            importedEntityIds = s.importedEntityIds, sidebarEntityIds = s.sidebarEntityIds,
            overlayLayout = s.overlayLayout, entityOverrides = s.entityOverrides,
            triggerKeyCode = s.triggerKeyCode, micKeyCode = s.micKeyCode,
            assistMicId = s.assistMicId,
            assistPipelineId = s.assistPipelineId,
            autoCloseSeconds = s.autoCloseSeconds,
            overlayPosition = s.overlayPosition, overlayCornerRadius = s.overlayCornerRadius,
            overlayMargin = s.overlayMargin, overlayOpacity = s.overlayOpacity,
            overlayBgColor = s.overlayBgColor, overlayTileColor = s.overlayTileColor,
            overlayAccentColor = s.overlayAccentColor, overlayBorderColor = s.overlayBorderColor,
            overlayBorderEnabled = s.overlayBorderEnabled, overlayIconOnColor = s.overlayIconOnColor,
            overlayIconOffColor = s.overlayIconOffColor, overlayFocusColor = s.overlayFocusColor,
            overlaySizeScale = s.overlaySizeScale, overlayAnimStyle = s.overlayAnimStyle,
            overlayAnimSpeedMs = s.overlayAnimSpeedMs,
            dimLevel = s.dimLevel, clockEnabled = s.clockEnabled, clockCorner = s.clockCorner,
            clockColor = s.clockColor, clockSeconds = s.clockSeconds, clock24Hour = s.clock24Hour,
            clockSize = s.clockSize, localCameras = s.localCameras, mapCards = s.mapCards,
            keepAlive = s.keepAlive, notificationsEnabled = s.notificationsEnabled,
            notificationPort = s.notificationPort, notificationDefaultDuration = s.notificationDefaultDuration,
            interactiveEnlargeTimeout = s.interactiveEnlargeTimeout,
            notificationToken = secret(s.notificationToken), streamPlayer = s.streamPlayer,
            googleMapsApiKey = secret(s.googleMapsApiKey), mapStyle = s.mapStyle, mapTraffic = s.mapTraffic,
            announceEnabled = s.announceEnabled, announceVolume = s.announceVolume,
            announceDuckMode = s.announceDuckMode, announceLanguage = s.announceLanguage,
            announceSpeakMode = s.announceSpeakMode,
            announceSoundRepeat = s.announceSoundRepeat, announceSpeakRepeat = s.announceSpeakRepeat,
            announceRepeatGap = s.announceRepeatGap,
        )
    }

    /** Reads the newest backup at [location] and applies it to settings. */
    suspend fun importBackup(location: BackupLocation): Result<SettingsBackup> {
        // listBackups already returns newest-first and covers both file- and MediaStore-backed locations.
        val newest = listBackups(location).firstOrNull()
            ?: return Result.failure(IOException("No backup found at ${location.label}"))
        return restoreFromFile(newest.path)
    }

    /**
     * Reads the backup at [path] (a file path or content:// URI) and applies it. [passphrase] decrypts
     * secret fields that were encrypted at backup time; plaintext/older backups ignore it, and encrypted
     * secrets with a wrong/blank passphrase are simply dropped (the rest of the backup still restores).
     */
    suspend fun restoreFromFile(path: String, passphrase: String = ""): Result<SettingsBackup> = withContext(Dispatchers.IO) {
        val raw = try {
            if (path.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(path))?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: return@withContext Result.failure(IOException("Backup not found: $path"))
            } else {
                val file = File(path)
                if (!file.exists()) return@withContext Result.failure(IOException("Backup not found: $path"))
                file.readText()
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        restoreFromText(raw, passphrase)
    }

    /** Restores directly from backup JSON [text] (e.g. pasted/uploaded via the web console). */
    suspend fun restoreFromText(text: String, passphrase: String = ""): Result<SettingsBackup> = withContext(Dispatchers.IO) {
      try {
        run {
            // Tolerate a UTF-8 BOM that some editors prepend.
            val cleaned = text.removePrefix("\uFEFF")
            val backup = json.decodeFromString(SettingsBackup.serializer(), cleaned)
            // Decrypt any passphrase-encrypted secrets (plaintext values pass through; a bad/blank
            // passphrase on an encrypted value yields "" so the rest of the restore still succeeds).
            val haToken = BackupCrypto.decrypt(backup.token, passphrase) ?: ""
            val notifToken = BackupCrypto.decrypt(backup.notificationToken, passphrase) ?: ""
            val mapsKey = BackupCrypto.decrypt(backup.googleMapsApiKey, passphrase) ?: ""
            context.credentialsStore.edit { creds ->
                creds[KEY_BASE_URL] = backup.baseUrl
                creds[KEY_TOKEN] = SecretCrypto.encrypt(haToken)
            }
            context.dataStore.edit { prefs ->
                prefs.remove(KEY_BASE_URL)
                prefs.remove(KEY_TOKEN)
                prefs[KEY_VERIFY_SSL] = backup.verifySsl
                prefs[KEY_IMPORTED] =
                    json.encodeToString(ListSerializer(String.serializer()), backup.importedEntityIds)
                prefs[KEY_SIDEBAR_ORDERED] =
                    json.encodeToString(ListSerializer(String.serializer()), backup.sidebarEntityIds)
                prefs[KEY_OVERLAY_LAYOUT] =
                    json.encodeToString(OverlayLayout.serializer(), backup.overlayLayout)
                prefs[KEY_OVERRIDES] =
                    json.encodeToString(overridesSerializer, backup.entityOverrides)
                prefs[KEY_TRIGGER] = backup.triggerKeyCode
                prefs[KEY_MIC] = backup.micKeyCode

                prefs[KEY_ASSIST_MIC] = backup.assistMicId
                prefs[KEY_ASSIST_PIPELINE] = backup.assistPipelineId
                prefs[KEY_AUTO_CLOSE] = backup.autoCloseSeconds
                prefs[KEY_OVERLAY_POSITION] = backup.overlayPosition
                prefs[KEY_OVERLAY_CORNER] = backup.overlayCornerRadius
                prefs[KEY_OVERLAY_MARGIN] = backup.overlayMargin
                prefs[KEY_OVERLAY_OPACITY] = backup.overlayOpacity
                prefs[KEY_OVERLAY_BG] = backup.overlayBgColor
                prefs[KEY_OVERLAY_TILE] = backup.overlayTileColor
                prefs[KEY_OVERLAY_ACCENT] = backup.overlayAccentColor
                prefs[KEY_OVERLAY_BORDER] = backup.overlayBorderColor
                prefs[KEY_OVERLAY_BORDER_ON] = backup.overlayBorderEnabled
                prefs[KEY_OVERLAY_ICON_ON] = backup.overlayIconOnColor
                prefs[KEY_OVERLAY_ICON_OFF] = backup.overlayIconOffColor
                prefs[KEY_OVERLAY_FOCUS] = backup.overlayFocusColor
                prefs[KEY_OVERLAY_SIZE] = backup.overlaySizeScale
                prefs[KEY_OVERLAY_ANIM] = backup.overlayAnimStyle
                prefs[KEY_OVERLAY_ANIM_MS] = backup.overlayAnimSpeedMs
                prefs[KEY_DIM_LEVEL] = backup.dimLevel
                prefs[KEY_CLOCK_ON] = backup.clockEnabled
                prefs[KEY_CLOCK_CORNER] = backup.clockCorner
                prefs[KEY_CLOCK_COLOR] = backup.clockColor
                prefs[KEY_CLOCK_SECONDS] = backup.clockSeconds
                prefs[KEY_CLOCK_24H] = backup.clock24Hour
                prefs[KEY_CLOCK_SIZE] = backup.clockSize
                prefs[KEY_LOCAL_CAMERAS] = json.encodeToString(localCamerasSerializer, backup.localCameras)
                prefs[KEY_MAP_CARDS] = json.encodeToString(mapCardsSerializer, backup.mapCards)
                prefs[KEY_KEEP_ALIVE] = backup.keepAlive
                prefs[KEY_NOTIFY_ENABLED] = backup.notificationsEnabled
                prefs[KEY_NOTIFY_PORT] = backup.notificationPort
                prefs[KEY_NOTIFY_DURATION] = backup.notificationDefaultDuration
                prefs[KEY_ENLARGE_TIMEOUT] = backup.interactiveEnlargeTimeout
                prefs[KEY_NOTIFY_TOKEN] = SecretCrypto.encrypt(notifToken)
                prefs[KEY_STREAM_PLAYER] = backup.streamPlayer
                prefs[KEY_MAPS_KEY] = SecretCrypto.encrypt(mapsKey)
                prefs[KEY_MAP_STYLE] = backup.mapStyle
                prefs[KEY_MAP_TRAFFIC] = backup.mapTraffic
                prefs[KEY_ANNOUNCE_ENABLED] = backup.announceEnabled
                prefs[KEY_ANNOUNCE_VOLUME] = backup.announceVolume
                prefs[KEY_ANNOUNCE_DUCK] = backup.announceDuckMode
                prefs[KEY_ANNOUNCE_LANG] = backup.announceLanguage
                prefs[KEY_ANNOUNCE_SPEAK_MODE] = backup.announceSpeakMode
                prefs[KEY_ANNOUNCE_SOUND_REPEAT] = backup.announceSoundRepeat
                prefs[KEY_ANNOUNCE_SPEAK_REPEAT] = backup.announceSpeakRepeat
                prefs[KEY_ANNOUNCE_REPEAT_GAP] = backup.announceRepeatGap
            }
            // Return the DECRYPTED secrets so callers (e.g. reconnect-after-restore) get the real token,
            // not the "enc1:" ciphertext that was in the file.
            Result.success(backup.copy(token = haToken, notificationToken = notifToken, googleMapsApiKey = mapsKey))
        }
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

    private companion object {
        const val BACKUP_PREFIX = "tv-assist-"
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_VERIFY_SSL = booleanPreferencesKey("verify_ssl")
        // Imported pool (JSON list of entity ids) the app tracks/loads.
        val KEY_IMPORTED = stringPreferencesKey("imported_entity_ids")
        // Ordered list (JSON) of sidebar entity ids; KEY_SIDEBAR_SET is the legacy set.
        val KEY_SIDEBAR_ORDERED = stringPreferencesKey("sidebar_entity_ids")
        val KEY_SIDEBAR_SET = stringSetPreferencesKey("sidebar_entities")
        // Structured overlay layout (JSON OverlayLayout).
        val KEY_OVERLAY_LAYOUT = stringPreferencesKey("overlay_layout")
        // Per-entity overrides (JSON Map<entityId, EntityOverride>).
        val KEY_OVERRIDES = stringPreferencesKey("entity_overrides")
        val KEY_TRIGGER = intPreferencesKey("trigger_key_code")
        val KEY_MIC = intPreferencesKey("mic_key_code")

        val KEY_ASSIST_MIC = stringPreferencesKey("assist_mic_id")
        val KEY_ASSIST_PIPELINE = stringPreferencesKey("assist_pipeline_id")
        val KEY_AUTO_CLOSE = intPreferencesKey("auto_close_seconds")
        val KEY_OVERLAY_POSITION = stringPreferencesKey("overlay_position")
        val KEY_OVERLAY_CORNER = intPreferencesKey("overlay_corner_radius")
        val KEY_OVERLAY_MARGIN = intPreferencesKey("overlay_margin")
        val KEY_OVERLAY_OPACITY = intPreferencesKey("overlay_opacity")
        val KEY_OVERLAY_BG = intPreferencesKey("overlay_bg_color")
        val KEY_OVERLAY_TILE = intPreferencesKey("overlay_tile_color")
        val KEY_OVERLAY_ACCENT = intPreferencesKey("overlay_accent_color")
        val KEY_OVERLAY_BORDER = intPreferencesKey("overlay_border_color")
        val KEY_OVERLAY_BORDER_ON = booleanPreferencesKey("overlay_border_enabled")
        val KEY_OVERLAY_ICON_ON = intPreferencesKey("overlay_icon_on_color")
        val KEY_OVERLAY_ICON_OFF = intPreferencesKey("overlay_icon_off_color")
        val KEY_OVERLAY_FOCUS = intPreferencesKey("overlay_focus_color")
        val KEY_OVERLAY_SIZE = intPreferencesKey("overlay_size_scale")
        val KEY_OVERLAY_ANIM = stringPreferencesKey("overlay_anim_style")
        val KEY_OVERLAY_ANIM_MS = intPreferencesKey("overlay_anim_speed_ms")
        val KEY_KEEP_ALIVE = booleanPreferencesKey("keep_alive")
        val KEY_NOTIFY_ENABLED = booleanPreferencesKey("notifications_enabled")
        val KEY_NOTIFY_PORT = intPreferencesKey("notification_port")
        val KEY_NOTIFY_DURATION = intPreferencesKey("notification_default_duration")
        val KEY_ENLARGE_TIMEOUT = intPreferencesKey("interactive_enlarge_timeout")
        val KEY_NOTIFY_TOKEN = stringPreferencesKey("notification_token")
        val KEY_STREAM_PLAYER = stringPreferencesKey("stream_player")
        val KEY_MAPS_KEY = stringPreferencesKey("google_maps_key")
        val KEY_MAP_STYLE = stringPreferencesKey("map_style")
        val KEY_MAP_TRAFFIC = booleanPreferencesKey("map_traffic")
        val KEY_ANNOUNCE_ENABLED = booleanPreferencesKey("announce_enabled")
        val KEY_ANNOUNCE_VOLUME = intPreferencesKey("announce_volume")
        val KEY_ANNOUNCE_DUCK = stringPreferencesKey("announce_duck_mode")
        val KEY_ANNOUNCE_LANG = stringPreferencesKey("announce_language")
        val KEY_ANNOUNCE_SPEAK_MODE = stringPreferencesKey("announce_speak_mode")
        val KEY_ANNOUNCE_SOUND_REPEAT = stringPreferencesKey("announce_sound_repeat")
        val KEY_ANNOUNCE_SPEAK_REPEAT = stringPreferencesKey("announce_speak_repeat")
        val KEY_ANNOUNCE_REPEAT_GAP = intPreferencesKey("announce_repeat_gap")
        val KEY_LOCAL_CAMERAS = stringPreferencesKey("local_cameras")
        val KEY_MAP_CARDS = stringPreferencesKey("map_cards")
        val KEY_FIXED_PILLS = stringPreferencesKey("fixed_pills")
        val KEY_NOTIFICATIONS = stringPreferencesKey("persistent_notifications")
        val KEY_DIM_LEVEL = intPreferencesKey("dim_level")
        val KEY_CLOCK_ON = booleanPreferencesKey("clock_enabled")
        val KEY_CLOCK_CORNER = stringPreferencesKey("clock_corner")
        val KEY_CLOCK_COLOR = intPreferencesKey("clock_color")
        val KEY_CLOCK_SECONDS = booleanPreferencesKey("clock_seconds")
        val KEY_CLOCK_24H = booleanPreferencesKey("clock_24h")
        val KEY_CLOCK_SIZE = intPreferencesKey("clock_size")
    }
}
