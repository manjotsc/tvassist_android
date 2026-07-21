package com.tvassist.data.notify

import com.tvassist.data.web.HttpRequest
import com.tvassist.data.web.HttpResponse
import com.tvassist.data.web.TinyHttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The always-on, machine-facing HTTP server that receives notifications pushed from Home Assistant
 * (via the tv_assist integration or a rest_command) and feeds them to [NotificationStore].
 *
 *  POST /notify           JSON body or query params (see [fieldsToNotification])
 *  GET  /notify?message=…  same fields as query params
 *  POST /notify/clear?id=…  remove one (or all, if id omitted)
 *  POST /set/overlay        dim/clock/corner overlay control
 *  POST /notify_fixed       pinned "pill" widgets
 *  GET  /                   a small status page
 *
 * When [token] is non-blank, every state-changing endpoint requires a matching `?token=` / `X-Token`.
 */
class NotificationServer(
    port: Int = DEFAULT_PORT,
    private val store: NotificationStore,
    private val defaultDuration: () -> Int = { 8 },
    private val applyOverlay: (Map<String, String>) -> Unit = {},
    private val fixedStore: FixedNotificationStore? = null,
    private val token: () -> String = { "" },
    private val speakMode: () -> String = { "both" },
    private val speak: (List<String>, Map<String, String>) -> Unit = { _, _ -> },
    private val playSound: (Map<String, String>) -> Unit = {},
) : TinyHttpServer(port, "NotifyServer") {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun handle(req: HttpRequest): HttpResponse {
        if (req.method == "GET" && req.path == "/") return html(statusHtml())
        val required = token().trim()
        val provided = (req.headers["x-token"] ?: req.query["token"] ?: "")
        // Constant-time compare so a matching prefix can't be found by timing the response.
        val authorized = required.isEmpty() ||
            java.security.MessageDigest.isEqual(provided.toByteArray(), required.toByteArray())
        if (!authorized) return json("""{"ok":false,"error":"unauthorized"}""", 401)

        fun fields() = if (req.method == "POST") jsonToFields(req.body) + req.query else req.query
        return when {
            req.path == "/notify" && (req.method == "POST" || req.method == "GET") -> {
                val f = if (req.method == "POST") jsonToFields(req.body) else req.query
                val notif = fieldsToNotification(f)
                if (notif != null) {
                    store.show(notif)
                    // Lifecycle context so looping sound / repeating speech can stop when this exact
                    // notification instance leaves the screen (see KeepAliveService).
                    val ctx = mapOf(
                        "notif_id" to notif.id,
                        "notif_created" to notif.createdAt.toString(),
                        "notif_duration" to notif.durationSec.toString(),
                    )
                    // `sound:` plays an audio file alongside the notification; `speak: true` voices it.
                    if (!f["sound"].isNullOrBlank()) playSound(f + ctx)
                    if (isTruthy(f["speak"])) speakNotification(notif, f + ctx)
                    json("""{"ok":true,"id":"${notif.id}"}""")
                } else {
                    json("""{"ok":false,"error":"empty notification (needs a message, title, icon or media)"}""", 400)
                }
            }
            req.path == "/speak" && (req.method == "POST" || req.method == "GET") -> {
                val f = fields()
                if (f["message"].isNullOrBlank()) {
                    json("""{"ok":false,"error":"message required"}""", 400)
                } else {
                    // Standalone speak: single utterance, no notification to repeat against.
                    speak(listOf(f["message"].orEmpty()), f); json("""{"ok":true}""")
                }
            }
            req.path == "/play" && (req.method == "POST" || req.method == "GET") -> {
                val f = fields()
                val url = f["url"]?.takeIf { it.isNotBlank() } ?: f["sound"].orEmpty()
                if (url.isBlank()) {
                    json("""{"ok":false,"error":"url (or sound) required"}""", 400)
                } else {
                    playSound(f); json("""{"ok":true}""")
                }
            }
            req.path == "/set/overlay" && (req.method == "POST" || req.method == "GET") -> {
                applyOverlay(fields()); json("""{"ok":true}""")
            }
            req.path == "/notify/clear" -> {
                val id = fields()["id"].orEmpty()
                if (id.isBlank()) store.clearAll() else store.remove(id)
                json("""{"ok":true}""")
            }
            req.path == "/notify_fixed" && (req.method == "POST" || req.method == "GET") -> {
                val f = fields()
                val id = f["id"]?.takeIf { it.isNotBlank() }
                if (id == null) {
                    json("""{"ok":false,"error":"id is required"}""", 400)
                } else {
                    val pill = fieldsToFixed(id, f)
                    fixedStore?.show(pill)
                    // Pills can voice / sound like toasts; looping is bound to the pill's own life
                    // (until it expires or is cleared), capped for pinned-forever pills. `notif_kind`
                    // tells KeepAliveService to watch the fixed store, not the toast store.
                    val durationSec = if (pill.expiresAt <= 0L) 0
                        else ((pill.expiresAt - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(1)
                    val ctx = mapOf(
                        "notif_id" to id,
                        "notif_created" to pill.createdAt.toString(),
                        "notif_duration" to durationSec.toString(),
                        "notif_kind" to "pill",
                    )
                    if (!f["sound"].isNullOrBlank()) playSound(f + ctx)
                    if (isTruthy(f["speak"])) {
                        // Custom `speak_text` wins; otherwise voice the pill's label + message.
                        val text = f["speak_text"]?.takeIf { it.isNotBlank() }
                            ?: listOf(pill.label, pill.message).filter { it.isNotBlank() }.joinToString(" ")
                        if (text.isNotBlank()) speak(listOf(text), f + ctx)
                    }
                    json("""{"ok":true,"id":"$id"}""")
                }
            }
            req.path == "/notify_fixed/clear" -> {
                val id = fields()["id"].orEmpty()
                if (id.isBlank()) fixedStore?.clearAll() else fixedStore?.remove(id)
                json("""{"ok":true}""")
            }
            else -> json("""{"ok":false,"error":"not found"}""", 404)
        }
    }

    /**
     * Voice a notification's title and/or message according to the speak mode. The mode comes from
     * this TV's default ([speakMode]) unless the push overrides it with a `speak_mode` field:
     *  - "both"     one utterance, "Title. Message" (the original behaviour)
     *  - "separate" the title, then the message queued behind it — two utterances so they don't run
     *               together or overwrite each other
     *  - "message"  the message only
     *  - "title"    the title only
     */
    internal fun speakNotification(notif: TvNotification, f: Map<String, String>) {
        val mode = f["speak_mode"]?.takeIf { it.isNotBlank() }?.trim()?.lowercase() ?: speakMode()
        val title = notif.title.trim()
        val message = notif.message.trim()
        // Build the utterance sequence; "separate" is two segments, the rest a single one.
        val utterances = when (mode) {
            "title" -> listOf(title)
            "message" -> listOf(message)
            "separate" -> listOf(title, message)
            else -> listOf(listOf(title, message).filter { it.isNotBlank() }.joinToString(". "))
        }.filter { it.isNotBlank() }
        if (utterances.isNotEmpty()) speak(utterances, f)
    }

    internal fun fieldsToNotification(f: Map<String, String>): TvNotification? {
        fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { f[it]?.takeIf { v -> v.isNotBlank() } }.orEmpty()
        val message = f["message"].orEmpty()
        val icon = pick("icon", "icon_url", "iconUrl")
        val smallIcon = pick("small_icon", "smallIcon", "small_icon_url", "smallIconUrl")
        val hasContent = listOf(
            message, f["title"].orEmpty(), f["source"].orEmpty(), icon, smallIcon,
            f["image"].orEmpty(), f["camera"].orEmpty(),
            pick("camera_stream", "cameraStream"), pick("media_url", "mediaUrl"),
        ).any { it.isNotBlank() }
        if (!hasContent) return null
        return TvNotification(
            id = f["id"]?.takeIf { it.isNotBlank() } ?: System.nanoTime().toString(),
            message = message,
            title = f["title"].orEmpty(),
            source = f["source"].orEmpty(),
            source2 = pick("source2", "source_2", "subsource"),
            icon = icon,
            smallIcon = smallIcon,
            iconSize = pick("icon_size", "iconSize").toIntOrNull()?.coerceIn(16, 120) ?: 64,
            smallIconSize = pick("small_icon_size", "smallIconSize").toIntOrNull()?.coerceIn(10, 60) ?: 22,
            borderColor = pick("color", "border_color", "borderColor"),
            iconColor = pick("icon_color", "iconColor"),
            smallIconColor = pick("small_icon_color", "smallIconColor"),
            titleColor = pick("title_color", "titleColor"),
            sourceColor = pick("source_color", "sourceColor"),
            messageColor = pick("message_color", "messageColor"),
            backgroundColor = pick("background_color", "background", "backgroundColor"),
            backgroundOpacity = pick("background_opacity", "backgroundOpacity").toIntOrNull()?.coerceIn(0, 100) ?: -1,
            iconBackground = pick("icon_background", "iconBackground"),
            smallIconBackground = pick("small_icon_background", "smallIconBackground"),
            iconBackgroundOpacity = pick("icon_background_opacity", "iconBackgroundOpacity").toIntOrNull()?.coerceIn(0, 100) ?: -1,
            smallIconBackgroundOpacity = pick("small_icon_background_opacity", "smallIconBackgroundOpacity").toIntOrNull()?.coerceIn(0, 100) ?: -1,
            durationSec = f["duration"]?.toIntOrNull() ?: defaultDuration(),
            position = f["position"]?.takeIf { it.isNotBlank() } ?: "top-right",
            size = f["size"]?.takeIf { it.isNotBlank() } ?: "medium",
            image = f["image"].orEmpty(),
            camera = f["camera"].orEmpty(),
            cameraStream = pick("camera_stream", "cameraStream"),
            mediaUrl = pick("media_url", "mediaUrl"),
            mediaType = pick("media_type", "mediaType"),
            player = pick("player").ifBlank { "auto" },
            interactive = isTruthy(pick("interactive")),
            enlargeTimeout = pick("enlarge_timeout", "enlargeTimeout").toIntOrNull() ?: -1,
            flash = pick("flash"),
            flashColor = pick("flash_color", "flashColor"),
            flashSpeed = pick("flash_speed", "flashSpeed").ifBlank { "medium" },
        )
    }

    internal fun fieldsToFixed(id: String, f: Map<String, String>): FixedPill {
        fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { f[it]?.takeIf { v -> v.isNotBlank() } }.orEmpty()
        val expSec = pick("expiration", "expires", "duration").toIntOrNull() ?: 0
        return FixedPill(
            id = id,
            icon = pick("icon", "icon_url", "iconUrl"),
            message = f["message"].orEmpty(),
            shape = pick("shape").ifBlank { "rounded" },
            iconColor = pick("icon_color", "iconColor"),
            iconBackground = pick("icon_background", "iconBackground"),
            iconBackgroundOpacity = pick("icon_background_opacity", "iconBackgroundOpacity").toIntOrNull() ?: -1,
            messageColor = pick("message_color", "messageColor"),
            borderColor = pick("border_color", "borderColor"),
            backgroundColor = pick("background_color", "background", "backgroundColor"),
            backgroundOpacity = pick("background_opacity", "backgroundOpacity").toIntOrNull() ?: -1,
            position = pick("position", "corner").ifBlank { "top-right" },
            expiresAt = if (expSec > 0) System.currentTimeMillis() + expSec * 1000L else 0L,
            entity = pick("entity", "entity_id", "entityId"),
            attribute = pick("attribute", "attr"),
            label = pick("label"),
            flashBorderColor = pick("flash_border_color", "flash_color", "flashBorderColor", "flashColor"),
            flashIconColor = pick("flash_icon_color", "flashIconColor"),
            flashBorderType = pick("flash_border_type", "flashBorderType").ifBlank { "pulse" },
            flashIconType = pick("flash_icon_type", "flashIconType").ifBlank { "pulse" },
            flashIconSpeed = pick("flash_icon_speed", "flashIconSpeed"),
            flashBorderSpeed = pick("flash_border_speed", "flashBorderSpeed"),
        )
    }

    private fun isTruthy(v: String?): Boolean = v?.trim()?.lowercase() in setOf("1", "true", "yes", "on")

    private fun jsonToFields(body: String): Map<String, String> {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyMap()
        return obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }

    private fun statusHtml(): String = """
        <!doctype html><html><head><meta charset="utf-8"><title>TV Assist notifications</title>
        <style>body{font-family:system-ui,sans-serif;background:#101418;color:#eaeaea;padding:24px}
        code{background:#1b1f24;padding:2px 6px;border-radius:6px}</style></head><body>
        <h2>TV Assist — notifications</h2>
        <p>This TV is listening. Push a notification with:</p>
        <p><code>POST /notify</code> &nbsp; {"message":"Hello","title":"TV Assist","icon":"mdi:bell","duration":8}</p>
        <p><code>POST /notify/clear?id=…</code> to dismiss a persistent one.</p>
        </body></html>
    """.trimIndent()

    companion object {
        const val DEFAULT_PORT = 8455

        /** Best-effort LAN IPv4 for display in the UI. */
        fun localIp(): String? = TinyHttpServer.localIp()
    }
}
