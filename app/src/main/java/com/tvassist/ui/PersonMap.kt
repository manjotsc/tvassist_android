package com.tvassist.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Speed
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.settings.MapCard
import com.tvassist.data.settings.OverlayTile
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fullscreen person/device-tracker location map. Renders an OpenStreetMap tile composite
 * (centered on the person) as a plain [Image] — works inside the overlay window, unlike a
 * WebView. A pulsing marker sits at the center and a frosted info card shows the details
 * selected for this tile ([options], see [OverlayTile] P_* keys). Parent owns BACK.
 */
@Composable
fun PersonMapScreen(
    entity: Entity,
    repository: HaRepository,
    options: List<String> = OverlayTile.PERSON_DEFAULTS,
    mapProvider: String = OverlayTile.MAP_AUTO,
    onBack: () -> Unit,
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
    // Eased "camera" center — follows the person's live position with per-frame smoothing so the map
    // slides (Life360-style) instead of hard-swapping. Kept in full Double precision (no Animatable).
    var camLat by remember(entity.entityId) { mutableStateOf<Double?>(null) }
    var camLng by remember(entity.entityId) { mutableStateOf<Double?>(null) }
    // Deadbanded follow target: only commit a new destination once the fix has moved > ~5 m, so GPS
    // jitter while stopped/slow doesn't make the camera chase noise and read as jumpy.
    var tgtLat by remember(entity.entityId) { mutableStateOf<Double?>(null) }
    var tgtLng by remember(entity.entityId) { mutableStateOf<Double?>(null) }
    LaunchedEffect(lat, lng) {
        val a = lat ?: return@LaunchedEffect
        val b = lng ?: return@LaunchedEffect
        val ct = tgtLat; val cn = tgtLng
        if (ct == null || cn == null || distanceKm(a, b, ct, cn) * 1000.0 > 5.0) {
            tgtLat = a; tgtLng = b
        }
    }
    // Ease from the current camera to the committed target, then finish (frame loop idles when still).
    LaunchedEffect(tgtLat, tgtLng) {
        val tLat = tgtLat ?: return@LaunchedEffect
        val tLng = tgtLng ?: return@LaunchedEffect
        if (camLat == null || camLng == null) { camLat = tLat; camLng = tLng; return@LaunchedEffect }
        while (true) {
            withFrameNanos { }
            val cl = camLat!!; val cn = camLng!!
            val dLat = tLat - cl; val dLng = tLng - cn
            if (kotlin.math.abs(dLat) < 1e-7 && kotlin.math.abs(dLng) < 1e-7) {
                camLat = tLat; camLng = tLng; break
            }
            camLat = cl + dLat * 0.10 // gentler glide → steadier, less jumpy
            camLng = cn + dLng * 0.10
        }
    }
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
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { runCatching { mapFocus.requestFocus() } }
    LaunchedEffect(Unit) { mapStyle = repository.currentMapStyle(); mapTraffic = repository.currentMapTraffic() }
    LaunchedEffect(mapProvider) { attribution = repository.mapAttribution(mapProvider) }
    // Re-fetch & re-center whenever HA reports new coordinates (or the zoom/style/traffic change) →
    // the map follows the person.
    LaunchedEffect(lat, lng, zoom, refreshKey) {
        if (lat != null && lng != null) {
            // Record the breadcrumb FIRST — before the cancellable fetch below — so back-to-back
            // position pushes that restart this effect still extend the trail without gaps.
            if (OverlayTile.P_TRAIL in options && trail.lastOrNull() != (lat to lng)) {
                trail.add(lat to lng)
                while (trail.size > 60) trail.removeAt(0)
            }
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
                        // ▶ toggles roadmap/satellite, ◀ toggles the traffic overlay (Google only).
                        Key.DirectionRight -> { scope.launch { mapStyle = repository.cycleMapStyle(); refreshKey++ }; true }
                        Key.DirectionLeft -> { scope.launch { mapTraffic = repository.toggleMapTraffic(); refreshKey++ }; true }
                        else -> false
                    }
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            lat == null || lng == null ->
                Text(
                    "No location available for ${entity.friendlyName}.\nCurrently: ${cap(entity.state)}",
                    color = Color.White, fontSize = 18.sp,
                )
            map == null -> Text("Loading map…", color = Color(0xFFB6C0CC), fontSize = 16.sp)
            else -> {
                // Pending-zoom scale so zoom-in feels instant until sharper tiles land (never < 1).
                val displayScale = maxOf(1f, Math.pow(2.0, (zoom - fetchedZoom).toDouble()).toFloat())
                val cLat = lat!!; val cLng = lng!!
                // Camera (eased) is the screen center; the person leads it slightly while moving.
                val camA = camLat ?: cLat; val camB = camLng ?: cLng
                val fLat = fetchedLat ?: cLat; val fLng = fetchedLng ?: cLng
                // Screen px per world px (world px are at fetchedZoom; displayScale covers pending zoom).
                val unit = (maxOf(constraints.maxWidth, constraints.maxHeight) / 1024f) * displayScale
                val (camGx, camGy) = worldPx(camA, camB, fetchedZoom)
                val (fGx, fGy) = worldPx(fLat, fLng, fetchedZoom)
                val dim = map!!.width
                val (pgx, pgy) = worldPx(cLat, cLng, fetchedZoom)
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
                        // One line through the breadcrumbs, fading + thinning toward the oldest end.
                        val pts = trail.map { (plat, plng) ->
                            val (gx, gy) = worldPx(plat, plng, fetchedZoom)
                            Offset(cx + ((gx - camGx) * unit).toFloat(), cy + ((gy - camGy) * unit).toFloat())
                        }
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
                    // Raw GPS fix — the eased avatar sits at center; this dot marks the exact reported
                    // point and slides into the avatar as the map catches up.
                    val px = cx + ((pgx - camGx) * unit).toFloat()
                    val py = cy + ((pgy - camGy) * unit).toFloat()
                    drawCircle(color = Color.White, radius = 6.dp.toPx(), center = Offset(px, py))
                    drawCircle(color = AppAccent, radius = 4.5f.dp.toPx(), center = Offset(px, py))
                }
                // Avatar sits at the eased camera center (Life360-style); the map slides beneath it.
                Box(
                    Modifier.align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    PulsingMarker(avatar)
                    val kmh = entity.speed?.let { (it * 3.6).roundToInt() }
                    if (kmh != null && kmh > 0) {
                        Box(Modifier.align(Alignment.TopCenter).offset(y = (-34).dp)) { SpeedBubble(kmh) }
                    }
                }
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
        Text(
            "▲▼ zoom · ▶ ${if (mapStyle == "satellite") "satellite" else "roadmap"} · " +
                "◀ traffic ${if (mapTraffic) "on" else "off"} · BACK to close",
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
            Text(updatedAgo(entity.lastChanged, nowMs), color = Color(0xFF8A94A3), fontSize = 12.sp)
        }
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color(0xFF8A94A3), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, color = Color(0xFFD7DEE7), fontSize = 14.sp)
    }
}

/** Global pixel coordinate (256 px/tile) of a lat/lng at zoom [z] — matches fetchPersonMap. */
private fun worldPx(lat: Double, lng: Double, z: Int = 16): Pair<Double, Double> {
    val n = (1 shl z).toDouble()
    val gx = (lng + 180.0) / 360.0 * n * 256.0
    val gy = (1.0 - kotlin.math.asinh(kotlin.math.tan(Math.toRadians(lat))) / Math.PI) / 2.0 * n * 256.0
    return gx to gy
}

private fun fmt1(v: Double): String = ((v * 10).roundToInt() / 10.0).toString()

/** Haversine great-circle distance in km. */
private fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private fun updatedAgo(lastChanged: String?, nowMs: Long): String {
    val ts = lastChanged?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
        ?: return "Live"
    val s = ((nowMs - ts) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "Updated ${s}s ago"
        s < 3600 -> "Updated ${s / 60}m ago"
        else -> "Updated ${s / 3600}h ago"
    }
}

// ---------------------------------------------------------------------------------------------
// Multi-entity "map card": one map framing several people/device-trackers relative to home.
// ---------------------------------------------------------------------------------------------

/** A member plotted on a [PeopleMapScreen]: the tracked entity + which detail rows its legend shows. */
data class PeopleMapMember(val entity: Entity, val options: List<String> = OverlayTile.PERSON_DEFAULTS)

/**
 * Fullscreen multi-entity location map. Unlike [PersonMapScreen] (which follows one person), this
 * centers on `zone.home` at a fixed [zoom] and plots every [members] entity relative to home. Anyone
 * outside the frame is clamped to the nearest edge with an arrow + distance so they stay visible. A
 * side legend lists each member, with details driven by that member's own personOptions. The D-pad
 * drives zoom (▲▼) and the global map style/traffic (▶◀); the parent owns BACK.
 */
@Composable
fun PeopleMapScreen(
    members: List<PeopleMapMember>,
    title: String,
    repository: HaRepository,
    zoom: Int = MapCard.DEFAULT_ZOOM,
    mapProvider: String = OverlayTile.MAP_AUTO,
    showLegend: Boolean = true,
    onBack: () -> Unit = {},
) {
    var map by remember { mutableStateOf<ImageBitmap?>(null) }
    var home by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // Center on `zone.home` when known; until it resolves (or on setups without a home zone) fall back
    // to the mean of members that have a fix, so the card still draws something useful.
    val centroid = remember(members) {
        val pts = members.mapNotNull { m ->
            val la = m.entity.latitude; val lo = m.entity.longitude
            if (la != null && lo != null) la to lo else null
        }
        if (pts.isEmpty()) {
            null
        } else {
            pts.fold(0.0 to 0.0) { a, p -> (a.first + p.first) to (a.second + p.second) }
                .let { (it.first / pts.size) to (it.second / pts.size) }
        }
    }
    val center = home ?: centroid
    val avatars = remember { mutableStateMapOf<String, ImageBitmap>() }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var zoomState by remember { mutableIntStateOf(zoom) }
    // Zoom the shown bitmap was fetched at; while it lags, scale the old image so zoom feels instant.
    var fetchedZoom by remember { mutableIntStateOf(zoom) }
    var attribution by remember { mutableStateOf("") }
    val mapFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var mapStyle by remember { mutableStateOf("roadmap") }
    var mapTraffic by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { runCatching { mapFocus.requestFocus() } }
    LaunchedEffect(Unit) { mapStyle = repository.currentMapStyle(); mapTraffic = repository.currentMapTraffic() }
    LaunchedEffect(mapProvider) { attribution = repository.mapAttribution(mapProvider) }
    LaunchedEffect(Unit) { home = repository.homeZoneLatLng() }

    val centerLat = center?.first
    val centerLng = center?.second
    // (Re)build the map whenever the center, zoom or style/traffic change.
    LaunchedEffect(centerLat, centerLng, zoomState, refreshKey) {
        if (centerLat != null && centerLng != null) {
            // Debounce only zoom presses; a new HA position (center move) fetches immediately so the
            // card tracks live.
            if (zoomState != fetchedZoom) delay(120)
            repository.fetchPersonMap(centerLat, centerLng, zoomState, mapProvider)?.asImageBitmap()?.let {
                map = it
                fetchedZoom = zoomState
            }
        }
    }
    // Each member's photo (entity_picture) for its marker + legend. Keyed on (id, picture) — NOT the
    // whole members list, which gets a fresh identity on every HA update — so a moving device doesn't
    // re-trigger a fetch storm; and fetched in parallel so N members load at once, not one-by-one.
    val avatarKeys = members.map { it.entity.entityId to it.entity.entityPicture }
    LaunchedEffect(avatarKeys) {
        coroutineScope {
            members.forEach { m ->
                val pic = m.entity.entityPicture ?: return@forEach
                launch { repository.fetchEntityPicture(pic)?.asImageBitmap()?.let { avatars[m.entity.entityId] = it } }
            }
        }
    }
    // Live refresh: nudge HA every ~15s for members that opted in. Keyed on the live-member id SET
    // (stable across position updates) so the 15s timer isn't reset — and thus never starved — when
    // several trackers report faster than that.
    val liveIds = members.filter { OverlayTile.P_LIVE in it.options }.map { it.entity.entityId }
    if (liveIds.isNotEmpty()) {
        LaunchedEffect(liveIds) {
            while (true) {
                delay(15_000)
                liveIds.forEach { id ->
                    runCatching { repository.callService("homeassistant", "update_entity", id) }
                }
            }
        }
    }
    // "Updated Xs ago" ticker if any member shows it.
    if (members.any { OverlayTile.P_UPDATED in it.options }) {
        LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1000) } }
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
                        Key.DirectionUp -> { zoomState = (zoomState + 1).coerceAtMost(20); true }
                        Key.DirectionDown -> { zoomState = (zoomState - 1).coerceAtLeast(3); true }
                        Key.DirectionRight -> { scope.launch { mapStyle = repository.cycleMapStyle(); refreshKey++ }; true }
                        Key.DirectionLeft -> { scope.launch { mapTraffic = repository.toggleMapTraffic(); refreshKey++ }; true }
                        else -> false
                    }
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            center == null -> Text("No locations available for $title.", color = Color.White, fontSize = 18.sp)
            map == null -> Text("Loading map…", color = Color(0xFFB6C0CC), fontSize = 16.sp)
            else -> {
                val displayScale = maxOf(1f, Math.pow(2.0, (zoomState - fetchedZoom).toDouble()).toFloat())
                Image(
                    bitmap = map!!,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = displayScale; scaleY = displayScale },
                )
                // Project each member from world pixels onto the screen (map is centered on `center`).
                val scale = maxOf(constraints.maxWidth, constraints.maxHeight) / 1024f
                val (cgx, cgy) = worldPx(centerLat!!, centerLng!!, zoomState)
                val halfW = constraints.maxWidth / 2f
                val halfH = constraints.maxHeight / 2f
                val margin = 64f // keep clamped markers fully on-screen
                members.forEach { m ->
                    val plat = m.entity.latitude
                    val plng = m.entity.longitude
                    if (plat != null && plng != null) {
                        val (gx, gy) = worldPx(plat, plng, zoomState)
                        val ox = ((gx - cgx) * scale).toFloat()
                        val oy = ((gy - cgy) * scale).toFloat()
                        if (kotlin.math.abs(ox) <= halfW - margin && kotlin.math.abs(oy) <= halfH - margin) {
                            Box(
                                Modifier.align(Alignment.Center)
                                    .offset { androidx.compose.ui.unit.IntOffset(ox.roundToInt(), oy.roundToInt()) },
                            ) { MemberMarker(m.entity, avatars[m.entity.entityId]) }
                        } else {
                            // Scale the vector down to the frame edge; the arrow points outward along it.
                            val tx = if (ox != 0f) (halfW - margin) / kotlin.math.abs(ox) else Float.MAX_VALUE
                            val ty = if (oy != 0f) (halfH - margin) / kotlin.math.abs(oy) else Float.MAX_VALUE
                            val t = minOf(tx, ty)
                            val bearing = Math.toDegrees(atan2(ox.toDouble(), -oy.toDouble())).toFloat()
                            val distKm = (home ?: center)?.let { distanceKm(plat, plng, it.first, it.second) }
                            Box(
                                Modifier.align(Alignment.Center)
                                    .offset { androidx.compose.ui.unit.IntOffset((ox * t).roundToInt(), (oy * t).roundToInt()) },
                            ) { EdgeIndicator(m.entity, avatars[m.entity.entityId], bearing, distKm) }
                        }
                    }
                }
                Box(Modifier.align(Alignment.Center)) { HomeMarker() }
            }
        }

        if (center != null) {
            if (showLegend) {
                MapLegend(
                    members = members,
                    avatars = avatars,
                    home = home ?: center,
                    nowMs = nowMs,
                    modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
                )
            }
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Color(0xC00E141C))
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Zoom in", tint = Color.White, modifier = Modifier.size(22.dp))
                Text("$zoomState", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.Remove, contentDescription = "Zoom out", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }

        Text(
            title,
            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopStart).padding(24.dp).mapPill(),
        )
        Text(
            "▲▼ zoom · ▶ ${if (mapStyle == "satellite") "satellite" else "roadmap"} · " +
                "◀ traffic ${if (mapTraffic) "on" else "off"} · BACK to close",
            color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(24.dp).mapPill(),
        )
        if (attribution.isNotBlank()) {
            Text(
                attribution,
                // Pilled and opaque enough to actually read: OpenStreetMap's licence asks that the
                // credit stay legible, and plain white washed out over light map tiles.
                color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).mapPill(),
            )
        }
    }
}

/** A white house pin marking `zone.home` at the map center. */
@Composable
private fun HomeMarker() {
    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF6FCF7F)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Home, contentDescription = "Home", tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** A member's avatar pin with a name chip beneath it, placed at the member's projected position. */
@Composable
private fun MemberMarker(entity: Entity, avatar: ImageBitmap?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AvatarDot(avatar, size = 34)
        Spacer(Modifier.height(3.dp))
        Text(
            entity.friendlyName,
            color = Color.White, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xCC0E141C))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** Edge marker for a member outside the frame: an outward arrow + avatar + name + distance. */
@Composable
private fun EdgeIndicator(entity: Entity, avatar: ImageBitmap?, bearing: Float, distKm: Double?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xE60E141C))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Icon(
            Icons.Rounded.Navigation, contentDescription = null, tint = AppAccent,
            modifier = Modifier.size(16.dp).rotate(bearing),
        )
        Spacer(Modifier.width(5.dp))
        AvatarDot(avatar, size = 26)
        Spacer(Modifier.width(7.dp))
        Column {
            Text(entity.friendlyName, color = Color.White, fontSize = 12.sp, maxLines = 1)
            distKm?.let { Text(distanceLabel(it), color = Color(0xFF8A94A3), fontSize = 10.sp, maxLines = 1) }
        }
        Spacer(Modifier.width(4.dp))
    }
}

/** Frosted list of every member down the side; each row's details follow that member's options. */
@Composable
private fun MapLegend(
    members: List<PeopleMapMember>,
    avatars: Map<String, ImageBitmap>,
    home: Pair<Double, Double>,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(min = 200.dp, max = 320.dp)
            .heightIn(max = 340.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE60E141C))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        members.forEach { m ->
            LegendRow(m.entity, m.options, avatars[m.entity.entityId], home, nowMs)
        }
    }
}

@Composable
private fun LegendRow(
    entity: Entity,
    options: List<String>,
    avatar: ImageBitmap?,
    home: Pair<Double, Double>,
    nowMs: Long,
) {
    Row(verticalAlignment = Alignment.Top) {
        AvatarDot(avatar, size = 30)
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entity.friendlyName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (OverlayTile.P_ZONE in options) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val homeNow = entity.state.equals("home", ignoreCase = true)
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (homeNow) Color(0xFF6FCF7F) else Color(0xFFF2A33C)))
                    Spacer(Modifier.width(7.dp))
                    Text(cap(entity.state), color = Color(0xFFD7DEE7), fontSize = 12.sp, maxLines = 1)
                }
            }
            if (OverlayTile.P_DISTANCE in options) {
                val la = entity.latitude; val lo = entity.longitude
                val km = if (la != null && lo != null) distanceKm(la, lo, home.first, home.second) else null
                InfoLine(Icons.Rounded.Place, if (km == null) "Distance unknown" else "${distanceLabel(km)} from home")
            }
            if (OverlayTile.P_BATTERY in options) {
                entity.batteryLevel?.let { InfoLine(Icons.Rounded.BatteryFull, "Battery $it%") }
                entity.gpsAccuracy?.let { InfoLine(Icons.Rounded.GpsFixed, "± $it m") }
            }
            if (OverlayTile.P_SPEED in options) {
                entity.speed?.takeIf { it > 0 }?.let { InfoLine(Icons.Rounded.Speed, "${(it * 3.6).roundToInt()} km/h") }
            }
            if (OverlayTile.P_UPDATED in options) {
                Text(updatedAgo(entity.lastChanged, nowMs), color = Color(0xFF8A94A3), fontSize = 11.sp)
            }
        }
    }
}

/** White-ringed circular avatar (photo, or a person glyph fallback), [size] dp across. */
@Composable
private fun AvatarDot(avatar: ImageBitmap?, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
        if (avatar != null) {
            Image(
                bitmap = avatar, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size((size - 4).dp).clip(CircleShape),
            )
        } else {
            Box(Modifier.size((size - 4).dp).clip(CircleShape).background(AppAccent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size((size / 2).dp))
            }
        }
    }
}

private fun distanceLabel(km: Double): String = when {
    km < 0.1 -> "at home"
    km < 1.0 -> "${(km * 1000).roundToInt()} m"
    else -> "${fmt1(km)} km"
}

/**
 * Dark rounded backing for text floating over map imagery. Map tiles are light, so plain white text
 * washes out over them — the zoom indicator and the person info card already use this treatment, so
 * labels and hints match rather than being the one unreadable element on the screen.
 */
private fun Modifier.mapPill(): Modifier = this
    .clip(RoundedCornerShape(12.dp))
    .background(Color(0xC00E141C))
    .padding(horizontal = 10.dp, vertical = 6.dp)
