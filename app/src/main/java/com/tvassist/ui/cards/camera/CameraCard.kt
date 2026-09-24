package com.tvassist.ui.cards.camera

import com.tvassist.ui.EntityIconContent
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.cap
import com.tvassist.ui.cards.displayName

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.ui.focus.FocusRequester
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.cards.EntityCard
import com.tvassist.ui.cards.EntityControlActions
import com.tvassist.ui.cards.generic.GenericControls

/**
 * A tile that shows a camera's live snapshot (refreshed periodically) beside its name/state.
 * The frame fills the tile at 16:9 with the name and state over a scrim along the bottom, so a row
 * of cameras reads as pictures rather than as labels. Falls back to the entity's icon on a neutral
 * ground until the first frame arrives. A press opens the fullscreen player.
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
        // A rolling-clip camera's source publishes every 30 s, so polling it every 5 s fetched the
        // same picture six times over — often from a public site that blocks clients which ask too
        // often.
        val every = if (entity.isLocalCamera && entity.localRefresh) CLIP_REFRESH_MS else 5_000L
        var failures = 0
        while (isActive) {
            if (localSnap != null) {
                // fetchStillImage, not fetchEntityPicture: the latter caches by URL with no expiry,
                // so a fixed snapshot URL would pin the first frame and make this refresh loop a
                // silent no-op for local cameras.
                val still = repository.fetchStillImage(localSnap, TILE_SNAPSHOT_MAX_PX)
                if (still != null) frame = still.asImageBitmap()
                failures = if (still != null) 0 else failures + 1
            } else {
                repository.cameraSnapshot(entity.entityId, TILE_SNAPSHOT_REQUEST_W, TILE_SNAPSHOT_REQUEST_H)?.let { bytes ->
                    decodeOffThread(bytes, TILE_SNAPSHOT_MAX_PX)?.let { frame = it.asImageBitmap() }
                }
            }
            // A snapshot URL that keeps failing backs off like a rolling clip does, rather than
            // being asked again every few seconds for as long as the tile is on screen.
            delay(if (failures == 0) every else maxOf(every, clipRetryDelayMs(failures)))
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
        // The picture *is* the tile. This was a 54x40dp stamp in a row with the name beside it —
        // a camera reduced to a postage stamp, when the frame is the entire reason the tile exists.
        // 16:9 because that is what a camera sends; cropping to it beats letterboxing a dark band
        // into a panel that is mostly dark already.
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            val f = frame
            if (f != null) {
                Image(
                    bitmap = f,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(th.chip),
                    contentAlignment = Alignment.Center,
                ) {
                    EntityIconContent(entity, override, th.subText, sizeDp = 30, repository = repository)
                }
            }
            // Text over the picture, on a scrim rather than beside it.
            //
            // White and a fixed scrim, not the theme's colours: what is behind is a photograph, so
            // a light palette's dark text would vanish into a night-time frame and a dark palette's
            // light text into an overexposed one. The gradient is what guarantees the contrast, so
            // it is drawn even when no frame has arrived and the placeholder is showing.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xE6000000)),
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    displayName(entity, override),
                    fontSize = 14.sp,
                    color = Color.White,
                    maxLines = 1,
                )
                Text(
                    cap(entity.state),
                    fontSize = 11.sp,
                    color = Color(0xCCFFFFFF),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * What a camera tile asks Home Assistant for. HA's JPEG scaling works in power-of-two steps and
 * keeps the result at least this big, so a 4K frame arrives as 960x540 — still sharper than the
 * tile it fills (~530 px wide on a 1080p panel).
 */
private const val TILE_SNAPSHOT_REQUEST_W = 640
private const val TILE_SNAPSHOT_REQUEST_H = 360

/**
 * The largest edge a tile keeps in memory, for sources nothing has shrunk: a local camera's
 * snapshot URL, or an HA camera sending PNG (HA only rescales JPEG). ~2 MB, against ~33 MB for a
 * 4K frame decoded whole every 5 s on a TV measured with under 140 MB free.
 */
private const val TILE_SNAPSHOT_MAX_PX = 960

/**
 * Decode a JPEG/PNG off the main thread — camera frames are too big to decode on the UI dispatcher.
 *
 * With [maxPx], downsampled in power-of-two steps until the longest edge is at most [maxPx], which
 * is what [HaRepository.fetchStillImage] does for the same reason. Without it, full size.
 */
internal suspend fun decodeOffThread(bytes: ByteArray, maxPx: Int? = null): android.graphics.Bitmap? =
    withContext(Dispatchers.Default) {
        runCatching {
            val opts = BitmapFactory.Options()
            if (maxPx != null) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPx) sample *= 2
                opts.inSampleSize = sample
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

/**
 * The `camera` domain. A camera tile is a square thumbnail and a press opens the fullscreen
 * player, so the card body is only reached by an explicit "Controls" press action.
 */
internal object CameraCard : EntityCard {
    override val domains = setOf("camera")
    /** A picture is a shape, not a rung on the control ladder. */
    override fun autoStyle(e: Entity) = OverlayTile.STYLE_SQUARE

    @Composable
    override fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) =
        GenericControls(e, actions, firstFocus)
}
