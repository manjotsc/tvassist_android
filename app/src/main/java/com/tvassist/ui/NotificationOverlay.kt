package com.tvassist.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import android.view.TextureView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.notify.FixedPill
import com.tvassist.data.notify.TvNotification

// Named colors (Material-ish palette) accepted in any color/background field.
private val COLOR_NAMES: Map<String, String> = mapOf(
    "red" to "#F44336", "pink" to "#E91E63", "purple" to "#9C27B0", "deeppurple" to "#673AB7",
    "indigo" to "#3F51B5", "blue" to "#2196F3", "lightblue" to "#03A9F4", "cyan" to "#00BCD4",
    "teal" to "#009688", "green" to "#4CAF50", "lightgreen" to "#8BC34A", "lime" to "#CDDC39",
    "yellow" to "#FFEB3B", "amber" to "#FFC107", "orange" to "#FF9800", "deeporange" to "#FF5722",
    "brown" to "#795548", "grey" to "#9E9E9E", "gray" to "#9E9E9E", "bluegrey" to "#607D8B",
    "bluegray" to "#607D8B", "black" to "#000000", "white" to "#FFFFFF", "silver" to "#C0C0C0",
    "gold" to "#FFD700", "magenta" to "#FF00FF", "fuchsia" to "#FF00FF", "violet" to "#EE82EE",
    "navy" to "#000080", "maroon" to "#800000", "olive" to "#808000", "aqua" to "#00FFFF",
    "turquoise" to "#40E0D0", "salmon" to "#FA8072", "coral" to "#FF7F50",
)

private val NUMBER_RE = Regex("""[-+]?\d*\.?\d+""")

/** Parses an rgb()/rgba() string, alpha as 0-1 or 0-255. */
private fun parseRgb(s: String): Color? {
    val n = NUMBER_RE.findAll(s).mapNotNull { it.value.toFloatOrNull() }.toList()
    if (n.size < 3) return null
    fun ch(v: Float) = (v.coerceIn(0f, 255f)) / 255f
    val a = n.getOrNull(3)
    val alpha = when { a == null -> 1f; a <= 1f -> a; else -> a / 255f }
    return Color(red = ch(n[0]), green = ch(n[1]), blue = ch(n[2]), alpha = alpha)
}

/** Accepts hex (#RGB/#RRGGBB/#AARRGGBB, or without '#'), rgb()/rgba(), or a named color. */
private fun String.toColorOrNull(): Color? {
    val s = trim().lowercase()
    if (s.isEmpty()) return null
    COLOR_NAMES[s.replace(" ", "")]?.let {
        return runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
    }
    if (s.startsWith("rgb")) return parseRgb(s)
    val hex = if (s.startsWith("#")) s else "#$s"
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
}

private fun widthForSize(size: String): Int = when (size.lowercase()) {
    "extra-small", "xs" -> 240
    "small" -> 300
    "large" -> 460
    else -> 380
}

/** True if [spec] should be loaded as a raster bitmap (photo/PNG/JPG) rather than a tinted vector. */
private fun isRasterIcon(spec: String): Boolean {
    val s = spec.lowercase()
    if (s.isBlank()) return false
    if (s.endsWith(".svg")) return false
    return s.endsWith(".png") || s.endsWith(".jpg") || s.endsWith(".jpeg") ||
        s.endsWith(".webp") || s.endsWith(".gif") || s.startsWith("/api/") ||
        (s.startsWith("http") && !s.endsWith(".svg"))
}

/** True if [url] is a live stream (rtsp/hls/dash) rather than a still image. */
private fun isStreamUrl(url: String): Boolean {
    val u = url.lowercase()
    return u.startsWith("rtsp://") || u.startsWith("rtsps://") ||
        u.contains(".m3u8") || u.endsWith(".mpd")
}

/** Resolve a card background spec to a color, or null to use the theme tile. */
private fun cardBackground(spec: String, tile: Color): Color = when (spec.lowercase()) {
    "" -> tile
    "transparent" -> Color.Transparent
    else -> spec.toColorOrNull() ?: tile
}

/**
 * Standalone default palette for notifications — deliberately independent of the control
 * overlay's theme, so toasts look consistent regardless of the user's overlay colors.
 * Every one of these is overridable per-notification from Home Assistant.
 */
private object NotifColors {
    val background = Color(0xFF1E2228)
    val accent = Color(0xFFE7ECF2)
    val iconChip = Color(0x22FFFFFF)
    val title = Color(0xFFF3F5F9)
    val text = Color(0xFFF3F5F9)
    val subText = Color(0xFFA7AFBC)
}

/** Apply an opacity 0-100 to a color's alpha; values outside that range leave it unchanged. */
private fun Color.withOpacity(op: Int): Color = if (op in 0..100) copy(alpha = op / 100f) else this

/** Resolve an icon/badge background spec, or null when unset (caller picks a default). */
private fun bgSpecOrNull(spec: String): Color? = when (spec.lowercase()) {
    "" -> null
    "transparent", "none" -> Color.Transparent
    else -> spec.toColorOrNull()
}

private fun alignmentFor(position: String): Alignment = when (position.lowercase()) {
    "top-left" -> Alignment.TopStart
    "top-center" -> Alignment.TopCenter
    "bottom-left" -> Alignment.BottomStart
    "bottom-center" -> Alignment.BottomCenter
    "bottom-right" -> Alignment.BottomEnd
    else -> Alignment.TopEnd
}

/** Renders the active pushed notifications as themed toasts/banners in their chosen corners. */
@Composable
fun NotificationOverlay(items: List<TvNotification>, repository: HaRepository, theme: OverlayTheme) {
    CompositionLocalProvider(LocalOverlayTheme provides theme) {
        Box(Modifier.fillMaxSize().padding(18.dp)) {
            items.groupBy { it.position }.forEach { (position, list) ->
                val fromTop = !position.lowercase().startsWith("bottom")
                Column(
                    modifier = Modifier.align(alignmentFor(position)),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    list.takeLast(5).forEach { n ->
                        key(n.id) { NotificationCard(n, repository, fromTop) }
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
private fun NotificationCard(n: TvNotification, repository: HaRepository, fromTop: Boolean) {
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
                    StreamVideo(url = vUrl, player = n.player, modifier = Modifier.fillMaxSize())
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


/**
 * Renders the pinned persistent pills as rows of small badges in their chosen corners. A pill with
 * a bound [FixedPill.entity] updates live from HA: its value, icon and color follow the entity's state.
 */
@Composable
fun FixedPillsOverlay(items: List<FixedPill>, repository: HaRepository) {
    val entities by repository.entities.collectAsState()
    val byId = remember(entities) { entities.associateBy { it.entityId } }
    Box(Modifier.fillMaxSize().padding(18.dp)) {
        items.groupBy { it.position }.forEach { (position, list) ->
            Row(
                modifier = Modifier.align(alignmentFor(position)),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                list.forEach { p -> key(p.id) { FixedPillView(p, byId[p.entity], repository) } }
            }
        }
    }
}

/**
 * Map the 0..1 animation [phase] to a flash intensity by style:
 *  - "blink" — hard on/off (crosses at the half-phase); makes the tempo/speed unmistakable.
 *  - "glow"  — never fully off; a sustained bright with a gentle pulse.
 *  - "pulse" — smooth breathe (default).
 */
private fun flashIntensity(type: String, phase: Float): Float = when (type.lowercase()) {
    "blink" -> if (phase > 0.5f) 1f else 0f
    "glow" -> 0.4f + 0.6f * phase
    else -> phase
}

/**
 * One flash cycle's period in milliseconds. Accepts either the legacy words (slow/medium/fast) or a
 * precise numeric ms value from the slider (clamped to a sane range); lower = faster. "" = medium.
 */
private fun flashPeriodMs(speed: String): Int = when (val s = speed.trim().lowercase()) {
    "slow" -> 1500
    "fast" -> 450
    "medium", "" -> 850
    else -> s.toIntOrNull()?.coerceIn(120, 6000) ?: 850
}

@Composable
private fun FixedPillView(p: FixedPill, entity: Entity?, repository: HaRepository) {
    val bound = p.entity.isNotBlank()
    // Live text: "label value" (value from the entity's state/attribute). Static pills use message.
    val text = if (bound) {
        val value = entity?.let { pillValue(it, p.attribute) }.orEmpty()
        listOf(p.label, value).filter { it.isNotBlank() }.joinToString(" ")
    } else {
        p.message
    }
    // State-driven accent when bound; an explicit color from the service call always wins.
    val stateColor = if (bound) entity?.let { pillStateColor(it) } else null
    val explicitIcon = p.iconColor.toColorOrNull()
    val baseIconTint = explicitIcon ?: stateColor ?: NotifColors.accent
    val textColor = p.messageColor.toColorOrNull() ?: NotifColors.title
    val baseBg = cardBackground(p.backgroundColor, Color(0xCC1E2228)).withOpacity(p.backgroundOpacity)
    val iconBg = bgSpecOrNull(p.iconBackground)?.withOpacity(p.iconBackgroundOpacity)
    val baseBorder = p.borderColor.toColorOrNull() ?: stateColor
    // Icon: explicit spec wins; else derive a (state-aware) icon from the bound entity.
    val iconSpec = p.icon.ifBlank { if (bound) entity?.let { pillIconSpec(it) }.orEmpty() else "" }

    // Flash / attention — setting a color pulses that element; icon and border are independent.
    // Each animates on its own tempo (flashIconSpeed / flashBorderSpeed) and maps its phase through
    // its own flash style.
    val borderFlashCol = p.flashBorderColor.toColorOrNull()
    val iconFlashCol = p.flashIconColor.toColorOrNull()
    val borderPeriodMs = flashPeriodMs(p.flashBorderSpeed)
    val iconPeriodMs = flashPeriodMs(p.flashIconSpeed)
    val borderPhase by rememberInfiniteTransition(label = "pillBorderFlash").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(borderPeriodMs, easing = LinearEasing), RepeatMode.Reverse),
        label = "pillBorderPhase",
    )
    val iconPhase by rememberInfiniteTransition(label = "pillIconFlash").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(iconPeriodMs, easing = LinearEasing), RepeatMode.Reverse),
        label = "pillIconPhase",
    )
    val borderIntensity = flashIntensity(p.flashBorderType, borderPhase)
    val iconIntensity = flashIntensity(p.flashIconType, iconPhase)
    val border = if (borderFlashCol != null) borderFlashCol.copy(alpha = 0.25f + 0.75f * borderIntensity) else baseBorder
    val borderWidth = if (borderFlashCol != null) 2.5.dp else 1.5.dp
    val iconTint = if (iconFlashCol != null) lerp(baseIconTint, iconFlashCol, iconIntensity) else baseIconTint

    val circle = p.shape.equals("circle", true)
    val shape = when (p.shape.lowercase()) {
        "circle" -> CircleShape
        "rectangular" -> RoundedCornerShape(8.dp)
        else -> RoundedCornerShape(percent = 50)
    }
    val hasText = text.isNotBlank()
    Row(
        modifier = Modifier
            .heightIn(min = 38.dp)
            .clip(shape)
            .background(baseBg)
            .then(if (border != null) Modifier.border(borderWidth, border, shape) else Modifier)
            .padding(horizontal = if (circle && !hasText) 8.dp else 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (iconSpec.isNotBlank()) {
            if (iconBg != null) {
                Box(
                    Modifier.clip(RoundedCornerShape(percent = 30)).background(iconBg).padding(5.dp),
                    contentAlignment = Alignment.Center,
                ) { PillIcon(iconSpec, iconTint, 24, repository) }
            } else {
                PillIcon(iconSpec, iconTint, 26, repository)
            }
        }
        if (hasText) {
            Text(text, color = textColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Live display value for a bound pill: the chosen attribute or the state, with a unit for sensors. */
private fun pillValue(e: Entity, attribute: String): String {
    if (attribute.isNotBlank()) return e.attributeString(attribute)?.let { cap(it) } ?: ""
    val unit = e.unitOfMeasurement?.trim().orEmpty()
    val s = e.state
    return when {
        unit == "°C" || unit == "°F" || unit == "°" -> "$s°"
        unit == "%" -> "$s%"
        unit.isNotBlank() -> "$s $unit"
        s.toDoubleOrNull() != null -> s
        else -> cap(s)
    }
}

/** State-driven accent: green = safe/secure/home/closed, amber = alert/open/away/unlocked. */
private fun pillStateColor(e: Entity): Color? {
    val green = Color(0xFF6FCF7F)
    val amber = Color(0xFFF2A33C)
    return when {
        e.isLock -> if (e.isLocked) green else amber
        e.domain == "binary_sensor" -> if (e.isOn) amber else green
        e.domain == "cover" -> if (e.isOn) amber else green
        e.isPerson -> if (e.state.equals("home", true)) green else amber
        e.domain in setOf("switch", "light", "fan", "input_boolean", "media_player") -> if (e.isOn) NotifColors.accent else null
        else -> null
    }
}

/** Icon for a bound pill with no explicit icon: HA's own (state-aware) icon, an avatar, or a domain glyph. */
private fun pillIconSpec(e: Entity): String {
    e.haIcon?.takeIf { it.contains(':') }?.let { return it }
    e.entityPicture?.let { return it }
    return when {
        e.isLock -> if (e.isLocked) "mdi:lock" else "mdi:lock-open-variant"
        e.domain == "binary_sensor" -> if (e.isOn) "mdi:alert-circle" else "mdi:check-circle"
        e.isPerson -> "mdi:account"
        e.domain == "light" -> "mdi:lightbulb"
        e.domain == "switch" || e.domain == "input_boolean" -> "mdi:toggle-switch-variant"
        e.domain == "cover" -> "mdi:window-shutter"
        e.domain == "climate" -> "mdi:thermostat"
        e.domain == "fan" -> "mdi:fan"
        e.domain == "media_player" -> "mdi:play-circle"
        e.domain == "sensor" -> "mdi:gauge"
        else -> "mdi:information-outline"
    }
}

@Composable
private fun PillIcon(spec: String, tint: Color, sizeDp: Int, repository: HaRepository) {
    if (isRasterIcon(spec)) {
        var bmp by remember(spec) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(spec) { bmp = repository.fetchEntityPicture(spec)?.asImageBitmap() }
        bmp?.let {
            Image(
                bitmap = it, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape(7.dp)),
            )
        }
    } else {
        IconifyIcon(spec, tint, sizeDp) {
            Icon(Icons.Rounded.Notifications, contentDescription = null, tint = tint, modifier = Modifier.size(sizeDp.dp))
        }
    }
}
