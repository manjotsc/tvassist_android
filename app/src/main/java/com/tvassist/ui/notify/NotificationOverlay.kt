package com.tvassist.ui.notify

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.tvassist.ui.cards.camera.decodeOffThread
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.notify.TvNotification
import com.tvassist.ui.IconLoader
import com.tvassist.ui.IconifyIcon
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.OverlayTheme
import com.tvassist.ui.StreamVideo
import com.tvassist.ui.cap

private fun widthForSize(size: String): Int = when (size.lowercase()) {
    "extra-small", "xs" -> 240
    "small" -> 300
    "large" -> 460
    else -> 380
}

/** True if [url] is a live stream (rtsp/hls/dash) rather than a still image. */
private fun isStreamUrl(url: String): Boolean {
    val u = url.lowercase()
    return u.startsWith("rtsp://") || u.startsWith("rtsps://") ||
        u.contains(".m3u8") || u.endsWith(".mpd")
}

/**
 * Whether [n] carries video — the same three cases [NotificationCard] resolves a stream from,
 * decidable before that resolution runs (a camera's stream URL comes back from HA asynchronously).
 */
private fun hasVideo(n: TvNotification): Boolean =
    n.cameraStream.isNotBlank() ||
        (n.mediaType.equals("video", true) && n.mediaUrl.isNotBlank()) ||
        isStreamUrl(n.mediaUrl)

/** Renders the active pushed notifications as themed toasts/banners in their chosen corners. */
@Composable
fun NotificationOverlay(
    items: List<TvNotification>,
    repository: HaRepository,
    theme: OverlayTheme,
    /** How far bottom-anchored pills must sit above the bottom edge — the voice bar's height. */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** The notification [NotificationEnlarged] is showing, if any; its card stops its own video. */
    enlargedId: String? = null,
) {
    // At most one notification video plays: the newest, and none while one is enlarged (which plays
    // its own). Each player is a hardware decoder and, for a 4K camera, ~260 MB of buffers; two at
    // once — a card and its enlarged view — took the UR3 to 120 MB free with a dozen apps killed and
    // a black enlarged view. Cards that don't play keep their poster.
    val playingId = if (enlargedId != null) null else items.lastOrNull(::hasVideo)?.id
    CompositionLocalProvider(LocalOverlayTheme provides theme) {
        Box(Modifier.fillMaxSize().padding(18.dp)) {
            items.groupBy { it.position }.forEach { (position, list) ->
                val fromTop = !position.lowercase().startsWith("bottom")
                Column(
                    modifier = Modifier.align(alignmentFor(position))
                        // Bottom-anchored pills move up by exactly the bar's measured height while
                        // it is showing, so the two never overlap and neither guesses at the other.
                        .padding(bottom = if (fromTop) 0.dp else bottomInset),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    list.takeLast(5).forEach { n ->
                        key(n.id) { NotificationCard(n, repository, fromTop, playVideo = n.id == playingId) }
                    }
                }
            }
        }
    }
}

/**
 * Fullscreen "enlarged" view of an interactive notification's camera (driven by the remote via
 * [NotificationStore.enlargedId]). Reuses the same stream/snapshot resolution as the toast card.
 * Rendered on top of the toasts in the notification overlay window.
 */
@Composable
fun NotificationEnlarged(items: List<TvNotification>, enlargedId: String?, repository: HaRepository) {
    val n = enlargedId?.let { id -> items.firstOrNull { it.id == id } } ?: return
    var vUrl by remember(n.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(n.id, n.cameraStream, n.mediaUrl, n.mediaType) {
        vUrl = when {
            n.cameraStream.isNotBlank() -> repository.cameraStreamUrl(n.cameraStream)
            n.mediaType.equals("video", true) && n.mediaUrl.isNotBlank() -> n.mediaUrl
            isStreamUrl(n.mediaUrl) -> n.mediaUrl
            else -> null
        }
    }
    var still by remember(n.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(n.id, vUrl) {
        val camId = n.cameraStream.ifBlank { n.camera }
        if (vUrl == null && camId.isNotBlank()) {
            still = repository.cameraSnapshot(camId)?.let { decodeOffThread(it)?.asImageBitmap() }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val v = vUrl
        when {
            v != null -> StreamVideo(url = v, player = n.player, modifier = Modifier.fillMaxSize())
            still != null -> Image(still!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else -> Text("Loading ${n.title.ifBlank { "camera" }}…", color = Color.White, fontSize = 18.sp)
        }
        Text(
            "BACK to close",
            color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
        )
    }
}

@Composable
private fun NotificationCard(
    n: TvNotification,
    repository: HaRepository,
    fromTop: Boolean,
    /**
     * Whether this card may play its video; otherwise it shows the poster. False for every card but
     * the newest video one, and for all of them while a notification is enlarged — see
     * NotificationOverlay's `playingId`.
     */
    playVideo: Boolean = true,
) {
    // The border color, when explicitly set, also tints the icon — otherwise no border, no chip,
    // neutral icon tint.
    val borderColor = n.borderColor.toColorOrNull()
    val iconDefaultTint = borderColor ?: NotifColors.accent
    // No circular chip behind the icon by default; a colored chip only if a border color is set.
    val iconDefaultBg = borderColor?.copy(alpha = 0.22f) ?: Color.Transparent
    val borderStroke = borderColor?.copy(alpha = 0.6f)
    val shape = RoundedCornerShape(16.dp)
    val baseBg = cardBackground(n.backgroundColor, NotifColors.background).withOpacity(n.backgroundOpacity)

    // Flashing / attention animation (none/glow/pulse/flash/blink), auto-runs while shown.
    val mode = n.flash.lowercase()
    val flashing = mode.isNotBlank() && mode != "none"
    val periodMs = flashPeriodMs(n.flashSpeed)
    val phase by rememberInfiniteTransition(label = "flash").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Reverse),
        label = "flashPhase",
    )
    val flashCol = n.flashColor.toColorOrNull() ?: (borderColor ?: Color(0xFFFF5252))
    val cardBg = if (flashing && mode == "flash") lerp(baseBg, flashCol, phase * 0.85f) else baseBg
    val cardAlpha = if (flashing && mode == "blink") 1f - 0.8f * phase else 1f
    val glowBorder = when {
        flashing && mode == "glow" -> flashCol.copy(alpha = 0.2f + 0.8f * phase)
        else -> borderStroke
    }
    val glowWidth = if (flashing && mode == "glow") 2.5.dp else 1.5.dp

    // A still image to show in the media area (camera snapshot, image URL, or a non-stream media_url).
    val stillUrl = when {
        n.mediaType.equals("video", true) -> null
        isStreamUrl(n.mediaUrl) -> null
        n.image.isNotBlank() -> n.image
        n.mediaUrl.isNotBlank() -> n.mediaUrl
        else -> null
    }
    var image by remember(n.id) { mutableStateOf<ImageBitmap?>(null) }
    // Keyed on createdAt as well as id: cards compose under key(n.id), so replacing a notification
    // that reuses an id (a doorbell firing twice inside its duration) keeps the same composition
    // alive. Without createdAt the URL is unchanged, the effect never re-runs, and the second ring
    // would show the first ring's photo.
    LaunchedEffect(n.id, n.createdAt, stillUrl, n.camera) {
        image = when {
            n.camera.isNotBlank() ->
                repository.cameraSnapshot(n.camera)?.let { decodeOffThread(it)?.asImageBitmap() }
            stillUrl != null -> repository.fetchStillImage(stillUrl)?.asImageBitmap()
            else -> null
        }
    }
    // Resolve the video source: a camera entity's live stream, or a stream media_url (auto-detected).
    var videoUrl by remember(n.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(n.id, n.cameraStream, n.mediaUrl, n.mediaType) {
        videoUrl = when {
            n.cameraStream.isNotBlank() -> repository.cameraStreamUrl(n.cameraStream)
            n.mediaType.equals("video", true) && n.mediaUrl.isNotBlank() -> n.mediaUrl
            isStreamUrl(n.mediaUrl) -> n.mediaUrl
            else -> null
        }
    }
    // Instant snapshot poster shown under the video while the stream starts (masks startup latency).
    var videoPoster by remember(n.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(n.id, n.createdAt, n.cameraStream) {
        val camId = n.cameraStream.ifBlank { n.camera }
        if (camId.isNotBlank()) {
            repository.cameraSnapshot(camId)?.let { bytes ->
                decodeOffThread(bytes)?.let { videoPoster = it.asImageBitmap() }
            }
        }
    }

    var visible by remember(n.id) { mutableStateOf(false) }
    LaunchedEffect(n.id) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { if (fromTop) -it / 2 else it / 2 },
        exit = fadeOut() + slideOutVertically { if (fromTop) -it / 2 else it / 2 },
    ) {
        // `size` scales text/icon/media together (width still auto-adjusts to content).
        val sizeScale = when (n.size.lowercase()) {
            "extra-small", "xs" -> 0.72f
            "small" -> 0.85f
            "large" -> 1.2f
            else -> 1f
        }
        // Header (icon + text) sits on top; media spans the FULL card width below it (under the
        // icon too). The icon caps to the header's text height (scaled baseline) so it never grows the card.
        val density = LocalDensity.current
        var headerPx by remember(n.id) { mutableIntStateOf(0) }
        val scaledIconSize = (n.iconSize * sizeScale).toInt()
        val baselineDp = 44f * sizeScale
        val cap = if (headerPx > 0) {
            minOf(scaledIconSize, maxOf(with(density) { headerPx.toDp() }.value, baselineDp).toInt()).coerceAtLeast(20)
        } else {
            minOf(scaledIconSize, baselineDp.toInt())
        }
        Column(
            // Width shrinks to fit short content, up to the size's max (media fills to the max).
            modifier = Modifier.widthIn(min = (180 * sizeScale).dp, max = widthForSize(n.size).dp)
                .alpha(cardAlpha)
                .clip(shape).background(cardBg)
                .then(if (glowBorder != null) Modifier.border(glowWidth, glowBorder, shape) else Modifier)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NotificationIcon(
                    spec = n.icon.ifBlank { "mdi:bell" },
                    smallSpec = n.smallIcon,
                    iconSize = cap,
                    smallSize = n.smallIconSize.coerceAtMost(cap),
                    iconTint = n.iconColor.toColorOrNull() ?: iconDefaultTint,
                    smallTint = n.smallIconColor.toColorOrNull() ?: iconDefaultTint,
                    iconBg = (bgSpecOrNull(n.iconBackground) ?: iconDefaultBg).withOpacity(n.iconBackgroundOpacity),
                    smallBg = (bgSpecOrNull(n.smallIconBackground) ?: cardBg).withOpacity(n.smallIconBackgroundOpacity),
                    ringColor = cardBg,
                    repository = repository,
                )
                Spacer(Modifier.width(11.dp))
                // No weight → the text column wraps to its content, so short text makes a narrow card.
                Column(modifier = Modifier.onSizeChanged { headerPx = it.height }) {
                    // Source sits on its own line ABOVE the title (small grey header line);
                    // a 2nd segment is appended after a "•".
                    if (n.source.isNotBlank() || n.source2.isNotBlank()) {
                        val sourceColor = n.sourceColor.toColorOrNull() ?: NotifColors.subText
                        Row {
                            if (n.source.isNotBlank()) {
                                Text(
                                    n.source, color = sourceColor,
                                    fontSize = (12 * sizeScale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                                )
                            }
                            if (n.source.isNotBlank() && n.source2.isNotBlank()) {
                                Text(
                                    "•", color = sourceColor, fontSize = (12 * sizeScale).sp,
                                    modifier = Modifier.padding(horizontal = 6.dp).alignByBaseline(),
                                )
                            }
                            if (n.source2.isNotBlank()) {
                                Text(
                                    n.source2, color = sourceColor,
                                    fontSize = (12 * sizeScale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.alignByBaseline(),
                                )
                            }
                        }
                    }
                    if (n.title.isNotBlank()) {
                        Text(
                            n.title, color = n.titleColor.toColorOrNull() ?: NotifColors.title,
                            fontSize = (16 * sizeScale).sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (n.message.isNotBlank()) {
                        Text(
                            // Collapse runs of whitespace so a double space / stray newline doesn't
                            // leave a leading gap when the line wraps.
                            n.message.replace(Regex("\\s+"), " ").trim(),
                            color = n.messageColor.toColorOrNull() ?: if (n.title.isBlank()) NotifColors.text else NotifColors.subText,
                            fontSize = (14 * sizeScale).sp,
                            lineHeight = (18 * sizeScale).sp,
                            maxLines = 3,
                        )
                    }
                }
            }
            // Media: full card width (spans under the icon column too).
            val vUrl = videoUrl
            when {
                vUrl != null -> Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
                ) {
                    // Snapshot shows instantly; the video draws over it once it starts.
                    videoPoster?.let {
                        Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    if (playVideo) StreamVideo(url = vUrl, player = n.player, modifier = Modifier.fillMaxSize())
                }
                image != null -> Image(
                    bitmap = image!!,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().heightIn(max = (320 * sizeScale).dp).clip(RoundedCornerShape(10.dp)),
                )
            }
            if (n.interactive) {
                Text(
                    "OK to view · BACK to dismiss",
                    color = NotifColors.subText, fontSize = (11 * sizeScale).sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** The large icon plus an optional small-icon badge on its top-right corner. */
@Composable
private fun NotificationIcon(
    spec: String,
    smallSpec: String,
    iconSize: Int,
    smallSize: Int,
    iconTint: Color,
    smallTint: Color,
    iconBg: Color,
    smallBg: Color,
    ringColor: Color,
    repository: HaRepository,
) {
    Box(Modifier.size(iconSize.dp)) {
        IconBubble(spec, iconTint, iconBg, iconSize, repository)
        if (smallSpec.isNotBlank()) {
            // Nudge the badge slightly off the icon's top-right corner, scaled to its size.
            val nudge = (smallSize * 0.22f).dp
            val badge = Modifier.align(Alignment.TopEnd).offset(x = nudge, y = -nudge).size(smallSize.dp)
            // Only draw the card-colored separator ring when the badge actually has a fill;
            // a transparent badge background should show no shape at all.
            Box(
                modifier = if (smallBg.alpha > 0f) badge.clip(RoundedCornerShape(percent = 28)).background(ringColor).padding(2.dp) else badge,
                contentAlignment = Alignment.Center,
            ) {
                IconBubble(smallSpec, smallTint, smallBg, (smallSize * 0.84f).toInt(), repository)
            }
        }
    }
}

/** A rounded-square chip rendering either a tinted vector icon or a cropped raster photo/avatar. */
@Composable
private fun IconBubble(spec: String, tint: Color, bg: Color, sizeDp: Int, repository: HaRepository) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(percent = 26)
    Box(
        Modifier.size(sizeDp.dp).clip(shape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (isRasterIcon(spec)) {
            if (spec.startsWith("http")) {
                // Decode at full resolution and downscale with high quality (sharper than letting
                // Coil downsample to the small view size, especially for the larger avatar).
                AsyncImage(
                    model = ImageRequest.Builder(context).data(spec).size(coil.size.Size.ORIGINAL).build(),
                    imageLoader = IconLoader.get(context),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize().clip(shape),
                )
            } else {
                // HA entity_picture path (needs the auth header) — fetched manually.
                var bmp by remember(spec) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(spec) { bmp = repository.fetchEntityPicture(spec)?.asImageBitmap() }
                bmp?.let {
                    Image(
                        bitmap = it, contentDescription = null, contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier.fillMaxSize().clip(shape),
                    )
                }
            }
        } else {
            // Fill most of the bubble when there's no chip (like a photo does); leave padding
            // only when a chip background is drawn behind the icon.
            val inner = (sizeDp * if (bg.alpha > 0f) 0.62f else 0.92f).toInt()
            IconifyIcon(spec, tint, inner) {
                Icon(Icons.Rounded.Notifications, contentDescription = null, tint = tint, modifier = Modifier.size(inner.dp))
            }
        }
    }
}
