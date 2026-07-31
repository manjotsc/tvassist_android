package com.tvassist.data.web

import com.tvassist.data.settings.LocalCamera

/**
 * One on-demand "Web setup" console (replaces the separate credential/camera/map servers). Serves a
 * PIN-gated dashboard from which a phone/laptop can configure the Home Assistant connection, cameras
 * and the Google Maps key. Started/stopped by a toggle; the PIN is shown on the TV. Human-facing and
 * ephemeral — distinct from the always-on, machine-facing NotificationServer.
 */
class SetupWebServer(
    port: Int = DEFAULT_PORT,
    sslContext: javax.net.ssl.SSLContext? = null,
    private val pin: () -> String,
    private val prefillUrl: () -> String,
    private val prefillVerifySsl: () -> Boolean,
    private val onCredentials: (url: String, token: String, verifySsl: Boolean) -> Unit,
    private val listCameras: () -> List<LocalCamera>,
    private val onSaveCamera: (LocalCamera) -> Unit,
    private val onDeleteCamera: (String) -> Unit,
    private val currentMapKey: () -> String,
    private val onSaveMapKey: (String) -> Unit,
    // "connected" | "connecting" | "failed" | "disconnected" — shown on the Connection page.
    private val connState: () -> String,
    /** Failure reason when [connState] is "failed"; blank otherwise. */
    private val connError: () -> String,
    // TV name/model, shown so you know which TV you're configuring.
    private val deviceName: () -> String,
    // Notification server: (enabled, port), the current push token, and a setter for it.
    private val notifInfo: () -> Pair<Boolean, Int>,
    private val notifToken: () -> String,
    private val onSaveNotifToken: (String) -> Unit,
    // Backup: returns the backup JSON for the given (includeSecrets, passphrase). Restore: applies the
    // uploaded JSON with the given passphrase, returning whether it succeeded. Blocking is fine — the
    // server runs on its own daemon thread.
    private val exportBackup: (includeSecrets: Boolean, passphrase: String) -> String,
    private val restoreBackup: (text: String, passphrase: String) -> Boolean,
    private val backupFileName: () -> String,
    private val onStop: () -> Unit,
) : TinyHttpServer(port, "SetupServer", sslContext) {

    // A random session token minted on a successful unlock (NOT the PIN itself), so the auth cookie
    // can't be reached by brute-forcing the PIN. Wrong PINs are counted and the console shuts itself
    // down after MAX_PIN_ATTEMPTS, making a brute-force infeasible within its short live window.
    @Volatile private var sessionToken: String? = null
    @Volatile private var failedAttempts = 0

    override fun handle(req: HttpRequest): HttpResponse {
        val pin = pin()
        val session = sessionToken
        val unlocked = pin.isNotEmpty() && session != null && req.cookie(PIN_COOKIE) == session

        // PIN gate.
        if (req.method == "POST" && req.path == "/unlock") {
            if (pin.isNotEmpty() && req.form()["pin"]?.trim() == pin) {
                failedAttempts = 0
                val token = java.util.UUID.randomUUID().toString()
                sessionToken = token
                return redirect(
                    "/",
                    listOf("Set-Cookie" to "$PIN_COOKIE=$token; Path=/; Max-Age=3600; HttpOnly; SameSite=Strict"),
                )
            }
            failedAttempts++
            if (failedAttempts >= MAX_PIN_ATTEMPTS) {
                onStop() // too many wrong PINs — shut the console down; the user restarts for a fresh PIN
                return html(stoppedHtml())
            }
            return html(gateHtml("That PIN didn't match — check the TV screen. (${MAX_PIN_ATTEMPTS - failedAttempts} tries left)"))
        }
        if (!unlocked) return html(gateHtml(null))

        return when {
            req.method == "POST" && req.path == "/stop" -> { onStop(); html(stoppedHtml()) }
            req.method == "GET" && req.path == "/" -> html(dashboardHtml())
            req.path == "/connection" -> connection(req)
            req.path == "/cameras" && req.method == "POST" -> saveCamera(req)
            req.path == "/cameras/delete" && req.method == "POST" -> {
                req.form()["id"]?.takeIf { it.isNotBlank() }?.let(onDeleteCamera); redirect("/cameras")
            }
            req.path == "/cameras" -> html(camerasHtml(null, req.query["edit"]))
            req.path == "/maps" -> maps(req)
            req.path == "/notifications" -> notifications(req)
            req.path == "/backup/download" && req.method == "POST" -> downloadBackup(req)
            req.path == "/backup" -> html(page("Backup", backupForm()))
            req.path == "/restore" && req.method == "POST" -> restore(req)
            req.path == "/restore" -> html(page("Restore", restoreForm(null)))
            else -> notFound()
        }
    }

    // ---- sections ----

    private fun connection(req: HttpRequest): HttpResponse {
        if (req.method == "POST") {
            val f = req.form()
            val url = f["url"]?.trim().orEmpty()
            val token = f["token"]?.trim().orEmpty()
            // An unchecked checkbox is simply absent from the POST body.
            val verifySsl = f["verify_ssl"] != null
            if (url.isNotEmpty() && token.isNotEmpty()) {
                onCredentials(url, token, verifySsl)
                // Credentials are applied asynchronously (save + WebSocket handshake), so rendering
                // the status here always caught it mid-flight — showing "Not connected" and an empty
                // URL a millisecond after submit, with no later render to correct it. Hand off to a
                // watch view that polls until the TV actually settles.
                return redirect("/connection?watch=1")
            }
            return html(page("Connection", """<div class="err">Fill in both fields.</div>${connectionForm()}"""))
        }
        if (req.query["watch"] == "1") return html(watchPage())
        return html(page("Connection", connectionForm()))
    }

    private fun saveCamera(req: HttpRequest): HttpResponse {
        val f = req.form()
        val name = f["name"]?.trim().orEmpty()
        val streamUrl = f["streamUrl"]?.trim().orEmpty()
        if (name.isNotEmpty() && streamUrl.isNotEmpty()) {
            onSaveCamera(
                LocalCamera(
                    id = f["id"]?.takeIf { it.isNotBlank() } ?: ("cam_" + System.currentTimeMillis().toString(36)),
                    name = name, streamUrl = streamUrl,
                    snapshotUrl = f["snapshotUrl"]?.trim().orEmpty(),
                    player = f["player"]?.trim()?.takeIf { it.isNotBlank() } ?: "auto",
                    refresh = f["refresh"] != null,
                ),
            )
        }
        return redirect("/cameras")
    }

    private fun maps(req: HttpRequest): HttpResponse {
        if (req.method == "POST") {
            val f = req.form()
            val newKey = f["key"]?.trim().orEmpty()
            // Blank submit KEEPS the existing key (the field is never pre-filled with the real key, so
            // we can't tell "unchanged" from "cleared"); clearing is an explicit checkbox.
            when {
                f["clear"] != null -> onSaveMapKey("")
                newKey.isNotEmpty() -> onSaveMapKey(newKey)
            }
            return html(page("Maps", """<div class="ok">Saved.</div>${mapsForm()}"""))
        }
        return html(page("Maps", mapsForm()))
    }

    private fun notifications(req: HttpRequest): HttpResponse {
        if (req.method == "POST") {
            val f = req.form()
            when {
                f["gen"] != null -> onSaveNotifToken(randomToken())
                f["clear"] != null -> onSaveNotifToken("")
                f["token"]?.trim()?.isNotEmpty() == true -> onSaveNotifToken(f["token"]!!.trim())
            }
            return html(page("Notifications", """<div class="ok">Saved.</div>${notificationsForm()}"""))
        }
        return html(page("Notifications", notificationsForm()))
    }

    private fun randomToken(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(24)

    private fun notificationsForm(): String {
        val (enabled, port) = notifInfo()
        val tok = notifToken()
        val addr = "http://${com.tvassist.data.notify.NotificationServer.localIp() ?: "<tv-ip>"}:$port"
        return """
        <p class="muted">${if (enabled) "The push server is <b>on</b>." else "The push server is <b>off</b> — turn it on in the TV's Notifications settings."}
          Home Assistant posts to:</p>
        <div class="ok" style="word-break:break-all">$addr/notify</div>
        <p class="muted">${if (tok.isNotBlank()) "A token is set (${escape(maskKey(tok))}); pushes must include <code>?token=…</code>." else "<b>No token — the server is open to anyone on your network.</b> Set or generate one, then add it to your HA pushes."}</p>
        <form method="post" action="/notifications">
          <label>Push token</label>
          <input name="token" autocapitalize="off" autocorrect="off" spellcheck="false"
            placeholder="${if (tok.isNotBlank()) "leave blank to keep current" else "paste one, or generate below"}" value="">
          <button type="submit">Save token</button>
        </form>
        <form method="post" action="/notifications" style="margin-top:10px">
          <input type="hidden" name="gen" value="1">
          <button type="submit" style="background:#1b2733;color:#9cc4ff">Generate a token</button>
        </form>
        ${if (tok.isNotBlank()) """<form method="post" action="/notifications" style="margin-top:10px"><input type="hidden" name="clear" value="1"><button class="danger" type="submit" style="width:100%">Clear token (open server)</button></form>""" else ""}
        """
    }

    /** first-2 + last-4, rest dotted (matches the on-device masked display). */
    private fun maskKey(s: String): String =
        if (s.length <= 6) "•".repeat(s.length)
        else s.take(2) + "•".repeat((s.length - 6).coerceAtMost(12)) + s.takeLast(4)

    // ---- HTML ----

    private fun gateHtml(error: String?): String = page(
        "Enter PIN",
        """
        ${if (error != null) "<div class=\"err\">${escape(error)}</div>" else ""}
        <p class="muted">Enter the 6-digit PIN shown on the bottom-left of the TV screen.</p>
        <form method="post" action="/unlock">
          <label>PIN</label>
          <input name="pin" inputmode="numeric" autocomplete="off" maxlength="6" placeholder="000000" style="letter-spacing:6px;text-align:center;font-size:22px">
          <button type="submit">Unlock</button>
        </form>
        """,
        home = false, showStop = false,
    )

    private fun stoppedHtml(): String = page(
        "Setup off",
        """<div class="ok">Web setup is now off on the TV. You can close this tab.</div>""",
        home = false, showStop = false,
    )

    private fun dashboardHtml(): String = page(
        "Setup",
        """
        <p class="muted">Configuring <b>${escape(deviceName())}</b>.</p>
        <a class="card" href="/connection"><b>Connection</b><span>Home Assistant URL &amp; token</span></a>
        <a class="card" href="/cameras"><b>Cameras</b><span>Add direct-URL (RTSP/HTTP) cameras</span></a>
        <a class="card" href="/notifications"><b>Notifications</b><span>Push server address &amp; token</span></a>
        <a class="card" href="/maps"><b>Maps</b><span>Google Maps API key</span></a>
        <a class="card" href="/backup"><b>Backup &amp; restore</b><span>Download or upload this TV's settings</span></a>
        """,
        home = false,
    )

    // ---- backup / restore ----

    /** POST /backup/download → returns the backup JSON as a file attachment. */
    private fun downloadBackup(req: HttpRequest): HttpResponse {
        val f = req.form()
        val includeSecrets = f["secrets"] != null
        val pass = f["pass"]?.trim().orEmpty()
        if (includeSecrets && pass.isEmpty()) {
            return html(page("Backup", """<div class="err">Enter a passphrase to protect the included secrets.</div>${backupForm()}"""))
        }
        val json = exportBackup(includeSecrets, pass)
        return HttpResponse(
            body = json,
            status = 200,
            contentType = "application/json; charset=utf-8",
            extraHeaders = listOf("Content-Disposition" to "attachment; filename=\"${backupFileName()}\""),
        )
    }

    /** POST /restore → applies uploaded backup JSON (read client-side into the "data" field). */
    private fun restore(req: HttpRequest): HttpResponse {
        val f = req.form()
        val data = f["data"].orEmpty()
        val pass = f["pass"]?.trim().orEmpty()
        if (data.isBlank()) return html(page("Restore", restoreForm("Choose a backup file first.")))
        val ok = runCatching { restoreBackup(data, pass) }.getOrDefault(false)
        return if (ok) {
            html(page("Restore", """<div class="ok">Restored — your TV has applied the backup and is reconnecting.</div>"""))
        } else {
            html(page("Restore", restoreForm("Couldn't restore — the file may be invalid, or the passphrase is wrong.")))
        }
    }

    private fun backupForm(): String = """
        <p class="muted">Download this TV's settings as a file. With secrets, the HA token &amp; keys are
          encrypted with your passphrase (you'll need it to restore).</p>
        <form method="post" action="/backup/download">
          <label style="display:flex;align-items:center;gap:8px;font-weight:normal">
            <input type="checkbox" name="secrets" value="1" id="sec" style="width:auto" onchange="document.getElementById('pw').style.display=this.checked?'block':'none'">
            Include secrets (HA token, notification token, Maps key)
          </label>
          <div id="pw" style="display:none">
            <label>Backup passphrase</label>
            <input name="pass" type="password" autocomplete="new-password" placeholder="Protects the secrets">
          </div>
          <button type="submit">Download backup</button>
        </form>
        <div class="formcard" style="margin-top:20px">
          <a class="back" href="/restore">Restore from a file →</a>
        </div>
    """

    private fun restoreForm(msg: String?): String = """
        ${if (msg != null) "<div class=\"err\">${escape(msg)}</div>" else ""}
        <p class="muted">Upload a backup file to overwrite this TV's settings. If it has encrypted
          secrets, enter the same passphrase used when it was created.</p>
        <form method="post" action="/restore" id="rf">
          <label>Backup file</label>
          <input type="file" id="file" accept=".json,application/json">
          <label>Passphrase (if the backup has encrypted secrets)</label>
          <input name="pass" type="password" autocomplete="off" placeholder="Leave blank if none">
          <input type="hidden" name="data" id="data">
          <button type="submit">Restore to this TV</button>
        </form>
        <script>
          // Read the chosen file client-side into a hidden field, so the server needs no multipart parsing.
          document.getElementById('rf').addEventListener('submit', function(e){
            e.preventDefault();
            var fi = document.getElementById('file');
            if (!fi.files.length) { alert('Choose a backup file first.'); return; }
            var fr = new FileReader();
            fr.onload = function(){ document.getElementById('data').value = fr.result; e.target.submit(); };
            fr.readAsText(fi.files[0]);
          });
        </script>
    """

    /**
     * Status-only view shown after submitting credentials, refreshing until the TV settles.
     *
     * Deliberately carries no form: the periodic reload would wipe anything half-typed. It also
     * keeps the retry countdown current, which a one-shot render can't.
     */
    private fun watchPage(): String {
        val state = connState()
        val settled = state == "connected"
        val (cls, label) = when (state) {
            "connected" -> "ok" to "Connected"
            "connecting" -> "muted" to "Connecting…"
            "failed" -> "err" to escape(connError().ifBlank { "Not connected" })
            else -> "muted" to "Sent to your TV — waiting for it to connect…"
        }
        val url = prefillUrl()
        val suffix = if (url.isNotBlank()) " · ${escape(url)}" else ""
        return page(
            "Connection",
            """
            <div class="$cls">$label$suffix</div>
            <p class="muted">${if (settled) "You can close this page." else "Updating automatically…"}</p>
            <form method="get" action="/connection"><button type="submit">Back to connection settings</button></form>
            """,
            // Stop reloading once connected; keep polling while connecting or retrying.
            refreshSecs = if (settled) 0 else 3,
        )
    }

    private fun connectionForm(): String {
        val (cls, label) = when (connState()) {
            "connected" -> "ok" to "Connected"
            "connecting" -> "muted" to "Connecting…"
            // Escaped: the reason can carry a server-supplied message (e.g. HA's auth_invalid text).
            "failed" -> "err" to escape(connError().ifBlank { "Not connected" })
            else -> "err" to "Not connected"
        }
        val url = prefillUrl()
        val statusLine = if (cls == "muted") {
            """<p class="muted">$label${if (url.isNotBlank()) " · ${escape(url)}" else ""}</p>"""
        } else {
            """<div class="$cls">$label${if (url.isNotBlank()) " · ${escape(url)}" else ""}</div>"""
        }
        return """
        $statusLine
        <form method="post" action="/connection">
          <label>Home Assistant URL</label>
          <input name="url" inputmode="url" autocapitalize="off" autocorrect="off"
            placeholder="http://homeassistant.local:8123" value="${escape(prefillUrl())}">
          <label>Long-lived access token</label>
          <textarea name="token" autocapitalize="off" autocorrect="off" placeholder="Paste token from your HA profile"></textarea>
          <label class="check"><input type="checkbox" name="verify_ssl" value="1"${if (prefillVerifySsl()) " checked" else ""}> Verify TLS certificate</label>
          <p class="muted">Uncheck only for a self-signed certificate. Ignored unless Home Assistant
            is on your local network — a public address always requires a valid certificate.</p>
          <button type="submit">Connect</button>
        </form>
    """
    }

    private fun camerasHtml(msg: String?, editId: String? = null): String {
        val cams = listCameras()
        val editing = editId?.let { id -> cams.firstOrNull { it.id == id } }
        val rows = if (cams.isEmpty()) "<p class=\"muted\">No cameras yet.</p>" else cams.joinToString("") { c ->
            val isEditing = c.id == editing?.id
            """
            <div class="row"${if (isEditing) " style=\"outline:1px solid #1565c0\"" else ""}>
              <div class="rowmain"><b>${escape(c.name)}</b><span class="url">${escape(c.streamUrl)}</span></div>
              <div style="display:flex;gap:6px">
                <a class="btn-sm" href="/cameras?edit=${escape(c.id)}">Edit</a>
                <form method="post" action="/cameras/delete" onsubmit="return confirm('Delete ${escape(c.name)}?')">
                  <input type="hidden" name="id" value="${escape(c.id)}">
                  <button class="danger" type="submit">Delete</button>
                </form>
              </div>
            </div>
            """
        }
        fun opt(v: String, label: String) =
            """<option value="$v"${if ((editing?.player ?: "auto") == v) " selected" else ""}>$label</option>"""
        return page(
            "Cameras",
            """
            ${if (msg != null) "<div class=\"ok\">${escape(msg)}</div>" else ""}
            $rows
            <div class="formcard">
              <h3 style="margin:0 0 4px">${if (editing != null) "Edit camera" else "Add a camera"}</h3>
              <form method="post" action="/cameras">
                <input type="hidden" name="id" value="${escape(editing?.id ?: "")}">
                <label>Name</label>
                <input name="name" placeholder="Front door" autocapitalize="off" value="${escape(editing?.name ?: "")}">
                <label>Stream URL</label>
                <input name="streamUrl" inputmode="url" autocapitalize="off" autocorrect="off"
                  placeholder="rtsp://user:pass@192.168.1.20:554/stream" value="${escape(editing?.streamUrl ?: "")}">
                <label>Snapshot URL (optional)</label>
                <input name="snapshotUrl" inputmode="url" autocapitalize="off" autocorrect="off"
                  placeholder="http://192.168.1.20/snapshot.jpg" value="${escape(editing?.snapshotUrl ?: "")}">
                <label>Player</label>
                <select name="player">
                  ${opt("auto", "Auto (ExoPlayer; VLC for RTSP)")}
                  ${opt("exoplayer", "ExoPlayer")}
                  ${opt("vlc", "VLC")}
                </select>
                <label style="display:flex;align-items:center;gap:8px;margin-top:8px">
                  <input type="checkbox" name="refresh" value="1" style="width:auto"${if (editing?.refresh == true) " checked" else ""}>
                  Keep refreshing (reload when the clip ends — for rolling-clip cameras like Québec 511)
                </label>
                <button type="submit">${if (editing != null) "Save changes" else "Add camera"}</button>
                ${if (editing != null) "<a class=\"back\" href=\"/cameras\" style=\"display:block;text-align:center;margin-top:12px\">Cancel</a>" else ""}
              </form>
            </div>
            """,
        )
    }

    private fun mapsForm(): String {
        val key = currentMapKey()
        val hasKey = key.isNotBlank()
        return """
        <p class="muted">${if (hasKey) "A key is set (Google tiles): ${escape(maskKey(key))}." else "No key set (using OpenStreetMap)."}
          Enable the "Map Tiles API" on the key. Paste a new key to replace it; leave blank to keep it.</p>
        <form method="post" action="/maps">
          <label>Google Maps API key</label>
          <input name="key" autocapitalize="off" autocorrect="off" spellcheck="false"
            placeholder="${if (hasKey) "leave blank to keep current" else "AIza…"}" value="">
          ${if (hasKey) """<label style="font-weight:normal"><input type="checkbox" name="clear" value="1"> Clear the saved key</label>""" else ""}
          <button type="submit">Save key</button>
        </form>
        """
    }

    /** The TVAssist toggle mark, inline so the console needs no external assets. */
    private fun logoSvg(size: Int = 30): String =
        """<svg width="$size" height="$size" viewBox="0 0 100 100" aria-hidden="true" style="flex:none">
          <defs><linearGradient id="tvlg" x1="0" y1="0" x2="0.7" y2="1">
            <stop offset="0" stop-color="#5FD8FF"/><stop offset="1" stop-color="#0E9AD6"/></linearGradient></defs>
          <rect width="100" height="100" rx="22" fill="url(#tvlg)"/>
          <path fill="#fff" d="M35,35 H65 A15,15 0 0 1 80,50 A15,15 0 0 1 65,65 H35 A15,15 0 0 1 20,50 A15,15 0 0 1 35,35 Z"/>
          <circle cx="65" cy="50" r="12" fill="#0E9AD6"/>
        </svg>"""

    /**
     * Shared page shell. [home] adds a back link; [showStop] adds the "turn off" footer button;
     * [refreshSecs] > 0 adds a meta refresh — only ever used on views without a form to fill in,
     * since a reload would wipe half-typed input.
     */
    private fun page(
        title: String,
        bodyHtml: String,
        home: Boolean = true,
        showStop: Boolean = true,
        refreshSecs: Int = 0,
    ): String = """
        <!doctype html><html lang="en"><head>
        <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        ${if (refreshSecs > 0) """<meta http-equiv="refresh" content="$refreshSecs">""" else ""}
        <title>TVAssist — $title</title>
        <style>
          :root { color-scheme: dark; }
          body { margin:0; font-family:system-ui,Segoe UI,Roboto,sans-serif; background:#101418; color:#eaeaea; }
          .wrap { max-width:520px; margin:0 auto; padding:24px; }
          h1 { font-size:22px; margin:0; }
          h1 .sub { color:#8a94a3; font-weight:400; }
          .brand { display:flex; align-items:center; gap:10px; margin:2px 0 10px; }
          a.back { color:#8ab4f8; text-decoration:none; font-size:14px; }
          p.muted { color:#8a94a3; }
          label { display:block; margin:14px 0 6px; font-size:14px; color:#bbb; }
          input, textarea, select { width:100%; box-sizing:border-box; padding:12px 14px; border-radius:10px;
            border:1px solid #333; background:#1b1f24; color:#fff; font-size:16px; }
          textarea { min-height:90px; resize:vertical; }
          /* Checkbox rows opt out of the full-width block styling above, which would otherwise
             stretch the box across the form and drop its caption onto the next line. */
          label.check { display:flex; align-items:center; gap:10px; margin:18px 0 4px;
            font-size:15px; color:#e6e6e6; cursor:pointer; }
          label.check input[type=checkbox] { width:18px; height:18px; flex:0 0 auto;
            margin:0; padding:0; accent-color:#1565c0; }
          label.check + p.muted { margin:0 0 4px; font-size:13px; }
          button { margin-top:20px; width:100%; padding:14px; border:0; border-radius:10px;
            background:#1565c0; color:#fff; font-size:17px; font-weight:600; cursor:pointer; }
          button.danger { width:auto; margin:0; padding:8px 12px; background:#4a2020; color:#ff8a80; font-size:14px; }
          a.btn-sm { display:inline-flex; align-items:center; padding:8px 12px; border-radius:10px; background:#1b2733;
            color:#9cc4ff; text-decoration:none; font-size:14px; }
          button.stop { margin-top:28px; background:#3a2323; color:#ffb4a8; }
          .ok { background:#1d3a24; color:#8be0a0; padding:10px 14px; border-radius:10px; margin-bottom:12px; }
          .err { background:#3a1d1d; color:#ff8a80; padding:10px 14px; border-radius:10px; margin-bottom:12px; }
          a.card { display:flex; flex-direction:column; text-decoration:none; background:#161b21; color:#eaeaea;
            border-radius:12px; padding:16px; margin-top:10px; }
          a.card b { font-size:16px; } a.card span { color:#8a94a3; font-size:13px; margin-top:2px; }
          .row { display:flex; align-items:center; justify-content:space-between; gap:10px; padding:12px;
            background:#161b21; border-radius:12px; margin-bottom:8px; }
          .rowmain { display:flex; flex-direction:column; overflow:hidden; }
          .rowmain .url { color:#888; font-size:12px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
          .row form { margin:0; }
          .formcard { background:#161b21; border-radius:14px; padding:16px; margin-top:16px; }
        </style></head><body><div class="wrap">
          ${if (home) "<a class=\"back\" href=\"/\">← Setup</a>" else ""}
          <div class="brand">${logoSvg()}<h1>TVAssist<span class="sub"> · $title</span></h1></div>
          $bodyHtml
          ${if (showStop) "<form method=\"post\" action=\"/stop\"><button class=\"stop\" type=\"submit\">Turn off web setup on the TV</button></form>" else ""}
        </div></body></html>
    """.trimIndent()

    companion object {
        const val DEFAULT_PORT = 8484
        const val PIN_COOKIE = "tvpin"
        // Shut the console down after this many wrong PINs to defeat brute-force of the gate.
        const val MAX_PIN_ATTEMPTS = 10
    }
}
