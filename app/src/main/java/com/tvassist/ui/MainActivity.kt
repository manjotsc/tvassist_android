package com.tvassist.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.Text
import com.tvassist.R
import com.tvassist.TvAssistApp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatColorReset
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import com.tvassist.data.ha.ConnectionState
import com.tvassist.data.ha.Entity
import com.tvassist.data.settings.DisplayCorner
import com.tvassist.data.settings.EntityOverride
import com.tvassist.data.settings.OverlayRow
import com.tvassist.data.settings.OverlayTile
import com.tvassist.data.settings.PressAction
import com.tvassist.keymap.KeyCaptureService
import com.tvassist.overlay.OverlayService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: ConnectionViewModel by viewModels {
        val app = application as TvAssistApp
        ConnectionViewModel.Factory(app.haRepository, app.settingsStore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start the background service if keep-alive or notifications need it.
        lifecycleScope.launch {
            val s = (application as TvAssistApp).settingsStore.settings.first()
            if (s.keepAlive || s.notificationsEnabled || s.dimLevel > 0 || s.clockEnabled) {
                com.tvassist.overlay.KeepAliveService.start(this@MainActivity)
            }
        }
        // Debug-only test hook: `adb shell am start ... --es ha_url <url> --es ha_token <tok>`
        // and optionally `--es action open_sidebar` to pop the overlay.
        if (com.tvassist.BuildConfig.DEBUG) {
            val u = intent?.getStringExtra("ha_url")
            val t = intent?.getStringExtra("ha_token")
            if (!u.isNullOrBlank() && !t.isNullOrBlank()) viewModel.saveAndConnect(u, t)
            when (intent?.getStringExtra("action")) {
                "open_sidebar" -> window.decorView.postDelayed({ OverlayService.show(this) }, 2500)
                "toggle_first" -> window.decorView.postDelayed({
                    viewModel.entities.value.firstOrNull { it.isToggleable }?.let(viewModel::toggle)
                }, 2500)
                "web" -> viewModel.startWebOnboarding()
                "import_demo" -> {
                    viewModel.toggleImport("light.kitchen")
                    viewModel.toggleImport("switch.fan")
                }
                "import_rich" -> {
                    viewModel.toggleImport("light.living_room")
                    viewModel.toggleImport("climate.living_room")
                    viewModel.toggleImport("switch.fan")
                }
                "seed_layout" -> {
                    viewModel.toggleImport("light.living_room")
                    viewModel.toggleImport("climate.living_room")
                    viewModel.toggleImport("switch.fan")
                    viewModel.setOverlayLayout(
                    com.tvassist.data.settings.OverlayLayout(
                        rows = listOf(
                            OverlayRow(type = OverlayRow.TYPE_HEADER, title = "Lighting"),
                            OverlayRow(columns = 1, tiles = listOf(OverlayTile("light.living_room"))),
                            OverlayRow(type = OverlayRow.TYPE_HEADER, title = "Climate"),
                            OverlayRow(columns = 1, tiles = listOf(OverlayTile("climate.living_room"))),
                            OverlayRow(
                                columns = 2,
                                tiles = listOf(
                                    OverlayTile("switch.fan", OverlayTile.STYLE_COMPACT),
                                    OverlayTile("light.living_room", OverlayTile.STYLE_COMPACT),
                                ),
                            ),
                        ),
                    ),
                    )
                }
                "seed_grid" -> {
                    viewModel.toggleImport("light.living_room")
                    viewModel.toggleImport("climate.living_room")
                    viewModel.toggleImport("switch.fan")
                    viewModel.setOverlayLayout(
                        com.tvassist.data.settings.OverlayLayout(
                            rows = listOf(
                                OverlayRow(
                                    columns = 2,
                                    tiles = listOf(
                                        OverlayTile("light.living_room", OverlayTile.STYLE_COMPACT),
                                        OverlayTile("switch.fan", OverlayTile.STYLE_COMPACT),
                                        OverlayTile("climate.living_room", OverlayTile.STYLE_COMPACT),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
                "autoclose5" -> viewModel.setAutoCloseSeconds(5)
                "look_test" -> {
                    viewModel.setOverlayPosition(com.tvassist.data.settings.OverlayPosition.RIGHT)
                    viewModel.setOverlayCornerRadius(0)   // square
                    viewModel.setOverlayMargin(56)        // large
                    viewModel.setOverlayOpacity(70)       // translucent
                }
                "pos_bottom" -> viewModel.setOverlayPosition(com.tvassist.data.settings.OverlayPosition.BOTTOM)
                "pos_right" -> viewModel.setOverlayPosition(com.tvassist.data.settings.OverlayPosition.RIGHT)
                "pos_left" -> viewModel.setOverlayPosition(com.tvassist.data.settings.OverlayPosition.LEFT)
                "backup" -> viewModel.backupSettings(com.tvassist.data.settings.BackupLocation.APP)
                "restore" -> viewModel.restoreSettings(com.tvassist.data.settings.BackupLocation.APP)
                "trigger" -> viewModel.setTriggerKey(KeyEvent.KEYCODE_GUIDE)
            }
        }

        val initialRoute = if (com.tvassist.BuildConfig.DEBUG) {
            when (intent?.getStringExtra("screen")) {
                "settings" -> Route.SettingsHub
                "import" -> Route.Import
                "layout" -> Route.Overlay
                else -> Route.Home
            }
        } else {
            Route.Home
        }

        val initialCardId = if (com.tvassist.BuildConfig.DEBUG) intent?.getStringExtra("card_id") else null
        // Fullscreen camera/person launched from the overlay (always honored, not debug-only).
        val initialCameraId = intent?.getStringExtra("open_camera")
        val initialPersonId = intent?.getStringExtra("open_person")

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen(
                        viewModel,
                        initialRoute = initialRoute,
                        initialCardId = initialCardId,
                        initialCameraId = initialCameraId,
                        initialPersonId = initialPersonId,
                    )
                }
            }
        }
    }

    // Re-read a fullscreen camera/person request when launched while already running.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("open_camera") || intent.hasExtra("open_person")) {
            setIntent(intent)
            recreate()
        }
    }
}

// --- App chrome palette (premium dark theme) — shared via PremiumComponents.kt ---

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppScreen(
    viewModel: ConnectionViewModel,
    initialRoute: Route = Route.Home,
    initialCardId: String? = null,
    initialCameraId: String? = null,
    initialPersonId: String? = null,
) {
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val entities by viewModel.entities.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val webOnboarding by viewModel.webOnboarding.collectAsStateWithLifecycle()
    val setupPin by viewModel.setupPin.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Connected

    var route by remember { mutableStateOf(initialRoute) }
    var openEntityId by remember { mutableStateOf(initialCardId) }
    // When set, a fullscreen live camera stream is shown for this entity id.
    var cameraStreamId by remember { mutableStateOf(initialCameraId) }
    // When set, a fullscreen person/location map is shown for this entity id.
    var personMapId by remember { mutableStateOf(initialPersonId) }
    // When set, a fullscreen multi-entity map card is shown for this synthetic map entity id.
    var mapCardId by remember { mutableStateOf<String?>(null) }

    // Warm the icon cache for the tracked entities so the overlay opens with icons ready
    // (resolved once per id-set; the cache is process-wide and persists with keep-alive).
    val iconNames = remember(entities, settings.entityOverrides) {
        entities.mapNotNull { resolveIconifyName(it, settings.entityOverrides[it.entityId]) }.distinct()
    }
    LaunchedEffect(iconNames) { if (iconNames.isNotEmpty()) IconStore.prefetch(iconNames) }

    // Hardware BACK: pushed pages return to their parent; top sections return Home.
    parentRoute(route)?.let { parent -> BackHandler { route = parent } }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavRail(
                current = route.section,
                setupPin = setupPin,
                onSelect = { section ->
                    route = when (section) {
                        Section.Home -> Route.Home
                        Section.Overlay -> Route.Overlay
                        Section.Settings -> Route.SettingsHub
                    }
                },
            )
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .background(Brush.verticalGradient(listOf(AppBgTop, AppBgBottom))),
            ) {
                when (route) {
                    Route.Home -> HomeContent(
                        viewModel = viewModel, entities = entities, connected = connected,
                        settings = settings, webOnboarding = webOnboarding, connection = connection,
                        onImport = { route = Route.Import },
                        onCustomize = { route = Route.Customize },
                        onOpenCard = { openEntityId = it },
                        onOpenCamera = { cameraStreamId = it },
                        onOpenPerson = { personMapId = it },
                        onOpenMapCard = { mapCardId = it },
                    )
                    Route.Import -> ImportScreen(viewModel) { viewModel.clearImporter(); route = Route.Home }
                    Route.Customize -> CustomizeScreen(viewModel) { route = Route.Home }
                    Route.Overlay -> LayoutEditorScreen(viewModel) { route = Route.Home }
                    Route.SettingsHub -> SettingsHub(viewModel) { route = it }
                    Route.Connection -> ConnectionPage(viewModel) { route = Route.SettingsHub }
                    Route.Permissions -> PermissionsPage(viewModel) { route = Route.SettingsHub }
                    Route.Triggers -> TriggersPage(viewModel) { route = Route.SettingsHub }
                    Route.Appearance -> AppearancePage(viewModel) { route = Route.SettingsHub }
                    Route.Backup -> BackupPage(viewModel) { route = Route.SettingsHub }
                    Route.Notifications -> NotificationsPage(viewModel) { route = Route.SettingsHub }
                    Route.Display -> DisplayPage(viewModel) { route = Route.SettingsHub }
                    Route.Cameras -> CamerasPage(viewModel) { route = Route.SettingsHub }
                    Route.Maps -> MapsPage(viewModel) { route = Route.SettingsHub }
                    Route.Audio -> AudioPage(viewModel) { route = Route.SettingsHub }
                    Route.Security -> SecurityPage(viewModel) { route = Route.SettingsHub }
                    Route.About -> AboutPage { route = Route.SettingsHub }
                }
            }
        }

        // Full-screen overlays — drawn over the rail + content.
        val openEntity = openEntityId?.let { id -> entities.firstOrNull { it.entityId == id } }
        if (openEntity != null) {
            BackHandler { openEntityId = null }
            EntityControlCard(
                entity = openEntity,
                actions = viewModel.controlActions,
                onDismiss = { openEntityId = null },
            )
        }

        val cameraEntity = cameraStreamId?.let { id -> entities.firstOrNull { it.entityId == id } }
        if (cameraEntity != null) {
            BackHandler { cameraStreamId = null }
            CameraPlayerScreen(
                entity = cameraEntity,
                repository = viewModel.repository,
                onBack = { cameraStreamId = null },
            )
        }

        val personEntity = personMapId?.let { id -> entities.firstOrNull { it.entityId == id } }
        if (personEntity != null) {
            BackHandler { personMapId = null }
            val personTile = settings.overlayLayout.rows.asSequence().flatMap { it.tiles.asSequence() }
                .firstOrNull { it.entityId == personEntity.entityId }
            PersonMapScreen(
                entity = personEntity,
                repository = viewModel.repository,
                options = personTile?.personOptions ?: OverlayTile.PERSON_DEFAULTS,
                mapProvider = personTile?.mapProvider ?: OverlayTile.MAP_AUTO,
                onBack = { personMapId = null },
            )
        }

        val mapEntity = mapCardId?.let { id -> entities.firstOrNull { it.entityId == id } }
        if (mapEntity != null) {
            BackHandler { mapCardId = null }
            val members = mapEntity.mapCardMembers.map { (mid, opts) ->
                PeopleMapMember(entities.firstOrNull { it.entityId == mid } ?: Entity(mid, "unavailable", mid), opts)
            }
            PeopleMapScreen(
                members = members,
                title = mapEntity.friendlyName,
                repository = viewModel.repository,
                zoom = mapEntity.mapCardZoom,
                mapProvider = mapEntity.mapCardProvider,
                showLegend = mapEntity.mapCardShowLegend,
                onBack = { mapCardId = null },
            )
        }
    }
}

private enum class Section { Home, Overlay, Settings }

/** Every navigable page, tagged with the rail [Section] it belongs to. */
private enum class Route(val section: Section) {
    Home(Section.Home), Import(Section.Home), Customize(Section.Home),
    Overlay(Section.Overlay),
    SettingsHub(Section.Settings), Connection(Section.Settings), Permissions(Section.Settings),
    Triggers(Section.Settings), Appearance(Section.Settings), Backup(Section.Settings),
    About(Section.Settings), Notifications(Section.Settings),
    Display(Section.Settings), Cameras(Section.Settings), Maps(Section.Settings),
    Audio(Section.Settings), Security(Section.Settings),
}

/** Where hardware BACK goes from [route]; null means exit the app (Home root). */
private fun parentRoute(route: Route): Route? = when (route) {
    Route.Home -> null
    Route.Import, Route.Customize, Route.Overlay, Route.SettingsHub -> Route.Home
    else -> Route.SettingsHub
}

/** Persistent left navigation rail; collapses to icons and expands to labels on focus. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavRail(current: Section, setupPin: String?, onSelect: (Section) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (focused) 224.dp else 88.dp, label = "railWidth")
    Column(
        modifier = Modifier.fillMaxHeight().width(width).background(RailBg)
            .onFocusChanged { focused = it.hasFocus }
            .padding(vertical = 30.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Brand mark
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 14.dp)) {
            Image(
                painter = painterResource(R.drawable.ic_brand_logo),
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)),
            )
            if (focused) {
                Spacer(Modifier.width(12.dp))
                Text("TVAssist", fontSize = 18.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        RailItem(Icons.Rounded.Home, "Home", current == Section.Home, focused) { onSelect(Section.Home) }
        RailItem(Icons.Rounded.GridView, "Overlay", current == Section.Overlay, focused) { onSelect(Section.Overlay) }
        RailItem(Icons.Rounded.Settings, "Settings", current == Section.Settings, focused) { onSelect(Section.Settings) }

        // Bottom: the Web-setup PIN while the console is running (so it's readable from the couch).
        Spacer(Modifier.weight(1f))
        if (setupPin != null) {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(0x2622C4F2)).border(1.dp, AppAccent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(vertical = 10.dp, horizontal = if (focused) 12.dp else 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (focused) {
                    Text("WEB SETUP PIN", fontSize = 9.sp, color = TxtMuted, letterSpacing = 1.sp)
                    Spacer(Modifier.height(3.dp))
                }
                // Collapsed, the rail is only ~56dp wide, so the full PIN with wide tracking can't fit
                // on one line and wraps unevenly. Split it into balanced rows of 3 with tighter spacing.
                Text(
                    if (focused) setupPin else setupPin.chunked(3).joinToString("\n"),
                    fontSize = if (focused) 22.sp else 17.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = if (focused) 3.sp else 1.5.sp,
                    lineHeight = if (focused) 26.sp else 19.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AppAccent else Color.Transparent,
            focusedContainerColor = if (selected) AppAccent else ChipDim,
            pressedContainerColor = if (selected) AppAccent else ChipDim,
            contentColor = if (selected) Color.White else TxtMuted,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                BorderStroke(1.5.dp, if (selected) Color.White.copy(alpha = 0.35f) else AppAccent),
                shape = RoundedCornerShape(16.dp),
            ),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp))
            if (expanded) {
                Spacer(Modifier.width(16.dp))
                Text(label, fontSize = 16.sp, maxLines = 1, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** The Home destination: connection status + imported entities dashboard. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeContent(
    viewModel: ConnectionViewModel,
    entities: List<Entity>,
    connected: Boolean,
    settings: com.tvassist.data.settings.Settings,
    webOnboarding: WebOnboarding,
    connection: ConnectionState,
    onImport: () -> Unit,
    onCustomize: () -> Unit,
    onOpenCard: (String) -> Unit,
    onOpenCamera: (String) -> Unit,
    onOpenPerson: (String) -> Unit,
    onOpenMapCard: (String) -> Unit,
) {
    val overrides = settings.entityOverrides
    val actions = viewModel.controlActions
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        CompactStatus(connection)
        Spacer(Modifier.height(16.dp))
        HealthWarnings()
        if (!connected) {
            OnboardingSection(
                initialUrl = settings.baseUrl,
                initialToken = settings.token,
                connection = connection,
                webOnboarding = webOnboarding,
                onConnect = { url, token -> viewModel.saveAndConnect(url, token) },
                onStartWeb = viewModel::startWebOnboarding,
                onStopWeb = viewModel::stopWebOnboarding,
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Entities (${entities.size})", fontSize = 22.sp, color = Color.White)
                Spacer(Modifier.width(20.dp))
                AddButton("Import entities", onImport)
                if (entities.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    AccentButton("Customize", onCustomize, leadingIcon = Icons.Rounded.Tune)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (entities.isEmpty()) {
                Text(
                    "No entities imported yet.\nTap \"Import entities\" to pick which Home Assistant " +
                        "entities the app should track — only those are loaded, keeping it fast.",
                    color = Color(0xFFBBBBBB),
                    fontSize = 16.sp,
                )
            } else {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Press to toggle · hold for more controls · Customize to edit names, icons & actions",
                    fontSize = 12.sp, color = TxtMuted,
                )
                Spacer(Modifier.height(10.dp))
                CategorizedEntityList(
                    entities = entities,
                    overrides = overrides,
                    repository = viewModel.repository,
                    onPrimary = { e ->
                        when {
                            e.isMapCard -> onOpenMapCard(e.entityId)
                            e.domain == "camera" -> onOpenCamera(e.entityId)
                            e.isPerson -> onOpenPerson(e.entityId)
                            else -> performPress(overrides[e.entityId]?.singlePress ?: "default", e, actions, { onOpenCard(it.entityId) }, single = true)
                        }
                    },
                    onMore = { e ->
                        when {
                            e.isMapCard -> onOpenMapCard(e.entityId)
                            e.domain == "camera" -> onOpenCamera(e.entityId)
                            e.isPerson -> onOpenPerson(e.entityId)
                            else -> performPress(overrides[e.entityId]?.longPress ?: "default", e, actions, { onOpenCard(it.entityId) }, single = false)
                        }
                    },
                )
            }
        }
    }
}

// Preferred ordering of entity categories (domains); others follow alphabetically.
private val CATEGORY_ORDER = listOf(
    "climate", "light", "switch", "fan", "cover", "lock", "media_player", "camera",
    "vacuum", "scene", "script", "automation", "input_boolean", "input_button",
    "button", "binary_sensor", "sensor",
)

private fun categoryLabel(domain: String): String =
    domain.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** Groups entities by domain, ordered by [CATEGORY_ORDER] then alphabetically. */
private fun groupByCategory(entities: List<Entity>): List<Pair<String, List<Entity>>> {
    val byDomain = entities.groupBy { it.domain }
    val ordered = LinkedHashMap<String, List<Entity>>()
    CATEGORY_ORDER.forEach { d -> byDomain[d]?.let { ordered[d] = it } }
    byDomain.keys.sorted().forEach { d -> if (d !in ordered) ordered[d] = byDomain.getValue(d) }
    return ordered.entries.map { (d, list) -> d to list.sortedBy { it.friendlyName.lowercase() } }
}

/** A labelled row of selectable chips used in the overlay-appearance settings. */
@Composable
private fun AppearanceRow(label: String, chips: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Color(0xFFBBBBBB),
            fontSize = 14.sp,
            modifier = Modifier.width(140.dp),
        )
        chips()
    }
    Spacer(Modifier.height(10.dp))
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun <T> OptionChips(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            ChipButton(label = label, selected = value == selected, onClick = { onSelect(value) })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) =
    ChipButton(label = label, selected = selected, onClick = onClick)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PermissionsRow() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-check both permissions whenever we resume (e.g. returning from system settings).
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasKeyCapture by remember { mutableStateOf(isKeyCaptureEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = Settings.canDrawOverlays(context)
                hasKeyCapture = isKeyCaptureEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChipButton(
                label = if (hasOverlay) "✓ Overlay allowed" else "Grant overlay permission",
                selected = hasOverlay,
                onClick = { openOverlaySettings(context) },
            )
            ChipButton(
                label = if (hasKeyCapture) "✓ Key capture on" else "Enable key capture",
                selected = hasKeyCapture,
                onClick = { openAccessibilitySettings(context) },
            )
            AccentButton("Open sidebar", {
                if (hasOverlay) {
                    OverlayService.toggle(context)
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "Grant overlay permission first to open the sidebar",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    openOverlaySettings(context)
                }
            })
        }
        if (!hasOverlay) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Overlay permission is required to draw the sidebar over other apps.",
                color = Color(0xFFFFB74D),
                fontSize = 12.sp,
            )
        }
    }
}

private fun openOverlaySettings(context: android.content.Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openAccessibilitySettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * A prominent banner surfacing conditions that silently break the app so the user notices without
 * digging into settings — chiefly the two that Android can revoke on its own: the overlay
 * permission, and the key-capture accessibility service (disabled on every app update). Re-checked
 * on resume; renders nothing when everything's healthy.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HealthWarnings() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasKeyCapture by remember { mutableStateOf(isKeyCaptureEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = Settings.canDrawOverlays(context)
                hasKeyCapture = isKeyCaptureEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (hasOverlay && hasKeyCapture) return

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color(0x33FF6E6E))
            .border(1.dp, Color(0xFFFF8A80), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Color(0xFFFFC078), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Attention needed", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!hasOverlay) {
            WarningLine(
                "Overlay permission is off",
                "The sidebar can't appear over other apps.",
            ) { openOverlaySettings(context) }
        }
        if (!hasKeyCapture) {
            WarningLine(
                "Key capture is off",
                "Your trigger button won't open the overlay. Android turns this off after each app update.",
            ) { openAccessibilitySettings(context) }
        }
    }
    Spacer(Modifier.height(16.dp))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WarningLine(title: String, detail: String, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xFFFFE0E0), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = TxtMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        ChipButton("Fix", selected = false, onClick = onFix)
    }
}

/**
 * True if our remote key-capture accessibility service is enabled. Combines several
 * signals because no single one is reliable across Android TV builds:
 *  1. Our own [KeyCaptureService.isRunning] flag (set when the service actually binds).
 *  2. The AccessibilityManager's list of enabled services.
 *  3. The raw secure setting string (some TV builds return null from #2).
 */
private fun isKeyCaptureEnabled(context: android.content.Context): Boolean {
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

@Composable
private fun ConnectionStatusLine(state: ConnectionState) {
    val (label, color) = statusLabelColor(state)
    Text(text = label, color = color, fontSize = 16.sp)
}

/** Small top-left connection indicator: a colored dot + short status. */
@Composable
private fun CompactStatus(state: ConnectionState) {
    val (label, color) = statusLabelColor(state)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label, color = Color(0xFFBBBBBB), fontSize = 13.sp)
    }
}

private fun statusLabelColor(state: ConnectionState): Pair<String, Color> = when (state) {
    ConnectionState.Connected -> "Connected" to Color(0xFF4CAF50)
    ConnectionState.Connecting -> "Connecting…" to Color(0xFFFFC107)
    ConnectionState.Authenticating -> "Authenticating…" to Color(0xFFFFC107)
    ConnectionState.Disconnected -> "Disconnected" to Color(0xFF9E9E9E)
    is ConnectionState.Failed -> "Error: ${state.reason}" to Color(0xFFF44336)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OnboardingSection(
    initialUrl: String,
    initialToken: String,
    connection: ConnectionState,
    webOnboarding: WebOnboarding,
    onConnect: (String, String) -> Unit,
    onStartWeb: () -> Unit,
    onStopWeb: () -> Unit,
) {
    // Web mode is driven by the actual server state so the UI always reflects whether
    // the onboarding server is running (started here or via a re-enable elsewhere).
    var manualMode by remember { mutableStateOf(false) }

    when {
        webOnboarding !is WebOnboarding.Off -> WebPanel(
            state = webOnboarding,
            connection = connection,
            onCancel = onStopWeb,
        )

        manualMode -> Column(modifier = Modifier.width(720.dp)) {
            SetupSection(
                initialUrl = initialUrl,
                initialToken = initialToken,
                onConnect = onConnect,
            )
            Spacer(Modifier.height(12.dp))
            AccentButton("Back", { manualMode = false }, leadingIcon = Icons.Rounded.ChevronLeft)
        }

        else -> Column(modifier = Modifier.width(820.dp)) {
            Text("How do you want to connect?", color = TxtPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentButton("Enter on this TV", { manualMode = true })
                AccentButton("Connect from phone", onStartWeb)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Typing on a TV remote is painful — \"Connect from phone\" lets you enter your " +
                    "URL and token from a browser on your phone or laptop.",
                color = Color(0xFF999999),
                fontSize = 13.sp,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WebPanel(
    state: WebOnboarding,
    connection: ConnectionState,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.width(820.dp)) {
        Text("Connect from your phone", color = Color.White, fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        when (state) {
            is WebOnboarding.Running -> {
                Text("On your phone or laptop, open this in a browser:", color = Color(0xFFBBBBBB), fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Text(state.address, color = Color(0xFF8AB4F8), fontSize = 30.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "The browser will ask for the PIN shown at the bottom-left of this screen. Then " +
                        "enter your Home Assistant URL and long-lived token and tap Connect. Turn this " +
                        "off with Cancel when you're done.",
                    color = Color(0xFF999999),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(16.dp))
                ConnectionStatusLine(connection)
            }

            is WebOnboarding.Error -> Text("Couldn't start: ${state.reason}", color = Color(0xFFF44336), fontSize = 16.sp)
            WebOnboarding.Off -> Text("Starting…", color = Color(0xFFBBBBBB), fontSize = 16.sp)
        }
        Spacer(Modifier.height(20.dp))
        AccentButton("Cancel", onCancel)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SetupSection(
    initialUrl: String,
    initialToken: String,
    onConnect: (String, String) -> Unit,
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var token by remember(initialToken) { mutableStateOf(initialToken) }

    Column(modifier = Modifier.width(720.dp)) {
        Text("Home Assistant URL", color = Color(0xFFBBBBBB), fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        TvTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = "http://homeassistant.local:8123",
        )
        Spacer(Modifier.height(16.dp))
        Text("Long-lived access token", color = Color(0xFFBBBBBB), fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        TvTextField(
            value = token,
            onValueChange = { token = it },
            placeholder = "Paste token from HA profile",
            secret = true,
        )
        Spacer(Modifier.height(20.dp))
        AccentButton("Connect", { onConnect(url, token) }, leadingIcon = Icons.Rounded.Link)
    }
}

/** Mask a secret to first-2 + last-4 (e.g. "ab••••wxyz"); anything ≤ 6 chars is fully dotted. */
private fun mask2x4(s: String): String =
    if (s.length <= 6) "•".repeat(s.length)
    else s.take(2) + "•".repeat((s.length - 6).coerceAtMost(12)) + s.takeLast(4)

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    // When true the field is write-only: the saved value shows masked as first-2 + last-4 and is
    // NEVER loaded into the editor. Tapping to edit starts blank; leaving it blank keeps the old
    // value, so the secret can be replaced but never read back on-device.
    secret: Boolean = false,
) {
    // Click-to-edit: on a TV, a focused text field auto-pops the soft keyboard, which is
    // annoying while just navigating. So the field is a focusable display until you press OK;
    // only then does it become an editor and show the keyboard (closing on Done/back/focus-away).
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (!editing) text = value }
    val shape = RoundedCornerShape(12.dp)
    // Secret fields buffer input and commit only on exit (non-blank = replace, blank = keep); plain
    // fields report every keystroke as before.
    val finishEdit = {
        if (secret) { if (text.isNotBlank()) onValueChange(text) else text = value }
        editing = false
    }

    if (editing) {
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        // Only exit edit mode once focus has actually been gained and then lost — otherwise the
        // initial (not-yet-focused) callback would immediately cancel editing.
        var everFocused by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
            delay(80)
            keyboard?.show()
        }
        BackHandler { finishEdit() }
        Box(
            modifier = Modifier.fillMaxWidth().clip(shape).background(ChipDim)
                .border(1.5.dp, AppAccent, shape).padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            if (text.isEmpty()) {
                Text(if (secret && value.isNotEmpty()) "Paste to replace (blank keeps current)" else placeholder, color = TxtMuted, fontSize = 15.sp)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it; if (!secret) onValueChange(it) },
                singleLine = true,
                textStyle = TextStyle(color = TxtPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(AppAccent),
                // A secret you're entering is shown while typing (so you can verify a paste); it's the
                // saved value that's never revealed — the editor always starts blank for secrets.
                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { finishEdit() }),
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (it.isFocused) everFocused = true
                        else if (everFocused) finishEdit()
                    },
            )
        }
    } else {
        Surface(
            // Secrets start the editor blank so the saved value is never shown.
            onClick = { if (secret) text = ""; editing = true },
            modifier = Modifier.fillMaxWidth(),
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = ChipDim, focusedContainerColor = ChipDim,
                pressedContainerColor = ChipDim, contentColor = TxtPrimary, focusedContentColor = TxtPrimary,
            ),
            // A full-width field must not grow on focus (the default focusedScale is 1.1x, which makes
            // it spill past its box toward both edges); the accent border is the focus cue instead.
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(1.5.dp, AppAccent), shape = shape),
            ),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
                val shown = if (secret && text.isNotEmpty()) mask2x4(text) else text
                Text(
                    text = if (text.isEmpty()) placeholder else shown,
                    color = if (text.isEmpty()) TxtMuted else TxtPrimary,
                    fontSize = 15.sp, maxLines = 1,
                )
            }
        }
    }
}

/** Home list: imported entities grouped into collapsible category sections. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategorizedEntityList(
    entities: List<Entity>,
    overrides: Map<String, com.tvassist.data.settings.EntityOverride>,
    repository: com.tvassist.data.ha.HaRepository,
    onPrimary: (Entity) -> Unit,
    onMore: (Entity) -> Unit,
) {
    val groups = remember(entities) { groupByCategory(entities) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        groups.forEach { (domain, list) ->
            val isExpanded = expanded[domain] ?: true
            item(key = "hdr_$domain") {
                CategoryHeader(categoryLabel(domain), list.size, isExpanded) {
                    expanded[domain] = !isExpanded
                }
            }
            if (isExpanded) {
                items(list, key = { it.entityId }) { entity ->
                    ControlRow(entity = entity, override = overrides[entity.entityId], repository = repository, onPrimary = onPrimary, onMore = onMore, resolve = { id -> entities.firstOrNull { it.entityId == id } })
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryHeader(title: String, count: Int, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = CardFocusBg,
            pressedContainerColor = CardFocusBg,
            contentColor = TxtPrimary,
            focusedContentColor = TxtPrimary,
        ),
        // A full-width header must not grow on focus (default focusedScale is 1.1x, which makes the
        // highlight spill past the content margins at the corners); the border/fill are the cue.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.5.dp, AppAccent), shape = RoundedCornerShape(14.dp)),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.ChevronRight,
                    contentDescription = null, tint = TxtMuted, modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 15.sp, color = TxtMuted, fontWeight = FontWeight.SemiBold)
            }
            Text("$count", fontSize = 13.sp, color = TxtMuted)
        }
    }
}

/** A control row on Home: single-press = quick action, long-press = more options (card). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ControlRow(
    entity: Entity,
    override: com.tvassist.data.settings.EntityOverride?,
    repository: com.tvassist.data.ha.HaRepository,
    onPrimary: (Entity) -> Unit,
    onMore: (Entity) -> Unit,
    resolve: (String) -> Entity? = { null },
) {
    PremiumRow(
        icon = displayIcon(entity, override),
        iconContent = { tint -> EntityIconContent(entity, override, tint, repository = repository) },
        title = displayName(entity, override),
        subtitle = entity.entityId,
        onClick = { onPrimary(entity) },
        onLongClick = { onMore(entity) },
        modifier = Modifier.padding(start = 20.dp),
        trailing = {
            Text(
                text = controlRowValue(entity),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (effectiveOn(entity, override, resolve)) Color(0xFF6FCF7F) else TxtMuted,
            )
        },
    )
}

/** Short right-aligned status for a Home row (brightness/temp when relevant). */
private fun controlRowValue(entity: Entity): String = when {
    entity.isButton -> ""
    entity.domain == "light" -> if (entity.isOn) entity.brightnessPct?.let { "$it%" } ?: "on" else "off"
    entity.domain == "climate" -> entity.currentTemperature?.let { "${fmt(it)}°" } ?: entity.state
    entity.isLock -> cap(entity.state) // Locked / Unlocked / Jammed / …
    else -> entity.state
}

// ---------------------------------------------------------------------------------------
// Import screen: browse ALL of HA (fetched once), categorized, search + multi-select
// ---------------------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ImportScreen(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val all by viewModel.importerAll.collectAsStateWithLifecycle()
    val loading by viewModel.importerLoading.collectAsStateWithLifecycle()
    val query by viewModel.importSearch.collectAsStateWithLifecycle()
    val groups by viewModel.importerGroups.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val imported = settings.importedEntityIds.toSet()

    // Load the full catalogue once we're connected (and reload on reconnect).
    LaunchedEffect(connection) {
        if (connection is ConnectionState.Connected) viewModel.loadImporter()
    }

    // Categories are collapsed by default so we render only ~a dozen headers, not every
    // entity. A search auto-expands every matching category so results stay visible.
    val searching = query.isNotBlank()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Column(modifier = Modifier.fillMaxSize().padding(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconButton(Icons.Rounded.ChevronLeft, "Done", onBack)
            Spacer(Modifier.width(16.dp))
            Text("Import entities", fontSize = 28.sp, color = Color.White)
            Spacer(Modifier.width(16.dp))
            Text("${imported.size} imported", fontSize = 14.sp, color = Color(0xFFFFC107))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Pick a category to expand it, or search by name.",
            fontSize = 13.sp,
            color = Color(0xFF999999),
        )
        Spacer(Modifier.height(12.dp))
        TvTextField(value = query, onValueChange = viewModel::setImportSearch, placeholder = "Search all entities…")
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Text("Loading entities from Home Assistant…", color = Color(0xFFBBBBBB), fontSize = 16.sp)
            all.isEmpty() -> Text("No entities found (is Home Assistant connected?).", color = Color(0xFFBBBBBB), fontSize = 16.sp)
            groups.isEmpty() -> Text("No entities match \"$query\".", color = Color(0xFFBBBBBB), fontSize = 16.sp)
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groups.forEach { (domain, list) ->
                    // While searching, force every matching category open.
                    val isExpanded = searching || (expanded[domain] ?: false)
                    item(key = "hdr_$domain") {
                        CategoryHeader(categoryLabel(domain), list.size, isExpanded) {
                            expanded[domain] = !isExpanded
                        }
                    }
                    if (isExpanded) {
                        items(list, key = { it.entityId }) { entity ->
                            ImportRow(
                                entity = entity,
                                isImported = entity.entityId in imported,
                                repository = viewModel.repository,
                                onToggle = { viewModel.toggleImport(entity.entityId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ImportRow(
    entity: Entity,
    isImported: Boolean,
    repository: com.tvassist.data.ha.HaRepository,
    onToggle: () -> Unit,
) {
    PremiumRow(
        icon = domainIcon(entity),
        iconContent = { tint -> EntityIconContent(entity, null, tint, repository = repository) },
        title = entity.friendlyName,
        subtitle = entity.entityId,
        onClick = onToggle,
        modifier = Modifier.padding(start = 20.dp),
        trailing = {
            Text(
                text = if (isImported) "✓ Imported" else "Add",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (isImported) Color(0xFF6FCF7F) else AppAccent,
            )
        },
    )
}

// ---------------------------------------------------------------------------------------
// Customize entities: per-entity name, icon, single/long press action
// ---------------------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CustomizeScreen(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val entities by viewModel.entities.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val overrides = settings.entityOverrides
    var editingId by remember { mutableStateOf<String?>(null) }

    val editing = editingId?.let { id -> entities.firstOrNull { it.entityId == id } }
    if (editing != null) {
        BackHandler { editingId = null }
        EntityEditorScreen(
            entity = editing,
            override = overrides[editing.entityId],
            allEntities = entities,
            viewModel = viewModel,
            onSave = { viewModel.setEntityOverride(it) },
            onBack = { editingId = null },
        )
        return
    }

    val groups = remember(entities) { groupByCategory(entities) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    Column(modifier = Modifier.fillMaxSize().padding(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconButton(Icons.Rounded.ChevronLeft, "Back", onBack)
            Spacer(Modifier.width(16.dp))
            Text("Customize entities", fontSize = 28.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Text("Edit an entity's name, icon and what a press does.", fontSize = 13.sp, color = TxtMuted)
        Spacer(Modifier.height(12.dp))
        if (entities.isEmpty()) {
            Text("No imported entities yet — import some from the Home screen first.",
                color = Color(0xFFBBBBBB), fontSize = 16.sp)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { (domain, list) ->
                    val isExpanded = expanded[domain] ?: true
                    item(key = "h_$domain") {
                        CategoryHeader(categoryLabel(domain), list.size, isExpanded) {
                            expanded[domain] = !isExpanded
                        }
                    }
                    if (isExpanded) {
                        items(list, key = { it.entityId }) { e ->
                            val ov = overrides[e.entityId]
                            PremiumRow(
                                icon = displayIcon(e, ov),
                                iconContent = { tint -> EntityIconContent(e, ov, tint, repository = viewModel.repository) },
                                title = displayName(e, ov),
                                subtitle = e.entityId,
                                onClick = { editingId = e.entityId },
                                modifier = Modifier.padding(start = 20.dp),
                                trailing = {
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TxtMuted, modifier = Modifier.size(20.dp))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EntityEditorScreen(
    entity: Entity,
    override: EntityOverride?,
    allEntities: List<Entity>,
    viewModel: ConnectionViewModel,
    onSave: (EntityOverride) -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember(entity.entityId) { mutableStateOf(override ?: EntityOverride(entity.entityId)) }
    // Persist is debounced so typing a name doesn't re-serialize + write DataStore on every
    // keystroke (which also re-emits settings and recomposes subscribers). `draft` stays the live
    // source for the preview; `saved` tracks what's on disk so we don't write no-op duplicates.
    var saved by remember(entity.entityId) { mutableStateOf(override ?: EntityOverride(entity.entityId)) }
    val latestSave by rememberUpdatedState(onSave)
    fun update(next: EntityOverride) { draft = next }
    LaunchedEffect(draft) {
        if (draft == saved) return@LaunchedEffect
        delay(350)
        latestSave(draft)
        saved = draft
    }
    // Flush an in-flight edit if the user leaves before the debounce fires.
    DisposableEffect(entity.entityId) {
        onDispose { if (draft != saved) latestSave(draft) }
    }

    // Picking the "mirror" source entity takes over the screen (reuses the layout picker).
    var mirrorPicker by remember { mutableStateOf(false) }
    if (mirrorPicker) {
        BackHandler { mirrorPicker = false }
        LayoutEntityPicker(
            viewModel = viewModel,
            multiSelect = false,
            title = "Choose mirror source",
            onAdd = { ids -> ids.firstOrNull()?.let { update(draft.copy(mirrorEntityId = it)) }; mirrorPicker = false },
            onBack = { mirrorPicker = false },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconButton(Icons.Rounded.ChevronLeft, "Back", onBack)
            Spacer(Modifier.width(16.dp))
            Text("Edit entity", fontSize = 26.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(18.dp))

        // Live preview of how the entity will look.
        PremiumRow(
            icon = displayIcon(entity, draft),
            iconContent = { tint -> EntityIconContent(entity, draft, tint, repository = viewModel.repository) },
            title = displayName(entity, draft),
            subtitle = entity.entityId,
            onClick = {},
            trailing = { Text(entity.state, fontSize = 13.sp, color = TxtMuted) },
        )
        Spacer(Modifier.height(24.dp))

        Text("Name", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(6.dp))
        TvTextField(value = draft.name, onValueChange = { update(draft.copy(name = it)) }, placeholder = entity.friendlyName)
        Spacer(Modifier.height(22.dp))

        Text("Icon", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(8.dp))
        IconPickerSection(entity, draft, viewModel.repository) { update(draft.copy(icon = it)) }
        Spacer(Modifier.height(22.dp))

        Text("Single press", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PressAction.ALL.forEach { a ->
                ChipButton(PressAction.label(a), draft.singlePress == a, onClick = { update(draft.copy(singlePress = a)) })
            }
        }
        Spacer(Modifier.height(18.dp))

        Text("Long press", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PressAction.ALL.forEach { a ->
                ChipButton(PressAction.label(a), draft.longPress == a, onClick = { update(draft.copy(longPress = a)) })
            }
        }
        Spacer(Modifier.height(18.dp))

        Text("Highlight state", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(2.dp))
        Text(
            "How the tile shows on/off. Use Always on/off for stateless buttons (input_button, IR, scripts).",
            fontSize = 12.sp, color = TxtMuted,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.tvassist.data.settings.DisplayState.ALL.forEach { d ->
                ChipButton(com.tvassist.data.settings.DisplayState.label(d), draft.displayState == d, onClick = { update(draft.copy(displayState = d)) })
            }
        }
        if (draft.displayState == com.tvassist.data.settings.DisplayState.MIRROR) {
            Spacer(Modifier.height(14.dp))
            Text("Mirror source", fontSize = 14.sp, color = TxtMuted)
            Spacer(Modifier.height(2.dp))
            Text("This tile's on/off follows the chosen entity.", fontSize = 12.sp, color = TxtMuted)
            Spacer(Modifier.height(8.dp))
            val srcName = draft.mirrorEntityId.takeIf { it.isNotBlank() }
                ?.let { id -> allEntities.firstOrNull { it.entityId == id }?.friendlyName ?: id }
            AccentButton(srcName ?: "Choose entity…", { mirrorPicker = true })
            Spacer(Modifier.height(14.dp))
            Text("On threshold (optional)", fontSize = 14.sp, color = TxtMuted)
            Spacer(Modifier.height(2.dp))
            Text(
                "Blank = follow the source's on/off. A number = on when the source's value is at least " +
                    "this (e.g. watts for a power sensor).",
                fontSize = 12.sp, color = TxtMuted,
            )
            Spacer(Modifier.height(8.dp))
            var thrText by remember(entity.entityId, draft.mirrorEntityId) {
                mutableStateOf(draft.mirrorThreshold?.let { thresholdText(it) } ?: "")
            }
            TvTextField(
                value = thrText,
                onValueChange = { t -> thrText = t; update(draft.copy(mirrorThreshold = t.trim().toDoubleOrNull())) },
                placeholder = "e.g. 5",
            )
        }
        Spacer(Modifier.height(26.dp))
        AccentButton("Reset to defaults", { update(EntityOverride(entity.entityId)) }, leadingIcon = Icons.Rounded.Refresh)
    }
}

/**
 * Searchable icon picker for the entity editor. "Default" uses Home Assistant's own icon (or a
 * domain guess); typing searches the Iconify catalog (MDI first, like HA) and renders results
 * as tinted PNGs. The chosen icon is stored as an Iconify name, e.g. "mdi:ceiling-light".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconPickerSection(
    entity: Entity,
    draft: EntityOverride,
    repository: com.tvassist.data.ha.HaRepository,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        delay(300)
        results = IconStore.search(query)
        searching = false
    }

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "Default" → fall back to HA's icon / domain guess (no override icon).
        IconPickerCell(selected = draft.icon.isBlank(), onClick = { onPick("") }) { tint ->
            EntityIconContent(entity, EntityOverride(entity.entityId), tint, 26, repository = repository)
        }
        Text("Default", fontSize = 12.sp, color = TxtMuted)
        // Keep the currently-chosen custom icon visible even if it's not in the latest results.
        if (draft.icon.contains(':')) {
            IconPickerCell(selected = true, onClick = {}) { tint -> IconifyIcon(draft.icon, tint, 26) {} }
        }
    }
    Spacer(Modifier.height(10.dp))
    TvTextField(value = query, onValueChange = { query = it }, placeholder = "Search icons (e.g. ceiling light)…")
    Spacer(Modifier.height(10.dp))
    when {
        searching -> Text("Searching…", fontSize = 13.sp, color = TxtMuted)
        query.isNotBlank() && results.isEmpty() -> Text("No icons match \"$query\".", fontSize = 13.sp, color = TxtMuted)
        results.isNotEmpty() -> FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            results.take(60).forEach { name ->
                IconPickerCell(selected = draft.icon == name, onClick = { onPick(name) }) { tint ->
                    IconifyIcon(name, tint, 26) {}
                }
            }
        }
        else -> Text(
            "Type to search thousands of icons (Home Assistant MDI + more).",
            fontSize = 12.sp, color = TxtMuted,
        )
    }
}

/** A selectable icon cell: renders [content] tinted, accent-filled when selected. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun IconPickerCell(selected: Boolean, onClick: () -> Unit, content: @Composable (tint: Color) -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AppAccent else ChipDim,
            focusedContainerColor = if (selected) AppAccent else CardFocusBg,
            pressedContainerColor = if (selected) AppAccent else CardFocusBg,
            contentColor = if (selected) Color.White else TxtPrimary,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.5.dp, AppAccent), shape = RoundedCornerShape(12.dp)),
        ),
    ) {
        Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
            content(if (selected) Color.White else TxtPrimary)
        }
    }
}

// ---------------------------------------------------------------------------------------
// Overlay layout editor: rows → columns → tiles, with a live Preview
// ---------------------------------------------------------------------------------------

/** A compact icon-only button used for reorder/delete actions in the editor. */
@Composable
private fun IconBtn(icon: ImageVector, desc: String, dense: Boolean = false, onClick: () -> Unit) =
    PremiumIconButton(icon = icon, desc = desc, onClick = onClick, dense = dense)

/** A button with a leading "+" icon and a label. */
@Composable
private fun AddButton(label: String, onClick: () -> Unit) =
    AccentButton(label = label, onClick = onClick, leadingIcon = Icons.Rounded.Add)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LayoutEditorScreen(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val entities by viewModel.entities.collectAsStateWithLifecycle()
    val layout = settings.overlayLayout
    val context = LocalContext.current

    var pickerForRow by remember { mutableStateOf<Int?>(null) }
    if (pickerForRow != null) {
        BackHandler { pickerForRow = null }
        LayoutEntityPicker(
            viewModel = viewModel,
            onAdd = { ids ->
                pickerForRow?.let { r -> viewModel.addTilesToRow(r, ids) }
                pickerForRow = null
            },
            onBack = { pickerForRow = null },
        )
        return
    }

    var pillPickerForRow by remember { mutableStateOf<Int?>(null) }
    if (pillPickerForRow != null) {
        BackHandler { pillPickerForRow = null }
        LayoutEntityPicker(
            viewModel = viewModel,
            title = "Add pills to header",
            onAdd = { ids ->
                pillPickerForRow?.let { r -> viewModel.addHeaderPills(r, ids) }
                pillPickerForRow = null
            },
            onBack = { pillPickerForRow = null },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconButton(Icons.Rounded.ChevronLeft, "Back", onBack)
            Spacer(Modifier.width(14.dp))
            Text("Overlay Layout", fontSize = 24.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            AccentButton("Preview", { OverlayService.show(context) })
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AddButton("Entity row") { viewModel.addLayoutRow(header = false) }
            AddButton("Header") { viewModel.addLayoutRow(header = true) }
        }
        Spacer(Modifier.height(16.dp))

        if (layout.rows.isEmpty()) {
            Text(
                "No rows yet. Add an entity row, then add entities to it. " +
                    "Press Preview to see the overlay.",
                color = Color(0xFFBBBBBB),
                fontSize = 15.sp,
            )
        } else {
            layout.rows.forEachIndexed { i, row ->
                RowEditor(
                    index = i,
                    row = row,
                    total = layout.rows.size,
                    nameOf = { id -> entities.firstOrNull { it.entityId == id }?.friendlyName ?: id },
                    isPersonOf = { id -> entities.firstOrNull { it.entityId == id }?.isPerson == true },
                    viewModel = viewModel,
                    onAddEntity = { pickerForRow = i },
                    onAddPill = { pillPickerForRow = i },
                    onRemovePill = { p -> viewModel.removeHeaderPill(i, p) },
                    onTogglePill = { p, field -> viewModel.togglePillField(i, p, field) },
                    onSetPillColor = { p, color -> viewModel.setPillIconColor(i, p, color) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** Format a mirror threshold for the editor field: drop the trailing ".0" for whole numbers. */
private fun thresholdText(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

/** Preset icon tints offered for header pills (white, muted, accent, amber, green, red, cyan, purple). */
private val PILL_ICON_SWATCHES = listOf(
    0xFFFFFFFF.toInt(), 0xFF8A94A3.toInt(), 0xFF5C7CFA.toInt(), 0xFFF39C12.toInt(),
    0xFF6FCF7F.toInt(), 0xFFE55B5B.toInt(), 0xFF00BCD4.toInt(), 0xFFB06FE0.toInt(),
)

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RowEditor(
    index: Int,
    row: OverlayRow,
    total: Int,
    nameOf: (String) -> String,
    isPersonOf: (String) -> Boolean,
    viewModel: ConnectionViewModel,
    onAddEntity: () -> Unit,
    onAddPill: () -> Unit,
    onRemovePill: (Int) -> Unit,
    onTogglePill: (Int, String) -> Unit,
    onSetPillColor: (Int, Int) -> Unit,
) {
    // Which pill's icon-color editor is expanded (ColorControlRow keys off its label).
    var pillColorExpanded by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF20262D)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.isHeader) {
                Text("Header", fontSize = 12.sp, color = Color(0xFFAAAAAA))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    TvTextField(row.title, { viewModel.setRowTitle(index, it) }, "Section title")
                }
            } else {
                Text("Row ${index + 1}", fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
                Text("Columns", fontSize = 11.sp, color = Color(0xFFAAAAAA))
                Spacer(Modifier.width(6.dp))
                IconBtn(Icons.Rounded.Remove, "Fewer columns", dense = true) { viewModel.setRowColumns(index, row.columns - 1) }
                Spacer(Modifier.width(8.dp))
                Text("${row.columns}", fontSize = 15.sp, color = Color.White)
                Spacer(Modifier.width(8.dp))
                IconBtn(Icons.Rounded.Add, "More columns", dense = true) { viewModel.setRowColumns(index, row.columns + 1) }
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.width(8.dp))
            IconBtn(Icons.Rounded.KeyboardArrowUp, "Move row up", dense = true) { viewModel.moveLayoutRow(index, up = true) }
            Spacer(Modifier.width(4.dp))
            IconBtn(Icons.Rounded.KeyboardArrowDown, "Move row down", dense = true) { viewModel.moveLayoutRow(index, up = false) }
            Spacer(Modifier.width(4.dp))
            IconBtn(Icons.Rounded.DeleteOutline, "Delete row", dense = true) { viewModel.removeLayoutRow(index) }
        }

        if (row.isHeader) {
            // Live pills (temperature/humidity/any sensor) shown on the header; each pill
            // chooses whether to show its icon, name and state.
            Spacer(Modifier.height(8.dp))
            Text("Pills", fontSize = 11.sp, color = TxtMuted)
            Spacer(Modifier.height(5.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                row.pills.forEachIndexed { pi, pill ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBg).padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(nameOf(pill.entityId), fontSize = 12.sp, color = TxtPrimary, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
                            IconBtn(Icons.Rounded.Close, "Remove pill", dense = true) { onRemovePill(pi) }
                        }
                        Spacer(Modifier.height(5.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChipButton("Icon", selected = pill.showIcon, onClick = { onTogglePill(pi, "icon") }, dense = true)
                            ChipButton("Name", selected = pill.showName, onClick = { onTogglePill(pi, "name") }, dense = true)
                            ChipButton("State", selected = pill.showState, onClick = { onTogglePill(pi, "state") }, dense = true)
                        }
                        // Icon tint (only relevant when the icon is shown). "Default" clears it.
                        if (pill.showIcon) {
                            Spacer(Modifier.height(8.dp))
                            ColorControlRow(
                                label = "Icon color ${pi + 1}",
                                argb = if (pill.iconColor != 0) pill.iconColor else 0xFF8A94A3.toInt(),
                                swatches = PILL_ICON_SWATCHES,
                                expandedKey = pillColorExpanded,
                                onExpand = { pillColorExpanded = it },
                                onChange = { onSetPillColor(pi, it) },
                                isUnset = pill.iconColor == 0,
                                onReset = { onSetPillColor(pi, 0) },
                            )
                        }
                    }
                }
                ChipButton("+ Add pill", selected = true, onClick = onAddPill, dense = true)
            }
        }

        if (!row.isHeader) {
            Spacer(Modifier.height(10.dp))
            val cols = row.columns.coerceIn(1, 12)
            // Tiles flow left-to-right into the column grid; a trailing "+" cell (-1)
            // shows where the next entity will land. Reorder with the ‹ › chevrons.
            val slots = row.tiles.indices.toList() + (-1)
            slots.chunked(cols).forEach { line ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    line.forEach { slot ->
                        if (slot == -1) {
                            AddCell(Modifier.weight(1f), onAddEntity)
                        } else {
                            TileCell(
                                modifier = Modifier.weight(1f),
                                name = nameOf(row.tiles[slot].entityId),
                                styleLabel = OverlayTile.label(row.tiles[slot].style),
                                showName = !row.tiles[slot].hideName,
                                showStatus = !row.tiles[slot].hideStatus,
                                showIcon = !row.tiles[slot].hideIcon,
                                isPerson = isPersonOf(row.tiles[slot].entityId),
                                personOptions = row.tiles[slot].personOptions,
                                mapProvider = row.tiles[slot].mapProvider,
                                onCycleStyle = { viewModel.cycleTileStyle(index, slot) },
                                onToggleName = { viewModel.toggleTileName(index, slot) },
                                onToggleStatus = { viewModel.toggleTileStatus(index, slot) },
                                onToggleIcon = { viewModel.toggleTileIcon(index, slot) },
                                onTogglePersonOption = { key -> viewModel.toggleTilePersonOption(index, slot, key) },
                                onSetMapProvider = { p -> viewModel.setTileMapProvider(index, slot, p) },
                                onEarlier = { viewModel.moveTile(index, slot, up = true) },
                                onLater = { viewModel.moveTile(index, slot, up = false) },
                                onRemove = { viewModel.removeTile(index, slot) },
                            )
                        }
                    }
                    repeat(cols - line.size) { Box(Modifier.weight(1f)) {} }
                }
            }
        }
    }
}

/** One filled cell in the row grid: entity name + style chip + reorder/remove. */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TileCell(
    modifier: Modifier,
    name: String,
    styleLabel: String,
    showName: Boolean,
    showStatus: Boolean,
    showIcon: Boolean,
    isPerson: Boolean,
    personOptions: List<String>,
    mapProvider: String,
    onCycleStyle: () -> Unit,
    onToggleName: () -> Unit,
    onToggleStatus: () -> Unit,
    onToggleIcon: () -> Unit,
    onTogglePersonOption: (String) -> Unit,
    onSetMapProvider: (String) -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onRemove: () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(CardBg).padding(10.dp),
    ) {
        // When the cell is narrow (many columns), stack the style chip above the
        // reorder/remove buttons so the ✕ is never clipped off the right edge.
        val narrow = maxWidth < 250.dp
        Column {
            Text(name, fontSize = 13.sp, color = TxtPrimary, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            val controls: @Composable RowScope.() -> Unit = {
                IconBtn(Icons.Rounded.ChevronLeft, "Move earlier", dense = true, onClick = onEarlier)
                Spacer(Modifier.width(4.dp))
                IconBtn(Icons.Rounded.ChevronRight, "Move later", dense = true, onClick = onLater)
                Spacer(Modifier.width(4.dp))
                IconBtn(Icons.Rounded.Close, "Remove", dense = true, onClick = onRemove)
            }
            if (narrow) {
                ChipButton(styleLabel, selected = false, onClick = onCycleStyle, dense = true)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, content = controls)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChipButton(styleLabel, selected = false, onClick = onCycleStyle, dense = true)
                    Spacer(Modifier.weight(1f))
                    controls()
                }
            }
            // Per-tile visibility: accent = shown, dim = hidden.
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipButton("Icon", selected = showIcon, onClick = onToggleIcon, dense = true)
                ChipButton("Name", selected = showName, onClick = onToggleName, dense = true)
                ChipButton("Status", selected = showStatus, onClick = onToggleStatus, dense = true)
            }
            // Person tiles: choose what the fullscreen map popup shows.
            if (isPerson) {
                Spacer(Modifier.height(8.dp))
                Text("Map shows", fontSize = 11.sp, color = TxtMuted)
                Spacer(Modifier.height(5.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OverlayTile.PERSON_OPTIONS_ALL.forEach { (key, label) ->
                        ChipButton(label, selected = key in personOptions, onClick = { onTogglePersonOption(key) }, dense = true)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Map source", fontSize = 11.sp, color = TxtMuted)
                Spacer(Modifier.height(5.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OverlayTile.MAP_PROVIDERS.forEach { (key, label) ->
                        ChipButton(label, selected = key == mapProvider, onClick = { onSetMapProvider(key) }, dense = true)
                    }
                }
            }
        }
    }
}

/** An empty grid cell that adds an entity into the next open position. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddCell(modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        // Subtle scale so a focused cell doesn't overlap its grid neighbours (default is 1.1x).
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF14181E),
            focusedContainerColor = CardFocusBg,
            pressedContainerColor = CardFocusBg,
            contentColor = TxtMuted,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.5.dp, AppAccent), shape = RoundedCornerShape(14.dp)),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "Add entity", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add", fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Picks one or more entities from the IMPORTED pool (the overlay is built from entities you've
 * already imported on Home). Multi-select: tapping a row toggles it; the "Add (N)" button commits
 * every selection at once via [onAdd]. Searchable within the pool.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LayoutEntityPicker(
    viewModel: ConnectionViewModel,
    onAdd: (List<String>) -> Unit,
    onBack: () -> Unit,
    filter: ((Entity) -> Boolean)? = null,
    title: String = "Add entities to row",
    // Multi-select accumulates picks behind an "Add (N)" button; single-select commits on tap
    // (used where exactly one entity makes sense, e.g. choosing a mirror source).
    multiSelect: Boolean = true,
) {
    val entities by viewModel.entities.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val searching = query.isNotBlank()
    val groups = remember(entities, query, filter) {
        val q = query.trim().lowercase()
        val pool = if (filter != null) entities.filter(filter) else entities
        val filtered = if (q.isEmpty()) pool else pool.filter { it.nameLower.contains(q) || it.idLower.contains(q) }
        groupByCategory(filtered)
    }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // Ids picked so far (order preserved so they're added in the order tapped).
    val selected = remember { mutableStateListOf<String>() }

    Column(modifier = Modifier.fillMaxSize().padding(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconButton(Icons.Rounded.ChevronLeft, "Back", onBack)
            Spacer(Modifier.width(16.dp))
            Text(title, fontSize = 28.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (multiSelect && selected.isNotEmpty()) {
                AccentButton("Add (${selected.size})", { onAdd(selected.toList()) }, leadingIcon = Icons.Rounded.Add)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (multiSelect) "Tap entities to select several, then press Add. Import more from the Home screen."
            else "Choose an entity. Import more from the Home screen.",
            fontSize = 13.sp, color = TxtMuted,
        )
        Spacer(Modifier.height(12.dp))
        TvTextField(value = query, onValueChange = { query = it }, placeholder = "Search imported entities…")
        Spacer(Modifier.height(12.dp))
        when {
            entities.isEmpty() -> Text(
                "No imported entities yet — import some from the Home screen first.",
                color = Color(0xFFBBBBBB), fontSize = 16.sp,
            )
            groups.isEmpty() -> Text("No imported entity matches \"$query\".", color = Color(0xFFBBBBBB), fontSize = 16.sp)
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groups.forEach { (domain, list) ->
                    val isExpanded = searching || (expanded[domain] ?: true)
                    item(key = "h_$domain") {
                        CategoryHeader(categoryLabel(domain), list.size, isExpanded) {
                            expanded[domain] = !isExpanded
                        }
                    }
                    if (isExpanded) {
                        items(list, key = { it.entityId }) { entity ->
                            val isSel = entity.entityId in selected
                            PremiumRow(
                                icon = domainIcon(entity),
                                iconContent = { tint -> EntityIconContent(entity, null, tint, repository = viewModel.repository) },
                                title = entity.friendlyName,
                                subtitle = entity.entityId,
                                onClick = {
                                    if (!multiSelect) {
                                        onAdd(listOf(entity.entityId))
                                    } else if (isSel) {
                                        selected.remove(entity.entityId)
                                    } else {
                                        selected.add(entity.entityId)
                                    }
                                },
                                modifier = Modifier.padding(start = 20.dp),
                                trailing = {
                                    when {
                                        !multiSelect -> Icon(Icons.Rounded.Add, contentDescription = "Add", tint = AppAccent, modifier = Modifier.size(20.dp))
                                        isSel -> Icon(Icons.Rounded.CheckCircle, contentDescription = "Selected", tint = AppAccent, modifier = Modifier.size(22.dp))
                                        else -> Icon(Icons.Rounded.RadioButtonUnchecked, contentDescription = "Tap to select", tint = TxtMuted, modifier = Modifier.size(22.dp))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Settings screen: trigger key capture + backup & restore
// ---------------------------------------------------------------------------------------

/** A standard sub-page: Back + title header, scrollable content. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(40.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconButton(Icons.Rounded.ChevronLeft, "Back", onBack)
            Spacer(Modifier.width(16.dp))
            Text(title, fontSize = 30.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(28.dp))
        content()
    }
}

/** Per-TV audio defaults for TTS + sound announcements (HA calls can override per announcement). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AudioPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    PageScaffold("Audio & announcements", onBack) {
        Text(
            "Defaults for text-to-speech and sound files on this TV. A Home Assistant service call can " +
                "override the volume, ducking and language per announcement.",
            color = TxtMuted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable announcements", color = TxtPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            ChipButton(
                if (settings.announceEnabled) "On" else "Off",
                selected = settings.announceEnabled,
                onClick = { viewModel.setAnnounceEnabled(!settings.announceEnabled) },
            )
        }
        if (settings.announceEnabled) {
            Spacer(Modifier.height(20.dp))
            val track = Brush.horizontalGradient(listOf(ChipDim, AppAccent))
            ColorSliderRow(
                "Volume", settings.announceVolume.toDouble(), 0.0, 100.0, 5.0, track,
                { "${it.roundToInt()}%" }, { viewModel.setAnnounceVolume(it.roundToInt()) }, resetKey = "announceVol",
            )
            Spacer(Modifier.height(20.dp))
            Text("Speak title & message", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OptionChips(
                options = listOf(
                    "both" to "Together",
                    "separate" to "Separate",
                    "message" to "Message only",
                    "title" to "Title only",
                ),
                selected = settings.announceSpeakMode.ifBlank { "both" },
                onSelect = { viewModel.setAnnounceSpeakMode(it) },
            )
            Text(
                "How a notification is voiced: Together reads \"Title. Message\" in one breath; Separate " +
                    "speaks the title, then the message, as two announcements; or read just one of them.",
                color = TxtMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text("Repeat speech", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OptionChips(
                options = listOf("once" to "Read once", "loop" to "Repeat until dismissed"),
                selected = settings.announceSpeakRepeat.ifBlank { "once" },
                onSelect = { viewModel.setAnnounceSpeakRepeat(it) },
            )
            Text(
                "Repeat re-reads the notification (with a pause) until it leaves the screen. " +
                    "A notification pinned on screen (duration 0) is capped at 60 seconds.",
                color = TxtMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
            if (settings.announceSpeakRepeat == "loop") {
                Spacer(Modifier.height(16.dp))
                val gapTrack = Brush.horizontalGradient(listOf(ChipDim, AppAccent))
                ColorSliderRow(
                    "Pause between repeats", settings.announceRepeatGap.toDouble(), 0.0, 15.0, 1.0, gapTrack,
                    { "${it.roundToInt()}s" }, { viewModel.setAnnounceRepeatGap(it.roundToInt()) },
                    resetKey = "announceGap",
                )
                Text(
                    "Waited after each full read before repeating. The next read only starts once the " +
                        "current one finishes, so it never overlaps.",
                    color = TxtMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text("While speaking", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OptionChips(
                options = listOf("off" to "Play over", "duck" to "Duck TV audio", "pause" to "Pause TV audio"),
                selected = settings.announceDuckMode.ifBlank { "duck" },
                onSelect = { viewModel.setAnnounceDuckMode(it) },
            )
            Text(
                "Duck lowers the current TV audio while speaking (amount decided by the TV); Pause pauses it and resumes after.",
                color = TxtMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text("Default language", fontSize = 14.sp, color = TxtMuted)
            Spacer(Modifier.height(2.dp))
            Text("BCP-47 tag (e.g. en-US, fr-CA). Blank = device default.", color = TxtMuted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            var lang by remember(settings.announceLanguage) { mutableStateOf(settings.announceLanguage) }
            TvTextField(value = lang, onValueChange = { lang = it }, placeholder = "device default")
            LaunchedEffect(lang) {
                if (lang == settings.announceLanguage) return@LaunchedEffect
                delay(500)
                viewModel.setAnnounceLanguage(lang)
            }
            Spacer(Modifier.height(20.dp))
            Text("Sound file playback", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OptionChips(
                options = listOf("once" to "Play once", "loop" to "Loop until dismissed"),
                selected = settings.announceSoundRepeat.ifBlank { "once" },
                onSelect = { viewModel.setAnnounceSoundRepeat(it) },
            )
            Text(
                "Applies to a sound file sent with a notification. Loop replays it until the notification " +
                    "leaves the screen; a notification pinned on screen (duration 0) is capped at 60 seconds.",
                color = TxtMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Settings landing page: a list of categories, each opening its own page. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsHub(viewModel: ConnectionViewModel, onOpen: (Route) -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 44.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", fontSize = 34.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(18.dp))
        HubItem(Icons.Rounded.Link, "Connection", "Home Assistant URL & token, web onboarding") { onOpen(Route.Connection) }
        HubItem(Icons.Rounded.Security, "Permissions", "Overlay draw & remote key-capture access") { onOpen(Route.Permissions) }
        HubItem(Icons.Rounded.Key, "Security", "Access tokens that secure this TV") { onOpen(Route.Security) }
        HubItem(Icons.Rounded.SettingsRemote, "Triggers & keys", "The remote button that opens the overlay") { onOpen(Route.Triggers) }
        HubItem(Icons.Rounded.Tune, "Appearance & timing", "Overlay position, style and auto-close") { onOpen(Route.Appearance) }
        HubItem(Icons.Rounded.Notifications, "Notifications", "Let Home Assistant push toasts to this TV") { onOpen(Route.Notifications) }
        HubItem(Icons.Rounded.VolumeUp, "Audio & announcements", "TTS/sound volume, ducking and language for this TV") { onOpen(Route.Audio) }
        HubItem(Icons.Rounded.Schedule, "On-screen display", "Always-on clock and screen dimming overlay") { onOpen(Route.Display) }
        HubItem(Icons.Rounded.Videocam, "Cameras", "Add direct-URL cameras (RTSP/HTTP) that start instantly") { onOpen(Route.Cameras) }
        HubItem(Icons.Rounded.Map, "Maps", "Multi-entity map cards + Google Maps source") { onOpen(Route.Maps) }
        HubItem(Icons.Rounded.Backup, "Backup & restore", "Save or restore your settings to a file") { onOpen(Route.Backup) }
        HubItem(Icons.Rounded.Info, "About", "Version & info") { onOpen(Route.About) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HubItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CardBg,
            focusedContainerColor = CardFocusBg,
            pressedContainerColor = CardFocusBg,
            contentColor = TxtPrimary,
            focusedContentColor = TxtPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.5.dp, AppAccent), shape = RoundedCornerShape(20.dp)),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(46.dp).clip(CircleShape)
                    .background(if (focused) AppAccent.copy(alpha = 0.18f) else ChipDim),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, contentDescription = null,
                    tint = if (focused) AppAccent else Color(0xFFB6C0CC),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, color = TxtPrimary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 13.sp, color = TxtMuted)
            }
            Icon(
                Icons.Rounded.ChevronRight, contentDescription = null,
                tint = if (focused) AppAccent else TxtMuted, modifier = Modifier.size(22.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConnectionPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val webOnboarding by viewModel.webOnboarding.collectAsStateWithLifecycle()
    PageScaffold("Connection", onBack) {
        ConnectionStatusLine(connection)
        Spacer(Modifier.height(4.dp))
        Text(
            if (settings.baseUrl.isNotBlank()) settings.baseUrl else "No Home Assistant configured",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        OnboardingSection(
            initialUrl = settings.baseUrl,
            initialToken = settings.token,
            connection = connection,
            webOnboarding = webOnboarding,
            onConnect = { url, token -> viewModel.saveAndConnect(url, token) },
            onStartWeb = viewModel::startWebOnboarding,
            onStopWeb = viewModel::stopWebOnboarding,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PermissionsPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    PageScaffold("Permissions", onBack) {
        Text(
            "The overlay permission lets the control sidebar draw over other apps; key capture " +
                "lets the remote button open it from anywhere. \"Open sidebar\" shows it now.",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        PermissionsRow()

        Spacer(Modifier.height(26.dp))
        Text("Run in background", fontSize = 18.sp, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            "Keeps the Home Assistant connection warm so the overlay opens instantly with live " +
                "states — even after an app update or reboot. Recommended on (TVs are always powered).",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep alive", color = TxtPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            ChipButton(
                if (settings.keepAlive) "On" else "Off",
                selected = settings.keepAlive,
                onClick = {
                    val next = !settings.keepAlive
                    viewModel.setKeepAlive(next)
                    if (next) com.tvassist.overlay.KeepAliveService.start(context)
                    else com.tvassist.overlay.KeepAliveService.stop(context)
                },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TriggersPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    PageScaffold("Triggers & keys", onBack) {
        Text(
            "The remote button that opens the Home Assistant control overlay from any app.",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text("Current: ${keyName(settings.triggerKeyCode)}", color = Color(0xFF8AB4F8), fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        TriggerKeyCapture(onCaptured = viewModel::setTriggerKey)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AppearancePage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pos = com.tvassist.data.settings.OverlayPosition.fromName(settings.overlayPosition)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PremiumIconButton(Icons.Rounded.ChevronLeft, "Back", onBack)
                Spacer(Modifier.width(14.dp))
                Text("Appearance & timing", fontSize = 23.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                // Pop the real overlay over this screen for a few seconds so changes can be judged live.
                ChipButton("Preview", selected = false, dense = true, onClick = {
                    com.tvassist.overlay.OverlayService.show(context)
                    scope.launch { delay(4500); com.tvassist.overlay.OverlayService.hide(context) }
                })
                Spacer(Modifier.width(8.dp))
                ChipButton("Reset", selected = false, dense = true, onClick = { viewModel.resetAppearance() })
            }
            Spacer(Modifier.height(16.dp))

            val controls = @Composable { AppearanceControls(settings, viewModel) }
            val preview = @Composable { OverlayPreviewPane(settings, pos) }

            // Studio layout: the preview docks where the overlay actually docks (kept compact).
            when (pos) {
                com.tvassist.data.settings.OverlayPosition.RIGHT ->
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { controls() }
                        Box(Modifier.width(212.dp).fillMaxHeight()) { preview() }
                    }
                com.tvassist.data.settings.OverlayPosition.LEFT ->
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(Modifier.width(212.dp).fillMaxHeight()) { preview() }
                        Box(Modifier.weight(1f).fillMaxHeight()) { controls() }
                    }
                com.tvassist.data.settings.OverlayPosition.TOP ->
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.fillMaxWidth().height(120.dp)) { preview() }
                        Box(Modifier.fillMaxWidth().weight(1f)) { controls() }
                    }
                com.tvassist.data.settings.OverlayPosition.BOTTOM ->
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.fillMaxWidth().weight(1f)) { controls() }
                        Box(Modifier.fillMaxWidth().height(120.dp)) { preview() }
                    }
            }
        }
    }
}

/** The compact, scrollable left/side controls pane of the appearance studio. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceControls(
    settings: com.tvassist.data.settings.Settings,
    viewModel: ConnectionViewModel,
) {
    var advancedExpanded by remember { mutableStateOf(false) }
    var expandedColor by remember { mutableStateOf<String?>(null) }
    val onExpand: (String?) -> Unit = { expandedColor = it }
    Column(
        Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard("Theme") {
            Row(
                // Pad inside the scroll so the first/last card's focus scale + selection border
                // aren't clipped flush against the scroll viewport edges.
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 7.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                THEME_PRESETS.forEach { preset ->
                    val selected = settings.overlayBgColor == preset.bg.toInt() &&
                        settings.overlayTileColor == preset.tile.toInt() &&
                        settings.overlayAccentColor == preset.accent.toInt()
                    ThemeCard(preset, selected) {
                        viewModel.applyOverlayColors(
                            preset.bg.toInt(), preset.tile.toInt(), preset.accent.toInt(),
                            preset.border.toInt(), preset.borderOn, preset.iconOn.toInt(), preset.iconOff.toInt(),
                            preset.focus.toInt(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            ColorGroupLabel("Accent")
            ColorControlRow("Accent", settings.overlayAccentColor, ACCENT_SWATCHES, expandedColor, onExpand, viewModel::setOverlayAccentColor)
        }

        SectionCard("Advanced colors") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Fine-tune every color individually.",
                    color = TxtMuted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                ChipButton(if (advancedExpanded) "Hide" else "Customize", selected = advancedExpanded, onClick = { advancedExpanded = !advancedExpanded }, dense = true)
            }
            if (advancedExpanded) {
                Spacer(Modifier.height(16.dp))
                ColorGroupLabel("Panel")
                ColorControlRow("Background", settings.overlayBgColor, BG_SWATCHES, expandedColor, onExpand, viewModel::setOverlayBgColor)
                Spacer(Modifier.height(2.dp))
                ColorControlRow("Tile", settings.overlayTileColor, TILE_SWATCHES, expandedColor, onExpand, viewModel::setOverlayTileColor)
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Panel border", color = TxtPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    ChipButton(if (settings.overlayBorderEnabled) "On" else "Off", selected = settings.overlayBorderEnabled, onClick = { viewModel.setOverlayBorderEnabled(!settings.overlayBorderEnabled) }, dense = true)
                }
                if (settings.overlayBorderEnabled) {
                    Spacer(Modifier.height(2.dp))
                    ColorControlRow("Border color", settings.overlayBorderColor, BORDER_SWATCHES, expandedColor, onExpand, viewModel::setOverlayBorderColor)
                }
                Spacer(Modifier.height(16.dp))
                ColorGroupLabel("Icons")
                ColorControlRow("Icon · on", settings.overlayIconOnColor, ICON_ON_SWATCHES, expandedColor, onExpand, viewModel::setOverlayIconOnColor)
                Spacer(Modifier.height(2.dp))
                ColorControlRow("Icon · off", settings.overlayIconOffColor, ICON_OFF_SWATCHES, expandedColor, onExpand, viewModel::setOverlayIconOffColor)
                Spacer(Modifier.height(16.dp))
                ColorGroupLabel("Focus")
                ColorControlRow("Highlight", settings.overlayFocusColor, ACCENT_SWATCHES, expandedColor, onExpand, viewModel::setOverlayFocusColor)
            }
        }
        SectionCard("Shape & position") {
            AppearanceRow("Position") {
                OptionChips(
                    options = listOf(
                        com.tvassist.data.settings.OverlayPosition.RIGHT to "Right",
                        com.tvassist.data.settings.OverlayPosition.LEFT to "Left",
                    ),
                    selected = com.tvassist.data.settings.OverlayPosition.fromName(settings.overlayPosition),
                    onSelect = viewModel::setOverlayPosition,
                )
            }
            Spacer(Modifier.height(12.dp))
            val sliderTrack = Brush.horizontalGradient(listOf(ChipDim, AppAccent))
            ColorSliderRow(
                "Size", settings.overlaySizeScale.toDouble(), 50.0, 130.0, 5.0, sliderTrack,
                { "${it.roundToInt()}%" }, { viewModel.setOverlaySizeScale(it.roundToInt()) }, resetKey = "size",
            )
            Spacer(Modifier.height(8.dp))
            ColorSliderRow(
                "Corners", settings.overlayCornerRadius.toDouble(), 0.0, 40.0, 2.0, sliderTrack,
                { "${it.roundToInt()} dp" }, { viewModel.setOverlayCornerRadius(it.roundToInt()) }, resetKey = "corner",
            )
            Spacer(Modifier.height(8.dp))
            ColorSliderRow(
                "Margin", settings.overlayMargin.toDouble(), 8.0, 72.0, 4.0, sliderTrack,
                { "${it.roundToInt()} dp" }, { viewModel.setOverlayMargin(it.roundToInt()) }, resetKey = "margin",
            )
            Spacer(Modifier.height(8.dp))
            ColorSliderRow(
                "Opacity", settings.overlayOpacity.toDouble(), 20.0, 100.0, 5.0, sliderTrack,
                { "${it.roundToInt()}%" }, { viewModel.setOverlayOpacity(it.roundToInt()) }, resetKey = "opacity",
            )
        }
        SectionCard("Motion") {
            AppearanceRow("Style") {
                OptionChips(
                    options = listOf(
                        com.tvassist.data.settings.OVERLAY_ANIM_SLIDE to "Slide",
                        com.tvassist.data.settings.OVERLAY_ANIM_FADE to "Fade",
                        com.tvassist.data.settings.OVERLAY_ANIM_NONE to "None",
                    ),
                    selected = settings.overlayAnimStyle,
                    onSelect = viewModel::setOverlayAnimStyle,
                )
            }
            if (settings.overlayAnimStyle != com.tvassist.data.settings.OVERLAY_ANIM_NONE) {
                Spacer(Modifier.height(12.dp))
                ColorSliderRow(
                    "Speed", settings.overlayAnimSpeedMs.toDouble(), 120.0, 500.0, 20.0,
                    Brush.horizontalGradient(listOf(ChipDim, AppAccent)),
                    { "${it.roundToInt()} ms" }, { viewModel.setOverlayAnimSpeedMs(it.roundToInt()) }, resetKey = "speed",
                )
            }
        }
        SectionCard("Timing") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Never", 5 to "5s", 10 to "10s", 15 to "15s", 30 to "30s", 60 to "60s").forEach { (secs, label) ->
                    ChipButton(label, settings.autoCloseSeconds == secs, onClick = { viewModel.setAutoCloseSeconds(secs) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** A sub-group divider inside the Colors card ("Panel" / "Icons" / "Focus") — label + hairline. */
@Composable
private fun ColorGroupLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = AppAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.07f)))
    }
    Spacer(Modifier.height(10.dp))
}

/**
 * One color as a premium accordion row: a clickable header (color chip + name + hex + chevron) that
 * expands to reveal the preset swatches and inline HSV sliders. Only one row is open at a time
 * (the one whose label == [expandedKey]) so the live preview stays visible.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ColorControlRow(
    label: String,
    argb: Int,
    swatches: List<Int>,
    expandedKey: String?,
    onExpand: (String?) -> Unit,
    onChange: (Int) -> Unit,
    // When [onReset] is set, a leading "Default (follow theme)" chip is shown in the swatch row,
    // selected while [isUnset]. Lets a control clear back to its theme default without a separate
    // reset button (used by header pills, where 0 = follow the theme's icon color).
    isUnset: Boolean = false,
    onReset: (() -> Unit)? = null,
) {
    val custom = expandedKey == label
    var h by remember { mutableFloatStateOf(0f) }
    var s by remember { mutableFloatStateOf(0f) }
    var v by remember { mutableFloatStateOf(0f) }
    // Seed HSV from the current color while the inline editor is closed.
    LaunchedEffect(argb, custom) {
        if (!custom) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(argb, hsv)
            h = hsv[0]; s = hsv[1] * 100f; v = hsv[2] * 100f
        }
    }
    fun emit() { onChange(Color.hsv(h, (s / 100f).coerceIn(0f, 1f), (v / 100f).coerceIn(0f, 1f)).toArgb()) }

    Column {
        Surface(
            onClick = { onExpand(if (custom) null else label) },
            modifier = Modifier.fillMaxWidth(),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (custom) CardFocusBg else Color.Transparent,
                focusedContainerColor = CardFocusBg,
                pressedContainerColor = CardFocusBg,
                contentColor = TxtPrimary,
                focusedContentColor = Color.White,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(1.5.dp, AppAccent), shape = RoundedCornerShape(12.dp)),
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(Color(argb))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Text(label, color = TxtPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(String.format("#%06X", 0xFFFFFF and argb), color = TxtMuted, fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
                Icon(
                    if (custom) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.ChevronRight,
                    contentDescription = null, tint = TxtMuted, modifier = Modifier.size(20.dp),
                )
            }
        }
        if (custom) {
            Spacer(Modifier.height(10.dp))
            ColorChips(
                swatches, argb, onChange,
                leading = onReset?.let { reset ->
                    {
                        // "Default" chip: theme-following (no override). Same circle style as swatches,
                        // marked with a reset glyph so it reads distinct from the plain grey swatch.
                        Surface(
                            onClick = reset,
                            shape = ClickableSurfaceDefaults.shape(CircleShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF3A414B), focusedContainerColor = Color(0xFF3A414B),
                                pressedContainerColor = Color(0xFF3A414B), contentColor = Color.White,
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
                            border = ClickableSurfaceDefaults.border(
                                border = if (isUnset) Border(BorderStroke(2.dp, Color.White), shape = CircleShape) else Border.None,
                                focusedBorder = Border(BorderStroke(2.dp, AppAccent), shape = CircleShape),
                            ),
                        ) {
                            Box(Modifier.size(27.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.FormatColorReset, contentDescription = "Default color",
                                    tint = TxtMuted, modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            ColorSliderRow("Hue", h.toDouble(), 0.0, 360.0, 4.0,
                Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) }),
                { "${it.roundToInt()}°" }, { h = it.toFloat(); emit() }, resetKey = "h$label")
            Spacer(Modifier.height(8.dp))
            ColorSliderRow("Saturation", s.toDouble(), 0.0, 100.0, 4.0,
                Brush.horizontalGradient(listOf(Color.hsv(h, 0f, v / 100f), Color.hsv(h, 1f, v / 100f))),
                { "${it.roundToInt()}%" }, { s = it.toFloat(); emit() }, resetKey = "s$label")
            Spacer(Modifier.height(8.dp))
            ColorSliderRow("Brightness", v.toDouble(), 0.0, 100.0, 4.0,
                Brush.horizontalGradient(listOf(Color.Black, Color.hsv(h, s / 100f, 1f))),
                { "${it.roundToInt()}%" }, { v = it.toFloat(); emit() }, resetKey = "v$label")
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** A titled rounded section container for the settings pages. */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg).padding(14.dp),
    ) {
        Text(title.uppercase(), color = TxtMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(11.dp))
        content()
    }
}

/** Live preview pane: a faux TV backdrop with the overlay panel docked where it really docks. */
@Composable
private fun OverlayPreviewPane(settings: com.tvassist.data.settings.Settings, pos: com.tvassist.data.settings.OverlayPosition) {
    val theme = remember(settings) {
        overlayThemeOf(
            settings.overlayBgColor, settings.overlayTileColor, settings.overlayAccentColor,
            settings.overlayBorderColor, settings.overlayBorderEnabled,
            settings.overlayIconOnColor, settings.overlayIconOffColor, settings.overlayFocusColor,
        )
    }
    val alpha = settings.overlayOpacity.coerceIn(0, 100) / 100f
    val base = Color(settings.overlayBgColor)
    val panelBrush = Brush.verticalGradient(
        listOf(base.copy(alpha = alpha), lerp(base, Color.Black, 0.22f).copy(alpha = alpha)),
    )
    val shape = RoundedCornerShape(settings.overlayCornerRadius.dp.coerceAtMost(28.dp))
    val borderMod = if (settings.overlayBorderEnabled) {
        Modifier.border(1.dp, Color(settings.overlayBorderColor).copy(alpha = 0.55f), shape)
    } else {
        Modifier
    }
    val alignment = when (pos) {
        com.tvassist.data.settings.OverlayPosition.RIGHT -> Alignment.CenterEnd
        com.tvassist.data.settings.OverlayPosition.LEFT -> Alignment.CenterStart
        com.tvassist.data.settings.OverlayPosition.TOP -> Alignment.TopCenter
        com.tvassist.data.settings.OverlayPosition.BOTTOM -> Alignment.BottomCenter
    }

    Box(
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B3A55), Color(0xFF4A3B5E), Color(0xFF1E3A34))))
            .padding(12.dp),
        contentAlignment = alignment,
    ) {
        CompositionLocalProvider(LocalOverlayTheme provides theme) {
            Column(
                modifier = Modifier.width(178.dp)
                    .clip(shape).background(panelBrush).then(borderMod).padding(9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Living Room", color = theme.subText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(theme.subText.copy(alpha = 0.25f)))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(theme.tile).padding(horizontal = 7.dp, vertical = 3.dp)) {
                        Text("21°", color = theme.text, fontSize = 11.sp)
                    }
                }
                PreviewTile(theme, Icons.Rounded.Lightbulb, "Lamp", "On · 80%", accentValue = true, focused = true)
                PreviewTile(theme, Icons.Rounded.DeviceThermostat, "Thermostat", "Heat · 22°", accentValue = false)
            }
        }
    }
}

@Composable
private fun PreviewTile(theme: OverlayTheme, icon: ImageVector, title: String, sub: String, accentValue: Boolean, focused: Boolean = false) {
    val tileShape = RoundedCornerShape(11.dp)
    Row(
        modifier = Modifier.fillMaxWidth().clip(tileShape)
            .background(if (focused) theme.tileFocused else theme.tile)
            .then(if (focused) Modifier.border(1.5.dp, theme.focus, tileShape) else Modifier)
            .padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .background(if (accentValue) theme.accent.copy(alpha = 0.22f) else theme.chip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (accentValue) theme.iconOn else theme.iconOff, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = theme.text, fontSize = 12.sp, maxLines = 1)
            Text(sub, color = theme.subText, fontSize = 10.sp, maxLines = 1)
        }
        Text(if (accentValue) "80%" else "22°", color = if (accentValue) theme.accent else theme.text, fontSize = 12.sp)
    }
}

// Curated color swatches for the overlay (ARGB). Background = dark, tile = mid, accent = vivid.
private val BG_SWATCHES = listOf(
    0xFF12161B, 0xFF0B0E12, 0xFF161B2E, 0xFF101A14, 0xFF1E1420, 0xFF1B2127, 0xFF201A12, 0xFF0E0E0E,
).map { it.toInt() }
private val TILE_SWATCHES = listOf(
    0xFF2A2F37, 0xFF1F2937, 0xFF2E2A3A, 0xFF243042, 0xFF323A2E, 0xFF3A2E2E, 0xFF26313A, 0xFF2C2C2C,
).map { it.toInt() }
private val ACCENT_SWATCHES = listOf(
    0xFFF39C12, 0xFF5C7CFA, 0xFF34D399, 0xFFF472B6, 0xFFA78BFA, 0xFF22D3EE, 0xFFEF4444, 0xFFFACC15,
).map { it.toInt() }
private val BORDER_SWATCHES = listOf(
    0xFFF39C12, 0xFF5C7CFA, 0xFF34D399, 0xFFF472B6, 0xFFFFFFFF, 0xFF9AA3AE, 0xFF3A4350, 0xFF000000,
).map { it.toInt() }
private val ICON_ON_SWATCHES = listOf(
    0xFFEAEDF0, 0xFFFFFFFF, 0xFFF39C12, 0xFF5C7CFA, 0xFF34D399, 0xFFFACC15, 0xFF22D3EE, 0xFFF472B6,
).map { it.toInt() }
private val ICON_OFF_SWATCHES = listOf(
    0xFF9AA3AE, 0xFF6B7280, 0xFF4B5563, 0xFF808890, 0xFF5A6472, 0xFF3A4350, 0xFFB6C0CC, 0xFF7A8696,
).map { it.toInt() }

/** A focusable row of color swatches; the selected one gets a white ring, focus gets accent. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ColorChips(
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading()
        options.forEach { argb ->
            val sel = argb == selected
            Surface(
                onClick = { onSelect(argb) },
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(argb), focusedContainerColor = Color(argb),
                    pressedContainerColor = Color(argb), contentColor = Color.White,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
                border = ClickableSurfaceDefaults.border(
                    border = if (sel) Border(BorderStroke(2.dp, Color.White), shape = CircleShape) else Border.None,
                    focusedBorder = Border(BorderStroke(2.dp, AppAccent), shape = CircleShape),
                ),
            ) {
                Box(Modifier.size(27.dp))
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}

/** A cohesive overlay color theme. Colors are Long ARGB (Color(Long) renders, .toInt() stores). */
private data class OverlayThemePreset(
    val name: String,
    val bg: Long, val tile: Long, val accent: Long,
    val border: Long, val borderOn: Boolean, val iconOn: Long, val iconOff: Long, val focus: Long,
)

private val THEME_PRESETS = listOf(
    // Dark — deeper backgrounds, lighter tiles for contrast, vivid accents.
    OverlayThemePreset("Graphite", 0xFF0E1217, 0xFF232A33, 0xFFFFA51E, 0xFFFFA51E, true, 0xFFFFFFFF, 0xFF8E97A3, 0xFFFFA51E),
    OverlayThemePreset("Midnight", 0xFF0A0F1E, 0xFF1C2742, 0xFF6E8BFF, 0xFF6E8BFF, true, 0xFFFFFFFF, 0xFF7E8AB0, 0xFF6E8BFF),
    OverlayThemePreset("OLED", 0xFF000000, 0xFF1A1A1C, 0xFF3DDC84, 0xFF3DDC84, true, 0xFFFFFFFF, 0xFF7A8088, 0xFF3DDC84),
    OverlayThemePreset("Nord", 0xFF2A2F3A, 0xFF3B4252, 0xFF8FD3E0, 0xFF8FD3E0, true, 0xFFECEFF4, 0xFF9AA3B2, 0xFF8FD3E0),
    OverlayThemePreset("Sunset", 0xFF170F19, 0xFF2E1E2A, 0xFFFF5FA2, 0xFFFF5FA2, true, 0xFFFFFFFF, 0xFFB08496, 0xFFFF5FA2),
    OverlayThemePreset("Forest", 0xFF0A150F, 0xFF19281E, 0xFF3DDC84, 0xFF3DDC84, true, 0xFFFFFFFF, 0xFF7FA38C, 0xFF3DDC84),
    OverlayThemePreset("Ocean", 0xFF07151B, 0xFF143038, 0xFF24E0FF, 0xFF24E0FF, true, 0xFFFFFFFF, 0xFF6F9AA6, 0xFF24E0FF),
    OverlayThemePreset("Mono", 0xFF0B0B0B, 0xFF242424, 0xFFFFFFFF, 0xFFFFFFFF, true, 0xFFFFFFFF, 0xFF8A8A8A, 0xFFFFFFFF),
    // Light — white tiles on a soft tint, vibrant accents.
    OverlayThemePreset("Daylight", 0xFFE7EDF6, 0xFFFFFFFF, 0xFF1A73E8, 0xFF1A73E8, true, 0xFF1A73E8, 0xFF8A93A3, 0xFF1A73E8),
    OverlayThemePreset("Linen", 0xFFF3EDE2, 0xFFFFFFFF, 0xFFD8602E, 0xFFD8602E, true, 0xFFD8602E, 0xFFA89A88, 0xFFD8602E),
    OverlayThemePreset("Paper", 0xFFE8EAF2, 0xFFFFFFFF, 0xFF5145E6, 0xFF5145E6, true, 0xFF5145E6, 0xFF8B90A0, 0xFF5145E6),
    OverlayThemePreset("Mint", 0xFFE4F3EC, 0xFFFFFFFF, 0xFF0BA86A, 0xFF0BA86A, true, 0xFF0BA86A, 0xFF7E9A8C, 0xFF0BA86A),
    // High contrast.
    OverlayThemePreset("Contrast", 0xFF000000, 0xFF1A1A1A, 0xFFFFE000, 0xFFFFE000, true, 0xFFFFFFFF, 0xFFD0D0D0, 0xFFFFE000),
    OverlayThemePreset("Hi-Light", 0xFFFFFFFF, 0xFFEEF0F3, 0xFF0A39FF, 0xFF111111, true, 0xFF111111, 0xFF555555, 0xFF0A39FF),
)

/** A tappable theme card showing a mini live preview of the overlay in that theme. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ThemeCard(preset: OverlayThemePreset, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF14181E), focusedContainerColor = Color(0xFF1C222B),
            pressedContainerColor = Color(0xFF1C222B), contentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, AppAccent), shape = RoundedCornerShape(14.dp)) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(14.dp)),
        ),
    ) {
        Column(Modifier.width(100.dp).padding(7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(9.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF2B3A55), Color(0xFF1E3A34))))
                    .padding(5.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(preset.bg))
                        .then(if (preset.borderOn) Modifier.border(1.dp, Color(preset.border).copy(alpha = 0.55f), RoundedCornerShape(6.dp)) else Modifier)
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(16.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(preset.accent)))
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(4.dp).clip(CircleShape).background(Color(preset.iconOff)))
                    }
                    MiniTile(preset, on = true)
                    MiniTile(preset, on = false)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(preset.name, color = Color.White, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun MiniTile(preset: OverlayThemePreset, on: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color(preset.tile)).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) Color(preset.iconOn) else Color(preset.iconOff)))
        Spacer(Modifier.width(3.dp))
        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(preset.iconOff).copy(alpha = 0.5f)))
        if (on) {
            Spacer(Modifier.width(3.dp))
            Box(Modifier.width(8.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(preset.accent)))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BackupPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val status by viewModel.backupStatus.collectAsStateWithLifecycle()
    val backups by viewModel.backups.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var location by remember { mutableStateOf(com.tvassist.data.settings.BackupLocation.DOWNLOAD) }
    // Off by default: an included secret is written to Download/USB, so it must be opt-in + encrypted.
    var includeSecrets by remember { mutableStateOf(false) }
    var backupPass by remember { mutableStateOf("") }
    var restorePass by remember { mutableStateOf("") }
    // Which backup row is expanded to show its Restore/Delete actions, and whether Restore is
    // awaiting its confirm tap.
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var confirmingRestore by remember { mutableStateOf(false) }
    // Re-checked on each recomposition (after returning from the grant screen).
    val hasAccess = viewModel.hasAllFilesAccess()

    // (Re)load the backup list on entry and whenever the location (or granted access) changes.
    LaunchedEffect(location, hasAccess) {
        selectedPath = null
        confirmingRestore = false
        if (!location.needsAllFiles || hasAccess) viewModel.refreshBackups(location)
    }
    // Android 10 and below grant Download/USB access via a runtime storage permission (not the
    // Android 11+ "All files access" settings screen).
    val storagePermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.refreshBackups(location)
        else viewModel.showMessage("Storage permission denied — it's needed for Download/USB backups.")
    }

    PageScaffold("Backup & restore", onBack) {
        Text(
            "Saves your connection, entities, overlay layout, cameras, colors, notification & map " +
                "settings and trigger key to a timestamped file (app + TV model + date). App folder " +
                "is wiped on uninstall; Download and USB survive it. Pick any saved backup below to " +
                "restore or delete it.",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Include secrets", color = TxtPrimary, fontSize = 15.sp)
                Text(
                    "HA token, Google Maps key, notification token. Encrypted with a passphrase so the " +
                        "backup file is safe to keep in Download/USB. Off = a share-safe backup.",
                    color = TxtMuted, fontSize = 12.sp,
                )
            }
            ChipButton(
                if (includeSecrets) "On" else "Off",
                selected = includeSecrets,
                onClick = { includeSecrets = !includeSecrets },
            )
        }
        if (includeSecrets) {
            Spacer(Modifier.height(10.dp))
            TvTextField(value = backupPass, onValueChange = { backupPass = it }, placeholder = "Backup passphrase", secret = true)
            Spacer(Modifier.height(4.dp))
            Text(
                "Needed to protect the secrets — you'll enter it again to restore. Keep it somewhere safe; " +
                    "it can't be recovered.",
                color = TxtMuted, fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        AppearanceRow("Location") {
            OptionChips(
                options = listOf(
                    com.tvassist.data.settings.BackupLocation.APP to "App folder",
                    com.tvassist.data.settings.BackupLocation.DOWNLOAD to "Download",
                    com.tvassist.data.settings.BackupLocation.USB to "USB",
                ),
                selected = location,
                onSelect = { location = it },
            )
        }
        Spacer(Modifier.height(6.dp))

        if (location.needsAllFiles && !hasAccess) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Text(
                    "Download/USB needs \"All files access\". Grant it once, then come back.",
                    color = Color(0xFFFFC107), fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                AccentButton("Grant file access", {
                    val pkg = android.net.Uri.parse("package:com.tvassist")
                    val intents = listOf(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
                    )
                    val opened = intents.any { intent ->
                        runCatching {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent); true
                        }.getOrDefault(false)
                    }
                    if (!opened) {
                        viewModel.showMessage("This TV has no All-files-access screen. Grant via adb:  adb shell appops set com.tvassist MANAGE_EXTERNAL_STORAGE allow")
                    }
                })
            } else {
                Text(
                    "Download/USB needs storage access. Grant it once, then come back.",
                    color = Color(0xFFFFC107), fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                AccentButton("Grant storage access", {
                    storagePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                })
            }
        } else {
            AccentButton(
                "Back up now",
                {
                    if (includeSecrets && backupPass.isBlank()) {
                        viewModel.showMessage("Enter a passphrase to protect the included secrets.")
                    } else {
                        viewModel.backupSettings(location, includeSecrets, backupPass)
                    }
                },
                leadingIcon = Icons.Rounded.Backup,
            )
            Spacer(Modifier.height(20.dp))

            Text(
                "Saved backups" + if (backups.isNotEmpty()) " (${backups.size})" else "",
                color = TxtPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            if (backups.isEmpty()) {
                Text(
                    "No backups in ${location.label} yet — tap \"Back up now\" to create one.",
                    color = TxtMuted, fontSize = 12.sp,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    backups.forEach { info ->
                        val isSelected = selectedPath == info.path
                        val rel = android.text.format.DateUtils.getRelativeTimeSpanString(info.timestampMs)
                        val size = android.text.format.Formatter.formatShortFileSize(context, info.sizeBytes)
                        PremiumRow(
                            icon = Icons.Rounded.Backup,
                            title = info.name,
                            subtitle = "$rel · $size",
                            onClick = {
                                selectedPath = if (isSelected) null else info.path
                                confirmingRestore = false
                            },
                        )
                        if (isSelected) {
                            Spacer(Modifier.height(6.dp))
                            if (confirmingRestore) {
                                Text(
                                    "Overwrites all current settings on this TV (and reconnects). " +
                                        "Restore this backup?",
                                    color = Color(0xFFFFC107), fontSize = 12.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                TvTextField(value = restorePass, onValueChange = { restorePass = it }, placeholder = "Passphrase (if secrets are encrypted)", secret = true)
                                Text(
                                    "Leave blank if the backup has no secrets — everything else still restores.",
                                    color = TxtMuted, fontSize = 11.sp,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AccentButton("Confirm restore", {
                                        viewModel.restoreFrom(info, restorePass)
                                        restorePass = ""
                                        selectedPath = null
                                        confirmingRestore = false
                                    })
                                    AccentButton("Cancel", { confirmingRestore = false })
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AccentButton("Restore", { confirmingRestore = true })
                                    AccentButton("Delete", {
                                        viewModel.deleteBackup(info)
                                        selectedPath = null
                                    }, leadingIcon = Icons.Rounded.DeleteOutline)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }

        when (val s = status) {
            is ConnectionViewModel.BackupStatus.Idle -> {}
            is ConnectionViewModel.BackupStatus.Loading -> {
                Spacer(Modifier.height(12.dp))
                Text(s.message, color = TxtMuted, fontSize = 13.sp)
            }
            is ConnectionViewModel.BackupStatus.Message -> {
                Spacer(Modifier.height(12.dp))
                Text(
                    s.text,
                    color = if (s.ok) Color(0xFF4CAF50) else Color(0xFFF44336),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** A random alphanumeric access token for the notification endpoint. 24 chars so the masked display
 * (first-2 + last-4 shown) still leaves a strong, un-guessable hidden part. */
private fun randomToken(length: Int = 24): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return buildString { repeat(length) { append(alphabet.random()) } }
}

/** All access tokens that secure this TV, in one place. Stored encrypted, shown masked (write-only). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SecurityPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ip = remember { com.tvassist.data.notify.NotificationServer.localIp() }
    PageScaffold("Security", onBack) {
        Text(
            "Access tokens and keys that secure this TV — all encrypted at rest and shown masked. " +
                "Enter or regenerate them here.",
            color = TxtMuted, fontSize = 13.sp,
        )

        // --- Home Assistant long-lived token (mirrors Settings → Connection; changing it reconnects) ---
        Spacer(Modifier.height(20.dp))
        Text("Home Assistant token", fontSize = 14.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            if (settings.baseUrl.isBlank()) {
                "Set up the connection first (Settings → Connection); then you can rotate the token here."
            } else {
                "Long-lived access token for ${settings.baseUrl}. Changing it reconnects to Home Assistant."
            },
            fontSize = 12.sp, color = TxtMuted,
        )
        if (settings.baseUrl.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            var haTok by remember { mutableStateOf(settings.token) }
            TvTextField(value = haTok, onValueChange = { haTok = it }, placeholder = "paste token from HA profile", secret = true)
            LaunchedEffect(haTok) {
                if (haTok == settings.token || haTok.isBlank()) return@LaunchedEffect
                delay(600)
                viewModel.saveAndConnect(settings.baseUrl, haTok)
            }
        }

        // --- Notification / push token ---
        Spacer(Modifier.height(22.dp))
        Text("Notification / push token", fontSize = 14.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "Secures pushes to this TV (notify, speak, play sound). Leave blank for no auth. When set, " +
                "pushes must include ?token=… (or an X-Token header) — e.g. " +
                "http://${ip ?: "<tv-ip>"}:${settings.notificationPort}/notify?token=…",
            fontSize = 12.sp, color = TxtMuted,
        )
        Spacer(Modifier.height(8.dp))
        // Shown once right after Generate; debounced write, seeded once.
        var tok by remember { mutableStateOf(settings.notificationToken) }
        var justGenerated by remember { mutableStateOf<String?>(null) }
        TvTextField(value = tok, onValueChange = { tok = it; justGenerated = null }, placeholder = "no token", secret = true)
        LaunchedEffect(tok) {
            if (tok == settings.notificationToken) return@LaunchedEffect
            delay(400)
            viewModel.setNotificationToken(tok)
        }
        justGenerated?.let { g ->
            Spacer(Modifier.height(8.dp))
            Text("New token — copy it into Home Assistant now (it won't be shown again):", color = AppAccent, fontSize = 12.sp)
            Text(g, color = TxtPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentButton("Generate token", { val t = randomToken(); tok = t; justGenerated = t }, leadingIcon = Icons.Rounded.Autorenew)
            if (tok.isNotEmpty()) ChipButton("Clear", selected = false, onClick = { tok = ""; justGenerated = null })
        }

        // --- Google Maps key (moved here from Settings → Maps) ---
        Spacer(Modifier.height(22.dp))
        Text("Google Maps key", fontSize = 14.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "Optional — enables Google map tiles for person maps (blank = free OpenStreetMap). Enable the " +
                "\"Map Tiles API\" on the key; restricting it to Android apps is recommended.",
            fontSize = 12.sp, color = TxtMuted,
        )
        Spacer(Modifier.height(6.dp))
        var mapsKey by remember { mutableStateOf(settings.googleMapsApiKey) }
        TvTextField(value = mapsKey, onValueChange = { mapsKey = it }, placeholder = "Google Maps API key (optional)", secret = true)
        LaunchedEffect(mapsKey) {
            if (mapsKey == settings.googleMapsApiKey) return@LaunchedEffect
            delay(500)
            viewModel.setGoogleMapsApiKey(mapsKey)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NotificationsPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ip = remember { com.tvassist.data.notify.NotificationServer.localIp() }
    PageScaffold("Notifications", onBack) {
        Text(
            "Let Home Assistant push toast/banner notifications to this TV. Install the tv_assist " +
                "integration (notify.tv_assist), or POST to the URL below from a rest_command.",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable notifications", color = TxtPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            ChipButton(
                if (settings.notificationsEnabled) "On" else "Off",
                selected = settings.notificationsEnabled,
                onClick = {
                    val next = !settings.notificationsEnabled
                    viewModel.setNotificationsEnabled(next)
                    if (next || settings.keepAlive) com.tvassist.overlay.KeepAliveService.start(context)
                    else com.tvassist.overlay.KeepAliveService.stop(context)
                },
            )
        }
        if (settings.notificationsEnabled && settings.notificationToken.isBlank()) {
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(0x33E55B5B)).border(1.dp, Color(0xFFE55B5B).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Text("No token set — this server is unauthenticated", color = Color(0xFFFF8A8A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    "Any device on your network can push notifications to this TV. Generate a token and add " +
                        "it to your Home Assistant pushes.",
                    color = TxtMuted, fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                AccentButton("Generate token", { viewModel.setNotificationToken(randomToken()) }, leadingIcon = Icons.Rounded.Autorenew)
            }
        }
        if (settings.notificationsEnabled) {
            Spacer(Modifier.height(18.dp))
            Text("Server address", fontSize = 14.sp, color = TxtMuted)
            Spacer(Modifier.height(4.dp))
            Text("http://${ip ?: "<tv-ip>"}:${settings.notificationPort}", color = AppAccent, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Point the tv_assist integration (or a rest_command) at this address. Needs the " +
                    "overlay permission to draw, and \"Run in background\" keeps it listening.",
                color = TxtMuted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(18.dp))
            Text("Default duration", fontSize = 14.sp, color = TxtMuted)
            Spacer(Modifier.height(2.dp))
            Text(
                "Used when a notification doesn't specify one. For a persistent toast, send duration 0 " +
                    "from the Home Assistant service call.",
                fontSize = 12.sp, color = TxtMuted,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4 to "4s", 6 to "6s", 8 to "8s", 12 to "12s", 20 to "20s", 30 to "30s").forEach { (secs, label) ->
                    ChipButton(label, settings.notificationDefaultDuration == secs, onClick = { viewModel.setNotificationDefaultDuration(secs) })
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Interactive hold", fontSize = 14.sp, color = TxtMuted)
            Spacer(Modifier.height(2.dp))
            Text(
                "When an interactive notification is opened (OK to enlarge), how long to keep it before " +
                    "auto-closing. \"Until BACK\" keeps it open until the remote dismisses it. A push can " +
                    "override this with enlarge_timeout.",
                fontSize = 12.sp, color = TxtMuted,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Until BACK", 30 to "30s", 60 to "1m", 120 to "2m", 300 to "5m").forEach { (secs, label) ->
                    ChipButton(label, settings.interactiveEnlargeTimeout == secs, onClick = { viewModel.setInteractiveEnlargeTimeout(secs) })
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Access token", fontSize = 14.sp, color = TxtMuted)
            Spacer(Modifier.height(2.dp))
            Text(
                "The token that secures pushes to this TV now lives in Settings → Security. " +
                    "When set, pushes must include ?token=… (or an X-Token header).",
                fontSize = 12.sp, color = TxtMuted,
            )
            Spacer(Modifier.height(18.dp))
            AccentButton(
                "Test notification",
                {
                    (context.applicationContext as TvAssistApp).notificationStore.show(
                        com.tvassist.data.notify.TvNotification(
                            id = "test",
                            message = "Notifications are working!",
                            title = "TV Assist",
                            icon = "mdi:check-circle",
                            durationSec = settings.notificationDefaultDuration,
                        ),
                    )
                },
                leadingIcon = Icons.Rounded.Notifications,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DisplayPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The dim/clock overlays are drawn by the keep-alive service — make sure it's running.
    fun ensureService() = com.tvassist.overlay.KeepAliveService.start(context)
    PageScaffold("On-screen display", onBack) {
        // ---- Screen dimming ----
        Text("Screen dimming", color = TxtPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "A translucent layer over the TV picture — good for movie night or a panel that's too bright.",
            color = TxtMuted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Off", 15 to "15%", 30 to "30%", 45 to "45%", 60 to "60%", 75 to "75%", 90 to "90%").forEach { (lvl, label) ->
                ChipButton(label, settings.dimLevel == lvl, onClick = { ensureService(); viewModel.setDimLevel(lvl) })
            }
        }

        Spacer(Modifier.height(26.dp))

        // ---- Always-on clock ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Always-on clock", color = TxtPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("A small clock pinned to a screen corner.", color = TxtMuted, fontSize = 13.sp)
            }
            ChipButton(
                if (settings.clockEnabled) "On" else "Off",
                selected = settings.clockEnabled,
                onClick = { ensureService(); viewModel.setClockEnabled(!settings.clockEnabled) },
            )
        }

        if (settings.clockEnabled) {
            Spacer(Modifier.height(18.dp))
            Text("Corner", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    DisplayCorner.TOP_START to "Top left",
                    DisplayCorner.TOP_END to "Top right",
                    DisplayCorner.BOTTOM_START to "Bottom left",
                    DisplayCorner.BOTTOM_END to "Bottom right",
                ).forEach { (corner, label) ->
                    ChipButton(label, DisplayCorner.fromName(settings.clockCorner) == corner, onClick = { viewModel.setClockCorner(corner) })
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Format", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton("12-hour", !settings.clock24Hour, onClick = { viewModel.setClock24Hour(false) })
                ChipButton("24-hour", settings.clock24Hour, onClick = { viewModel.setClock24Hour(true) })
                ChipButton("Seconds", settings.clockSeconds, onClick = { viewModel.setClockSeconds(!settings.clockSeconds) })
            }

            Spacer(Modifier.height(18.dp))
            Text("Size", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(32 to "Small", 44 to "Medium", 60 to "Large", 80 to "Huge").forEach { (sp, label) ->
                    ChipButton(label, settings.clockSize == sp, onClick = { viewModel.setClockSize(sp) })
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            "Dim/clock update live and survive reboots. Home Assistant can also control these by POSTing " +
                "to /set/overlay (dim, clock, corner) on the notification server.",
            color = TxtMuted, fontSize = 12.sp,
        )
    }
}

/**
 * Toggle row for the shared Web setup console. All setup pages (Connection/Cameras/Maps) show this;
 * they all drive the one server. [section] just tailors the hint. PIN shows in the nav rail.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WebSetupRow(viewModel: ConnectionViewModel, section: String) {
    val onboarding by viewModel.webOnboarding.collectAsStateWithLifecycle()
    val addr = (onboarding as? WebOnboarding.Running)?.address
    val running = addr != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Set up from phone/laptop", color = TxtPrimary, fontSize = 15.sp)
            Text(
                if (running) "Open $addr on your phone, enter the PIN (TV, bottom-left), then tap $section."
                else "Turn on a PIN-protected web console to configure $section (and more) from a browser.",
                color = if (running) AppAccent else TxtMuted, fontSize = 12.sp,
            )
        }
        ChipButton(
            if (running) "On" else "Off",
            selected = running,
            onClick = { if (running) viewModel.stopWebOnboarding() else viewModel.startWebOnboarding() },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MapsPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val entities by viewModel.entities.collectAsStateWithLifecycle()
    val hasKey = settings.googleMapsApiKey.isNotBlank()
    val mapCards = settings.mapCards
    fun nameOf(id: String) = entities.firstOrNull { it.entityId == id }?.friendlyName ?: id

    // The map card being added/edited (null = collapsed) and whether the member picker is showing.
    var draft by remember { mutableStateOf<com.tvassist.data.settings.MapCard?>(null) }
    var pickingMember by remember { mutableStateOf(false) }

    val d = draft
    if (pickingMember && d != null) {
        BackHandler { pickingMember = false }
        LayoutEntityPicker(
            viewModel = viewModel,
            filter = { it.isPerson },
            title = "Add people to map card",
            onAdd = { ids ->
                val fresh = ids.filter { id -> d.members.none { it.entityId == id } }
                    .map { com.tvassist.data.settings.MapCardMember(it) }
                draft = d.copy(members = d.members + fresh)
                pickingMember = false
            },
            onBack = { pickingMember = false },
        )
        return
    }

    PageScaffold("Maps", onBack) {
        // ---- Map cards: saved multi-entity location maps ----
        Text("Map cards", fontSize = 16.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "A map card plots several people/device-trackers on one map, centered on your home zone. " +
                "Each card shows up as an entity you can add to the overlay like any other.",
            color = TxtMuted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))

        mapCards.forEach { card ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(card.name.ifBlank { "(unnamed)" }, color = TxtPrimary, fontSize = 15.sp, maxLines = 1)
                    Text(
                        if (card.members.size == 1) "1 person" else "${card.members.size} people",
                        color = TxtMuted, fontSize = 12.sp, maxLines = 1,
                    )
                }
                ChipButton("Edit", selected = d?.id == card.id, onClick = { draft = card })
                Spacer(Modifier.width(8.dp))
                ChipButton("Delete", selected = false, onClick = {
                    viewModel.deleteMapCard(card.id)
                    if (d?.id == card.id) draft = null
                })
            }
        }

        Spacer(Modifier.height(12.dp))
        if (d == null) {
            AccentButton(
                "Add map card",
                {
                    draft = com.tvassist.data.settings.MapCard(
                        id = "map_" + System.currentTimeMillis().toString(36),
                        name = "",
                    )
                },
                leadingIcon = Icons.Rounded.Add,
            )
        } else {
            MapCardEditor(
                draft = d,
                isNew = mapCards.none { it.id == d.id },
                nameOf = ::nameOf,
                onChange = { draft = it },
                onAddMember = { pickingMember = true },
                onSave = {
                    if (d.name.isNotBlank() && d.members.isNotEmpty()) {
                        viewModel.saveMapCard(d)
                        draft = null
                    }
                },
                onCancel = { draft = null },
            )
        }

        Spacer(Modifier.height(28.dp))

        // ---- Map source (global tiles config) ----
        Text("Map source", fontSize = 16.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Applies to all person/map tiles. Leave the key blank for free OpenStreetMap; add a Google " +
                "Maps Platform API key for Google tiles + live traffic (Google bills per map load).",
            color = TxtMuted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasKey) "✓ Key set — using Google tiles" else "No key — using OpenStreetMap",
            color = if (hasKey) Color(0xFF6FCF7F) else TxtMuted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))

        WebSetupRow(viewModel, "Maps")
        Spacer(Modifier.height(18.dp))

        Text("API key", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(2.dp))
        Text(
            if (settings.googleMapsApiKey.isNotBlank()) {
                "A key is set (using Google tiles). Manage it in Settings → Security."
            } else {
                "No key set (using OpenStreetMap). Add a Google Maps key in Settings → Security for Google tiles."
            },
            color = TxtMuted, fontSize = 12.sp,
        )

        if (hasKey) {
            Spacer(Modifier.height(18.dp))
            Text("Map style", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("roadmap" to "Roadmap", "satellite" to "Satellite").forEach { (value, label) ->
                    ChipButton(label, settings.mapStyle == value, onClick = { viewModel.setMapStyle(value) })
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Live traffic overlay", color = TxtPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                ChipButton(
                    if (settings.mapTraffic) "On" else "Off",
                    selected = settings.mapTraffic,
                    onClick = { viewModel.setMapTraffic(!settings.mapTraffic) },
                )
            }
        }
    }
}

/** Add/edit form for a single [com.tvassist.data.settings.MapCard]. */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MapCardEditor(
    draft: com.tvassist.data.settings.MapCard,
    isNew: Boolean,
    nameOf: (String) -> String,
    onChange: (com.tvassist.data.settings.MapCard) -> Unit,
    onAddMember: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).padding(12.dp)) {
        Text(if (isNew) "New map card" else "Edit map card", color = TxtPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("Name", color = TxtMuted, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        TvTextField(draft.name, { onChange(draft.copy(name = it)) }, "Family")
        Spacer(Modifier.height(12.dp))

        Text("Zoom", color = TxtMuted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Rounded.Remove, "Zoom out", dense = true) { onChange(draft.copy(mapZoom = (draft.mapZoom - 1).coerceIn(3, 20))) }
            Spacer(Modifier.width(10.dp))
            Text("${draft.mapZoom}", fontSize = 15.sp, color = Color.White)
            Spacer(Modifier.width(10.dp))
            IconBtn(Icons.Rounded.Add, "Zoom in", dense = true) { onChange(draft.copy(mapZoom = (draft.mapZoom + 1).coerceIn(3, 20))) }
        }
        Spacer(Modifier.height(12.dp))

        Text("Map source", color = TxtMuted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OverlayTile.MAP_PROVIDERS.forEach { (key, label) ->
                ChipButton(label, draft.mapProvider == key, onClick = { onChange(draft.copy(mapProvider = key)) }, dense = true)
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Legend", color = TxtPrimary, fontSize = 14.sp)
                Text("Side list of members on the map", color = TxtMuted, fontSize = 12.sp)
            }
            ChipButton(
                if (draft.showLegend) "On" else "Off",
                selected = draft.showLegend,
                onClick = { onChange(draft.copy(showLegend = !draft.showLegend)) },
            )
        }
        Spacer(Modifier.height(14.dp))

        Text("People / devices on this map", color = TxtMuted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            draft.members.forEachIndexed { i, m ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF14181E)).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(nameOf(m.entityId), fontSize = 13.sp, color = TxtPrimary, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
                        IconBtn(Icons.Rounded.Close, "Remove", dense = true) {
                            onChange(draft.copy(members = draft.members.filterIndexed { idx, _ -> idx != i }))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Legend shows", fontSize = 11.sp, color = TxtMuted)
                    Spacer(Modifier.height(5.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OverlayTile.PERSON_OPTIONS_ALL.forEach { (key, label) ->
                            val on = key in m.options
                            ChipButton(label, selected = on, dense = true, onClick = {
                                val nextOpts = if (on) m.options - key else m.options + key
                                onChange(draft.copy(members = draft.members.mapIndexed { idx, mm -> if (idx == i) mm.copy(options = nextOpts) else mm }))
                            })
                        }
                    }
                }
            }
            ChipButton("+ Add person", selected = true, onClick = onAddMember, dense = true)
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentButton("Save", onSave, leadingIcon = Icons.Rounded.Add)
            Spacer(Modifier.width(10.dp))
            ChipButton("Cancel", selected = false, onClick = onCancel)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CamerasPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cameras = settings.localCameras
    // The camera currently being edited/added (null = the "add" form is collapsed).
    var draft by remember { mutableStateOf<com.tvassist.data.settings.LocalCamera?>(null) }

    PageScaffold("Cameras", onBack) {
        Text(
            "Add cameras by their direct stream URL so the TV plays them instantly — skipping Home " +
                "Assistant's HLS start-up delay. They appear as camera tiles you can place in the overlay.",
            color = TxtMuted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        WebSetupRow(viewModel, "Cameras")
        Spacer(Modifier.height(18.dp))

        // Video player — applies to camera tiles and notification/person-map streams.
        Text("Video player", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(2.dp))
        Text("Engine for camera/video streams. VLC handles more cameras (HEVC, quirky RTSP); ExoPlayer is lighter.", fontSize = 12.sp, color = TxtMuted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Auto", "exoplayer" to "ExoPlayer", "vlc" to "VLC").forEach { (value, label) ->
                ChipButton(label, settings.streamPlayer == value, onClick = { viewModel.setStreamPlayer(value) })
            }
        }
        Spacer(Modifier.height(16.dp))

        cameras.forEach { cam ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(cam.name.ifBlank { "(unnamed)" }, color = TxtPrimary, fontSize = 15.sp, maxLines = 1)
                    Text(cam.streamUrl, color = TxtMuted, fontSize = 12.sp, maxLines = 1)
                }
                ChipButton("Edit", selected = draft?.id == cam.id, onClick = { draft = cam })
                Spacer(Modifier.width(8.dp))
                ChipButton("Delete", selected = false, onClick = { viewModel.deleteLocalCamera(cam.id); if (draft?.id == cam.id) draft = null })
            }
        }

        Spacer(Modifier.height(14.dp))
        val d = draft
        if (d == null) {
            AccentButton(
                "Add camera",
                {
                    draft = com.tvassist.data.settings.LocalCamera(
                        id = "cam_" + System.currentTimeMillis().toString(36),
                        name = "", streamUrl = "",
                    )
                },
                leadingIcon = Icons.Rounded.Add,
            )
        } else {
            Text(if (cameras.any { it.id == d.id }) "Edit camera" else "New camera", color = TxtPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text("Name", color = TxtMuted, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            TvTextField(d.name, { draft = d.copy(name = it) }, "Front door")
            Spacer(Modifier.height(12.dp))
            Text("Stream URL", color = TxtMuted, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            TvTextField(d.streamUrl, { draft = d.copy(streamUrl = it) }, "rtsp://user:pass@192.168.1.20:554/stream")
            Spacer(Modifier.height(12.dp))
            Text("Snapshot URL (optional)", color = TxtMuted, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            TvTextField(d.snapshotUrl, { draft = d.copy(snapshotUrl = it) }, "http://192.168.1.20/snapshot.jpg")
            Spacer(Modifier.height(12.dp))
            Text("Player", color = TxtMuted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to "Auto", "exoplayer" to "ExoPlayer", "vlc" to "VLC").forEach { (v, label) ->
                    ChipButton(label, d.player == v, onClick = { draft = d.copy(player = v) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep refreshing", color = TxtPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                ChipButton(if (d.refresh) "On" else "Off", selected = d.refresh, onClick = { draft = d.copy(refresh = !d.refresh) })
            }
            Text(
                "For \"rolling clip\" cameras that return a short video per request (e.g. Québec 511) " +
                    "instead of a continuous stream — reloads when the clip ends so it stays live.",
                color = TxtMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row {
                AccentButton(
                    "Save",
                    {
                        if (d.name.isNotBlank() && d.streamUrl.isNotBlank()) {
                            viewModel.saveLocalCamera(d)
                            draft = null
                        }
                    },
                    leadingIcon = Icons.Rounded.Add,
                )
                Spacer(Modifier.width(10.dp))
                ChipButton("Cancel", selected = false, onClick = { draft = null })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AboutPage(onBack: () -> Unit) {
    PageScaffold("About", onBack) {
        Text("TV Assist", fontSize = 22.sp, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("Version ${com.tvassist.BuildConfig.VERSION_NAME} (${com.tvassist.BuildConfig.VERSION_CODE})",
            color = Color(0xFF999999), fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text("Home Assistant control overlays + remote key mapping for Android TV.",
            color = Color(0xFF999999), fontSize = 13.sp)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TriggerKeyCapture(onCaptured: (Int) -> Unit) {
    var capturing by remember { mutableStateOf(false) }
    var lastCaptured by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(capturing) { if (capturing) runCatching { focus.requestFocus() } }

    Column {
        AccentButton(
            label = if (capturing) "Press a remote button…  (Back to cancel)" else "Set trigger key",
            onClick = { capturing = true },
            leadingIcon = Icons.Rounded.SettingsRemote,
            modifier = Modifier
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (capturing && e.type == KeyEventType.KeyDown) {
                        val code = e.nativeKeyEvent.keyCode
                        capturing = false
                        if (code != KeyEvent.KEYCODE_BACK) {
                            lastCaptured = code
                            onCaptured(code)
                        }
                        true
                    } else {
                        false
                    }
                },
        )
        if (lastCaptured != null) {
            Spacer(Modifier.height(8.dp))
            Text("Captured: ${keyName(lastCaptured!!)}", color = Color(0xFF6FCF7F), fontSize = 14.sp)
        }
    }
}

/** Human-readable name for a keycode (0 = unset → defaults to MENU in the service). */
private fun keyName(code: Int): String =
    if (code == 0) "Not set (defaults to MENU)" else KeyEvent.keyCodeToString(code)
