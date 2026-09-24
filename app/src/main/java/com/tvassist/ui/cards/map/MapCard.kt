package com.tvassist.ui.cards.map

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.settings.EntityOverride
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.EntityIconContent
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.cards.EntityCard
import com.tvassist.ui.cards.EntityControlActions
import com.tvassist.ui.cards.generic.GenericControls

/**
 * The `map` domain — an app-defined map card, never a Home Assistant entity (see
 * `HaRepository`'s `MapCard.toEntity`).
 *
 * Registered only so the style picker has rungs to offer; the sidebar still routes a map card to
 * [MapCardTile] before any other dispatch, and a press opens the fullscreen map rather than a
 * control card. Its body is what [GenericCard][com.tvassist.ui.cards.generic.GenericCard] drew
 * for it before it had a card of its own.
 */
internal object MapCard : EntityCard {
    override val domains = setOf("map")

    /** Normal is the row with the map as its icon, Compact a 3:1 strip of map, Full the 16:9 picture. */
    override fun variants(e: Entity) =
        listOf(OverlayTile.STYLE_STANDARD, OverlayTile.STYLE_COMPACT, OverlayTile.STYLE_FULL)

    override fun autoStyle(e: Entity) = OverlayTile.STYLE_STANDARD

    @Composable
    override fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) =
        GenericControls(e, actions, firstFocus)
}

/** Where the home dot is drawn: the same green the fullscreen map marks home with. */
private val HOME_GREEN = Color(0xFF6FCF7F)

/**
 * A map card in the grid, opening the fullscreen map on a press, drawn at [style] — one of
 * [MapCard.variants], already resolved.
 *
 * Full draws the map itself: a card whose whole subject is *where people are* showing an icon and a
 * count told you nothing it did not already say in its name. The picture is the same composite the
 * fullscreen view uses, centred on `zone.home`, with the label over a scrim exactly as a camera tile
 * does, so the two picture tiles are siblings rather than two inventions. Compact is a 3:1 strip of
 * that map with the label beside home rather than under it; Normal is the ordinary row, with a
 * crop of the map in the icon's chip.
 *
 * Every style falls back to the icon row while there is no picture to show: no home zone and no
 * member with a fix, or no network.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun MapCardTile(
    entity: Entity,
    override: EntityOverride?,
    repository: HaRepository,
    name: String,
    showIcon: Boolean,
    showStatus: Boolean,
    count: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    style: String = OverlayTile.STYLE_STANDARD,
    /** Resolves a member id, for the centre fallback when there is no home zone. */
    resolve: (String) -> Entity? = { null },
) {
    val th = LocalOverlayTheme.current
    val zoom = entity.mapCardZoom
    val provider = entity.mapCardProvider
    val cacheKey = mapThumbKey(entity)
    var map by remember(cacheKey) { mutableStateOf(MapThumbCache.get(cacheKey)) }
    var onHome by remember(cacheKey) { mutableStateOf(false) }
    LaunchedEffect(cacheKey) {
        // Home does not move, so this is fetched once and cached for the life of the process — a
        // refresh loop like the camera's would re-composite tiles to redraw the same street.
        val home = repository.homeZoneLatLng()
        onHome = home != null
        val centre = home ?: entity.mapCardMembers
            .mapNotNull { (id, _) ->
                val m = resolve(id) ?: return@mapNotNull null
                val la = m.latitude ?: return@mapNotNull null
                val lo = m.longitude ?: return@mapNotNull null
                la to lo
            }
            .takeIf { it.isNotEmpty() }
            ?.let { pts ->
                pts.fold(0.0 to 0.0) { a, p -> (a.first + p.first) to (a.second + p.second) }
                    .let { (it.first / pts.size) to (it.second / pts.size) }
            }
        if (map == null && centre != null) {
            // radius 1 — a 512px composite. The fullscreen view asks for 2 because it pans; a
            // thumbnail never does, and four times the tiles for the same picture is four times
            // the fetching. Every style crops the same bitmap, so switching styles fetches nothing.
            repository.fetchPersonMap(centre.first, centre.second, zoom, provider, radius = 1)
                ?.asImageBitmap()
                ?.let {
                    MapThumbCache.put(cacheKey, it)
                    map = it
                }
        }
    }
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
        val subtitle = if (showStatus) (if (count == 1) "1 location" else "$count locations") else ""
        val hasText = name.isNotBlank() || subtitle.isNotBlank()
        val picture = map

        if (style == OverlayTile.STYLE_FULL && picture != null) {
            // Full ignores Hide icon: the map *is* the picture, and there is no icon on it to hide.
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Image(
                    bitmap = picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Says *which* place this is rather than just showing streets.
                if (onHome) HomeDot(14.dp, Modifier.align(Alignment.Center))
                // White on a fixed scrim, for the reason a camera tile does it: what is behind is a
                // photograph of a map, and no palette wins against arbitrary pixels.
                if (hasText) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        OverlayLabel(name, subtitle)
                    }
                }
            }
            return@Surface
        }

        if (style == OverlayTile.STYLE_COMPACT && picture != null) {
            // A strip of the same map, a third of Full's height. The first version put a 72dp crop
            // where the icon goes, and at a glance that read as the Normal row with a different
            // icon — a style has to change the tile's shape to look like a choice.
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(3f)) {
                Image(
                    bitmap = picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (onHome) HomeDot(11.dp, Modifier.align(Alignment.Center))
                if (hasText) {
                    // The scrim runs sideways here: a strip this short has no bottom third to spare,
                    // and fading from the left keeps home, at the centre, clear of it.
                    Box(
                        modifier = Modifier.matchParentSize().background(
                            Brush.horizontalGradient(
                                0f to Color(0xE6000000),
                                0.5f to Color(0x66000000),
                                0.75f to Color.Transparent,
                            ),
                        ),
                    )
                    Column(modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp)) {
                        OverlayLabel(name, subtitle)
                    }
                }
            }
            return@Surface
        }

        // Mirrors HaTile: no text at all means the icon chip is the only content, so it is
        // centred and the tile shrinks to match every other icon-only tile in the row. Without
        // this the name fell back to "Map" and the count always drew, so hiding Name and Status
        // still left a text column forcing the tile wider than its neighbours.
        val iconOnly = showIcon && !hasText
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (!hasText) Arrangement.Center else Arrangement.Start,
        ) {
            if (showIcon) {
                Box(
                    modifier = Modifier.size(if (iconOnly) 36.dp else 54.dp, if (iconOnly) 36.dp else 40.dp)
                        .clip(RoundedCornerShape(8.dp)).background(th.chip),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picture != null) {
                        // The map is the icon: same chip, same size, so the row is no taller than a
                        // light's. The glyph below only shows until the picture arrives.
                        Image(
                            bitmap = picture,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (onHome) HomeDot(8.dp)
                    } else {
                        // Honor a custom icon set under Customize entities (falls back to the map glyph).
                        EntityIconContent(entity, override, th.subText, sizeDp = 22, repository = repository)
                    }
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

/** Name and count in white, for the two styles that draw them over a map. */
@Composable
private fun OverlayLabel(name: String, subtitle: String) {
    if (name.isNotBlank()) {
        Text(name, fontSize = 14.sp, color = Color.White, maxLines = 1)
    }
    if (subtitle.isNotBlank()) {
        Text(subtitle, fontSize = 11.sp, color = Color(0xCCFFFFFF), maxLines = 1)
    }
}

/** Home, marked at the centre of a map picture — the composite is centred on `zone.home`. */
@Composable
private fun HomeDot(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(HOME_GREEN)
            .border(if (size < 12.dp) 1.5.dp else 2.dp, Color.White, CircleShape),
    )
}

/**
 * The thumbnail cache's key: per card, and per zoom and provider so an edit to either refetches.
 *
 * Its own function because it was once written with the `${'$'}` escapes a raw string needs, inside
 * an ordinary one — which made it the same literal text for every card, so every map tile would
 * have drawn whichever card's streets were fetched first. `MapCardTest` pins that it differs.
 */
internal fun mapThumbKey(e: Entity): String = "${e.entityId}|${e.mapCardZoom}|${e.mapCardProvider}"

/**
 * The composited map thumbnail per card, keyed by card, zoom and provider.
 *
 * Home does not move, so a thumbnail fetched once stays right for the life of the process.
 * Without this, every scroll that recomposed a map tile re-composited the same streets.
 */
private object MapThumbCache {
    private val cache = LruCache<String, ImageBitmap>(4)
    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, bmp: ImageBitmap) { cache.put(key, bmp) }
}
