package com.tvassist.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Fullscreen live MJPEG camera view, rendered with a plain [Image] (no SurfaceView) so it
 * works inside the overlay window. Shows an instant snapshot, then live frames. BACK is
 * handled by the caller.
 */
@Composable
fun CameraMjpegFullscreen(entity: Entity, repository: HaRepository, name: String = entity.friendlyName) {
    var frame by remember(entity.entityId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(entity.entityId) {
        repository.cameraSnapshot(entity.entityId)?.let { bytes ->
            decodeOffThread(bytes)?.let { frame = it.asImageBitmap() }
        }
        repository.streamCameraMjpeg(entity.entityId) { bmp -> frame = bmp.asImageBitmap() }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val f = frame
        if (f != null) {
            Image(bitmap = f, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text("Connecting to $name…", color = Color.White, fontSize = 16.sp)
        }
        Text(name, color = Color.White, fontSize = 20.sp, modifier = Modifier.align(Alignment.TopStart).padding(24.dp))
    }
}

/**
 * A tile that shows a camera's live snapshot (refreshed periodically) beside its name/state.
 * Falls back to a camera icon until the first frame loads. Click opens the full card.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CameraTile(
    entity: Entity,
    repository: HaRepository,
    onOpen: (Entity) -> Unit,
    modifier: Modifier = Modifier,
    override: com.tvassist.data.settings.EntityOverride? = null,
) {
    // Seed with the last captured live frame (a snapshot-less camera still shows a thumbnail).
    var frame by remember(entity.entityId) { mutableStateOf(CameraFrameCache.get(entity.entityId)) }
    LaunchedEffect(entity.entityId) {
        val localSnap = entity.localSnapshotUrl?.takeIf { it.isNotBlank() }
        // Periodic snapshots — reliable for any camera (MJPEG isn't continuous on some).
        // A local camera without a snapshot URL relies on the cached frame (set above).
        if (entity.isLocalCamera && localSnap == null) return@LaunchedEffect
        while (isActive) {
            if (localSnap != null) {
                // fetchStillImage, not fetchEntityPicture: the latter caches by URL with no expiry,
                // so a fixed snapshot URL would pin the first frame and make this refresh loop a
                // silent no-op for local cameras.
                repository.fetchStillImage(localSnap)?.let { frame = it.asImageBitmap() }
            } else {
                repository.cameraSnapshot(entity.entityId)?.let { bytes ->
                    decodeOffThread(bytes)?.let { frame = it.asImageBitmap() }
                }
            }
            delay(5_000)
        }
    }

    val th = LocalOverlayTheme.current
    Surface(
        onClick = { onOpen(entity) },
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = th.tile,
            focusedContainerColor = th.tileFocused,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.045f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.5.dp, th.focus), shape = RoundedCornerShape(18.dp)),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(54.dp, 40.dp).clip(RoundedCornerShape(8.dp)).background(th.chip),
                contentAlignment = Alignment.Center,
            ) {
                val f = frame
                if (f != null) {
                    Image(
                        bitmap = f,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    EntityIconContent(entity, override, th.subText, sizeDp = 22, repository = repository)
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName(entity, override), fontSize = 14.sp, color = th.text, maxLines = 1)
                Text(cap(entity.state), fontSize = 11.sp, color = th.subText, maxLines = 1)
            }
        }
    }
}

/** Decode a JPEG/PNG off the main thread — camera frames are too big to decode on the UI dispatcher. */
internal suspend fun decodeOffThread(bytes: ByteArray): android.graphics.Bitmap? =
    withContext(Dispatchers.Default) {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }
