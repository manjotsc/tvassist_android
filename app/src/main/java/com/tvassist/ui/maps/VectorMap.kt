package com.tvassist.ui.maps

/*
 * Vector OpenStreetMap, drawn by MapLibre Native from OpenFreeMap's tiles.
 *
 * Only the OpenStreetMap provider goes through here. Google stays the raster composite in
 * `HaRepository.fetchPersonMap`: Google hands no vector tiles to a third-party renderer, and its
 * satellite and traffic layers are raster everywhere.
 */

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume
import kotlin.math.ln
import kotlin.math.max

/**
 * The MapLibre zoom that frames the same ground as raster zoom [z] did on a screen whose longer
 * side is [maxDimPx] at [density].
 *
 * The raster screens stretch a 1024 px composite across the longer side, so one 256 px tile
 * covered `maxDimPx / 4` screen pixels; MapLibre draws a tile as 512 dp. Converting keeps the
 * integer zoom levels users have saved on map cards (and the 16 a person map opens at) meaning the
 * same streets as before. On a 1080p BRAVIA at density 2 this is `z − 1.09`.
 */
internal fun vectorZoom(z: Int, maxDimPx: Int, density: Float): Double =
    z + ln((maxDimPx / 4.0) / (512.0 * density)) / ln(2.0)

/**
 * The live map a [VectorMap] draws, and the projection the Compose markers over it need.
 *
 * [cameraTick] changes on every camera frame; [toScreen] reads it, so a `Canvas` or `offset` that
 * projects through it redraws in step with the map instead of lagging an animation behind.
 */
@Stable
internal class VectorMapState {
    internal var map: MapLibreMap? by mutableStateOf(null)
    private var cameraTick by mutableIntStateOf(0)

    internal fun cameraMoved() { cameraTick++ }

    /** Screen pixel of [lat],[lng] in the map view's coordinates (the same box it fills), or null before the map is ready. */
    fun toScreen(lat: Double, lng: Double): Offset? {
        // Read for its subscription, not its value: it is what makes a caller redraw per camera frame.
        if (cameraTick < 0) return null
        val m = map ?: return null
        val p = m.projection.toScreenLocation(LatLng(lat, lng))
        return Offset(p.x, p.y)
    }
}

/**
 * A MapLibre map centred on [lat],[lng] at MapLibre zoom [zoom], with every gesture off — the
 * screens drive it from the D-pad.
 *
 * Moving the centre glides over [glideMs]; changing zoom eases over a quarter-second, which is
 * what replaced the raster screens' "scale the old bitmap until the new tiles land" trick.
 * [glideLinear] makes the glide constant-speed instead of easing in and out: a follow camera that
 * is re-aimed mid-glide by the next fix then carries on without slowing to a stop at each one.
 *
 * TextureView mode, not the default SurfaceView: the overlay window clips the fullscreen map to
 * rounded corners and draws pills over it, and a SurfaceView punches through both.
 */
@Composable
internal fun VectorMap(
    state: VectorMapState,
    lat: Double,
    lng: Double,
    zoom: Double,
    look: VectorLook,
    modifier: Modifier = Modifier,
    glideMs: Int = 1000,
    glideLinear: Boolean = false,
) {
    val context = LocalContext.current
    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .logoEnabled(false)
            // Attribution is drawn by the screens themselves, in the same pill as before.
            .attributionEnabled(false)
            .compassEnabled(false)
            .foregroundLoadColor(MAP_BACKGROUND)
            .camera(CameraPosition.Builder().target(LatLng(lat, lng)).zoom(zoom).build())
        MapView(context, options).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.setAllGesturesEnabled(false)
                m.addOnCameraMoveListener { state.cameraMoved() }
                state.map = m
            }
        }
    }
    DisposableEffect(mapView) {
        // Driven by hand rather than from a LifecycleOwner: the same screen is hosted in the
        // activity and in the overlay window, which has no activity lifecycle, and it only lives
        // as long as this composition does.
        mapView.onStart()
        mapView.onResume()
        onDispose {
            state.map = null
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    val map = state.map
    // Style set here rather than at creation, so the ▶ toggle swaps it on the live map in place:
    // the camera, and the markers projected through it, stay where they are.
    LaunchedEffect(map, look) {
        val m = map ?: return@LaunchedEffect
        m.setStyle(look.style())
    }
    var shownZoom by remember { mutableStateOf(zoom) }
    LaunchedEffect(map, lat, lng, zoom) {
        if (map == null) return@LaunchedEffect
        val zooming = zoom != shownZoom
        shownZoom = zoom
        val update = CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom)
        if (zooming) {
            map.easeCamera(update, ZOOM_EASE_MS)
        } else {
            // easingInterpolator = false is MapLibre's linear glide.
            map.easeCamera(update, glideMs, !glideLinear)
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}

/**
 * A still of the vector map centred on [lat],[lng], framing what a radius-1 raster composite at
 * raster zoom [z] framed (512 px, two tiles across) — for the sidebar's map-card thumbnails, where
 * a live GL view per tile would be far too much.
 *
 * Drawn at pixel ratio 2 into a 256-point canvas, so the labels come out twice the size a raster
 * tile prints them: the thumbnail is read from across a room. Null on failure or after 20 s.
 */
internal suspend fun vectorMapSnapshot(context: Context, lat: Double, lng: Double, z: Int, look: VectorLook): Bitmap? =
    withContext(Dispatchers.Main) {
        MapLibre.getInstance(context.applicationContext)
        val style = look.style()
        withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val options = MapSnapshotter.Options(SNAPSHOT_POINTS, SNAPSHOT_POINTS)
                    .withPixelRatio(2f)
                    .withLogo(false)
                    .withStyleBuilder(style)
                    // 256 points at ratio 2 is 512 px; z − 2 makes one raster tile 256 px of it.
                    .withCameraPosition(
                        CameraPosition.Builder().target(LatLng(lat, lng)).zoom(max(0.0, z - 2.0)).build(),
                    )
                val snapshotter = MapSnapshotter(context, options)
                cont.invokeOnCancellation { snapshotter.cancel() }
                snapshotter.start(
                    object : MapSnapshotter.SnapshotReadyCallback {
                        override fun onSnapshotReady(snapshot: MapSnapshot) {
                            if (cont.isActive) cont.resume(snapshot.bitmap)
                        }
                    },
                    object : MapSnapshotter.ErrorHandler {
                        override fun onError(error: String) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }
        }
    }

/** The screens' own background, so the map's first frames match it instead of flashing white. */
private const val MAP_BACKGROUND = 0xFF0B0E12.toInt()
private const val ZOOM_EASE_MS = 250
private const val SNAPSHOT_POINTS = 256
private const val SNAPSHOT_TIMEOUT_MS = 20_000L
