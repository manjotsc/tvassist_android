package com.tvassist.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tvassist.data.ha.ConnectionState
import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.web.SetupWebServer
import com.tvassist.data.settings.DisplayCorner
import com.tvassist.data.settings.OverlayLayout
import com.tvassist.data.settings.OverlayPill
import com.tvassist.data.settings.OverlayRow
import com.tvassist.data.settings.OverlayTile
import com.tvassist.data.settings.Settings
import com.tvassist.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class ConnectionViewModel(
    val repository: HaRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val entities: StateFlow<List<Entity>> = repository.entities

    /** Domain-aware service calls for the entity control cards. */
    val controlActions = EntityControlActions(repository)

    val settings: StateFlow<Settings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    // --- Import: browse ALL of HA once and pick which entities the app should track ---
    private val _importerAll = MutableStateFlow<List<Entity>>(emptyList())
    val importerAll: StateFlow<List<Entity>> = _importerAll.asStateFlow()

    private val _importerLoading = MutableStateFlow(false)
    val importerLoading: StateFlow<Boolean> = _importerLoading.asStateFlow()

    // Assist pipelines for the Triggers page. Null = not loaded, or the request failed — which is
    // deliberately distinct from an instance that genuinely has none configured.
    private val _assistPipelines = MutableStateFlow<com.tvassist.data.ha.AssistPipelines?>(null)
    val assistPipelines: StateFlow<com.tvassist.data.ha.AssistPipelines?> = _assistPipelines.asStateFlow()

    private val _assistPipelinesLoading = MutableStateFlow(false)
    val assistPipelinesLoading: StateFlow<Boolean> = _assistPipelinesLoading.asStateFlow()

    private val _importSearch = MutableStateFlow("")
    val importSearch: StateFlow<String> = _importSearch.asStateFlow()

    fun setImportSearch(query: String) { _importSearch.value = query }

    /**
     * The importer catalogue filtered by the (debounced) search and grouped by category,
     * computed off the main thread so typing stays smooth on a large HA instance.
     */
    @OptIn(FlowPreview::class)
    val importerGroups: StateFlow<List<Pair<String, List<Entity>>>> =
        combine(
            _importerAll,
            _importSearch.debounce(180).onStart { emit("") }.distinctUntilChanged(),
        ) { all, query ->
            val filtered = if (query.isBlank()) {
                all
            } else {
                val q = query.trim().lowercase()
                all.filter { it.nameLower.contains(q) || it.idLower.contains(q) }
            }
            groupEntitiesByCategory(filtered)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Fetch the full entity catalogue from HA (once) for the import picker. */
    fun loadImporter() {
        viewModelScope.launch {
            _importerLoading.value = true
            _importerAll.value = repository.fetchAllStates()
            _importerLoading.value = false
        }
    }

    fun clearImporter() {
        _importerAll.value = emptyList()
        _importSearch.value = ""
    }

    /** Add/remove an entity from the imported pool (what the app tracks). Atomic. */
    fun toggleImport(entityId: String) {
        viewModelScope.launch { settingsStore.toggleImportedEntity(entityId) }
    }

    /** Ensure an entity is in the imported pool (so the overlay can track it). */
    fun ensureImported(entityId: String) {
        viewModelScope.launch { settingsStore.ensureImportedEntity(entityId) }
    }

    /** Save a per-entity customization (name/icon/press actions). */
    fun setEntityOverride(override: com.tvassist.data.settings.EntityOverride) {
        viewModelScope.launch { settingsStore.setEntityOverride(override) }
    }

    // --- Overlay layout editor ---
    fun setOverlayLayout(layout: OverlayLayout) {
        viewModelScope.launch { settingsStore.setOverlayLayout(layout) }
    }

    private fun updateLayout(block: (OverlayLayout) -> OverlayLayout) {
        // Atomic read-modify-write in the store (see updateOverlayLayout): reading settings.value here
        // then writing would race — a rapid second edit reads stale state and clobbers the first.
        viewModelScope.launch { settingsStore.updateOverlayLayout(block) }
    }

    fun addLayoutRow(header: Boolean) = updateLayout { l ->
        val row = if (header) {
            OverlayRow(type = OverlayRow.TYPE_HEADER, title = "Section")
        } else {
            OverlayRow(columns = 1)
        }
        l.copy(rows = l.rows + row)
    }

    fun removeLayoutRow(i: Int) = updateLayout { l ->
        l.copy(rows = l.rows.filterIndexed { idx, _ -> idx != i })
    }

    fun moveLayoutRow(i: Int, up: Boolean) = updateLayout { l ->
        val j = if (up) i - 1 else i + 1
        if (i !in l.rows.indices || j !in l.rows.indices) return@updateLayout l
        val m = l.rows.toMutableList()
        m[i] = m[j].also { m[j] = m[i] }
        l.copy(rows = m)
    }

    fun setRowColumns(i: Int, cols: Int) = updateLayout { l ->
        l.copy(rows = l.rows.mapIndexed { idx, r -> if (idx == i) r.copy(columns = cols.coerceIn(1, MAX_COLUMNS)) else r })
    }

    fun setRowTitle(i: Int, title: String) = updateLayout { l ->
        l.copy(rows = l.rows.mapIndexed { idx, r -> if (idx == i) r.copy(title = title) else r })
    }

    fun addTileToRow(i: Int, entityId: String) = addTilesToRow(i, listOf(entityId))

    /**
     * Add several tiles to a row in ONE update. Adding them via repeated single calls races: each
     * [updateLayout] reads the async settings StateFlow, which hasn't reflected the previous add yet,
     * so only the last write survives. Batching keeps it a single read-modify-write.
     */
    fun addTilesToRow(i: Int, entityIds: List<String>) = updateLayout { it.withTilesAdded(i, entityIds) }

    fun addHeaderPill(i: Int, entityId: String) = addHeaderPills(i, listOf(entityId))

    /** Add several live pills to a header row in ONE update (see [addTilesToRow] on why batching). */
    fun addHeaderPills(i: Int, entityIds: List<String>) = updateLayout { it.withPillsAdded(i, entityIds) }

    /** Remove the pill at [p] from a header row. */
    fun removeHeaderPill(i: Int, p: Int) = updateLayout { l ->
        l.copy(rows = l.rows.mapIndexed { idx, r -> if (idx == i) r.copy(pills = r.pills.filterIndexed { pi, _ -> pi != p }) else r })
    }

    /** Toggle a pill's shown field ("icon" / "name" / "state"). */
    fun togglePillField(i: Int, p: Int, field: String) = updateLayout { l ->
        l.copy(rows = l.rows.mapIndexed { idx, r ->
            if (idx != i) r
            else r.copy(pills = r.pills.mapIndexed { pi, pill ->
                if (pi != p) pill
                else when (field) {
                    "icon" -> pill.copy(showIcon = !pill.showIcon)
                    "name" -> pill.copy(showName = !pill.showName)
                    else -> pill.copy(showState = !pill.showState)
                }
            })
        })
    }

    /** Set a header pill's icon tint (ARGB); 0 = theme default. */
    fun setPillIconColor(i: Int, p: Int, color: Int) = updateLayout { l ->
        l.copy(rows = l.rows.mapIndexed { idx, r ->
            if (idx != i) r
            else r.copy(pills = r.pills.mapIndexed { pi, pill -> if (pi == p) pill.copy(iconColor = color) else pill })
        })
    }

    fun removeTile(i: Int, t: Int) = updateLayout { l ->
        l.copy(rows = l.rows.mapIndexed { idx, r -> if (idx == i) r.copy(tiles = r.tiles.filterIndexed { ti, _ -> ti != t }) else r })
    }

    fun moveTile(i: Int, t: Int, up: Boolean) = updateLayout { l ->
        l.copy(
            rows = l.rows.mapIndexed { idx, r ->
                if (idx != i) {
                    r
                } else {
                    val j = if (up) t - 1 else t + 1
                    if (t !in r.tiles.indices || j !in r.tiles.indices) {
                        r
                    } else {
                        val m = r.tiles.toMutableList()
                        m[t] = m[j].also { m[j] = m[t] }
                        r.copy(tiles = m)
                    }
                }
            },
        )
    }

    fun cycleTileStyle(i: Int, t: Int) = updateLayout { l ->
        l.copy(
            rows = l.rows.mapIndexed { idx, r ->
                if (idx != i) {
                    r
                } else {
                    r.copy(
                        tiles = r.tiles.mapIndexed { ti, tile ->
                            if (ti != t) {
                                tile
                            } else {
                                val next = OverlayTile.CYCLE[(OverlayTile.CYCLE.indexOf(tile.style) + 1) % OverlayTile.CYCLE.size]
                                tile.copy(style = next)
                            }
                        },
                    )
                }
            },
        )
    }

    /** Update one tile in-place (used for per-tile visibility flags). */
    private fun updateTile(i: Int, t: Int, transform: (OverlayTile) -> OverlayTile) = updateLayout { l ->
        l.copy(
            rows = l.rows.mapIndexed { idx, r ->
                if (idx != i) r
                else r.copy(tiles = r.tiles.mapIndexed { ti, tile -> if (ti == t) transform(tile) else tile })
            },
        )
    }

    /** Toggle whether a tile shows the entity name. */
    fun toggleTileName(i: Int, t: Int) = updateTile(i, t) { it.copy(hideName = !it.hideName) }

    /** Toggle whether a tile shows the status/state line. */
    fun toggleTileStatus(i: Int, t: Int) = updateTile(i, t) { it.copy(hideStatus = !it.hideStatus) }

    /** Toggle whether a tile shows the icon. */
    fun toggleTileIcon(i: Int, t: Int) = updateTile(i, t) { it.copy(hideIcon = !it.hideIcon) }

    /** Toggle a person-map option (e.g. battery, distance) on a person tile. */
    fun toggleTilePersonOption(i: Int, t: Int, key: String) = updateTile(i, t) { tile ->
        val next = if (key in tile.personOptions) tile.personOptions - key else tile.personOptions + key
        tile.copy(personOptions = next)
    }

    /** Set the map source (auto/osm/google) for a person tile. */
    fun setTileMapProvider(i: Int, t: Int, provider: String) = updateTile(i, t) { it.copy(mapProvider = provider) }


    // --- Web onboarding (enter credentials from a phone browser) ---
    private val _webOnboarding = MutableStateFlow<WebOnboarding>(WebOnboarding.Off)
    val webOnboarding: StateFlow<WebOnboarding> = _webOnboarding.asStateFlow()
    // Non-null while the Web setup console is running: the PIN to type in the browser (shown in the
    // app nav rail). The console covers connection + cameras + maps, so it isn't auto-stopped.
    private val _setupPin = MutableStateFlow<String?>(null)
    val setupPin: StateFlow<String?> = _setupPin.asStateFlow()
    private var setupServer: SetupWebServer? = null

    init {
        // Auto-connect once on launch if we already have stored credentials.
        viewModelScope.launch {
            val stored = settingsStore.settings.first()
            if (stored.baseUrl.isNotBlank() && stored.token.isNotBlank()) {
                repository.connect(stored.baseUrl, stored.token)
            }
        }
        // Close the onboarding console once the TV is actually connected — the behaviour the README
        // has always described, but which nothing implemented. Leaves it running when it was opened
        // from Settings on a connected TV, where it's the only way to reach cameras/maps/backup.
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state is ConnectionState.Connected && closeOnConnect && _setupPin.value != null) {
                    closeOnConnect = false
                    // Let the browser see the result before the server vanishes. The watch page
                    // polls every 3s, so this guarantees it renders "Connected" (and stops polling)
                    // rather than being killed mid-request and showing a connection error instead.
                    delay(WEB_SETUP_CLOSE_DELAY_MS)
                    // A drop during the wait means the user still needs the console — leave it up
                    // and re-arm, so it closes on the next successful connection instead.
                    if (connectionState.value is ConnectionState.Connected) {
                        stopWebOnboarding()
                    } else {
                        closeOnConnect = true
                    }
                }
            }
        }
    }

    /**
     * True when the console was started while NOT connected, i.e. it's being used to onboard.
     * Only then does it close itself once the TV connects — started from Settings on an
     * already-connected TV, it must stay up so cameras/maps/backup remain reachable.
     */
    private var closeOnConnect = false

    /** Grace period before the console shuts down, so the browser can render the success. */
    private val WEB_SETUP_CLOSE_DELAY_MS = 10_000L

    /** Start the on-demand Web setup console (idempotent). */
    fun startWebOnboarding() {
        if (_setupPin.value != null) return // already running or starting up
        closeOnConnect = connectionState.value !is ConnectionState.Connected
        // Publish the PIN first so no request can slip through the gate before it's live; it also
        // guards the async start below (if it's cleared/changed, we were toggled off meanwhile).
        // Cryptographically-secure so the PIN can't be predicted from the RNG state.
        val pin = (java.security.SecureRandom().nextInt(900000) + 100000).toString()
        _setupPin.value = pin
        viewModelScope.launch {
            // Mint the self-signed TLS cert off the main thread (RSA keygen takes ~100-300ms).
            val ssl = withContext(Dispatchers.Default) {
                val ip = com.tvassist.data.web.TinyHttpServer.localIp()
                runCatching {
                    com.tvassist.data.web.SelfSignedTls.contextFor(ip, settingsStore.tlsKeystoreFile())
                }.getOrNull()
            }
            if (_setupPin.value != pin) return@launch
            val server = SetupWebServer(
                sslContext = ssl,
                pin = { _setupPin.value ?: "" },
                prefillUrl = { settings.value.baseUrl },
                prefillVerifySsl = { settings.value.verifySsl },
                onCredentials = { url, token, verify -> saveAndConnect(url, token, verify) },
                listCameras = { settings.value.localCameras },
                onSaveCamera = { saveLocalCamera(it) },
                onDeleteCamera = { deleteLocalCamera(it) },
                currentMapKey = { settings.value.googleMapsApiKey },
                onSaveMapKey = { setGoogleMapsApiKey(it) },
                connState = {
                    when (connectionState.value) {
                        is ConnectionState.Connected -> "connected"
                        is ConnectionState.Connecting, is ConnectionState.Authenticating -> "connecting"
                        is ConnectionState.Failed -> "failed"
                        else -> "disconnected"
                    }
                },
                // The reason behind a "failed" state, so the phone form can show why rather than a
                // bare "Not connected" — the same message the TV screen shows.
                connError = { (connectionState.value as? ConnectionState.Failed)?.reason.orEmpty() },
                deviceName = { android.os.Build.MODEL ?: "this TV" },
                notifInfo = { settings.value.notificationsEnabled to settings.value.notificationPort },
                notifToken = { settings.value.notificationToken },
                onSaveNotifToken = { setNotificationToken(it) },
                // The console runs on its own daemon thread, so blocking on these suspend calls is fine.
                exportBackup = { inc, pass -> runBlocking { settingsStore.exportBackupText(inc, pass) } },
                restoreBackup = { text, pass ->
                    runBlocking {
                        val result = settingsStore.restoreFromText(text, pass)
                        applyRestore(result) // reconnect + surface status on the TV, like an on-device restore
                        result.isSuccess
                    }
                },
                backupFileName = { settingsStore.backupFileName() },
                onStop = { stopWebOnboarding() },
            )
            if (server.start()) {
                setupServer = server
                _webOnboarding.value = WebOnboarding.Running(server.address())
            } else {
                _setupPin.value = null
                _webOnboarding.value = WebOnboarding.Error("Port ${SetupWebServer.DEFAULT_PORT} is unavailable")
            }
        }
    }

    fun stopWebOnboarding() {
        setupServer?.stop()
        setupServer = null
        _setupPin.value = null
        _webOnboarding.value = WebOnboarding.Off
    }

    override fun onCleared() {
        stopWebOnboarding()
        super.onCleared()
    }

    /**
     * Saves credentials and dials HA. [verifySsl] is written first when supplied, so the connect
     * below already sees the intended TLS mode instead of reconnecting a moment later.
     */
    fun saveAndConnect(baseUrl: String, token: String, verifySsl: Boolean? = null) {
        viewModelScope.launch {
            if (verifySsl != null) settingsStore.setVerifySsl(verifySsl)
            settingsStore.setConnection(baseUrl.trim(), token.trim())
            repository.connect(baseUrl.trim(), token.trim())
        }
    }

    /** Toggle HA certificate verification; the repository re-dials on the change on its own. */
    fun setVerifySsl(on: Boolean) {
        viewModelScope.launch { settingsStore.setVerifySsl(on) }
    }

    fun connectWithStored() {
        val s = settings.value
        if (s.baseUrl.isNotBlank() && s.token.isNotBlank()) {
            repository.connect(s.baseUrl, s.token)
        }
    }

    fun disconnect() = repository.disconnect()

    fun toggle(entity: Entity) = repository.toggle(entity)

    // --- Trigger key ---
    fun setTriggerKey(keyCode: Int) {
        viewModelScope.launch { settingsStore.setTriggerKeyCode(keyCode) }
    }

    fun setMicKeyCode(keyCode: Int) {
        viewModelScope.launch { settingsStore.setMicKeyCode(keyCode) }
    }



    fun setAssistMicId(micKey: String) {
        viewModelScope.launch { settingsStore.setAssistMicId(micKey) }
    }

    fun setAssistPipelineId(pipelineId: String) {
        viewModelScope.launch { settingsStore.setAssistPipelineId(pipelineId) }
    }

    /**
     * Loads the Assist pipelines for the settings picker. Asked for on demand rather than kept in
     * sync: pipelines are edited in Home Assistant, not here, and the only screen that cares is the
     * one the user has just opened.
     */
    fun loadAssistPipelines() {
        viewModelScope.launch {
            _assistPipelinesLoading.value = true
            // Opening this page straight after launch beats the WebSocket to it, and asking early
            // just returns null — an error banner on a perfectly healthy instance. Wait for the
            // connection first, but not forever: a genuinely unreachable HA still has to report.
            withTimeoutOrNull(CONNECT_WAIT_MS) {
                repository.connectionState.first { it == ConnectionState.Connected }
            }
            _assistPipelines.value = repository.fetchAssistPipelines()
            _assistPipelinesLoading.value = false
        }
    }

    fun setAutoCloseSeconds(seconds: Int) {
        viewModelScope.launch { settingsStore.setAutoCloseSeconds(seconds) }
    }

    fun setOverlayPosition(position: com.tvassist.data.settings.OverlayPosition) {
        viewModelScope.launch { settingsStore.setOverlayPosition(position) }
    }

    fun setOverlayCornerRadius(dp: Int) {
        viewModelScope.launch { settingsStore.setOverlayCornerRadius(dp) }
    }

    fun setOverlayMargin(dp: Int) {
        viewModelScope.launch { settingsStore.setOverlayMargin(dp) }
    }

    fun setOverlayOpacity(percent: Int) {
        viewModelScope.launch { settingsStore.setOverlayOpacity(percent) }
    }

    fun setOverlaySizeScale(percent: Int) {
        viewModelScope.launch { settingsStore.setOverlaySizeScale(percent) }
    }

    fun setOverlayAnimStyle(style: String) {
        viewModelScope.launch { settingsStore.setOverlayAnimStyle(style) }
    }

    fun setOverlayAnimSpeedMs(ms: Int) {
        viewModelScope.launch { settingsStore.setOverlayAnimSpeedMs(ms) }
    }

    /** Reset all overlay appearance/motion settings to defaults. */
    fun resetAppearance() {
        viewModelScope.launch { settingsStore.resetAppearance() }
    }

    fun setOverlayBgColor(argb: Int) {
        viewModelScope.launch { settingsStore.setOverlayBgColor(argb) }
    }

    fun setOverlayTileColor(argb: Int) {
        viewModelScope.launch { settingsStore.setOverlayTileColor(argb) }
    }

    fun setOverlayAccentColor(argb: Int) {
        viewModelScope.launch { settingsStore.setOverlayAccentColor(argb) }
    }

    fun setOverlayBorderColor(argb: Int) {
        viewModelScope.launch { settingsStore.setOverlayBorderColor(argb) }
    }

    fun setOverlayBorderEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setOverlayBorderEnabled(enabled) }
    }

    fun setOverlayIconOnColor(argb: Int) {
        viewModelScope.launch { settingsStore.setOverlayIconOnColor(argb) }
    }

    fun setOverlayIconOffColor(argb: Int) {
        viewModelScope.launch { settingsStore.setOverlayIconOffColor(argb) }
    }

    fun setOverlayFocusColor(argb: Int) {
        viewModelScope.launch { settingsStore.setOverlayFocusColor(argb) }
    }

    fun setKeepAlive(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setKeepAlive(enabled) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNotificationsEnabled(enabled) }
    }

    fun setNotificationDefaultDuration(seconds: Int) {
        viewModelScope.launch { settingsStore.setNotificationDefaultDuration(seconds) }
    }

    fun setInteractiveEnlargeTimeout(seconds: Int) {
        viewModelScope.launch { settingsStore.setInteractiveEnlargeTimeout(seconds) }
    }

    fun setStreamPlayer(player: String) {
        viewModelScope.launch { settingsStore.setStreamPlayer(player) }
    }

    fun setNotificationToken(token: String) {
        viewModelScope.launch { settingsStore.setNotificationToken(token) }
    }

    fun setAnnounceEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAnnounceEnabled(enabled) }
    }

    fun setAnnounceVolume(volume: Int) {
        viewModelScope.launch { settingsStore.setAnnounceVolume(volume) }
    }

    fun setAnnounceDuckMode(mode: String) {
        viewModelScope.launch { settingsStore.setAnnounceDuckMode(mode) }
    }

    fun setAnnounceLanguage(language: String) {
        viewModelScope.launch { settingsStore.setAnnounceLanguage(language) }
    }

    fun setAnnounceSpeakMode(mode: String) {
        viewModelScope.launch { settingsStore.setAnnounceSpeakMode(mode) }
    }

    fun setAnnounceSoundRepeat(mode: String) {
        viewModelScope.launch { settingsStore.setAnnounceSoundRepeat(mode) }
    }

    fun setAnnounceSpeakRepeat(mode: String) {
        viewModelScope.launch { settingsStore.setAnnounceSpeakRepeat(mode) }
    }

    fun setAnnounceRepeatGap(seconds: Int) {
        viewModelScope.launch { settingsStore.setAnnounceRepeatGap(seconds) }
    }

    fun setGoogleMapsApiKey(key: String) {
        viewModelScope.launch { settingsStore.setGoogleMapsApiKey(key) }
    }

    fun setMapStyle(style: String) {
        viewModelScope.launch { settingsStore.setMapStyle(style) }
    }

    fun setMapTraffic(on: Boolean) {
        viewModelScope.launch { settingsStore.setMapTraffic(on) }
    }

    fun saveLocalCamera(camera: com.tvassist.data.settings.LocalCamera) {
        viewModelScope.launch { settingsStore.saveLocalCamera(camera) }
    }

    fun deleteLocalCamera(id: String) {
        viewModelScope.launch { settingsStore.deleteLocalCamera(id) }
    }

    fun saveMapCard(card: com.tvassist.data.settings.MapCard) {
        viewModelScope.launch { settingsStore.saveMapCard(card) }
    }

    fun deleteMapCard(id: String) {
        viewModelScope.launch { settingsStore.deleteMapCard(id) }
    }

    fun setDimLevel(level: Int) {
        viewModelScope.launch { settingsStore.setDimLevel(level) }
    }

    fun setClockEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setClockEnabled(enabled) }
    }

    fun setClockCorner(corner: DisplayCorner) {
        viewModelScope.launch { settingsStore.setClockCorner(corner) }
    }

    fun setClockSeconds(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setClockSeconds(enabled) }
    }

    fun setClock24Hour(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setClock24Hour(enabled) }
    }

    fun setClockSize(sp: Int) {
        viewModelScope.launch { settingsStore.setClockSize(sp) }
    }

    fun applyOverlayColors(bg: Int, tile: Int, accent: Int, border: Int, borderOn: Boolean, iconOn: Int, iconOff: Int, focus: Int) {
        viewModelScope.launch { settingsStore.applyOverlayColors(bg, tile, accent, border, borderOn, iconOn, iconOff, focus) }
    }

    // --- Backup & restore ---

    /** UI state for the backup screen: idle, an in-flight op (spinner), or a final result. */
    sealed interface BackupStatus {
        data object Idle : BackupStatus
        /** An operation is running; [message] labels the spinner. */
        data class Loading(val message: String) : BackupStatus
        /** A finished operation. [ok] drives success (green) vs failure (red) styling. */
        data class Message(val text: String, val ok: Boolean) : BackupStatus
    }

    private val _backupStatus = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val backupStatus: StateFlow<BackupStatus> = _backupStatus.asStateFlow()

    /** Backups at the location the screen is currently showing, newest first. */
    private val _backups = MutableStateFlow<List<com.tvassist.data.settings.BackupInfo>>(emptyList())
    val backups: StateFlow<List<com.tvassist.data.settings.BackupInfo>> = _backups.asStateFlow()

    fun clearBackupMessage() { _backupStatus.value = BackupStatus.Idle }

    /** Surface a neutral warning/error message on the backup screen (e.g. missing-permission hints). */
    fun showMessage(msg: String) { _backupStatus.value = BackupStatus.Message(msg, ok = false) }

    fun hasAllFilesAccess(): Boolean = settingsStore.hasAllFilesAccess()

    /** Reload the backup list for [location] (call on entry and after any backup/delete/restore). */
    fun refreshBackups(location: com.tvassist.data.settings.BackupLocation) {
        viewModelScope.launch { _backups.value = settingsStore.listBackups(location) }
    }

    fun backupSettings(
        location: com.tvassist.data.settings.BackupLocation,
        includeSecrets: Boolean = true,
        passphrase: String = "",
    ) {
        viewModelScope.launch {
            _backupStatus.value = BackupStatus.Loading("Backing up…")
            settingsStore.exportBackup(location, includeSecrets, passphrase)
                .onSuccess { path ->
                    _backupStatus.value = BackupStatus.Message("Saved to $path", ok = true)
                    _backups.value = settingsStore.listBackups(location)
                }
                .onFailure { _backupStatus.value = BackupStatus.Message("Backup failed: ${it.message}", ok = false) }
        }
    }

    /** Restore the newest backup at [location] (kept for the debug intent hook). */
    fun restoreSettings(location: com.tvassist.data.settings.BackupLocation) {
        viewModelScope.launch { applyRestore(settingsStore.importBackup(location)) }
    }

    /** Restore a specific backup chosen from the list. [passphrase] decrypts its secrets, if encrypted. */
    fun restoreFrom(info: com.tvassist.data.settings.BackupInfo, passphrase: String = "") {
        viewModelScope.launch {
            _backupStatus.value = BackupStatus.Loading("Restoring…")
            applyRestore(settingsStore.restoreFromFile(info.path, passphrase))
        }
    }

    private suspend fun applyRestore(result: Result<com.tvassist.data.settings.SettingsBackup>) {
        result
            .onSuccess { backup ->
                _backupStatus.value =
                    BackupStatus.Message("Restored backup (${backup.sidebarEntityIds.size} favorites)", ok = true)
                if (backup.baseUrl.isNotBlank() && backup.token.isNotBlank()) {
                    repository.connect(backup.baseUrl, backup.token)
                }
            }
            .onFailure { _backupStatus.value = BackupStatus.Message("Restore failed: ${it.message}", ok = false) }
    }

    /** Delete a backup chosen from the list, then refresh the list. */
    fun deleteBackup(info: com.tvassist.data.settings.BackupInfo) {
        viewModelScope.launch {
            val deleted = settingsStore.deleteBackup(info.path)
            _backupStatus.value =
                if (deleted) BackupStatus.Message("Deleted ${info.name}", ok = true)
                else BackupStatus.Message("Couldn't delete ${info.name}", ok = false)
            _backups.value = settingsStore.listBackups(info.location)
        }
    }

    class Factory(
        private val repository: HaRepository,
        private val settingsStore: SettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConnectionViewModel(repository, settingsStore) as T
    }
}

/** Maximum columns a layout row may have. */
const val MAX_COLUMNS = 12

// How long the pipeline fetch waits for the WebSocket before reporting a failure. Generous: the
// cost of waiting is a spinner, the cost of giving up early is an error on a healthy instance.
private const val CONNECT_WAIT_MS = 8_000L

// Preferred ordering of entity categories (domains); others follow alphabetically.
private val CATEGORY_ORDER = listOf(
    "climate", "light", "switch", "fan", "cover", "lock", "media_player", "camera",
    "vacuum", "scene", "script", "automation", "conversation", "input_boolean", "input_button",
    "button", "binary_sensor", "sensor",
)

/** Groups entities by domain, ordered by [CATEGORY_ORDER] then alphabetically; names sorted. */
fun groupEntitiesByCategory(entities: List<Entity>): List<Pair<String, List<Entity>>> {
    val byDomain = entities.groupBy { it.domain }
    val ordered = LinkedHashMap<String, List<Entity>>()
    CATEGORY_ORDER.forEach { d -> byDomain[d]?.let { ordered[d] = it } }
    byDomain.keys.sorted().forEach { d -> if (d !in ordered) ordered[d] = byDomain.getValue(d) }
    return ordered.entries.map { (d, list) -> d to list.sortedBy { it.nameLower } }
}

/** State of the phone-based onboarding web server. */
sealed interface WebOnboarding {
    data object Off : WebOnboarding
    data class Running(val address: String) : WebOnboarding
    data class Error(val reason: String) : WebOnboarding
}
