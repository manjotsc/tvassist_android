package com.tvassist.data.ha

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.tvassist.data.settings.LocalCamera
import com.tvassist.data.settings.MapCard
import com.tvassist.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for the Home Assistant connection. Wraps one
 * [HaWebSocketClient] and exposes live entity/connection state to the rest of the app.
 *
 * Only the user's imported entities are tracked — the client filters everything else out,
 * so a large/busy HA instance doesn't flood the app with updates.
 */
class HaRepository(
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val client = HaWebSocketClient()

    // HTTP base + token kept for REST-style fetches (e.g. camera snapshots).
    @Volatile
    private var baseUrl: String = ""
    @Volatile
    private var token: String = ""
    private fun httpBuilder() = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        // Map tiles all come from one host; raise the per-host cap so the grid fetches in parallel
        // (otherwise OkHttp serialises to 5/host and a zoom takes a second+).
        .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 16 })

    // Long-lived client for MJPEG streaming (no read timeout — the stream stays open).
    private fun streamBuilder() = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)

    // Full certificate verification — the default, and the only thing public hosts ever get.
    private val httpStrict = httpBuilder().build()
    private val streamStrict = streamBuilder().build()

    // Relaxed variants, built only if the user turns verification off for a private HA host.
    private val httpRelaxed by lazy { InsecureTls.relax(httpBuilder()).build() }
    private val streamRelaxed by lazy { InsecureTls.relax(streamBuilder()).build() }

    /** Set on connect: verification is off AND the HA host resolves entirely into private space. */
    @Volatile
    private var relaxTls = false

    // Clients for requests built from [baseUrl] — the host [relaxTls] was decided against.
    private val haClient: OkHttpClient get() = if (relaxTls) httpRelaxed else httpStrict
    private val haStream: OkHttpClient get() = if (relaxTls) streamRelaxed else streamStrict

    /**
     * Client for an arbitrary [url]: relaxed only when it targets the exact HA host we cleared.
     *
     * Without the host check, turning verification off for Home Assistant would also disable it for
     * OpenStreetMap/Google map tiles and for local-camera snapshot URLs on other hosts — none of
     * which the user consented to, and some of which are on the public internet.
     */
    private fun clientFor(url: String): OkHttpClient {
        val h = hostOf(url)
        return if (relaxTls && h != null && h == hostOf(baseUrl)) httpRelaxed else httpStrict
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()

    val connectionState: StateFlow<ConnectionState> = client.connectionState

    /**
     * Live entities (imported/tracked from HA) plus the user's app-defined local cameras, which
     * are always present regardless of the HA connection.
     */
    val entities: StateFlow<List<Entity>> =
        combine(
            client.entities,
            settingsStore.settings.map { it.localCameras }.distinctUntilChanged(),
            settingsStore.settings.map { it.mapCards }.distinctUntilChanged(),
        ) { live, cams, maps -> live + cams.map { it.toEntity() } + maps.map { it.toEntity() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Entity ids to track on top of the imported pool — e.g. entities that a live pill binds to,
    // so they update even when the user hasn't imported them. Fed by [setExtraTrackedIds].
    private val extraTrackedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Set the extra entity ids to keep live (union'd with the imported pool). */
    fun setExtraTrackedIds(ids: Set<String>) { extraTrackedIds.value = ids }

    init {
        // Keep the client's tracked set in sync with the imported pool plus any extra (pill-bound) ids.
        scope.launch {
            combine(
                settingsStore.settings.map { it.importedEntityIds.toSet() }.distinctUntilChanged(),
                extraTrackedIds,
            ) { imported, extra -> imported + extra }
                .distinctUntilChanged()
                .collect { client.setTrackedIds(it) }
        }
        // Auto-connect from stored credentials on process start, so the overlay has live states
        // even when launched from the accessibility key without opening the app (e.g. after an
        // app update or reboot).
        scope.launch {
            val s = settingsStore.settings.first()
            if (s.baseUrl.isNotBlank() && s.token.isNotBlank()) connect(s.baseUrl, s.token)
        }
        // Re-dial when the verify-certificate preference changes, so the toggle takes effect without
        // the user having to re-enter credentials. connect() is idempotent per (url, token, tls).
        scope.launch {
            settingsStore.settings.map { it.verifySsl }.distinctUntilChanged().collect {
                val s = settingsStore.settings.first()
                if (s.baseUrl.isNotBlank() && s.token.isNotBlank()) connect(s.baseUrl, s.token)
            }
        }
    }

    fun connect(baseUrl: String, token: String) {
        val url = baseUrl.trim().trimEnd('/')
        this.baseUrl = url
        this.token = token
        // The TLS decision needs a DNS lookup, so it can't happen on the caller's thread.
        scope.launch(Dispatchers.IO) {
            relaxTls = shouldRelaxTls(url)
            client.connect(url, token, relaxTls)
        }
    }

    /**
     * Whether to skip certificate verification for [url]. Requires all three: an `https://` URL, the
     * user having turned verification off, and the host resolving entirely into private address
     * space. Anything else keeps full verification — see [InsecureTls].
     */
    private suspend fun shouldRelaxTls(url: String): Boolean {
        if (!url.startsWith("https", ignoreCase = true)) return false
        if (settingsStore.settings.first().verifySsl) return false
        return InsecureTls.isPrivateHost(url)
    }

    /**
     * (Re)connect from stored credentials when we're NOT already connected/connecting — a no-op when
     * healthy. Called from the overlay-show path and a keep-alive watchdog so the headless process
     * recovers a dropped connection on its own (e.g. after a reboot where the first connect failed
     * before the network was ready, and the WS-level retry didn't bring it back).
     */
    fun ensureConnected() {
        scope.launch {
            when (connectionState.value) {
                ConnectionState.Connected, ConnectionState.Connecting, ConnectionState.Authenticating -> return@launch
                else -> {}
            }
            val s = settingsStore.settings.first()
            if (s.baseUrl.isNotBlank() && s.token.isNotBlank()) connect(s.baseUrl, s.token)
        }
    }

    /** Fetches a camera's current frame (JPEG/PNG bytes) via HA's camera_proxy, or null. */
    suspend fun cameraSnapshot(entityId: String): ByteArray? = withContext(Dispatchers.IO) {
        val base = baseUrl
        if (base.isBlank() || token.isBlank()) return@withContext null
        runCatching {
            val req = Request.Builder()
                .url("$base/api/camera_proxy/$entityId")
                .header("Authorization", "Bearer $token")
                .build()
            haClient.newCall(req).execute().use { resp ->
                Log.i(CAM, "snapshot($entityId) code=${resp.code}")
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.onFailure { Log.w(CAM, "snapshot error for $entityId", it) }.getOrNull()
    }

    fun disconnect() = client.disconnect()

    fun toggle(entity: Entity) = client.toggle(entity)

    fun callService(
        domain: String,
        service: String,
        entityId: String,
        data: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    ) = client.callService(domain, service, entityId, data)

    /** Fetches ALL entities once (for the import picker); not added to the tracked set. */
    suspend fun fetchAllStates(): List<Entity> = client.fetchAllStates()

    /** Resolves a camera's full HLS stream URL (asks HA for it via the WS API), or null. */
    suspend fun cameraStreamUrl(entityId: String): String? {
        val base = baseUrl
        if (base.isBlank()) { Log.w(CAM, "cameraStreamUrl: no baseUrl"); return null }
        val path = client.requestCameraStreamPath(entityId)
        Log.i(CAM, "cameraStreamUrl($entityId): path=$path")
        if (path == null) return null
        return if (path.startsWith("http")) path else base + path
    }

    /**
     * Opens the camera's MJPEG stream and invokes [onFrame] for each decoded JPEG frame
     * until the coroutine is cancelled. Frames are detected by JPEG SOI/EOI markers.
     */
    suspend fun streamCameraMjpeg(entityId: String, onFrame: (Bitmap) -> Unit) = withContext(Dispatchers.IO) {
        val base = baseUrl
        if (base.isBlank() || token.isBlank()) { Log.w(CAM, "mjpeg: no baseUrl/token"); return@withContext }
        val url = "$base/api/camera_proxy_stream/$entityId"
        Log.i(CAM, "mjpeg connecting: $url")
        try {
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            haStream.newCall(req).execute().use { resp ->
                Log.i(CAM, "mjpeg response code=${resp.code} type=${resp.header("Content-Type")}")
                if (!resp.isSuccessful) return@use
                val input = BufferedInputStream(resp.body?.byteStream() ?: return@use, 64 * 1024)
                val frame = ByteArrayOutputStream(64 * 1024)
                var prev = -1
                var inFrame = false
                var frames = 0
                var b = input.read()
                while (b != -1 && currentCoroutineContext().isActive) {
                    if (!inFrame) {
                        if (prev == 0xFF && b == 0xD8) {
                            inFrame = true
                            frame.reset()
                            frame.write(0xFF); frame.write(0xD8)
                        }
                    } else {
                        frame.write(b)
                        if (prev == 0xFF && b == 0xD9) {
                            val bytes = frame.toByteArray()
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(onFrame)
                            if (frames++ == 0) Log.i(CAM, "mjpeg first frame (${bytes.size} bytes)")
                            inFrame = false
                        }
                    }
                    prev = b
                    b = input.read()
                }
                Log.i(CAM, "mjpeg ended: $frames frames")
            }
        } catch (e: Exception) {
            Log.w(CAM, "mjpeg error for $entityId", e)
        }
    }

    // Cache of OSM tiles (shared across nearby positions and refreshes), bounded by memory
    // (~12 MB) rather than count — each 256×256 tile is ~256 KB, so count-based caps balloon.
    private val tileCache = object : android.util.LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    private fun osmTile(zoom: Int, x: Int, y: Int): Bitmap? {
        val key = "$zoom/$x/$y"
        tileCache.get(key)?.let { return it }
        return runCatching {
            val url = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
            val req = Request.Builder().url(url).header("User-Agent", "TVAssist/1.0 (Android TV)").build()
            httpStrict.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } else null
            }
        }.getOrNull()?.also { tileCache.put(key, it) }
    }

    private data class GoogleSession(val token: String, val expiryMs: Long)
    private val googleSessions = ConcurrentHashMap<String, GoogleSession>()

    /** Creates (or reuses) a Google Map Tiles API session for a style/traffic combo; null on failure. */
    private fun googleSession(style: String, traffic: Boolean, key: String): String? {
        val cacheKey = "$style|$traffic"
        val now = System.currentTimeMillis()
        googleSessions[cacheKey]?.let { if (it.expiryMs > now + 60_000) return it.token }
        return runCatching {
            val mapType = if (style == "satellite") "satellite" else "roadmap"
            val layers = buildList {
                if (style == "satellite" && traffic) add("layerRoadmap") // roads under traffic on imagery
                if (traffic) add("layerTraffic")
            }
            val body = buildJsonObject {
                put("mapType", mapType)
                put("language", "en-US")
                put("region", "US")
                if (layers.isNotEmpty()) put("layerTypes", JsonArray(layers.map { JsonPrimitive(it) }))
            }.toString()
            val req = Request.Builder()
                .url("https://tile.googleapis.com/v1/createSession?key=$key")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            httpStrict.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(CAM, "google map session failed: ${resp.code}"); return@use null }
                val obj = Json.parseToJsonElement(resp.body?.string() ?: return@use null).jsonObject
                val token = obj["session"]?.jsonPrimitive?.contentOrNull ?: return@use null
                val exp = obj["expiry"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.times(1000)
                    ?: (now + 6 * 3600 * 1000)
                googleSessions[cacheKey] = GoogleSession(token, exp)
                token
            }
        }.onFailure { Log.w(CAM, "google map session error", it) }.getOrNull()
    }

    private fun googleTile(zoom: Int, x: Int, y: Int, session: String, key: String): Bitmap? {
        val cacheKey = "g/$session/$zoom/$x/$y"
        tileCache.get(cacheKey)?.let { return it }
        return runCatching {
            val url = "https://tile.googleapis.com/v1/2dtiles/$zoom/$x/$y?session=$session&key=$key"
            httpStrict.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } else null
            }
        }.getOrNull()?.also { tileCache.put(cacheKey, it) }
    }

    /** Whether Google tiles are used, given a per-tile [provider] override and the global key. */
    private fun usesGoogle(provider: String, hasKey: Boolean): Boolean = when (provider) {
        "osm" -> false
        "google" -> hasKey
        else -> hasKey // auto: follow the global setting
    }

    /** Current global map style / traffic (for the on-map D-pad toggles). */
    suspend fun currentMapStyle(): String = settingsStore.settings.first().mapStyle
    suspend fun currentMapTraffic(): Boolean = settingsStore.settings.first().mapTraffic

    /** Flip the global map style (roadmap ↔ satellite) and return the new value. */
    suspend fun cycleMapStyle(): String {
        val next = if (settingsStore.settings.first().mapStyle == "satellite") "roadmap" else "satellite"
        settingsStore.setMapStyle(next)
        return next
    }

    /** Toggle the global traffic overlay and return the new value. */
    suspend fun toggleMapTraffic(): Boolean {
        val next = !settingsStore.settings.first().mapTraffic
        settingsStore.setMapTraffic(next)
        return next
    }

    /** Attribution label for the active map provider (both OSM and Google require attribution). */
    suspend fun mapAttribution(provider: String = "auto"): String =
        if (usesGoogle(provider, settingsStore.settings.first().googleMapsApiKey.isNotBlank())) "Map data ©Google"
        else "© OpenStreetMap contributors"

    /**
     * Builds a map image centered exactly on [lat],[lng] (so a UI marker drawn at the image
     * center aligns with the person). Composites a grid of cached tiles (Google Map Tiles when a
     * key is set, else OpenStreetMap) into a plain bitmap — works in the overlay window, unlike a
     * WebView. No marker is baked in.
     */
    suspend fun fetchPersonMap(
        lat: Double,
        lng: Double,
        zoom: Int = 16,
        provider: String = "auto",
        // Tile radius around the center. 2 → 1024px square; a follow-map passes 3 (1536px) so there
        // is off-screen margin to pan the tiles into while the camera glides between fixes.
        radius: Int = 2,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val s = runCatching { settingsStore.settings.first() }.getOrNull()
        val key = s?.googleMapsApiKey?.trim().orEmpty()
        val googleSession =
            if (usesGoogle(provider, key.isNotEmpty())) googleSession(s!!.mapStyle, s.mapTraffic, key) else null
        val tileAt: (Int, Int, Int) -> Bitmap? =
            if (googleSession != null) { z, x, y -> googleTile(z, x, y, googleSession, key) }
            else { z, x, y -> osmTile(z, x, y) }
        runCatching {
            val n = 1 shl zoom
            val xf = (lng + 180.0) / 360.0 * n
            val yf = (1.0 - kotlin.math.asinh(kotlin.math.tan(Math.toRadians(lat))) / Math.PI) / 2.0 * n
            val cx = kotlin.math.floor(xf).toInt()
            val cy = kotlin.math.floor(yf).toInt()
            val sz = 256
            val dim = 2 * radius * sz // output square; the (2r+1)² grid fully covers it once centered
            val out = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(out)
            canvas.drawColor(android.graphics.Color.parseColor("#0B0E12"))
            // Place the center tile so the person's fractional pixel lands at the image center.
            val centerLeft = dim / 2f - ((xf - cx).toFloat() * sz)
            val centerTop = dim / 2f - ((yf - cy).toFloat() * sz)
            // Fetch the whole grid in parallel, then composite — one wave instead of 25 serial GETs.
            val offsets = (-radius..radius).flatMap { dx -> (-radius..radius).map { dy -> dx to dy } }
            val fetched = coroutineScope {
                offsets.map { (dx, dy) -> async { Triple(dx, dy, tileAt(zoom, cx + dx, cy + dy)) } }.awaitAll()
            }
            fetched.forEach { (dx, dy, t) ->
                if (t != null) canvas.drawBitmap(t, centerLeft + dx * sz, centerTop + dy * sz, null)
            }
            Log.i(CAM, "person map built for $lat,$lng")
            out
        }.onFailure { Log.w(CAM, "person map error", it) }.getOrNull()
    }

    /** Coordinates of HA's `zone.home` (lat, lng), fetched once and cached, or null. */
    @Volatile
    private var homeZone: Pair<Double, Double>? = null
    suspend fun homeZoneLatLng(): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        homeZone?.let { return@withContext it }
        val base = baseUrl
        if (base.isBlank() || token.isBlank()) return@withContext null
        runCatching {
            val req = Request.Builder()
                .url("$base/api/states/zone.home")
                .header("Authorization", "Bearer $token")
                .build()
            haClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val attrs = kotlinx.serialization.json.Json.parseToJsonElement(body)
                    .jsonObject["attributes"]?.jsonObject ?: return@use null
                val la = attrs["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                val lo = attrs["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                if (la != null && lo != null) (la to lo) else null
            }
        }.getOrNull()?.also { homeZone = it }
    }

    // Cache for entity pictures (avatars), keyed by their relative/absolute path.
    private val pictureCache = android.util.LruCache<String, Bitmap>(24)

    /** Resolves a relative HA path against [baseUrl]; absolute http(s) URLs pass through. */
    private fun absoluteUrl(path: String): String? {
        if (path.startsWith("http")) return path
        val base = baseUrl
        if (base.isBlank()) return null
        return base + (if (path.startsWith("/")) path else "/$path")
    }

    /** Fetches an entity_picture (avatar) by its HA path, authenticated, or null. */
    suspend fun fetchEntityPicture(path: String): Bitmap? = withContext(Dispatchers.IO) {
        pictureCache.get(path)?.let { return@withContext it }
        val url = absoluteUrl(path) ?: return@withContext null
        runCatching {
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            clientFor(url).newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes()?.let { decodeSampled(it, MAX_AVATAR_PX) } else null
            }
        }.getOrNull()?.also { pictureCache.put(path, it) }
    }

    /**
     * Fetches a notification's still image (its ``image``/``media_url``) by URL or HA path, or null.
     *
     * Deliberately **uncached**, unlike [fetchEntityPicture]: a still lives behind a fixed filename
     * whose content changes — a doorbell automation snapshots to the same `/local/…jpg` on every
     * ring — so a path-keyed cache would pin the first frame for the life of the process. Decoded to
     * [MAX_STILL_PX] rather than the avatar cap, since the card draws it full-width, not in a chip.
     */
    suspend fun fetchStillImage(path: String): Bitmap? = withContext(Dispatchers.IO) {
        val url = absoluteUrl(path) ?: return@withContext null
        runCatching {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("Cache-Control", "no-cache")
                .build()
            clientFor(url).newCall(req).execute().use { resp ->
                Log.i(CAM, "still($url) code=${resp.code}")
                if (resp.isSuccessful) resp.body?.bytes()?.let { decodeSampled(it, MAX_STILL_PX) } else null
            }
        }.getOrNull()
    }

    /**
     * Decode [bytes] downsampled so its longest edge is at most [maxPx]. HA entity_pictures are often
     * 500px+ but avatars draw at 14–64dp, so decoding full-res just wastes memory (~1 MB each, ×24
     * cached) and CPU; notification stills draw far larger and get a correspondingly higher cap.
     */
    private fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private companion object {
        const val CAM = "HaCamera"
        // Longest-edge cap for cached avatars; plenty for list icons and person-map markers.
        const val MAX_AVATAR_PX = 256
        // Longest-edge cap for notification stills. The card draws them up to 460dp wide — ~1840px
        // on a 4K panel (density 4) — so 1920 keeps them sharp at ~8 MB peak, while still bounding a
        // 4K camera snapshot that would otherwise decode to ~33 MB and risk an OOM on a TV stick.
        const val MAX_STILL_PX = 1920
    }
}

/** Represent an app-defined local camera as a synthetic camera [Entity] (domain "camera"). */
private fun LocalCamera.toEntity(): Entity = Entity(
    entityId = "camera.ta_$id",
    state = "streaming",
    friendlyName = name,
    attributes = JsonObject(buildMap {
        put("friendly_name", JsonPrimitive(name))
        put("ta_stream_url", JsonPrimitive(streamUrl))
        if (snapshotUrl.isNotBlank()) put("ta_snapshot_url", JsonPrimitive(snapshotUrl))
        put("ta_player", JsonPrimitive(player))
        if (refresh) put("ta_refresh", JsonPrimitive(true))
    }),
)

/** Represent an app-defined map card as a synthetic map [Entity] (domain "map"). */
private fun MapCard.toEntity(): Entity = Entity(
    entityId = "map.ta_$id",
    state = "${members.size}",
    friendlyName = name,
    attributes = JsonObject(buildMap {
        put("friendly_name", JsonPrimitive(name))
        put("ta_map", JsonPrimitive(true))
        put("ta_map_zoom", JsonPrimitive(mapZoom))
        put("ta_map_provider", JsonPrimitive(mapProvider))
        put("ta_map_legend", JsonPrimitive(showLegend))
        // Members carried inline so the overlay can plot them without re-reading settings.
        put(
            "ta_map_members",
            JsonArray(
                members.map { m ->
                    JsonObject(
                        mapOf(
                            "e" to JsonPrimitive(m.entityId),
                            "o" to JsonArray(m.options.map { JsonPrimitive(it) }),
                        ),
                    )
                },
            ),
        )
    }),
)
