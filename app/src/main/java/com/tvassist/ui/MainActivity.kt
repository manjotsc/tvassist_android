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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.type
import android.content.Intent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.Text
import com.tvassist.R
import com.tvassist.TvAssistApp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import com.tvassist.data.ha.ConnectionState
import com.tvassist.ui.cards.EntityControlCard
import com.tvassist.data.ha.Entity
import com.tvassist.data.settings.OverlayRow
import com.tvassist.data.settings.OverlayTile
import com.tvassist.overlay.OverlayService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.tvassist.ui.settings.AboutPage
import com.tvassist.ui.settings.AudioPage
import com.tvassist.ui.settings.BackupPage
import com.tvassist.ui.settings.CamerasPage
import com.tvassist.ui.settings.ConnectionPage
import com.tvassist.ui.settings.DisplayPage
import com.tvassist.ui.settings.MapsPage
import com.tvassist.ui.settings.NotificationsPage
import com.tvassist.ui.settings.PermissionsPage
import com.tvassist.ui.settings.SecurityPage
import com.tvassist.ui.settings.SettingsHub
import com.tvassist.ui.settings.TriggersPage
import com.tvassist.ui.settings.appearance.AppearancePage
import com.tvassist.ui.entities.CustomizeScreen
import com.tvassist.ui.entities.ImportScreen
import com.tvassist.ui.home.HomeContent
import com.tvassist.ui.layouteditor.LayoutEditorScreen
import com.tvassist.ui.maps.PeopleMapMember
import com.tvassist.ui.maps.PeopleMapScreen
import com.tvassist.ui.maps.PersonMapScreen

class MainActivity : ComponentActivity() {

    private val viewModel: ConnectionViewModel by viewModels {
        val app = application as TvAssistApp
        ConnectionViewModel.Factory(app.haRepository, app.settingsStore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start the background service if anything still needs it — see [Settings.needsKeepAlive],
        // which BootReceiver applies to the same decision at boot.
        lifecycleScope.launch {
            val s = (application as TvAssistApp).settingsStore.settings.first()
            if (s.needsKeepAlive) com.tvassist.overlay.KeepAliveService.start(this@MainActivity)
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
                // The mic key reaches the app through the accessibility service, which injected
                // key events never do — so `adb input keyevent` cannot open the voice bar and this
                // is the only way to look at it from a script.
                "voice" -> window.decorView.postDelayed(
                    { (application as TvAssistApp).voice.start() },
                    2_000,
                )
            }
        }

        val initialRoute = if (com.tvassist.BuildConfig.DEBUG) {
            when (intent?.getStringExtra("screen")) {
                "settings" -> Route.SettingsHub
                "import" -> Route.Import
                "layout" -> Route.Overlay
                "triggers" -> Route.Triggers
                "appearance" -> Route.Appearance
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

    // Talk presses go to the app-scoped voice controller, which raises the bar in the notification
    // overlay window — no card, and nothing for this screen to hold on to.
    val voice = (LocalContext.current.applicationContext as TvAssistApp).voice

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
                        onOpenAssist = { voice.start() },
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
            // The control card and everything it embeds (tiles, sliders, the Assist input) read
            // their colors from LocalOverlayTheme. Without this provider they fall back to
            // DefaultOverlayTheme's hardcoded palette, so the card ignored the user's appearance
            // settings in-app while honouring them in the overlay — the same card, two looks.
            val cardTheme = remember(settings) {
                overlayThemeOf(
                    settings.overlayBgColor, settings.overlayTileColor, settings.overlayAccentColor,
                    settings.overlayBorderColor, settings.overlayBorderEnabled,
                    settings.overlayIconOnColor, settings.overlayIconOffColor, settings.overlayFocusColor,
                )
            }
            CompositionLocalProvider(LocalOverlayTheme provides cardTheme) {
                EntityControlCard(
                    entity = openEntity,
                    actions = viewModel.controlActions,
                    onDismiss = { openEntityId = null },
                )
            }
        }

        val cameraEntity = cameraStreamId?.let { id -> entities.firstOrNull { it.entityId == id } }
        if (cameraEntity != null) {
            BackHandler { cameraStreamId = null }
            CameraPlayerScreen(
                entity = cameraEntity,
                repository = viewModel.repository,
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
            )
        }
    }
}

internal enum class Section { Home, Overlay, Settings }

/** Every navigable page, tagged with the rail [Section] it belongs to. */
internal enum class Route(val section: Section) {
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
