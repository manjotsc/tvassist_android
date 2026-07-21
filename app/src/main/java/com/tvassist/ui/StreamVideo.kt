package com.tvassist.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.TextureView
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer

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
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(url) { onDispose { exo.release() } }
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
    // Rolling-clip cameras: on EndReached, re-fetch a fresh clip. Runs off the VLC event thread via
    // main, with a small delay so a persistently-failing URL can't hammer in a tight loop.
    DisposableEffect(url, reloadOnEnd) {
        if (reloadOnEnd) {
            player.setEventListener { ev ->
                if (ev.type == VlcMediaPlayer.Event.EndReached) {
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
