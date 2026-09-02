package com.tvassist.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import java.util.concurrent.atomic.AtomicBoolean
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.tvassist.data.ha.safeUrlForLog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer

/**
 * Tagged `HaCamera` so a stream failure lands under the Images / Notifications / Overlay filters on
 * the Logs page rather than in the noise.
 *
 * Until this existed, the only record of a camera that never appeared was the engine's own output —
 * `E/VLC: cannot connect to 192.168.13.67:554`, filed under a tag nobody would think to filter by,
 * and nothing at all from ExoPlayer's side inside a notification card. Warned, not logged at info,
 * so "Problems only" surfaces it.
 */
private const val CAMERA_TAG = "HaCamera"

/**
 * Reports a stream that never produced a picture, at the moment the player goes away.
 *
 * Listening for the engine's error event is not enough on its own, and measuring it proved why: a
 * `duration: 8` notification pointing at an unreachable RTSP camera was disposed at 07:18:26.663
 * and libvlc gave up at 07:18:26.690 — 27 ms too late, with the listener already nulled. A card
 * outliving its own failure is the normal case, not the edge case, because an unreachable host
 * costs a TCP timeout and notifications are seconds long.
 *
 * So the outcome is settled exactly once, by whichever comes first: the engine saying it failed, or
 * teardown finding that nothing ever played. [settled] is an AtomicBoolean because the engine's
 * event thread and the composition's dispose both race for it.
 */
private fun reportStreamOutcome(settled: AtomicBoolean, engine: String, url: String, openedAt: Long, reason: String?) {
    if (!settled.compareAndSet(false, true)) return
    val secs = (SystemClock.elapsedRealtime() - openedAt) / 1000.0
    android.util.Log.w(
        CAMERA_TAG,
        if (reason != null) "stream failed ($engine): ${safeUrlForLog(url)} — $reason"
        else "stream never started ($engine): ${safeUrlForLog(url)} — " +
            "closed after ${"%.1f".format(secs)}s with no picture",
    )
}

/**
 * Plays a stream into a TextureView (overlay-safe), choosing the engine:
 *  - **exoplayer** (default): light, low-latency, hardware H.264.
 *  - **vlc**: wider RTSP/codec support (HEVC, quirky cameras) with software-decode fallback.
 *  - **auto**: ExoPlayer, but VLC for rtsp:// (where it's far more compatible).
 * All variants are muted + looped.
 */
@Composable
fun StreamVideo(
    url: String,
    player: String,
    modifier: Modifier = Modifier,
    captureKey: String? = null,
    // For "rolling clip" cameras (finite MP4 per request): re-fetch when the clip ends instead of
    // stopping. ExoVideo already loops (REPEAT_MODE_ALL); this drives the VLC path's re-fetch.
    reloadOnEnd: Boolean = false,
) {
    val useVlc = player.equals("vlc", true) ||
        (player.equals("auto", true) && url.startsWith("rtsp", ignoreCase = true))
    if (useVlc) VlcVideo(url, modifier, captureKey, reloadOnEnd) else ExoVideo(url, modifier, captureKey)
}

/** Periodically grab a live frame from [tv] into the cache under [captureKey] (once it's rendering). */
private suspend fun captureFrames(tv: TextureView?, captureKey: String?) {
    if (captureKey == null || tv == null) return
    repeat(6) {
        kotlinx.coroutines.delay(2500)
        tv.grabFrameInto(captureKey)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ExoVideo(url: String, modifier: Modifier, captureKey: String?) {
    val context = LocalContext.current
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    val settled = remember(url) { AtomicBoolean(false) }
    val openedAt = remember(url) { SystemClock.elapsedRealtime() }
    val exo = remember(url) {
        // Start on minimal data (low startup latency) instead of filling a large buffer first.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 8000, 500, 1000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
            if (url.contains(".m3u8", ignoreCase = true)) {
                // Chunkless HLS preparation → skips probing media chunks, starts faster.
                val source = HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(MediaItem.fromUri(url))
                setMediaSource(source)
            } else {
                setMediaItem(MediaItem.fromUri(url))
            }
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            addListener(object : Player.Listener {
                // A frame on screen is the only proof the stream actually worked; "buffering" and
                // "ready" both happen on streams that never render.
                override fun onRenderedFirstFrame() { settled.set(true) }
                override fun onPlayerError(error: PlaybackException) {
                    reportStreamOutcome(
                        settled, "exoplayer", url, openedAt,
                        "${error.errorCodeName}: ${error.message}",
                    )
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(url) {
        onDispose {
            reportStreamOutcome(settled, "exoplayer", url, openedAt, reason = null)
            exo.release()
        }
    }
    LaunchedEffect(textureView, captureKey) { captureFrames(textureView, captureKey) }
    // key(url): the factory only attaches `exo` to the TextureView when the view is first created,
    // so when `url` (and thus `exo`) changes we must rebuild the view — otherwise the new player
    // renders to nothing (black video). See the matching VLC path below.
    key(url) {
        AndroidView(
            modifier = modifier,
            factory = { ctx -> TextureView(ctx).also { exo.setVideoTextureView(it); textureView = it } },
        )
    }
}

/** One shared LibVLC instance (creation is heavy); MediaPlayers are per-stream. */
private object VlcHolder {
    @Volatile private var instance: LibVLC? = null
    fun get(context: Context): LibVLC = instance ?: synchronized(this) {
        instance ?: LibVLC(
            context.applicationContext,
            arrayListOf("--network-caching=300", "--rtsp-tcp", "--no-audio"),
        ).also { instance = it }
    }
}

@Composable
private fun VlcVideo(url: String, modifier: Modifier, captureKey: String?, reloadOnEnd: Boolean = false) {
    val context = LocalContext.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    val settled = remember(url) { AtomicBoolean(false) }
    val openedAt = remember(url) { SystemClock.elapsedRealtime() }
    LaunchedEffect(textureView, captureKey) { captureFrames(textureView, captureKey) }
    val libVlc = remember { VlcHolder.get(context) }
    fun newMedia() = Media(libVlc, Uri.parse(url)).apply {
        setHWDecoderEnabled(true, false) // hardware, with software fallback
        // Low-latency: shrink every caching stage, not just network-caching.
        addOption(":network-caching=300")
        addOption(":live-caching=300")
        addOption(":rtsp-caching=300")
        addOption(":tcp-caching=300")
        addOption(":realrtsp-caching=300")
        addOption(":clock-jitter=0")
        addOption(":clock-synchro=0")
    }
    val player = remember(url) {
        VlcMediaPlayer(libVlc).apply {
            val media = newMedia()
            this.media = media
            media.release()
        }
    }
    // Always listening now, not only for rolling clips: the engine's own view of whether this
    // stream ever came up is not available anywhere else.
    //
    // Rolling-clip cameras additionally re-fetch a fresh clip on EndReached. That runs off the VLC
    // event thread via main, with a small delay so a persistently-failing URL can't hammer in a
    // tight loop.
    DisposableEffect(url, reloadOnEnd) {
        player.setEventListener { ev ->
            when (ev.type) {
                VlcMediaPlayer.Event.Vout -> if (ev.voutCount > 0) settled.set(true)
                VlcMediaPlayer.Event.EncounteredError ->
                    // The event carries no reason; libvlc's own preceding lines say why, and they
                    // are in the same capture — under this same filter now that VLC maps to Images.
                    reportStreamOutcome(settled, "vlc", url, openedAt, "the player reported an error")
                VlcMediaPlayer.Event.EndReached -> if (reloadOnEnd) {
                    main.postDelayed({
                        runCatching {
                            val m = newMedia()
                            player.media = m
                            m.release()
                            player.play()
                        }
                    }, 200)
                }
            }
        }
        onDispose { runCatching { player.setEventListener(null) } }
    }
    DisposableEffect(url) {
        onDispose {
            // Before the listener is gone and the player is released — this is the branch that
            // actually catches an unreachable camera, whose TCP timeout outlives the card.
            reportStreamOutcome(settled, "vlc", url, openedAt, reason = null)
            runCatching { player.stop() }
            runCatching { player.vlcVout.detachViews() }
            runCatching { player.release() }
        }
    }
    // Render into a raw TextureView (like ExoPlayer) and force the display aspect to the view so
    // it FILLS the box instead of letterboxing in a corner. key(url): the new player attaches in
    // onSurfaceTextureAvailable, which only fires for a freshly-created view, so rebuild on url change.
    key(url) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                textureView = tv
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        val vout = player.vlcVout
                        runCatching {
                            vout.setVideoView(tv)
                            vout.setWindowSize(w, h)
                            vout.attachViews()
                            player.aspectRatio = "$w:$h"
                            player.scale = 0f
                            player.play()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                        runCatching {
                            player.vlcVout.setWindowSize(w, h)
                            player.aspectRatio = "$w:$h"
                        }
                    }
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        runCatching { player.vlcVout.detachViews() }
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )
    }
}
