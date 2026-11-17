package com.tvassist.data.logs

import com.tvassist.data.ha.REDACTED
import com.tvassist.data.ha.URL_USERINFO

/**
 * Scrubs one log line before it is stored anywhere — memory or disk — so nothing that leaves the TV
 * can be used to act as it, or point at the household's own hosts.
 *
 * Two kinds of thing go:
 *
 * - **Credentials**: literal [secrets] (the HA token, the push token, the Maps key), `user:password@`
 *   in any URL, `Bearer …`, credential-ish query parameters, signed `tts_proxy` paths, and
 *   credential fields in JSON. These patterns came over unchanged from the parked log viewer
 *   (`logproject/`), where they were measured against real captures.
 * - **Configured hosts**: each host the user typed in — Home Assistant's, every camera's — becomes a
 *   stable placeholder such as `<ha-host>` or `<camera-2-host>`. Logs get posted to public GitHub
 *   issues, and reporters were editing addresses out by hand. Stable rather than a blanket
 *   `«redacted»`, so "camera 2 timed out while HA was fine" can still be read off the file.
 *
 * Everything else — entity ids, error text, other hosts — is left alone: over-redacting produces a
 * file nobody can diagnose from. In particular a hostname's *resolved address* (OkHttp prints
 * `host/192.168.1.20`) is not hidden; a LAN address says little, and guessing which addresses
 * belong to which host would need DNS lookups on the logging path.
 */
class LogRedactor(
    secrets: Collection<String> = emptyList(),
    /** Host → placeholder, from [hostPlaceholders]. */
    hosts: Map<String, String> = emptyMap(),
) {
    private val secrets = secrets.filter { it.length >= MIN_SECRET_LENGTH }.distinct()

    // Longest first, so `cam.ha.lan` is replaced before a configured `ha.lan` could claim part of it.
    private val hostRules: List<Pair<Regex, String>> = hosts.entries
        // A host of `ha` or `nas` would turn every standalone "ha"/"nas" in the file into a
        // placeholder, and a name that short identifies nobody anyway.
        .filter { it.key.length >= MIN_HOST_LENGTH }
        .sortedByDescending { it.key.length }
        .map { (host, placeholder) ->
            // Not inside a longer name on either side: `ha.lan` must not touch `ha.lan.example` or
            // `myha.lan`. A trailing `.` is allowed only when nothing name-like follows it, so the
            // full stop ending a sentence still lets the host match.
            Regex(
                """(?<![A-Za-z0-9.\-])""" + Regex.escape(host) + """(?![A-Za-z0-9\-]|\.[A-Za-z0-9])""",
                RegexOption.IGNORE_CASE,
            ) to placeholder
        }

    fun redact(line: String): String {
        var out = line
        for (secret in secrets) out = out.replace(secret, REDACTED)
        // Before the rest: both players print the stream URL they failed on, credentials and all.
        out = URL_USERINFO.replace(out, "$1$REDACTED@")
        out = BEARER.replace(out, "Bearer $REDACTED")
        out = QUERY_TOKEN.replace(out) { "${it.groupValues[1]}=$REDACTED" }
        out = TTS_PROXY.replace(out, "/api/tts_proxy/$REDACTED")
        out = AUTH_JSON.replace(out) { "\"${it.groupValues[1]}\":\"$REDACTED\"" }
        for ((rule, placeholder) in hostRules) out = rule.replace(out, placeholder)
        return out
    }

    companion object {
        /** Redacts credential patterns only; for before settings have loaded. */
        val BASIC = LogRedactor()

        /**
         * Short strings are not redacted as literals: a 4-digit PIN would otherwise match every
         * timestamp and frame count in the file. Short secrets are covered by the `pin=` pattern.
         */
        const val MIN_SECRET_LENGTH = 8

        /** Shorter configured hosts are left as they are; see [hostRules]. */
        const val MIN_HOST_LENGTH = 4

        private val BEARER = Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-]+""")
        private val QUERY_TOKEN = Regex("""(?i)\b(access_token|auth|token|api_key|apikey|key|password|pin)=([^\s&"']+)""")
        private val TTS_PROXY = Regex("""/api/tts_proxy/[A-Za-z0-9._\-]+""")
        private val AUTH_JSON = Regex("""(?i)"(access_token|token|password|api_key)"\s*:\s*"[^"]*"""")
    }
}

/**
 * The placeholder for every host the user configured: Home Assistant's as `<ha-host>`, then each
 * camera's in list order as `<camera-N-host>` (numbered by camera, so a camera's stream and
 * snapshot hosts share one number). A host already named keeps its first name — a camera served by
 * Home Assistant's own machine reads as `<ha-host>`, which is the useful thing to know.
 */
fun hostPlaceholders(haUrl: String, cameraUrls: List<List<String>>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    hostOf(haUrl)?.let { out[it] = "<ha-host>" }
    cameraUrls.forEachIndexed { i, urls ->
        urls.mapNotNull(::hostOf).forEach { h -> out.putIfAbsent(h, "<camera-${i + 1}-host>") }
    }
    return out
}

/** The host part of [url], lower-cased, or null. Tolerates a missing scheme (`ha.lan:8123`). */
internal fun hostOf(url: String): String? {
    val u = url.trim()
    if (u.isEmpty()) return null
    val withScheme = if ("://" in u) u else "http://$u"
    // java.net.URI rejects what cameras routinely accept — a password with `#` or a space in it —
    // and a host that fails to parse would silently stay in the log. Fall back to cutting by hand.
    val parsed = runCatching { java.net.URI(withScheme).host }.getOrNull() ?: run {
        val authority = withScheme.substringAfter("://").substringBefore('/')
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
    }
    return parsed
        .removePrefix("[").removeSuffix("]") // IPv6 literal
        .lowercase()
        .takeIf { it.isNotBlank() }
}
