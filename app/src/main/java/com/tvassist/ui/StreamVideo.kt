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
import com.tvassist.ui.cards.camera.HardwareDecoder
import com.tvassist.ui.cards.camera.grabFramesInto
import com.tvassist.ui.cards.camera.tooLargeForSoftware
import com.tvassist.data.ha.safeUrlForLog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import org.videolan.libvlc.Media

/**
 * Every camera line — a stream that failed, one that never started, its closing stats — goes under
 * `HaCamera`, so one filter (`logcat -s HaCamera`) answers "what happened to that camera".
 *
 * Until this existed, the only record of a camera that never appeared was the engine's own output —
 * `E/VLC: cannot connect to <camera>:554`, filed under a tag nobody would think to filter by, and
 * nothing at all from ExoPlayer's side inside a notification card. That VLC output is now silenced
 * in release builds (it carried camera passwords; see VlcHolder), which makes this the only record
 * there is. Failures are warned, not logged at info.
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
    // With [reloadOnEnd]: what to load when the clip ends, instead of [url] again. A rolling-clip
    // camera passes its newest downloaded copy here (see rememberRollingClip), so the end of a loop
    // replays a local file rather than going back to the network.
    nextUrl: (() -> String?)? = null,
    // Called when the stream was stopped for being too large to decode in software while another
    // app holds the hardware decoder (see VlcVideo). The caller explains; without one the picture
    // simply stops, which is still far better than the frozen TV it replaces.
    onTooLargeForSoftware: (() -> Unit)? = null,
) {
    val useVlc = player.equals("vlc", true) ||
        (player.equals("auto", true) && url.startsWith("rtsp", ignoreCase = true))
    if (useVlc) {
        VlcVideo(url, modifier, captureKey, reloadOnEnd, nextUrl, onTooLargeForSoftware)
    } else {
        // No guard needed here: ExoPlayer's decoder fallback is off by default, so a refused
        // hardware decoder is a playback error, not a slide into software decoding.
        ExoVideo(url, modifier, captureKey)
    }
}

/** Grab live frames from [tv] into the cache under [captureKey] (once it's rendering). */
private suspend fun captureFrames(tv: TextureView?, captureKey: String?) {
    if (captureKey == null || tv == null) return
    tv.grabFramesInto(captureKey)
}

/** The playing media's counters. [VlcMediaPlayer.getMedia] retains, so this releases. */
private fun VlcMediaPlayer.currentStats(): org.videolan.libvlc.interfaces.IMedia.Stats? =
    runCatching { media?.let { m -> try { m.stats } finally { m.release() } } }.getOrNull()

private fun org.videolan.libvlc.interfaces.IMedia.Stats.describe(): String =
    "shown $displayedPictures, dropped $lostPictures, decoded $decodedVideo, corrupt $demuxCorrupted"

/**
 * Frames shown and dropped across a stream's life — for a rolling-clip camera, across every clip,
 * since each loop is a new media whose counters start at zero.
 *
 * Reported once, when the stream closes, and in release builds too: numbers only, nothing that
 * identifies the camera. Without it the only measure of "was 4K smooth on this TV" was asking.
 */
private class StreamTally {
    private var shown = 0
    private var dropped = 0
    private var decoded = 0
    private var corrupt = 0

    fun add(s: org.videolan.libvlc.interfaces.IMedia.Stats?) {
        s ?: return
        shown += s.displayedPictures
        dropped += s.lostPictures
        decoded += s.decodedVideo
        corrupt += s.demuxCorrupted
    }

    fun report(openedAt: Long) {
        if (shown == 0 && dropped == 0) return // never played; reportStreamOutcome already said so
        val secs = (SystemClock.elapsedRealtime() - openedAt) / 1000.0
        val pct = if (shown + dropped > 0) 100.0 * dropped / (shown + dropped) else 0.0
        android.util.Log.i(
            CAMERA_TAG,
            "stream stats (vlc): ${"%.0f".format(secs)} s, shown $shown, dropped $dropped " +
                "(${"%.1f".format(pct)}%), decoded $decoded, corrupt $corrupt",
        )
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

/** How often, and for how long (15 s), the software-size guard looks for the stream's resolution. */
private const val GUARD_INTERVAL_MS = 500L
private const val GUARD_CHECKS = 30

/** One shared LibVLC instance (creation is heavy); MediaPlayers are per-stream. */
private object VlcHolder {
    @Volatile private var instance: LibVLC? = null
    fun get(context: Context): LibVLC = instance ?: synchronized(this) {
        instance ?: LibVLC(
            context.applicationContext,
            arrayListOf("--network-caching=300", "--rtsp-tcp", "--no-audio").apply {
                // Release builds silence libVLC's own logging. It writes the MRL it failed on,
                // credentials and all — `unable to open the MRL 'rtsp://user:password@cam/…'`
                // was captured from a real TV — and logcat is readable by anyone with adb.
                // [safeUrlForLog] cannot help: these lines come from native code, not from us.
                // Our own `HaCamera` line still reports the failure, with the credential removed.
                // Debug builds keep it, being the only view into *why* a stream failed.
                //
                // `--verbose=-1`, not `--quiet`: on Android libVLC logs through its own android
                // logger, which reads the verbosity and ignores `--quiet` (that only silences the
                // console logger). `--quiet` was tried on the UR3 and E/VLC lines kept coming; the
                // default verbosity of 0 is exactly "errors only", which is what was seen.
                if (!com.tvassist.BuildConfig.DEBUG) add("--verbose=-1")
            },
        ).also { instance = it }
    }
}

@Composable
private fun VlcVideo(
    url: String,
    modifier: Modifier,
    captureKey: String?,
    reloadOnEnd: Boolean = false,
    nextUrl: (() -> String?)? = null,
    onTooLargeForSoftware: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    val settled = remember(url) { AtomicBoolean(false) }
    val openedAt = remember(url) { SystemClock.elapsedRealtime() }
    // Asked before playing, never during: libVLC gives no sign that it fell back to software, and a
    // probe racing VLC's own decoder set-up could take the slot and cause the very fallback it is
    // looking for. Null until known; playback waits for it (tens of milliseconds).
    var decoderBusy by remember(url) { mutableStateOf<Boolean?>(null) }
    var surfaceReady by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        decoderBusy = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { HardwareDecoder.isBusy() }
    }
    LaunchedEffect(textureView, captureKey) { captureFrames(textureView, captureKey) }
    val libVlc = remember { VlcHolder.get(context) }
    fun newMedia(from: String = url) = Media(libVlc, Uri.parse(from)).apply {
        // Caching first, then the decoder. setHWDecoderEnabled adds `:network-caching=1500` and
        // `:file-caching=1500` unless those options are already set (read from libVLC 3.5.1's own
        // bytecode), so setting ours afterwards left two conflicting values in the media. And the
        // file one applied to rolling-clip cameras, which now loop a local copy (see
        // rememberRollingClip): a 1.5 s read-ahead of a file already on the TV, on every ~5 s loop.
        addOption(":network-caching=300")
        addOption(":file-caching=300")
        setHWDecoderEnabled(true, false) // hardware, with software fallback
        // Low-latency: shrink every caching stage, not just network-caching.
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
    val tally = remember(url) { StreamTally() }
    // Debug builds: how the stream is doing while it plays. Release builds get one summary line
    // when it closes (see the dispose below), which is enough to answer "was it smooth" on a TV
    // that cannot run a debug build.
    if (com.tvassist.BuildConfig.DEBUG) {
        LaunchedEffect(player) {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                player.currentStats()?.let { android.util.Log.d(CAMERA_TAG, "vlc stats now: ${it.describe()}") }
            }
        }
    }
    // Always listening now, not only for rolling clips: the engine's own view of whether this
    // stream ever came up is not available anywhere else.
    //
    // Rolling-clip cameras load the next clip on EndReached — the newest local copy when [nextUrl]
    // is given, otherwise [url] again. That runs off the VLC event thread via main, with a small
    // delay so a persistently-failing source can't spin in a tight loop.
    LaunchedEffect(surfaceReady, decoderBusy) {
        if (surfaceReady && decoderBusy != null) runCatching { player.play() }
    }
    // The guard. With the hardware decoder taken, libVLC decodes in software whatever the stream's
    // size, and on this TV a 4K stream in software ends with the whole TV locked up. The size is
    // not known until the stream is open, so watch for it over the first seconds and stop anything
    // over MAX_SOFTWARE_PIXELS before it gets anywhere near that.
    LaunchedEffect(player, decoderBusy) {
        if (decoderBusy != true) return@LaunchedEffect
        repeat(GUARD_CHECKS) {
            kotlinx.coroutines.delay(GUARD_INTERVAL_MS)
            val track = runCatching { player.currentVideoTrack }.getOrNull() ?: return@repeat
            if (tooLargeForSoftware(track.width, track.height)) {
                runCatching { player.stop() }
                reportStreamOutcome(
                    settled, "vlc", url, openedAt,
                    "${track.width}x${track.height} is too large to decode without the hardware " +
                        "decoder, which another app is using",
                )
                onTooLargeForSoftware?.invoke()
                return@LaunchedEffect
            }
            if (track.width > 0) return@LaunchedEffect // known, and small enough: nothing to guard
        }
    }
    DisposableEffect(url, reloadOnEnd) {
        fun loadNext(afterMs: Long) = main.postDelayed({
            runCatching {
                // Each clip is a new media with its own counters; bank this one's first.
                tally.add(player.currentStats())
                val m = newMedia(nextUrl?.invoke() ?: url)
                player.media = m
                m.release()
                player.play()
            }
        }, afterMs)
        player.setEventListener { ev ->
            when (ev.type) {
                VlcMediaPlayer.Event.Vout -> if (ev.voutCount > 0) settled.set(true)
                VlcMediaPlayer.Event.EncounteredError -> {
                    // The event carries no reason. In debug builds libVLC's own preceding lines say
                    // why; release builds silence them (they carried camera passwords), so there
                    // this line is the whole record.
                    reportStreamOutcome(settled, "vlc", url, openedAt, "the player reported an error")
                    // A bad local copy (a truncated download) would otherwise stop the player for
                    // good. Only with [nextUrl]: that is a local file, so retrying costs nothing,
                    // whereas retrying a network URL on error is how the app kept itself blocked.
                    if (reloadOnEnd && nextUrl != null) loadNext(1000)
                }
                VlcMediaPlayer.Event.EndReached -> if (reloadOnEnd) loadNext(200)
            }
        }
        onDispose { runCatching { player.setEventListener(null) } }
    }
    DisposableEffect(url) {
        onDispose {
            // Before the listener is gone and the player is released — this is the branch that
            // actually catches an unreachable camera, whose TCP timeout outlives the card.
            reportStreamOutcome(settled, "vlc", url, openedAt, reason = null)
            tally.add(player.currentStats())
            tally.report(openedAt)
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
                        }
                        surfaceReady = true
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
