package com.tvassist.ui

import android.graphics.BitmapFactory
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.tvassist.TvAssistApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import android.os.SystemClock
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import com.tvassist.ui.cards.camera.CameraFrameCache
import com.tvassist.ui.cards.camera.CLIP_REFRESH_MS
import com.tvassist.ui.cards.camera.CameraSource
import com.tvassist.ui.cards.camera.HardwareDecoder
import com.tvassist.ui.cards.camera.chooseCameraSource
import com.tvassist.ui.cards.camera.decodeOffThread
import com.tvassist.ui.cards.camera.grabFramesInto
import com.tvassist.ui.cards.camera.rememberRollingClip
import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.ha.safeUrlForLog

/** How long TV Assist's own camera screen waits for the app it replaced to release the decoder. */
private const val DECODER_WAIT_MS = 8_000L

/** How often the snapshot refreshes when live video is unavailable — the camera tile's own pace. */
private const val UNAVAILABLE_REFRESH_MS = 5_000L

/**
 * Live camera view: the overlay's camera popup, and TV Assist's own full-screen camera. Plays a
 * local camera's own stream, or an HA camera's HLS, into a [TextureView] (works inside the overlay
 * window, unlike SurfaceView), with the snapshot showing until the first frame. Which stream — main,
 * low-res, or none — depends on whether the hardware decoder is free; see [chooseCameraSource].
 * Parent owns BACK.
 */
@OptIn(UnstableApi::class)
@Composable
fun CameraPlayerScreen(
    entity: Entity,
    repository: HaRepository,
    /**
     * True for the overlay's popup, drawn over another app — the app that holds the hardware decoder
     * and is not going to release it. False inside TV Assist, where that app has just gone to the
     * background and will. Decides what a busy decoder with no low-res stream means; see
     * [chooseCameraSource].
     */
    inOverlay: Boolean = false,
) {
    val context = LocalContext.current
    var url by remember(entity.entityId) { mutableStateOf<String?>(null) }
    var failed by remember(entity.entityId) { mutableStateOf(false) }
    // Set when the stream was stopped for being too big to decode in software (see StreamVideo).
    var tooLarge by remember(entity.entityId) { mutableStateOf(false) }
    // Set when the decoder is taken and there is no low-res stream: snapshots only (CameraSource.UNAVAILABLE).
    var unavailable by remember(entity.entityId) { mutableStateOf(false) }
    var snapshot by remember(entity.entityId) { mutableStateOf<ImageBitmap?>(null) }
    var playing by remember(entity.entityId) { mutableStateOf(false) }

    // Seed the poster from the last captured frame (so even a snapshot-less camera shows something).
    LaunchedEffect(entity.entityId) { snapshot = CameraFrameCache.get(entity.entityId) }
    LaunchedEffect(entity.entityId) {
        // Local cameras use their own snapshot URL (if any); HA cameras fetch via the API.
        if (entity.isLocalCamera) {
            entity.localSnapshotUrl?.takeIf { it.isNotBlank() }?.let { snapUrl ->
                // Uncached: a local camera's snapshot URL is fixed but its content changes, so the
                // avatar cache would show the first frame ever loaded instead of the current one.
                repository.fetchStillImage(snapUrl)?.let { snapshot = it.asImageBitmap() }
            }
        } else {
            repository.cameraSnapshot(entity.entityId)?.let { bytes ->
                // Decode off the main thread — a full camera JPEG on the UI dispatcher drops frames.
                withContext(Dispatchers.Default) {
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                }?.let { snapshot = it.asImageBitmap() }
            }
        }
    }
    LaunchedEffect(entity.entityId) {
        // Local cameras play their direct URL immediately (no HA HLS round-trip).
        val main = if (entity.isLocalCamera) entity.localStreamUrl else repository.cameraStreamUrl(entity.entityId)
        if (main.isNullOrBlank()) {
            failed = true
            return@LaunchedEffect
        }
        // Which stream this TV can actually carry right now — see HardwareDecoder for why it has to
        // ask. The check is tens of milliseconds, behind a poster that is already showing.
        var busy = withContext(Dispatchers.IO) { HardwareDecoder.isBusy() }
        val lowRes = entity.localLowResUrl
        when (chooseCameraSource(busy, lowRes != null, inOverlay)) {
            CameraSource.MAIN -> url = main
            CameraSource.LOW_RES -> url = lowRes
            CameraSource.UNAVAILABLE -> unavailable = true
            CameraSource.MAIN_GUARDED -> {
                // TV Assist's own screen, just brought up over the app that held the decoder. That
                // app is only now being told it is in the background; measured on the UR3, Netflix
                // gave its decoder back 1–6 s later. Wait for it rather than start in software.
                val deadline = SystemClock.elapsedRealtime() + DECODER_WAIT_MS
                while (busy && SystemClock.elapsedRealtime() < deadline) {
                    delay(500)
                    busy = withContext(Dispatchers.IO) { HardwareDecoder.isBusy() }
                }
                // Still busy after that: play anyway, and the size guard stops it if it is 4K.
                url = main
            }
        }
    }

    // No live video to be had: keep the snapshot current instead, at the pace a camera tile uses.
    if (unavailable) {
        LaunchedEffect(entity.entityId) {
            while (true) {
                delay(UNAVAILABLE_REFRESH_MS)
                val fresh = if (entity.isLocalCamera) {
                    entity.localSnapshotUrl?.takeIf { it.isNotBlank() }?.let { repository.fetchStillImage(it) }
                } else {
                    repository.cameraSnapshot(entity.entityId, 1280, 720)?.let { decodeOffThread(it, 1920) }
                }
                fresh?.let { snapshot = it.asImageBitmap() }
            }
        }
    }

    val app = context.applicationContext as TvAssistApp
    // remember the mapped flow so a new one isn't created every recomposition (which would reset
    // collectAsState); Flow operators must not be invoked directly in composition.
    val playerFlow = remember(app) { app.settingsStore.settings.map { it.streamPlayer } }
    val globalPlayer by playerFlow.collectAsState(initial = "auto")
    // A local camera can pin its own engine; otherwise use the app-wide default.
    val streamPlayer = if (entity.isLocalCamera) entity.localPlayer else globalPlayer

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        snapshot?.let {
            Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }

        val source = url
        // "Rolling clip" cameras return a short looped video per request; reload when it ends so a
        // fresh clip plays instead of freezing on the last frame.
        val reloadOnEnd = entity.isLocalCamera && entity.localRefresh
        // Over HTTP the clip is downloaded every 30 s and looped from disk (see rememberRollingClip)
        // rather than re-requested at the end of every ~5 s loop. Anything else keeps reloading the
        // URL itself — there is no file to download from an RTSP stream.
        val downloaded = reloadOnEnd && source != null && source.startsWith("http", ignoreCase = true)
        val newestClip = if (downloaded) rememberRollingClip(source!!, repository) else null
        val latestClip by rememberUpdatedState(newestClip)
        // The players are keyed on their URL, so hand them the *first* clip and let later ones in
        // through `nextUrl` at the end of a loop. Passing each new clip as the URL would rebuild the
        // player every 30 s and cut the clip off mid-play.
        var firstClip by remember(source) { mutableStateOf<String?>(null) }
        if (downloaded && firstClip == null && newestClip != null) firstClip = newestClip
        val u = if (downloaded) firstClip else source
        val nextUrl: (() -> String?)? = if (downloaded) ({ latestClip }) else null
        val useVlc = u != null &&
            (streamPlayer.equals("vlc", true) || (streamPlayer.equals("auto", true) && u.startsWith("rtsp", true)))
        if (u != null && useVlc) {
            // libVLC path (HEVC / quirky RTSP / software fallback).
            StreamVideo(
                url = u, player = "vlc", modifier = Modifier.fillMaxSize(), captureKey = entity.entityId,
                reloadOnEnd = reloadOnEnd, nextUrl = nextUrl,
                onTooLargeForSoftware = { tooLarge = true },
            )
        } else if (u != null) {
            var exoTexture by remember(u) { mutableStateOf<TextureView?>(null) }
            // Drives the rolling-clip reload/retry off the player callbacks (ExoPlayer is main-thread only).
            val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
            // Cache a live frame so this camera shows a real thumbnail/poster next time.
            LaunchedEffect(exoTexture) {
                exoTexture?.grabFramesInto(entity.entityId)
            }
            val player = remember(u) {
                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1000, 8000, 500, 1000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
                ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
                    setMediaItem(MediaItem.fromUri(u))
                    prepare()
                    playWhenReady = true
                    addListener(object : Player.Listener {
                        // The next clip: the newest downloaded copy, or — without a download — the URL
                        // again, whose next HTTP request pulls a fresh clip.
                        fun refetch() = runCatching {
                            this@apply.setMediaItem(MediaItem.fromUri(nextUrl?.invoke() ?: u))
                            this@apply.prepare()
                            this@apply.playWhenReady = true
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            playing = isPlaying
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED && reloadOnEnd) {
                                // Small backoff (like the VLC path) so a fast/empty clip can't hammer.
                                main.postDelayed({ refetch() }, 200)
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            // Same shape as the StreamVideo paths, so one grep for "stream failed"
                            // finds every engine's version of this.
                            android.util.Log.w(
                                "HaCamera",
                                "stream failed (exoplayer): ${safeUrlForLog(u)} — " +
                                    "${error.errorCodeName}: ${error.message}",
                            )
                            // A rolling-clip cam shouldn't die on a transient blip — retry instead of giving up.
                            // A local copy retries at once, since that costs nothing. A network URL
                            // waits: retrying it every second is how a refusing server (Cloudflare's
                            // 403) was kept refusing, with the app never getting a picture back.
                            when {
                                !reloadOnEnd -> failed = true
                                nextUrl != null -> main.postDelayed({ refetch() }, 1000)
                                else -> main.postDelayed({ refetch() }, CLIP_REFRESH_MS)
                            }
                        }
                    })
                }
            }
            DisposableEffect(u) { onDispose { main.removeCallbacksAndMessages(null); player.release() } }
            // key(u): rebuild the TextureView when the stream URL changes so the new player attaches
            // (the factory only wires the player to the view on first creation).
            key(u) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it); exoTexture = it } },
                )
            }
        }

        when {
            unavailable -> Text(
                "Another app is using the TV's video decoder, so this camera can't play live right now." +
                    "\nAdd a low-res stream for it in Settings → Cameras to watch it live over other apps.",
                color = Color.White, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .background(Color(0xB3000000))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )
            tooLarge -> Text(
                "Another app is using the TV's video decoder, and this camera's stream is too large " +
                    "to play without it.\nClose the other app, or add a low-res stream for this camera " +
                    "in Settings → Cameras.",
                color = Color.White, fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
            failed -> Text(
                "Couldn't start the camera stream.\nThis camera may not support live streaming.",
                color = Color.White, fontSize = 18.sp,
            )
            !playing && !useVlc -> Text("Connecting…", color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(40.dp))
        }

        Column(modifier = Modifier.align(Alignment.TopStart).padding(24.dp)) {
            Text(entity.friendlyName, color = Color.White, fontSize = 20.sp)
        }
        Text(
            "Press BACK to close",
            color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
        )
    }
}
