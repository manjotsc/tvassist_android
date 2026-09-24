package com.tvassist.overlay

import com.tvassist.ui.cards.map.MapCardTile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.settings.OverlayAppearance
import com.tvassist.data.settings.OverlayLayout
import com.tvassist.data.settings.OverlayPill
import com.tvassist.data.settings.PressAction
import com.tvassist.data.settings.OverlayPosition
import com.tvassist.data.settings.OverlayRow
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.cards.camera.CameraTile
import com.tvassist.ui.cards.EntityControlActions
import com.tvassist.ui.cards.EntityControlCard
import com.tvassist.ui.cards.EntityControlPanel
import com.tvassist.ui.cards.HaTile
import com.tvassist.ui.cards.InlineControlTile
import com.tvassist.ui.cards.cardFor
import com.tvassist.ui.cards.climate.InlineClimateTile
import com.tvassist.ui.cards.TrackBar
import com.tvassist.ui.cards.resolveTileStyle
import com.tvassist.ui.cards.light.rememberInlineBrightness
import com.tvassist.ui.cards.TILE_ROW_HEIGHT
import com.tvassist.ui.cards.stateTint
import com.tvassist.ui.cards.entityStatus
import com.tvassist.ui.cap
import com.tvassist.ui.CameraPlayerScreen
import com.tvassist.data.settings.EntityOverride
import com.tvassist.ui.maps.PeopleMapMember
import com.tvassist.ui.maps.PeopleMapScreen
import com.tvassist.ui.maps.PersonMapScreen
import com.tvassist.ui.EntityIconContent
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.cards.displayIcon
import com.tvassist.ui.cards.displayName
import com.tvassist.ui.cards.effectiveOn
import com.tvassist.ui.cards.performPress
import com.tvassist.ui.cards.rememberPress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** How much of an `icon` tile the glyph occupies, measured against the tile's shorter side. */
private const val ICON_GLYPH_FRACTION = 0.46f

/**
 * The narrowest a Normal tile can be and still draw its brightness bar.
 *
 * The bar is a fixed 96dp; with the chip (36dp) and the tile's own horizontal padding (22dp) in
 * front of it, anything under this has the Row compress it into a grey disc. Two columns of a
 * 320dp panel is 142dp, so this is the line between a one-column row and the rest.
 */
private val STANDARD_BAR_MIN_WIDTH = 170.dp

/**
 * The control overlay drawn over other apps as a floating rounded panel. Its content is
 * driven by a user-defined [OverlayLayout] (rows of tiles); when no layout exists it falls
 * back to a single column of all toggleable entities. Selecting an entity opens its
 * [EntityControlCard]; [openCardId] tracks which is open.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SidebarContent(
    repository: HaRepository,
    layout: StateFlow<OverlayLayout>,
    appearance: StateFlow<OverlayAppearance>,
    overrides: StateFlow<Map<String, EntityOverride>>,
    openCardId: StateFlow<String?>,
    openFullscreenId: StateFlow<String?>,
    closing: StateFlow<Boolean>,
    actions: EntityControlActions,
    onOpenEntity: (Entity) -> Unit,
    /**
     * Starts a spoken exchange with a conversation agent (the Talk press action).
     *
     * Required rather than defaulting to [onOpenEntity]: a caller that forgot to wire Talk would
     * then silently open the entity's card instead, which looks like a working button doing the
     * wrong thing. Making it required turns that into a compile error.
     */
    onOpenAssist: (Entity) -> Unit,
    onLaunchFullscreen: (Entity) -> Unit,
    onCloseCard: () -> Unit,
) {
    val allEntities by repository.entities.collectAsStateWithLifecycle()
    val savedLayout by layout.collectAsStateWithLifecycle()
    val look by appearance.collectAsStateWithLifecycle()
    val overrideMap by overrides.collectAsStateWithLifecycle()
    val openId by openCardId.collectAsStateWithLifecycle()
    val fullscreenId by openFullscreenId.collectAsStateWithLifecycle()
    val pos = look.position

    // A fullscreen camera/person/map view pops up over the whole overlay window.
    val fsEntity = fullscreenId?.let { id -> allEntities.firstOrNull { it.entityId == id } }
    if (fsEntity != null) {
        // A contained popup card over a dim scrim (floats over the app behind the overlay).
        // The scrim lightens on a light palette — a near-opaque black one under a light theme reads
        // as a different app entirely. The card itself stays black: it holds video/map imagery.
        val scrim = if (LocalOverlayTheme.current.background.luminance() > 0.5f) {
            Color(0x66000000)
        } else {
            Color(0xCC000000)
        }
        Box(
            modifier = Modifier.fillMaxSize().background(scrim),
            contentAlignment = Alignment.Center,
        ) {
            val cardMod = if (fsEntity.domain == "camera") {
                Modifier.fillMaxWidth(0.82f).aspectRatio(16f / 9f)
            } else {
                Modifier.fillMaxWidth(0.82f).fillMaxHeight(0.82f)
            }
            Box(modifier = cardMod.clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
                when {
                    fsEntity.domain == "camera" ->
                        CameraPlayerScreen(
                            entity = fsEntity, repository = repository,
                            inOverlay = true,
                        )
                    fsEntity.isMapCard -> {
                        // A map card is a synthetic entity carrying its members/zoom/source in attributes.
                        val members = fsEntity.mapCardMembers.map { (id, opts) ->
                            PeopleMapMember(allEntities.firstOrNull { it.entityId == id } ?: Entity(id, "unavailable", id), opts)
                        }
                        PeopleMapScreen(
                            members = members,
                            title = fsEntity.friendlyName,
                            repository = repository,
                            zoom = fsEntity.mapCardZoom,
                            mapProvider = fsEntity.mapCardProvider,
                            showLegend = fsEntity.mapCardShowLegend,
                        )
                    }
                    else -> {
                        val fsTile = savedLayout.rows.asSequence().flatMap { it.tiles.asSequence() }
                            .firstOrNull { it.entityId == fsEntity.entityId }
                        PersonMapScreen(
                            entity = fsEntity,
                            repository = repository,
                            options = fsTile?.personOptions ?: OverlayTile.PERSON_DEFAULTS,
                            mapProvider = fsTile?.mapProvider ?: OverlayTile.MAP_AUTO,
                        )
                    }
                }
            }
        }
        return
    }

    val openEntity = openId?.let { id -> allEntities.firstOrNull { it.entityId == id } }
    // The Assist card is the one that still takes over the surface, and deliberately: it accepts
    // typed input with the IME opening over it and holds a transcript worth reading, neither of
    // which survives a 320dp column at the screen edge. OverlayService.dismissBlocked() already
    // special-cases it for the same reason. Every other entity now docks into the panel below.
    if (openEntity != null && openEntity.isConversation) {
        EntityControlCard(entity = openEntity, actions = actions, onDismiss = onCloseCard)
        return
    }

    val byId = remember(allEntities) { allEntities.associateBy { it.entityId } }
    // When a user layout exists, don't recompute it on every state update (it doesn't depend on
    // entities) — only the fallback (no layout) is derived from the entity list.
    val effectiveLayout = if (!savedLayout.isEmpty) {
        savedLayout
    } else {
        remember(allEntities) {
            OverlayLayout.fromFlat(allEntities.filter { it.isToggleable }.map { it.entityId })
        }
    }
    val firstTileId = remember(effectiveLayout) {
        effectiveLayout.rows.firstOrNull { !it.isHeader && it.tiles.isNotEmpty() }
            ?.tiles?.firstOrNull()?.entityId
    }

    val firstItemFocus = remember { FocusRequester() }
    // The panel animates in (AnimatedVisibility), so the first tile isn't placed yet on the initial
    // frame — requestFocus() would no-op and leave nothing focused (dead D-pad). Retry until the
    // requester is attached (covers the longest enter animation).
    //
    // `openEntity == null` is a key for the same reason: a card now renders *inside* the panel
    // rather than replacing the whole surface, so this effect is no longer disposed and re-created
    // around one. Without it, closing a card would leave the D-pad with nothing focused.
    LaunchedEffect(firstTileId, pos, openEntity == null) {
        if (openEntity == null && firstTileId != null) {
            repeat(30) {
                if (runCatching { firstItemFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(30)
            }
        }
    }

    val alignment = when (pos) {
        OverlayPosition.RIGHT -> Alignment.CenterEnd
        OverlayPosition.LEFT -> Alignment.CenterStart
        OverlayPosition.BOTTOM -> Alignment.BottomCenter
        OverlayPosition.TOP -> Alignment.TopCenter
    }

    val baseAlpha = look.opacityPercent.coerceIn(0, 100) / 100f
    val base = Color(look.bgColor)
    // Subtle top-to-bottom gradient panel with an optional hairline border (its own color).
    val panelBrush = Brush.verticalGradient(
        listOf(
            base.copy(alpha = baseAlpha),
            lerp(base, Color.Black, 0.22f).copy(alpha = baseAlpha),
        ),
    )
    val panelShape = RoundedCornerShape(look.cornerRadiusDp.dp)
    val borderMod = if (look.borderEnabled) {
        Modifier.border(1.dp, Color(look.borderColor).copy(alpha = 0.55f), panelShape)
    } else {
        Modifier
    }
    // Shared with the Home Assistant-page backend so the two cannot drift; see [PanelMetrics].
    val panelWidth = PanelMetrics.widthDp(pos).dp

    // Open/close motion. `closing` is flipped true by the service just before it removes the window,
    // giving the exit transition time to play; the enter transition runs once on first composition.
    val isClosing by closing.collectAsStateWithLifecycle()
    val visibleState = remember { MutableTransitionState(false) }
    LaunchedEffect(isClosing) { visibleState.targetState = !isClosing }
    val durationMs = look.animSpeedMs.coerceIn(0, 1000)
    // Slide the panel toward the edge it docks against.
    val edgeOffset: (IntSize) -> IntOffset = { s ->
        when (pos) {
            OverlayPosition.RIGHT -> IntOffset(s.width, 0)
            OverlayPosition.LEFT -> IntOffset(-s.width, 0)
            OverlayPosition.TOP -> IntOffset(0, -s.height)
            OverlayPosition.BOTTOM -> IntOffset(0, s.height)
        }
    }
    val (enter, exit) = when (look.animStyle) {
        com.tvassist.data.settings.OVERLAY_ANIM_NONE ->
            EnterTransition.None to ExitTransition.None
        com.tvassist.data.settings.OVERLAY_ANIM_FADE ->
            fadeIn(tween(durationMs)) to fadeOut(tween(durationMs))
        else ->
            (slideIn(tween(durationMs), initialOffset = edgeOffset) + fadeIn(tween(durationMs))) to
                (slideOut(tween(durationMs), targetOffset = edgeOffset) + fadeOut(tween(durationMs)))
    }

    // Overlay "size" scales the whole bar uniformly (dp + sp) by boosting the local density; the
    // outer margin stays in the real density so it doesn't grow/shrink with size.
    val sizeFactor = PanelMetrics.sizeFactor(look)
    val baseDensity = LocalDensity.current

    Box(
        modifier = Modifier.fillMaxSize().padding(look.marginDp.dp),
        contentAlignment = alignment,
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(baseDensity.density * sizeFactor, baseDensity.fontScale),
        ) {
            AnimatedVisibility(visibleState = visibleState, enter = enter, exit = exit) {
                // The panel's own geometry and chrome, worn by whichever of the two is showing. A
                // card gets the bar's exact width, slot, corner radius, gradient and border, so
                // opening an entity reads as the bar changing content rather than a centred card
                // and a full-screen dim landing over whatever is playing. It also picks up the
                // overlay size setting for free, which as a separate surface it never did.
                val panelMod = Modifier
                    .width(panelWidth)
                    .heightIn(max = PanelMetrics.MAX_HEIGHT_DP.dp)
                    .clip(panelShape)
                    .background(panelBrush)
                    .then(borderMod)
                if (openEntity != null) {
                    EntityControlPanel(
                        entity = openEntity,
                        actions = actions,
                        onDismiss = onCloseCard,
                        modifier = panelMod,
                    )
                } else {
                    Column(
                        modifier = panelMod
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        effectiveLayout.rows.forEach { row ->
                            LayoutRow(
                                row = row,
                                byId = byId,
                                overrideMap = overrideMap,
                                actions = actions,
                                onOpenEntity = onOpenEntity,
                                onOpenAssist = onOpenAssist,
                                onLaunchFullscreen = onLaunchFullscreen,
                                repository = repository,
                                firstTileId = firstTileId,
                                firstFocus = firstItemFocus,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayoutRow(
    row: OverlayRow,
    byId: Map<String, Entity>,
    overrideMap: Map<String, EntityOverride>,
    actions: EntityControlActions,
    onOpenEntity: (Entity) -> Unit,
    /** Starts a spoken exchange with a conversation agent (the Talk press action). */
    onOpenAssist: (Entity) -> Unit,
    onLaunchFullscreen: (Entity) -> Unit,
    repository: HaRepository,
    firstTileId: String?,
    firstFocus: FocusRequester,
) {
    if (row.isHeader) {
        // Modern section header: uppercase label · divider fills the gap · live pills, all inline.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.title.isNotBlank()) {
                Text(
                    row.title,
                    color = LocalOverlayTheme.current.subText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f).height(1.dp).background(LocalOverlayTheme.current.subText.copy(alpha = 0.22f)))
            row.pills.forEach { pill ->
                byId[pill.entityId]?.let { e ->
                    Spacer(Modifier.width(8.dp))
                    SensorPill(pill, e, overrideMap[pill.entityId], repository)
                }
            }
        }
        return
    }

    val cols = row.columns.coerceIn(1, 12)
    row.title.takeIf { it.isNotBlank() }?.let {
        Text(it, color = LocalOverlayTheme.current.subText, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
    // Measured once for the whole row so every line's squares match, including a short final one.
    row.tiles.chunked(cols).forEach { line ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // Tiles in one row need not be the same height — a square Icon tap beside a plain row,
            // say — and the default Top alignment left the shorter ones hanging from the ceiling.
            verticalAlignment = Alignment.CenterVertically,
        ) {
            line.forEach { tile ->
                val entity = byId[tile.entityId] ?: Entity(tile.entityId, "unavailable", tile.entityId)
                val mod = Modifier
                    .weight(1f)
                    .then(if (tile.entityId == firstTileId) Modifier.focusRequester(firstFocus) else Modifier)
                LayoutTile(
                    tile = tile,
                    entity = entity,
                    override = overrideMap[tile.entityId],
                    actions = actions,
                    onOpenEntity = onOpenEntity,
                    onOpenAssist = onOpenAssist,
                    onLaunchFullscreen = onLaunchFullscreen,
                    repository = repository,
                    resolve = { byId[it] },
                    modifier = mod,
                    soleInLine = cols == 1,
                )
            }
            // Pad short final lines so tiles keep a consistent width.
            repeat(cols - line.size) { Box(Modifier.weight(1f)) {} }
        }
    }
}

/** A small live header pill: entity icon + name + value/unit, per the pill's show flags. */
@Composable
private fun SensorPill(pill: OverlayPill, entity: Entity, override: EntityOverride?, repository: HaRepository) {
    val th = LocalOverlayTheme.current
    Row(
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(th.tile)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pill.showIcon) {
            Box(Modifier.size(14.dp)) {
                val iconTint = if (pill.iconColor != 0) Color(pill.iconColor) else th.subText
                EntityIconContent(entity, override, iconTint, sizeDp = 14, repository = repository)
            }
        }
        if (pill.showName) {
            if (pill.showIcon) Spacer(Modifier.width(5.dp))
            Text(displayName(entity, override), color = th.subText, fontSize = 12.sp, maxLines = 1)
        }
        if (pill.showState) {
            if (pill.showIcon || pill.showName) Spacer(Modifier.width(5.dp))
            Text(pillValue(entity), color = th.text, fontSize = 12.sp, maxLines = 1)
        }
    }
}

private fun pillValue(e: Entity): String {
    val unit = e.unitOfMeasurement?.trim().orEmpty()
    val v = e.state
    return when {
        unit == "°C" || unit == "°F" || unit == "°" -> "$v°"
        unit == "%" -> "$v%"
        unit.isNotBlank() -> "$v $unit"
        else -> cap(v)
    }
}

@Composable
private fun LayoutTile(
    tile: OverlayTile,
    entity: Entity,
    override: EntityOverride?,
    actions: EntityControlActions,
    onOpenEntity: (Entity) -> Unit,
    /** Starts a spoken exchange with a conversation agent (the Talk press action). */
    onOpenAssist: (Entity) -> Unit,
    onLaunchFullscreen: (Entity) -> Unit,
    repository: HaRepository,
    resolve: (String) -> Entity?,
    modifier: Modifier = Modifier,
    /**
     * Whether this tile is the only one on its line.
     *
     * A tile that shares its line has neighbours Left and Right must be able to reach, so the ones
     * that would otherwise take those keys for themselves — the Normal light row's inline
     * brightness — stand down. Alone on a line there is nowhere sideways to go and nothing is lost.
     */
    soleInLine: Boolean = true,
) {
    // A map card is a synthetic entity — render its own tile and open the fullscreen map on click.
    if (entity.isMapCard) {
        MapCardTile(
            entity = entity,
            override = override,
            repository = repository,
            name = if (tile.hideName) "" else displayName(entity, override),
            showIcon = !tile.hideIcon,
            showStatus = !tile.hideStatus,
            count = entity.mapCardMembers.size,
            onOpen = { onLaunchFullscreen(entity) },
            modifier = modifier,
            style = resolveTileStyle(entity, tile.style),
            resolve = { byId -> resolve(byId) },
        )
        return
    }

    // Resolves Auto *and* anything the entity's card does not offer — a style saved back when the
    // picker handed all six to everything. See [resolveTileStyle].
    //
    // Icon tap is an explicit choice and nothing else. A tile with its name and status hidden used
    // to be coerced into one, which meant hiding the text in the layout editor silently changed the
    // tile's *shape* — two quite different intents arriving at one renderer, and a row could end up
    // mixing squares with rows without anyone having asked for a square. Such a tile now keeps the
    // style it was given and simply draws no text, which HaTile already centres.
    val effectiveStyle = resolveTileStyle(entity, tile.style)

    val name = if (tile.hideName) "" else displayName(entity, override)
    val icon = displayIcon(entity, override)
    // Camera/person open a fullscreen view (in the app); others run the configured actions.
    val primary: () -> Unit = when {
        entity.domain == "camera" || entity.isPerson -> ({ onLaunchFullscreen(entity) })
        else -> ({ performPress(override?.singlePress ?: "default", entity, actions, onOpenEntity, single = true, openVoice = onOpenAssist) })
    }
    val more: () -> Unit = when {
        entity.domain == "camera" || entity.isPerson -> ({ onLaunchFullscreen(entity) })
        else -> ({ performPress(override?.longPress ?: "default", entity, actions, onOpenEntity, single = false, openVoice = onOpenAssist) })
    }
    // HA's `double_tap_action`. Null unless the entity actually has one, so the overwhelmingly
    // common tile keeps firing on the press instead of waiting out the double-tap window.
    val twice = (override?.doublePress ?: PressAction.NONE).takeIf { it != PressAction.NONE }
    val press = rememberPress(
        onSingle = primary,
        onDouble = twice?.let { { performPress(it, entity, actions, onOpenEntity, single = true, openVoice = onOpenAssist) } },
    )

    when (effectiveStyle) {
        OverlayTile.STYLE_CLIMATE ->
            InlineClimateTile(entity = entity, actions = actions, onOpen = onOpenEntity, modifier = modifier)

        OverlayTile.STYLE_SQUARE ->
            CameraTile(entity = entity, repository = repository, onOpen = { onLaunchFullscreen(entity) }, modifier = modifier, override = override)

        OverlayTile.STYLE_ACTION ->
            HaTile(
                icon = icon,
                iconOn = effectiveOn(entity, override, resolve),
                // The bulb's own colour where it has one; null everywhere else falls back to
                // the theme's on/off icon colours exactly as before. See [stateTint].
                iconTint = stateTint(entity),
                iconContent = { tint -> EntityIconContent(entity, override, tint, repository = repository) },
                showIcon = !tile.hideIcon,
                title = name,
                subtitle = if (tile.hideStatus) "" else "Run",
                onClick = press,
                onLongClick = more,
                modifier = modifier,
            )

        // A square, the icon and nothing else. Press toggles; hold still opens the card.
        OverlayTile.STYLE_ICON ->
            // One height, always [TILE_ROW_HEIGHT]. The column count decides the button's width
            // and nothing else, so a row of these never changes how tall its line is and they sit
            // level with every other tile in the panel.
            //
            // Being *square* was the goal for several rounds and it was the wrong goal: a square
            // has to grow taller as the columns get fewer, which is the one thing that kept looking
            // wrong. 292dp at one column, then a 108dp cap, then 92dp at three — each fix moved the
            // number without removing the coupling, and tying height to width is what made it
            // impossible to satisfy. At four or five columns the slot is near enough 54dp that the
            // button still reads as a square; it simply is not forced to be one.
            //
            // The glyph sits straight on the tile — no chip. Three sizes were tried with one
            // (0.56, 0.42, 0.52) and the problem was never the size: a rounded chip inside a
            // rounded tile is a box in a box, and a disc inside it is two unrelated shapes.
            BoxWithConstraints(modifier) {
                // Against the shorter side, so neither a wide button nor a narrow one in a
                // twelve-column row grows a glyph bigger than the tile holding it.
                val glyph = (minOf(maxWidth, TILE_ROW_HEIGHT).value * ICON_GLYPH_FRACTION)
                    .toInt()
                    .coerceAtLeast(12)
                val lit = effectiveOn(entity, override, resolve)
                // The chip's filled background used to carry the on state. Without it the glyph has
                // to: its own colour when the bulb reports one, the accent otherwise. Two greys a
                // shade apart is not a state you can read from a sofa.
                val th = LocalOverlayTheme.current
                HaTile(
                    icon = icon,
                    iconOn = lit,
                    iconTint = stateTint(entity) ?: if (lit) th.accent else null,
                    iconContent = { tint ->
                        EntityIconContent(entity, override, tint, glyph, repository)
                    },
                    showIcon = true,
                    title = "",
                    subtitle = "",
                    onClick = press,
                    onLongClick = more,
                    modifier = Modifier.fillMaxWidth().height(TILE_ROW_HEIGHT),
                    iconSize = glyph,
                    iconChip = false,
                )
            }

        // Normal: the row, with its level adjustable in place rather than printed and inert.
        OverlayTile.STYLE_STANDARD -> {
            val level = rememberInlineBrightness(entity, actions, adjustable = soleInLine)
            // Measured because the bar cannot be squeezed. It is a fixed 96dp beside a 36dp chip and
            // the tile's own padding, so under about [STANDARD_BAR_MIN_WIDTH] the Row compresses it
            // into a grey disc that reads as a broken element rather than as a level. Hiding the
            // text is *not* the test — a Normal tile is an icon and a brightness bar whether or not
            // it is labelled — only whether the tile is wide enough to draw one.
            BoxWithConstraints(modifier) {
                val roomForBar = maxWidth >= STANDARD_BAR_MIN_WIDTH
                HaTile(
                    icon = icon,
                    iconOn = effectiveOn(entity, override, resolve),
                    iconTint = stateTint(entity),
                    iconContent = { tint -> EntityIconContent(entity, override, tint, repository = repository) },
                    showIcon = !tile.hideIcon,
                    title = name,
                    subtitle = if (tile.hideStatus) "" else entityStatus(entity, compact = true),
                    onClick = press,
                    onLongClick = more,
                    modifier = Modifier.fillMaxWidth().then(level.modifier),
                    trailing = if (entity.isOn && roomForBar) {
                        { TrackBar(level.pct, Modifier.width(96.dp), height = 26) }
                    } else {
                        null
                    },
                )
            }
        }

        // The control ladder: a tile header with the domain's controls under it. Which controls a
        // rung draws is the card's business — see [EntityCard.TileControls].
        in OverlayTile.CONTROL_STYLES ->
            InlineControlTile(
                icon = icon,
                iconOn = effectiveOn(entity, override, resolve),
                // The bulb's own colour where it has one; null everywhere else falls back to
                // the theme's on/off icon colours exactly as before. See [stateTint].
                iconTint = stateTint(entity),
                iconContent = { tint -> EntityIconContent(entity, override, tint, repository = repository) },
                showIcon = !tile.hideIcon,
                title = name,
                subtitle = if (tile.hideStatus) "" else entityStatus(entity, compact = true),
                onClick = press,
                onLongClick = more,
                modifier = modifier,
                trailing = { cardFor(entity).TileTrailing(entity, actions, effectiveStyle) },
            ) {
                cardFor(entity).TileControls(entity, actions, effectiveStyle)
            }

        else -> // STYLE_AUTO and anything unrecognised: the plain row.
            HaTile(
                icon = icon,
                iconOn = effectiveOn(entity, override, resolve),
                // The bulb's own colour where it has one; null everywhere else falls back to
                // the theme's on/off icon colours exactly as before. See [stateTint].
                iconTint = stateTint(entity),
                iconContent = { tint -> EntityIconContent(entity, override, tint, repository = repository) },
                showIcon = !tile.hideIcon,
                title = name,
                subtitle = if (tile.hideStatus) "" else entityStatus(entity, compact = true),
                onClick = press,
                onLongClick = more,
                modifier = modifier,
                // Read-only: how bright it is, not a control. The controls live on the rungs above.
                trailing = if (entity.domain == "light" && entity.isOn) {
                    { TrackBar(entity.brightnessPct ?: 0, Modifier.width(96.dp), height = 26) }
                } else {
                    null
                },
            )
    }
}
