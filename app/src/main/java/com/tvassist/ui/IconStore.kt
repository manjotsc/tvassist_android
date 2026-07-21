package com.tvassist.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fetches and searches icons from the public Iconify API (https://iconify.design), which
 * includes all of Home Assistant's MDI set plus many others. Icons are served as SVG; we parse
 * each into a Compose [ImageVector] (which composites in the overlay window and tints cleanly).
 * No API key.
 */
object IconStore {
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = LruCache<String, ImageVector>(400)

    private val viewBoxRe = Regex("""viewBox="([\d.\- ]+)"""")
    private val pathRe = Regex("""<path\b[^>]*\sd="([^"]+)"""")

    /**
     * Fetches an icon as an [ImageVector], or null on failure. [name] is either an Iconify name
     * (e.g. "mdi:lightbulb") or a direct SVG URL (e.g. "https://…/icon.svg").
     */
    suspend fun fetch(name: String): ImageVector? = withContext(Dispatchers.IO) {
        cache.get(name)?.let { return@withContext it }
        runCatching {
            val url = if (name.startsWith("http")) name
            else "https://api.iconify.design/${name.replaceFirst(':', '/')}.svg"
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()?.let { parseSvg(it) }
            }
        }.getOrNull()?.also { cache.put(name, it) }
    }

    /** Builds a single-color [ImageVector] from an Iconify SVG body (tinted later by Icon). */
    private fun parseSvg(svg: String): ImageVector? {
        val box = viewBoxRe.find(svg)?.groupValues?.get(1)?.trim()?.split(" ")
        val vw = box?.getOrNull(2)?.toFloatOrNull() ?: 24f
        val vh = box?.getOrNull(3)?.toFloatOrNull() ?: 24f
        val paths = pathRe.findAll(svg).map { it.groupValues[1] }.toList()
        if (paths.isEmpty()) return null
        val builder = ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = vw, viewportHeight = vh,
        )
        paths.forEach { d ->
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = SolidColor(Color.White),
            )
        }
        return builder.build()
    }

    /** Warm the cache for a set of icon names (in parallel) so tiles render instantly later. */
    suspend fun prefetch(names: Collection<String>) {
        val todo = names.toSet().filter { cache.get(it) == null }
        if (todo.isEmpty()) return
        kotlinx.coroutines.coroutineScope {
            todo.forEach { name -> launch { fetch(name) } }
        }
    }

    /** Searches icon names for [query], MDI (Home-Assistant-native) results first. */
    suspend fun search(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        runCatching {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://api.iconify.design/search?query=$q&limit=120"
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val icons = json.parseToJsonElement(body).jsonObject["icons"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                icons.sortedByDescending { it.startsWith("mdi:") }
            }
        }.getOrDefault(emptyList())
    }
}
