package com.tvassist.ui.cards.camera

import android.util.LruCache
import android.view.TextureView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.tvassist.ui.cap

/**
 * A tiny in-memory cache of the last live frame grabbed from each camera, keyed by entity id.
 * Lets a camera without a snapshot URL still show a real thumbnail/poster after it's been viewed
 * once (survives while the app process is alive).
 */
object CameraFrameCache {
    private val cache = LruCache<String, ImageBitmap>(8)
    fun get(key: String?): ImageBitmap? = key?.let { cache.get(it) }
    fun put(key: String, bmp: ImageBitmap) { cache.put(key, bmp) }
}

/**
 * Best-effort: grab the currently displayed frame from a [TextureView] into [CameraFrameCache].
 * Captured DOWNSCALED to a poster size — these are only thumbnails/posters, and grabbing at the
 * TextureView's full pixel size would cost ~33 MB per frame on a 4K screen (× the cache = OOM risk).
 */
fun TextureView.grabFrameInto(key: String) {
    runCatching {
        if (isAvailable && width > 0 && height > 0) {
            val cap = 960 // longest side; keeps a crisp-enough poster at a fraction of the memory
            val scale = minOf(1f, cap.toFloat() / maxOf(width, height))
            val w = (width * scale).toInt().coerceAtLeast(1)
            val h = (height * scale).toInt().coerceAtLeast(1)
            getBitmap(w, h)?.let { CameraFrameCache.put(key, it.asImageBitmap()) }
        }
    }
}

/**
 * Grabs [FRAME_GRAB_DELAYS_MS]-spaced frames from this view into [CameraFrameCache] under [key].
 *
 * Two, not the six it used to be (one every 2.5 s for 15 s). [grabFrameInto] reads the picture
 * back from the GPU on the main thread, which stalls the UI for as long as the readback takes, and
 * the cache keeps only the latest frame anyway: one early grab for a quick poster, one later for a
 * settled one, is all a thumbnail needs.
 */
suspend fun TextureView.grabFramesInto(key: String) {
    for (wait in FRAME_GRAB_DELAYS_MS) {
        kotlinx.coroutines.delay(wait)
        grabFrameInto(key)
    }
}

/** Waits before each grab: one at ~3 s, once a first picture is up, and one at ~10 s. */
private val FRAME_GRAB_DELAYS_MS = listOf(3_000L, 7_000L)
