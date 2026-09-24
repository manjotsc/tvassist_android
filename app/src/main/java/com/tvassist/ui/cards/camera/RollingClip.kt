package com.tvassist.ui.cards.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.tvassist.data.ha.HaRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

/**
 * How often a rolling-clip camera is re-downloaded. These sources typically replace their short
 * looped video every 30 s, so asking sooner only fetches the same file again.
 */
internal const val CLIP_REFRESH_MS = 30_000L

/** The ceiling of the back-off after failures: 30 s, 1, 2, 4, then every 5 min. */
internal const val CLIP_RETRY_MAX_MS = 5 * 60_000L

/** How long to wait before the next download, after [failures] failures in a row (0 = the last one worked). */
internal fun clipRetryDelayMs(failures: Int): Long =
    if (failures <= 0) CLIP_REFRESH_MS
    else (CLIP_REFRESH_MS shl (failures - 1).coerceAtMost(8)).coerceAtMost(CLIP_RETRY_MAX_MS)

/**
 * The newest downloaded copy of a rolling clip at [url], as a `file://` URI, or null until the first
 * download lands. Re-downloaded every [CLIP_REFRESH_MS], and on failure after [clipRetryDelayMs].
 *
 * This replaces re-requesting the URL each time the clip ended. With a ~5 s clip that was about a
 * dozen requests a minute per camera for a file that changes twice a minute, and a failed load
 * retried as fast as the player gave up — so once Cloudflare started refusing (a 403 "Sorry, you
 * have been blocked"), the app went on asking and kept itself blocked, showing a black screen.
 * Now the player loops a local file, and a failed download leaves the last good clip playing.
 *
 * Two slots are alternated so the file just handed to the player is not the one the next download
 * replaces; [HaRepository.fetchClipTo] renames over the target in any case, which leaves a reader
 * of the old file undisturbed.
 */
@Composable
internal fun rememberRollingClip(url: String, repository: HaRepository): String? {
    val context = LocalContext.current
    var latest by remember(url) { mutableStateOf<String?>(null) }
    // Per camera URL, so two cameras never share a slot; no need for anything stronger than a hash.
    val stem = remember(url) { "clip-" + Integer.toHexString(url.hashCode()) }
    LaunchedEffect(url) {
        val dir = File(context.cacheDir, "clips").apply { mkdirs() }
        var slot = 0
        var failures = 0
        while (isActive) {
            val dest = File(dir, "$stem-$slot.mp4")
            if (repository.fetchClipTo(url, dest)) {
                latest = android.net.Uri.fromFile(dest).toString()
                slot = 1 - slot
                failures = 0
            } else {
                failures++
            }
            delay(clipRetryDelayMs(failures))
        }
    }
    DisposableEffect(url) {
        onDispose {
            File(context.cacheDir, "clips").listFiles { f -> f.name.startsWith(stem) }?.forEach { it.delete() }
        }
    }
    return latest
}
