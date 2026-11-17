package com.tvassist.ui.maps

/*
 * Fullscreen location maps. The sidebar *tile* that opens one lives in
 * ui/cards/map/ — this package is the screens themselves.
 */

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Speed
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.settings.MapSource
import com.tvassist.data.settings.OverlayTile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.tvassist.ui.AppAccent
import com.tvassist.ui.cap

/**
 * Fullscreen person/device-tracker location map. OpenStreetMap is a live vector map ([VectorMap]);
 * Google is a raster tile composite centred on the person, drawn as a plain image. Both work
 * inside the overlay window, unlike a WebView. A pulsing marker sits at the center and a frosted info card shows the details
 * selected for this tile ([options], see [OverlayTile] P_* keys). Parent owns BACK.
 */
@Composable
fun PersonMapScreen(
    entity: Entity,
    repository: HaRepository,
    options: List<String> = OverlayTile.PERSON_DEFAULTS,
    mapProvider: String = OverlayTile.MAP_AUTO,
) {
    val lat = entity.latitude
    val lng = entity.longitude
    var map by remember(entity.entityId) { mutableStateOf<ImageBitmap?>(null) }
    var home by remember(entity.entityId) { mutableStateOf<Pair<Double, Double>?>(null) }
    var avatar by remember(entity.entityId) { mutableStateOf<ImageBitmap?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var zoom by remember(entity.entityId) { mutableIntStateOf(16) }
    // The zoom the currently-shown bitmap was fetched at. While it lags [zoom] (during a fetch),
    // we scale the old image by 2^(zoom - fetchedZoom) so zooming feels instant.
    var fetchedZoom by remember(entity.entityId) { mutableIntStateOf(16) }
    // The center the currently-shown bitmap was fetched at, so tiles can be drawn world-aligned while
    // the camera glides — a re-centered fetch then swaps in seamlessly (no jump).
    var fetchedLat by remember(entity.entityId) { mutableStateOf<Double?>(null) }
    var fetchedLng by remember(entity.entityId) { mutableStateOf<Double?>(null) }
    // OpenStreetMap draws as a live vector map that glides its own camera; Google stays the raster
    // composite with the hand-glided camera below. Null until known.
    var vector by remember { mutableStateOf<Boolean?>(null) }
    val vectorState = remember { VectorMapState() }
    // Where the camera — and so the avatar, which sits at its centre — is headed: the live fix
    // carried on by the person's velocity for one reporting interval, so the avatar keeps pace with
    // them instead of trailing a whole interval behind (FollowPredictor). The vector map glides to
    // it itself; the raster camera glides through rememberGlide. There is deliberately no dot at the
    // raw fix: it jumped ahead the moment a fix landed and the avatar then crawled after it, which
    // read as the dot moving before the person did.
    val plan = androidx.compose.runtime.key(entity.entityId) { rememberFollowPlan(lat, lng, entity.speed) }
    // Only the raster map reads the glided point; keeping it null otherwise stops a per-frame
    // recomposition of this whole screen while MapLibre does the gliding.
    val rasterCam = androidx.compose.runtime.key(entity.entityId) { rememberGlide(if (vector == false) plan else null) }
    var attribution by remember { mutableStateOf("") }
    val trail = remember(entity.entityId) { mutableStateListOf<Pair<Double, Double>>() }
    // The map grabs focus so the D-pad drives zoom directly — a remote can't reliably focus the
    // on-screen +/- buttons inside the overlay window.
    val mapFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // Global map style/traffic, driven by the ▶ / ◀ D-pad keys. refreshKey forces a re-fetch when
    // they change (coordinates/zoom didn't change, so the fetch effect needs another trigger).
    var mapStyle by remember { mutableStateOf("roadmap") }
    var mapTraffic by remember { mutableStateOf(false) }
    // The resolved map (a MapSource key) and, for the two vector maps, whether it is drawn dark —
    // each map remembers its own. Null until resolved; `vector` is what lets the map compose.
    var source by remember { mutableStateOf<String?>(null) }
    var mapDark by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var needsKey by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { mapFocus.requestFocus() } }
    LaunchedEffect(Unit) {
        mapStyle = repository.currentMapStyle()
        mapTraffic = repository.currentMapTraffic()
    }
    LaunchedEffect(mapProvider) {
        // Google chosen with no key: say so instead of drawing OpenStreetMap in its place. `vector`
        // stays null, which also keeps the raster fetch from asking Google with an empty key.
        needsKey = repository.mapNeedsKey(mapProvider)
        if (needsKey) {
            vector = null
            attribution = ""
            return@LaunchedEffect
        }
        // Light/dark first: `vector` is what lets the map compose, and it should open in the right
        // style rather than load one and then swap.
        val src = repository.resolvedMapSource(mapProvider)
        mapDark = repository.mapDark(src)
        source = src
        vector = src != MapSource.GOOGLE
        attribution = repository.mapAttribution(mapProvider)
    }
    // Re-fetch & re-center whenever HA reports new coordinates (or the zoom/style/traffic change) →
    // the map follows the person.
    LaunchedEffect(lat, lng, zoom, refreshKey, vector) {
        if (lat != null && lng != null) {
            // Record the breadcrumb FIRST — before the cancellable fetch below — so back-to-back
            // position pushes that restart this effect still extend the trail without gaps.
            if (OverlayTile.P_TRAIL in options && trail.lastOrNull() != (lat to lng)) {
                trail.add(lat to lng)
                while (trail.size > 60) trail.removeAt(0)
            }
            if (vector != false) return@LaunchedEffect // the vector map has no tiles to fetch
            // Coalesce rapid zoom presses (each re-fetches the whole tile grid) and let the instant
            // scale below show first — but only debounce for zoom; a new HA position fetches at once
            // so the map tracks live. Keep the old map on failure instead of blanking it.
            if (zoom != fetchedZoom) delay(120)
            repository.fetchPersonMap(lat, lng, zoom, mapProvider, radius = 3)?.asImageBitmap()?.let {
                map = it
                fetchedZoom = zoom
                fetchedLat = lat
                fetchedLng = lng
            }
        }
    }
    // The person's photo from Home Assistant (entity_picture), used for the marker + card.
    LaunchedEffect(entity.entityPicture) {
        avatar = entity.entityPicture?.let { repository.fetchEntityPicture(it)?.asImageBitmap() }
    }
    // Distance: read the home zone once if requested.
    LaunchedEffect(entity.entityId) {
        if (OverlayTile.P_DISTANCE in options) home = repository.homeZoneLatLng()
    }
    // Live refresh: nudge HA to pull a fresher fix where the integration supports it.
    if (OverlayTile.P_LIVE in options) {
        LaunchedEffect(entity.entityId) {
            while (true) {
                delay(15_000)
                runCatching { repository.callService("homeassistant", "update_entity", entity.entityId) }
            }
        }
    }
    // "Updated Xs ago" ticker.
    if (OverlayTile.P_UPDATED in options) {
        LaunchedEffect(entity.entityId) {
            while (true) { nowMs = System.currentTimeMillis(); delay(1000) }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E12))
            .focusRequester(mapFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                    when (e.key) {
                        Key.DirectionUp -> { zoom = (zoom + 1).coerceAtMost(20); true }
                        Key.DirectionDown -> { zoom = (zoom - 1).coerceAtLeast(3); true }
                        // Google Maps: ▶ steps roadmap → dark → satellite, ◀ toggles traffic. The vector
                        // map (OpenStreetMap) has no satellite or traffic: ▶ flips light ↔
                        // dark and ◀ does nothing. Both keys are still consumed, so a stray ◀ cannot move
                        // focus off the map.
                        Key.DirectionRight -> {
                            when (vector) {
                                false -> scope.launch { mapStyle = repository.cycleMapStyle(); refreshKey++ }
                                true -> {
                                    val src = source
                                    if (src != null) {
                                        mapDark = !mapDark
                                        val dark = mapDark
                                        scope.launch { repository.setMapDark(src, dark) }
                                    }
                                }
                                null -> Unit
                            }
                            true
                        }
                        Key.DirectionLeft -> {
                            if (vector == false) scope.launch { mapTraffic = repository.toggleMapTraffic(); refreshKey++ }
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            needsKey -> MapKeyRequired()
            lat == null || lng == null ->
                Text(
                    "No location available for ${entity.friendlyName}.\nCurrently: ${cap(entity.state)}",
                    color = Color.White, fontSize = 18.sp,
                )
            vector == null || (vector == false && map == null) ->
                Text("Loading map…", color = Color(0xFFB6C0CC), fontSize = 16.sp)
            vector == true -> {
                val cLat = lat!!; val cLng = lng!!
                VectorMap(
                    state = vectorState,
                    lat = plan?.lat ?: cLat,
                    lng = plan?.lng ?: cLng,
                    zoom = vectorZoom(zoom, maxOf(constraints.maxWidth, constraints.maxHeight), LocalDensity.current.density),
                    look = VectorLook.of(source ?: MapSource.OSM, mapDark),
                    modifier = Modifier.fillMaxSize(),
                    glideMs = plan?.durMs?.coerceAtLeast(FOLLOW_GLIDE_MIN_MS) ?: FOLLOW_GLIDE_MIN_MS,
                    glideLinear = true,
                )
                Canvas(Modifier.fillMaxSize()) {
                    if (OverlayTile.P_TRAIL in options && trail.size >= 2) {
                        drawTrail(trailToAvatar(trail.mapNotNull { (plat, plng) -> vectorState.toScreen(plat, plng) }, center))
                    }
                }
                PersonMarker(entity, avatar, Modifier.align(Alignment.Center))
            }
            else -> {
                // Pending-zoom scale so zoom-in feels instant until sharper tiles land (never < 1).
                val displayScale = maxOf(1f, Math.pow(2.0, (zoom - fetchedZoom).toDouble()).toFloat())
                val cLat = lat!!; val cLng = lng!!
                // Camera (eased) is the screen center; the person leads it slightly while moving.
                val camA = rasterCam?.first ?: cLat; val camB = rasterCam?.second ?: cLng
                val fLat = fetchedLat ?: cLat; val fLng = fetchedLng ?: cLng
                // Screen px per world px (world px are at fetchedZoom; displayScale covers pending zoom).
                val unit = (maxOf(constraints.maxWidth, constraints.maxHeight) / 1024f) * displayScale
                val (camGx, camGy) = worldPx(camA, camB, fetchedZoom)
                val (fGx, fGy) = worldPx(fLat, fLng, fetchedZoom)
                val dim = map!!.width
                // Everything is drawn world-aligned relative to the eased camera, so the tiles slide
                // smoothly and a freshly re-centered fetch swaps in without a jump.
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    // The bitmap is centered on (fetchedLat, fetchedLng); place + scale it in world space.
                    val bx = cx + ((fGx - camGx) * unit).toFloat()
                    val by = cy + ((fGy - camGy) * unit).toFloat()
                    val ds = (dim * unit).toFloat()
                    drawImage(
                        image = map!!,
                        dstOffset = IntOffset((bx - ds / 2f).roundToInt(), (by - ds / 2f).roundToInt()),
                        dstSize = IntSize(ds.roundToInt(), ds.roundToInt()),
                    )
                    if (OverlayTile.P_TRAIL in options && trail.size >= 2) {
                        drawTrail(
                            trailToAvatar(
                                trail.map { (plat, plng) ->
                                    val (gx, gy) = worldPx(plat, plng, fetchedZoom)
                                    Offset(cx + ((gx - camGx) * unit).toFloat(), cy + ((gy - camGy) * unit).toFloat())
                                },
                                Offset(cx, cy),
                            ),
                        )
                    }
                }
                PersonMarker(entity, avatar, Modifier.align(Alignment.Center))
            }
        }

        if (lat != null && lng != null) {
            InfoCard(
                entity = entity,
                options = options,
                home = home,
                avatar = avatar,
                nowMs = nowMs,
                modifier = Modifier.align(Alignment.BottomStart).padding(24.dp),
            )
        }
        if (!needsKey) Text(
            mapKeyHint(vector = vector != false, dark = mapDark, googleLook = mapStyle, traffic = mapTraffic),
            color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(24.dp).mapPill(),
        )

        // Passive zoom indicator (the D-pad ▲/▼ actually change the zoom; see onPreviewKeyEvent).
        if (lat != null && lng != null) {
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Color(0xC00E141C))
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Zoom in", tint = Color.White, modifier = Modifier.size(22.dp))
                Text("$zoom", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.Remove, contentDescription = "Zoom out", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            if (attribution.isNotBlank()) {
                Text(
                    attribution,
                    // Pilled and opaque enough to actually read: OpenStreetMap's licence asks that
                    // the credit stay legible, and plain white washed out over light map tiles.
                    color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).mapPill(),
                )
            }
        }
    }
}

/**
 * The breadcrumbs with the newest one swapped for the avatar's own position (the screen centre).
 * The newest crumb is the live fix, which the gliding camera has not reached yet, so drawing it
 * pushed the line out ahead of the avatar, as the raw-fix dot did. Once the avatar has arrived the
 * two points coincide and nothing is lost.
 */
private fun trailToAvatar(pts: List<Offset>, avatar: Offset): List<Offset> =
    if (pts.isEmpty()) pts else pts.dropLast(1) + avatar

/** One line through the breadcrumbs, fading + thinning toward the oldest end. */
private fun DrawScope.drawTrail(pts: List<Offset>) {
    val w = 5.dp.toPx()
    for (i in 0 until pts.size - 1) {
        val frac = (i + 1) / (pts.size - 1f)
        drawLine(
            color = AppAccent.copy(alpha = 0.12f + 0.58f * frac),
            start = pts[i], end = pts[i + 1],
            strokeWidth = w * (0.4f + 0.6f * frac), cap = StrokeCap.Round,
        )
    }
}

/** Avatar at the screen centre, which is the camera (Life360-style); the map slides beneath it. */
@Composable
private fun PersonMarker(entity: Entity, avatar: ImageBitmap?, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        PulsingMarker(avatar)
        val kmh = entity.speed?.let { (it * 3.6).roundToInt() }
        if (kmh != null && kmh > 0) {
            Box(Modifier.align(Alignment.TopCenter).offset(y = (-34).dp)) { SpeedBubble(kmh) }
        }
    }
}

/** A small white speed pill (car icon + "N km/h") shown above the marker while the person moves. */
@Composable
private fun SpeedBubble(kmh: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0xF2FFFFFF))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Icon(Icons.Rounded.Speed, contentDescription = null, tint = AppAccent, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text("$kmh km/h", color = Color(0xFF1A1D22), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** An animated location pin centered in the map: pulsing accent halo behind the person's photo. */
@Composable
private fun PulsingMarker(avatar: ImageBitmap?) {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(
        initialValue = 0.6f, targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "scale",
    )
    val a by t.animateFloat(
        initialValue = 0.45f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "alpha",
    )
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).scale(s).alpha(a).clip(CircleShape).background(AppAccent))
        // White ring + photo (or a person glyph fallback).
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            if (avatar != null) {
                Image(bitmap = avatar, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(33.dp).clip(CircleShape))
            } else {
                Box(Modifier.size(33.dp).clip(CircleShape).background(AppAccent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Frosted info panel: name + the detail rows selected for this tile. */
@Composable
private fun InfoCard(
    entity: Entity,
    options: List<String>,
    home: Pair<Double, Double>?,
    avatar: ImageBitmap?,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(min = 220.dp, max = 380.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE60E141C))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (avatar != null) {
                Image(bitmap = avatar, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(34.dp).clip(CircleShape))
            } else {
                Box(Modifier.size(34.dp).clip(CircleShape).background(AppAccent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Person, contentDescription = null, tint = AppAccent, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(11.dp))
            Text(entity.friendlyName, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }

        if (OverlayTile.P_ZONE in options) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val homeNow = entity.state.equals("home", ignoreCase = true)
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (homeNow) Color(0xFF6FCF7F) else Color(0xFFF2A33C)))
                Spacer(Modifier.width(9.dp))
                Text(cap(entity.state), color = Color(0xFFD7DEE7), fontSize = 14.sp)
            }
        }

        if (OverlayTile.P_DISTANCE in options) {
            val lat = entity.latitude; val lng = entity.longitude
            val km = if (home != null && lat != null && lng != null) distanceKm(lat, lng, home.first, home.second) else null
            InfoLine(Icons.Rounded.Place, when {
                km == null -> "Distance unknown"
                km < 0.1 -> "At home"
                km < 1.0 -> "${(km * 1000).roundToInt()} m from home"
                else -> "${fmt1(km)} km from home"
            })
        }

        if (OverlayTile.P_BATTERY in options) {
            entity.batteryLevel?.let { InfoLine(Icons.Rounded.BatteryFull, "Battery $it%") }
            entity.gpsAccuracy?.let { InfoLine(Icons.Rounded.GpsFixed, "± $it m") }
        }

        if (OverlayTile.P_SPEED in options) {
            entity.speed?.takeIf { it > 0 }?.let { InfoLine(Icons.Rounded.Speed, "${(it * 3.6).roundToInt()} km/h") }
        }

        if (OverlayTile.P_UPDATED in options) {
            Text(updatedAgo(entity.lastUpdated ?: entity.lastChanged, nowMs), color = Color(0xFF8A94A3), fontSize = 12.sp)
        }
    }
}
