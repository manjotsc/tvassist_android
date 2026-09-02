package com.tvassist.overlay

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.BorderStroke
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.settings.OverlayAppearance
import com.tvassist.data.settings.OverlayLayout
import com.tvassist.data.settings.OverlayPill
import com.tvassist.data.settings.OverlayPosition
import com.tvassist.data.settings.OverlayRow
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.CameraTile
import com.tvassist.ui.EntityControlActions
import com.tvassist.ui.EntityControlCard
import com.tvassist.ui.HaTile
import com.tvassist.ui.InlineClimateTile
import com.tvassist.ui.SubText
import com.tvassist.ui.TrackBar
import com.tvassist.ui.cap
import com.tvassist.data.settings.EntityOverride
import com.tvassist.ui.CameraPlayerScreen
import com.tvassist.ui.PeopleMapMember
import com.tvassist.ui.PeopleMapScreen
import com.tvassist.ui.PersonMapScreen
import com.tvassist.ui.EntityIconContent
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.displayIcon
import com.tvassist.ui.displayName
import com.tvassist.ui.effectiveOn
import com.tvassist.ui.domainIcon
import com.tvassist.ui.fmt
import com.tvassist.ui.performPress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    onCloseFullscreen: () -> Unit,
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
                        CameraPlayerScreen(entity = fsEntity, repository = repository, onBack = onCloseFullscreen)
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
                            onBack = onCloseFullscreen,
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
                            onBack = onCloseFullscreen,
                        )
                    }
                }
            }
        }
        return
    }

    // A control card open over the panel takes over the whole overlay surface.
    val openEntity = openId?.let { id -> allEntities.firstOrNull { it.entityId == id } }
    if (openEntity != null) {
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
    LaunchedEffect(firstTileId, pos) {
        if (firstTileId != null) {
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
    val panelWidth = if (pos.isVertical) 320.dp else 720.dp

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
    val sizeFactor = look.sizeScale.coerceIn(50, 200) / 100f
    val baseDensity = LocalDensity.current

    Box(
        modifier = Modifier.fillMaxSize().padding(look.marginDp.dp),
        contentAlignment = alignment,
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(baseDensity.density * sizeFactor, baseDensity.fontScale),
        ) {
            AnimatedVisibility(visibleState = visibleState, enter = enter, exit = exit) {
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .heightIn(max = 620.dp)
                        .clip(panelShape)
                        .background(panelBrush)
                        .then(borderMod)
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
    row.tiles.chunked(cols).forEach { line ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                )
            }
            // Pad short final lines so tiles keep a consistent width.
            repeat(cols - line.size) { Box(Modifier.weight(1f)) {} }
        }
    }
}

/** A map card in the grid: a single tile (icon + label + count) that opens the fullscreen map. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MapCardTile(
    entity: Entity,
    override: EntityOverride?,
    repository: HaRepository,
    name: String,
    showIcon: Boolean,
    showStatus: Boolean,
    count: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val th = LocalOverlayTheme.current
    Surface(
        onClick = onOpen,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = th.tile,
            focusedContainerColor = th.tileFocused,
            // Follows the palette: the container above is themed, so white content vanished on a
            // light theme (same white-on-white failure as the slider labels).
            contentColor = th.text,
            focusedContentColor = th.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.045f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.5.dp, th.focus), shape = RoundedCornerShape(18.dp)),
        ),
    ) {
        // Mirrors HaTile: no text at all means the icon is the only content, so the chip is square
        // and centred and the tile shrinks to match every other icon-only tile in the row. Without
        // this the name fell back to "Map" and the count always drew, so hiding Name and Status
        // still left a text column forcing the tile wider than its neighbours.
        val subtitle = if (showStatus) (if (count == 1) "1 location" else "$count locations") else ""
        val hasText = name.isNotBlank() || subtitle.isNotBlank()
        val iconOnly = showIcon && !hasText
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (iconOnly) Arrangement.Center else Arrangement.Start,
        ) {
            if (showIcon) {
                Box(
                    modifier = Modifier.size(if (iconOnly) 36.dp else 54.dp, if (iconOnly) 36.dp else 40.dp)
                        .clip(RoundedCornerShape(8.dp)).background(th.chip),
                    contentAlignment = Alignment.Center,
                ) {
                    // Honor a custom icon set under Customize entities (falls back to the map glyph).
                    EntityIconContent(entity, override, th.subText, sizeDp = 22, repository = repository)
                }
            }
            if (hasText) {
                if (showIcon) Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    if (name.isNotBlank()) {
                        Text(name, fontSize = 14.sp, color = th.text, maxLines = 1)
                    }
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, fontSize = 11.sp, color = th.subText, maxLines = 1)
                    }
                }
            }
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
        )
        return
    }

    val effectiveStyle = when (tile.style) {
        OverlayTile.STYLE_AUTO -> when (entity.domain) {
            "climate" -> OverlayTile.STYLE_CLIMATE
            "camera" -> OverlayTile.STYLE_SQUARE
            else -> OverlayTile.STYLE_FULL
        }
        else -> tile.style
    }

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

    when (effectiveStyle) {
        OverlayTile.STYLE_CLIMATE ->
            InlineClimateTile(entity = entity, actions = actions, onOpen = onOpenEntity, modifier = modifier)

        OverlayTile.STYLE_SQUARE ->
            CameraTile(entity = entity, repository = repository, onOpen = { onLaunchFullscreen(entity) }, modifier = modifier, override = override)

        OverlayTile.STYLE_ACTION ->
            HaTile(
                icon = icon,
                iconOn = effectiveOn(entity, override, resolve),
                iconContent = { tint -> EntityIconContent(entity, override, tint, repository = repository) },
                showIcon = !tile.hideIcon,
                title = name,
                subtitle = if (tile.hideStatus) "" else "Run",
                onClick = primary,
                onLongClick = more,
                modifier = modifier,
            )

        OverlayTile.STYLE_COMPACT ->
            HaTile(
                icon = icon,
                iconOn = effectiveOn(entity, override, resolve),
                iconContent = { tint -> EntityIconContent(entity, override, tint, repository = repository) },
                showIcon = !tile.hideIcon,
                title = name,
                subtitle = if (tile.hideStatus) "" else tileSubtitle(entity),
                onClick = primary,
                onLongClick = more,
                modifier = modifier,
            )

        else -> // STYLE_FULL
            HaTile(
                icon = icon,
                iconOn = effectiveOn(entity, override, resolve),
                iconContent = { tint -> EntityIconContent(entity, override, tint, repository = repository) },
                showIcon = !tile.hideIcon,
                title = name,
                subtitle = if (tile.hideStatus) "" else tileSubtitle(entity),
                onClick = primary,
                onLongClick = more,
                modifier = modifier,
                trailing = if (entity.domain == "light" && entity.isOn) {
                    { TrackBar(entity.brightnessPct ?: 0, Modifier.width(96.dp), height = 26) }
                } else {
                    null
                },
            )
    }
}

/** Short status line shown under a tile title. */
private fun tileSubtitle(e: Entity): String = when {
    e.isButton -> ""
    // Its raw state is an ISO timestamp of the last use, which is unreadable on a tile.
    e.isConversation -> "Assist"
    e.domain == "light" -> if (e.isOn) (e.brightnessPct?.let { "$it%" } ?: "On") else "Off"
    e.domain == "climate" -> e.currentTemperature?.let { "${cap(e.state)} · ${fmt(it)}°" } ?: cap(e.state)
    e.domain == "switch" || e.domain == "input_boolean" || e.domain == "fan" -> if (e.isOn) "On" else "Off"
    else -> cap(e.state)
}
