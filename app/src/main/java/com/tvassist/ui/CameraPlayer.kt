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
import androidx.compose.runtime.setValue
import com.tvassist.TvAssistApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository

/**
 * Fullscreen live camera view. Plays the camera's HLS stream with ExoPlayer rendered into a
 * [TextureView] (works inside the overlay window, unlike SurfaceView). Shows an instant
 * snapshot until the first video frame. Works both in-app and over the overlay. Parent owns BACK.
 */
@OptIn(UnstableApi::class)
@Composable
fun CameraPlayerScreen(entity: Entity, repository: HaRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    var url by remember(entity.entityId) { mutableStateOf<String?>(null) }
    var failed by remember(entity.entityId) { mutableStateOf(false) }
    var snapshot by remember(entity.entityId) { mutableStateOf<ImageBitmap?>(null) }
    var playing by remember(entity.entityId) { mutableStateOf(false) }

    // Seed the poster from the last captured frame (so even a snapshot-less camera shows something).
    LaunchedEffect(entity.entityId) { snapshot = CameraFrameCache.get(entity.entityId) }
    LaunchedEffect(entity.entityId) {
        // Local cameras use their own snapshot URL (if any); HA cameras fetch via the API.
        if (entity.isLocalCamera) {
            entity.localSnapshotUrl?.takeIf { it.isNotBlank() }?.let { snapUrl ->
                repository.fetchEntityPicture(snapUrl)?.let { snapshot = it.asImageBitmap() }
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
        val u = if (entity.isLocalCamera) entity.localStreamUrl else repository.cameraStreamUrl(entity.entityId)
        if (u.isNullOrBlank()) failed = true else url = u
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

        val u = url
        val useVlc = u != null &&
            (streamPlayer.equals("vlc", true) || (streamPlayer.equals("auto", true) && u.startsWith("rtsp", true)))
        // "Rolling clip" cameras (e.g. Québec 511) return a short finite MP4 per request; reload when
        // it ends so a fresh clip plays instead of freezing on the last frame.
        val reloadOnEnd = entity.isLocalCamera && entity.localRefresh
        if (u != null && useVlc) {
            // libVLC path (HEVC / quirky RTSP / software fallback).
            StreamVideo(url = u, player = "vlc", modifier = Modifier.fillMaxSize(), captureKey = entity.entityId, reloadOnEnd = reloadOnEnd)
        } else if (u != null) {
            var exoTexture by remember(u) { mutableStateOf<TextureView?>(null) }
            // Drives the rolling-clip reload/retry off the player callbacks (ExoPlayer is main-thread only).
            val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
            // Cache a live frame so this camera shows a real thumbnail/poster next time.
            LaunchedEffect(exoTexture) {
                if (exoTexture != null) repeat(6) { delay(2500); exoTexture?.grabFrameInto(entity.entityId) }
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
                        // Re-fetch the URL — a new HTTP request pulls the next fresh clip.
                        fun refetch() = runCatching {
                            this@apply.setMediaItem(MediaItem.fromUri(u))
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
                            android.util.Log.w("HaCamera", "exo error: ${error.errorCodeName}", error)
                            // A rolling-clip cam shouldn't die on a transient blip — retry instead of giving up.
                            if (reloadOnEnd) main.postDelayed({ refetch() }, 1000) else failed = true
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
