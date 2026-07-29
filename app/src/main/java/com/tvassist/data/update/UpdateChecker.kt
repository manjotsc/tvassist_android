package com.tvassist.data.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Checks the project's GitHub Releases for a newer build.
 *
 * Read-only and advisory: it never downloads or installs anything, it only reports what the newest
 * published release is so the About page can show it.
 */
object UpdateChecker {
    private const val TAG = "UpdateCheck"
    private const val REPO = "manjotsc/tvassist_android"
    // per_page caps the payload: the full list is ~26 KB today and grows with every release, but
    // only the newest entry is ever used. 5 rather than 1 so a draft or two at the top can't hide
    // the newest published release.
    private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases?per_page=5"

    /** How long a successful answer stays good, so re-opening the page doesn't re-hit the API. */
    private const val CACHE_MS = 6 * 60 * 60 * 1000L

    /**
     * Its **own** client, deliberately not one of [com.tvassist.data.ha.HaRepository]'s.
     *
     * This talks to the public internet, so it must never inherit the relaxed-TLS client the user
     * can enable for a self-signed Home Assistant on the LAN. Certificate verification here is
     * always full and non-negotiable.
     */
    private val http = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        /** Running the newest published release (or newer — a local build). */
        data object UpToDate : Result

        /** [prerelease] mirrors GitHub's flag on the release — shown as a Pre-release/Latest tag. */
        data class Available(val version: String, val notes: String, val prerelease: Boolean) : Result

        /** No network, rate limited, malformed response, or no releases published yet. */
        data object Unknown : Result
    }

    @Volatile
    private var cached: Pair<Long, Result>? = null

    /** Checks for a newer release than [current] (a `versionName`). Never throws. */
    suspend fun check(current: String, force: Boolean = false): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force) {
            cached?.let { (at, result) -> if (now - at < CACHE_MS) return@withContext result }
        }
        val result = runCatching { fetch(current) }
            .onFailure { Log.w(TAG, "update check failed", it) }
            .getOrDefault(Result.Unknown)
        // Never cache Unknown: a momentary network blip shouldn't suppress checking for six hours.
        if (result !is Result.Unknown) cached = now to result
        result
    }

    private fun fetch(current: String): Result {
        val req = Request.Builder()
            .url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            // GitHub asks callers to identify themselves, and pinning the API version keeps a future
            // default change from silently altering the response shape.
            .header("User-Agent", "TVAssist-Android")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "releases returned HTTP ${resp.code}")
                return Result.Unknown
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return Result.Unknown
            val releases = Json.parseToJsonElement(body).jsonArray
            // Newest first. Prereleases are deliberately INCLUDED — every release this project
            // publishes is flagged prerelease, so filtering them out would find nothing. Drafts are
            // invisible to unauthenticated callers anyway, but are skipped defensively.
            val newest = releases.firstOrNull {
                it.jsonObject["draft"]?.jsonPrimitive?.booleanOrNull != true
            }?.jsonObject ?: return Result.Unknown

            val tag = newest["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (tag.isEmpty()) return Result.Unknown

            return if (isNewer(tag, current)) {
                Result.Available(
                    version = tag.removePrefix("v"),
                    notes = newest["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    prerelease = newest["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            } else {
                Result.UpToDate
            }
        }
    }

    /**
     * Component-wise numeric comparison. A string compare gets this wrong the first time a component
     * reaches double digits — "1.1.10" sorts *before* "1.1.9" lexically.
     */
    private fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** "v1.1.2" / "1.1.2-beta" → [1, 1, 2]; non-numeric suffixes are ignored rather than failing. */
    private fun parts(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
