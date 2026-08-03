package com.tvassist.data.ha

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Talks to a Home Assistant instance over its WebSocket API
 * (https://developers.home-assistant.io/docs/api/websocket).
 *
 * Lifecycle: [connect] opens a socket, authenticates with the long-lived token,
 * loads all states, then subscribes to `state_changed` events. Live entity data is
 * exposed through [entities]; connection status through [connectionState].
 */
class HaWebSocketClient {

    private val json = Json { ignoreUnknownKeys = true }
    private fun httpBuilder() = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS)

    private val httpStrict = httpBuilder().build()
    // Built only if the user actually turns verification off for a private host (see [InsecureTls]).
    private val httpRelaxed by lazy { InsecureTls.relax(httpBuilder()).build() }

    @Volatile
    private var relaxTls = false
    private val http: OkHttpClient get() = if (relaxTls) httpRelaxed else httpStrict

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Backing map updated in place per event (O(1)); snapshots are published to [entities]
    // on a throttle so a burst of HA state_changed events doesn't re-copy/re-sort 1000s of
    // entities (and thrash the GC) on every single update.
    private val entityMap = LinkedHashMap<String, Entity>()
    private val publishScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var publishScheduled = false

    private val _entities = MutableStateFlow<List<Entity>>(emptyList())
    val entities: StateFlow<List<Entity>> = _entities.asStateFlow()

    private fun publishNow() {
        _entities.value = synchronized(entityMap) { entityMap.values.toList() }
    }

    /** Coalesce bursts: publish at most once per [PUBLISH_THROTTLE_MS]. */
    private fun schedulePublish() {
        if (publishScheduled) return
        publishScheduled = true
        publishScope.launch {
            delay(PUBLISH_THROTTLE_MS)
            publishScheduled = false
            publishNow()
        }
    }

    // The socket for the current connection attempt. Callbacks from any other (stale)
    // socket are ignored so a superseded connection can't clobber live state.
    // Read on the OkHttp reader thread but written from caller-thread coroutines, so these must
    // be @Volatile for the reader to observe fresh values (avoid dropping messages / stale token).
    @Volatile private var currentSocket: WebSocket? = null
    private val msgId = AtomicInteger(0)
    @Volatile private var token: String = ""

    // Reconnect state: remember the desired target so a dropped connection can be re-established
    // with exponential backoff. [deliberate] suppresses reconnect after an explicit disconnect().
    @Volatile private var lastUrl: String? = null
    @Volatile private var lastToken: String? = null
    @Volatile private var deliberate = false
    // Set when HA rejects our token: suppresses reconnect so a bad credential doesn't loop forever
    // re-sending the same token. Cleared on the next explicit connect() (i.e. a fresh credential).
    @Volatile private var authFailed = false
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var reconnectAttempt = 0

    // Command ids we issue during setup so we can recognise their replies.
    @Volatile private var getStatesId = -1

    /**
     * Why the last attempt failed, kept across retries so the reason stays on screen while the
     * backoff runs. Null means "no failure yet" — a fresh attempt, which may show "Connecting".
     */
    @Volatile private var lastFailure: String? = null
    @Volatile private var subscribeId = -1

    // Only these entities are kept/processed (the imported pool). Empty = none tracked.
    @Volatile
    private var trackedIds: Set<String> = emptySet()

    // Pending one-shot requests (camera stream / full state fetch), keyed by message id so
    // concurrent callers don't clobber each other and each reply is routed to its own waiter.
    // The callback receives the reply's `result` element, or null if the socket drops first.
    private val pendingResults = ConcurrentHashMap<Int, (JsonElement?) -> Unit>()

    /** Asks HA for an HLS stream path for a camera (e.g. /api/hls/<token>/master.m3u8). */
    suspend fun requestCameraStreamPath(entityId: String): String? = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val ws = currentSocket
            if (ws == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val id = msgId.incrementAndGet()
            pendingResults[id] = { result ->
                val path = (result as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                if (cont.isActive) cont.resume(path)
            }
            cont.invokeOnCancellation { pendingResults.remove(id) }
            ws.send(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("id", id)
                        put("type", "camera/stream")
                        put("entity_id", entityId)
                        put("format", "hls")
                    },
                ),
            )
        }
    }

    /** Sets which entities to track; refreshes their states immediately if connected. */
    fun setTrackedIds(ids: Set<String>) {
        trackedIds = ids
        currentSocket?.let { sendGetStates(it) }
    }

    private fun sendGetStates(ws: WebSocket) {
        getStatesId = msgId.incrementAndGet()
        ws.send(
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject { put("id", getStatesId); put("type", "get_states") },
            ),
        )
    }

    /** One-off fetch of ALL entities (for the import picker); not added to the tracked map. */
    suspend fun fetchAllStates(): List<Entity> = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
        suspendCancellableCoroutine<List<Entity>> { cont ->
            val ws = currentSocket
            if (ws == null) {
                cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            val id = msgId.incrementAndGet()
            pendingResults[id] = { result ->
                val all = (result as? JsonArray)?.mapNotNull { Entity.fromStateJson(it.jsonObject) }.orEmpty()
                if (cont.isActive) cont.resume(all)
            }
            cont.invokeOnCancellation { pendingResults.remove(id) }
            ws.send(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject { put("id", id); put("type", "get_states") },
                ),
            )
        }
    }.orEmpty()

    /** Resumes every in-flight one-shot request with null so callers don't hang after a drop. */
    private fun failPendingResults() {
        for (id in pendingResults.keys.toList()) {
            pendingResults.remove(id)?.invoke(null)
        }
    }

    @Synchronized
    fun connect(baseUrl: String, accessToken: String, relaxTls: Boolean = false) {
        // Idempotent: skip if we're already (re)connecting to the same target — avoids the
        // connect→disconnect→reconnect churn when several callers connect on launch. The TLS mode is
        // part of the target: flipping the verify toggle must force a genuine reconnect.
        if (baseUrl == lastUrl && accessToken == lastToken && relaxTls == this.relaxTls &&
            currentSocket != null &&
            (_connectionState.value is ConnectionState.Connected || _connectionState.value is ConnectionState.Connecting)
        ) {
            return
        }
        this.relaxTls = relaxTls
        lastUrl = baseUrl
        lastToken = accessToken
        deliberate = false
        authFailed = false // a fresh credential clears the bad-token reconnect suppression
        lastFailure = null // user-initiated attempt: start clean so it shows "Connecting"
        reconnectAttempt = 0
        reconnectJob?.cancel()
        openSocket(baseUrl, accessToken)
    }

    /** Opens a socket (closing any current one) without changing reconnect intent. */
    private fun openSocket(baseUrl: String, accessToken: String) {
        currentSocket?.close(1000, "reconnect")
        currentSocket = null
        // Fail any leftover one-shot waiters from the previous connection so their (now stale)
        // ids can't be re-matched by a low id after msgId is reset below.
        failPendingResults()
        getStatesId = -1
        subscribeId = -1
        token = accessToken
        val wsUrl = toWebSocketUrl(baseUrl)
        if (wsUrl == null) {
            _connectionState.value = ConnectionState.Failed("Invalid URL: $baseUrl")
            return
        }
        Log.i(TAG, "connect -> $wsUrl")
        synchronized(entityMap) { entityMap.clear() }
        _entities.value = emptyList()
        // Only announce "Connecting" on a fresh attempt. On a retry the previous failure reason must
        // survive — overwriting it here meant every error flashed for one backoff interval and then
        // sat as a permanent, uninformative "Connecting" that looked like progress.
        if (lastFailure == null) _connectionState.value = ConnectionState.Connecting
        msgId.set(0)
        val request = Request.Builder().url(wsUrl).build()
        currentSocket = http.newWebSocket(request, listener)
    }

    @Synchronized
    fun disconnect() {
        deliberate = true
        lastFailure = null // a deliberate close isn't a failure; don't let it colour a later dial
        reconnectJob?.cancel()
        reconnectJob = null
        currentSocket?.close(1000, "client disconnect")
        currentSocket = null
        failPendingResults()
        _connectionState.value = ConnectionState.Disconnected
    }

    /** Reconnect with exponential backoff (1,2,4,8,16,30s) after an unexpected drop. */
    @Synchronized
    private fun scheduleReconnect() {
        if (deliberate || authFailed) return
        val url = lastUrl ?: return
        val tok = lastToken ?: return
        reconnectJob?.cancel()
        reconnectAttempt++
        val backoff = (1000L shl (reconnectAttempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
        Log.i(TAG, "reconnect #$reconnectAttempt in ${backoff}ms")
        // Keep the reason visible and say what happens next, rather than silently spinning.
        lastFailure?.let {
            _connectionState.value = ConnectionState.Failed("$it · retrying in ${backoff / 1000}s")
        }
        reconnectJob = publishScope.launch {
            delay(backoff)
            synchronized(this@HaWebSocketClient) { if (!deliberate) openSocket(url, tok) }
        }
    }

    /**
     * Calls a HA service, e.g. domain="light", service="toggle". [data] becomes the
     * `service_data` payload (e.g. {"brightness_pct": 60}) for parameterised controls.
     */
    fun callService(
        domain: String,
        service: String,
        entityId: String,
        data: Map<String, JsonElement>? = null,
    ) {
        val ws = currentSocket ?: return
        val payload = buildJsonObject {
            put("id", msgId.incrementAndGet())
            put("type", "call_service")
            put("domain", domain)
            put("service", service)
            if (!data.isNullOrEmpty()) {
                put("service_data", buildJsonObject { data.forEach { (k, v) -> put(k, v) } })
            }
            put("target", buildJsonObject { put("entity_id", entityId) })
        }
        ws.send(json.encodeToString(JsonObject.serializer(), payload))
    }

    /** Convenience: toggle a toggleable entity using its domain's toggle service. */
    fun toggle(entity: Entity) {
        val service = when (entity.domain) {
            "scene", "script" -> "turn_on"
            "cover" -> if (entity.isOn) "close_cover" else "open_cover"
            "lock" -> if (entity.isLocked) "unlock" else "lock"
            else -> "toggle"
        }
        callService(entity.domain, service, entity.entityId)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== currentSocket) return
            Log.i(TAG, "socket open (HTTP ${response.code}) — authenticating")
            _connectionState.value = ConnectionState.Authenticating
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== currentSocket) return
            try {
                handleMessage(webSocket, json.parseToJsonElement(text).jsonObject)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse HA message", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== currentSocket) return // stale socket being torn down
            Log.w(TAG, "WebSocket failure", t)
            val reason = failureReason(t)
            lastFailure = reason
            _connectionState.value = ConnectionState.Failed(reason)
            failPendingResults()
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== currentSocket) return
            Log.i(TAG, "socket closed (code=$code${if (reason.isNotBlank()) ", $reason" else ""})")
            if (_connectionState.value !is ConnectionState.Failed) {
                _connectionState.value = ConnectionState.Disconnected
            }
            failPendingResults()
            scheduleReconnect()
        }
    }

    /**
     * Turns a connection exception into something a person can act on.
     *
     * Raw OkHttp/JSSE messages are useless on a TV — a self-signed certificate surfaces as
     * "Trust anchor for certification path not found", which tells the user nothing about the
     * setting that would fix it. Walks the cause chain, because the interesting exception is
     * usually wrapped a level or two down.
     */
    internal fun failureReason(t: Throwable): String {
        var c: Throwable? = t
        while (c != null) {
            when (c) {
                is javax.net.ssl.SSLPeerUnverifiedException ->
                    return "Certificate doesn't match this address — check the URL, or turn off " +
                        "Verify certificate in Settings → Security"
                is java.security.cert.CertificateException,
                is javax.net.ssl.SSLHandshakeException ->
                    return "Certificate not trusted — if Home Assistant uses a self-signed " +
                        "certificate, turn off Verify certificate in Settings → Security"
                is java.net.UnknownHostException ->
                    return "Can't find that address — check the Home Assistant URL"
                is java.net.ConnectException ->
                    return "Can't reach Home Assistant — check it's running and the port is right"
                is java.net.SocketTimeoutException ->
                    return "Timed out reaching Home Assistant"
                is javax.net.ssl.SSLException ->
                    return "Secure connection failed — is Home Assistant really using https?"
            }
            c = c.cause
        }
        return t.message ?: "Connection failed"
    }

    private fun handleMessage(ws: WebSocket, msg: JsonObject) {
        when (msg["type"]?.jsonPrimitive?.contentOrNull) {
            "auth_required" -> {
                Log.i(TAG, "auth_required — sending token")
                val auth = buildJsonObject {
                    put("type", "auth")
                    put("access_token", token)
                }
                ws.send(json.encodeToString(JsonObject.serializer(), auth))
            }

            "auth_ok" -> {
                Log.i(TAG, "auth_ok — connected, requesting states")
                lastFailure = null // recovered: a later retry should show "Connecting" again
                _connectionState.value = ConnectionState.Connected
                reconnectAttempt = 0 // successful connection resets the backoff
                sendGetStates(ws)
                subscribeId = msgId.incrementAndGet()
                ws.send(
                    json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("id", subscribeId)
                            put("type", "subscribe_events")
                            put("event_type", "state_changed")
                        },
                    ),
                )
            }

            "auth_invalid" -> {
                val message = msg["message"]?.jsonPrimitive?.contentOrNull ?: "Invalid token"
                Log.w(TAG, "auth_invalid: $message")
                // Stop the reconnect loop: re-sending the same rejected token would just fail again.
                authFailed = true
                reconnectJob?.cancel()
                failPendingResults()
                _connectionState.value = ConnectionState.Failed(message)
            }

            "result" -> {
                val id = msg["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val pending = if (id != null) pendingResults.remove(id) else null
                when {
                    pending != null -> pending(msg["result"])
                    id == getStatesId -> {
                        val arr = msg["result"] as? JsonArray ?: return
                        val tracked = trackedIds
                        // Distinguishes "HA sent nothing" from "nothing imported yet" — the latter
                        // looks identical in the UI (Connected, but zero entities).
                        Log.i(TAG, "get_states: ${arr.size} from HA, ${tracked.size} imported/tracked")
                        synchronized(entityMap) {
                            entityMap.clear()
                            for (el in arr) {
                                val obj = el.jsonObject
                                val eid = obj["entity_id"]?.jsonPrimitive?.contentOrNull ?: continue
                                if (eid !in tracked) continue // keep only imported entities
                                Entity.fromStateJson(obj)?.let { entityMap[it.entityId] = it }
                            }
                        }
                        publishNow() // initial/refresh load: publish immediately
                    }
                }
            }

            "event" -> {
                val data = msg["event"]?.jsonObject?.get("data")?.jsonObject ?: return
                val entityId = data["entity_id"]?.jsonPrimitive?.contentOrNull ?: return
                // Skip entities we don't track (cheap id check) — this is the perf win.
                if (entityId !in trackedIds) return
                val newState = data["new_state"]?.jsonObject
                if (newState != null) {
                    Entity.fromStateJson(newState)?.let { updated ->
                        synchronized(entityMap) { entityMap[updated.entityId] = updated }
                        // Location-bearing entities (people/device-trackers) publish immediately so a
                        // map view reflects HA's new position with no coalescing delay; everything else
                        // stays on the throttle to batch bursts.
                        if (updated.latitude != null) publishNow() else schedulePublish()
                    }
                } else {
                    synchronized(entityMap) { entityMap.remove(entityId) }
                    schedulePublish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "HaWebSocketClient"
        // Coalesce bursts of state_changed events. Higher = fewer recompositions (smoother
        // UI on large/busy HA instances) at the cost of a little state-update latency.
        private const val PUBLISH_THROTTLE_MS = 400L
        // Cap for one-shot requests (stream path / full fetch) so a silent HA never hangs the caller.
        private const val REQUEST_TIMEOUT_MS = 15_000L

        /** Normalises a base URL like `http://homeassistant.local:8123` to its WS endpoint. */
        fun toWebSocketUrl(baseUrl: String): String? {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = when {
                trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
                trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
                trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
                else -> "ws://$trimmed"
            }
            return "$withScheme/api/websocket"
        }
    }
}
