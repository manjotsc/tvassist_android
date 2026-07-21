package com.tvassist.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.settings.EntityOverride
import com.tvassist.data.settings.PressAction

/** Display name for an entity, honoring a custom override name. */
fun displayName(entity: Entity, override: EntityOverride?): String =
    override?.name?.takeIf { it.isNotBlank() } ?: entity.friendlyName

/**
 * Whether the tile should highlight as "on", honoring a custom display-state override.
 * For [DisplayState.MIRROR], [lookup] resolves the source entity by id (pass one from the current
 * entity list); with a numeric threshold set, "on" means the source's value is >= the threshold.
 */
fun effectiveOn(
    entity: Entity,
    override: EntityOverride?,
    lookup: ((String) -> Entity?)? = null,
): Boolean = when (override?.displayState) {
    com.tvassist.data.settings.DisplayState.ON -> true
    com.tvassist.data.settings.DisplayState.OFF -> false
    com.tvassist.data.settings.DisplayState.MIRROR -> {
        val src = override.mirrorEntityId.takeIf { it.isNotBlank() }?.let { lookup?.invoke(it) }
        val thr = override.mirrorThreshold
        when {
            src == null -> entity.isOn
            thr != null -> (src.state.toDoubleOrNull() ?: 0.0) >= thr
            else -> src.isOn
        }
    }
    // A locked door reads as the "active/secure" (green) state; every other domain uses on/off.
    else -> if (entity.isLock) entity.isLocked else entity.isOn
}

/** Display icon for an entity, honoring a custom override icon. */
fun displayIcon(entity: Entity, override: EntityOverride?): ImageVector =
    override?.icon?.takeIf { it.isNotBlank() }?.let { iconForKey(it) } ?: domainIcon(entity)

/**
 * The Iconify icon name (e.g. "mdi:lightbulb") to render for an entity, or null to fall back
 * to a built-in Material vector. Order: a custom override icon, then HA's own icon attribute.
 */
fun resolveIconifyName(entity: Entity, override: EntityOverride?): String? {
    val ov = override?.icon
    if (ov != null && ov.contains(':')) return ov
    if (ov != null && ov.isNotBlank()) return null // legacy curated key → vector
    // HA's explicit icon, then its device_class default (temperature → mdi:thermometer, …), then the
    // domain's HA default (light → mdi:lightbulb, …) so everything matches HA, not just sensors.
    return entity.haIcon?.takeIf { it.contains(':') }
        ?: deviceClassIconifyName(entity)
        ?: domainIconifyName(entity)
}

/**
 * Renders an entity's icon at [sizeDp], tinted [tint]. Priority: a custom override icon, then
 * the entity's own photo (entity_picture, e.g. a person's avatar — needs [repository] to fetch),
 * then HA's Iconify icon, then a built-in Material vector. Works in the overlay window.
 */
@Composable
fun EntityIconContent(
    entity: Entity,
    override: EntityOverride?,
    tint: Color,
    sizeDp: Int = 21,
    repository: HaRepository? = null,
) {
    val ov = override?.icon
    val legacyVector = ov?.takeIf { it.isNotBlank() && !it.contains(':') }?.let { iconForKey(it) }
    val fallback: @Composable () -> Unit = {
        Icon(legacyVector ?: domainIcon(entity), null, tint = tint, modifier = Modifier.size(sizeDp.dp))
    }
    when {
        // Explicit custom icon chosen by the user wins.
        ov != null && ov.contains(':') -> IconifyIcon(ov, tint, sizeDp, fallback)
        ov != null && ov.isNotBlank() -> fallback()
        // The entity's own photo (person avatar, etc.) — render as a circular image.
        entity.entityPicture != null && repository != null ->
            EntityPhoto(entity.entityPicture!!, repository, fallback)
        // HA's own icon, then its device_class default, then the domain default — all via MDI so the
        // whole list matches HA. Anything unmapped/unfetchable falls back to the Material glyph.
        entity.haIcon?.contains(':') == true -> IconifyIcon(entity.haIcon!!, tint, sizeDp, fallback)
        else -> {
            val mdi = deviceClassIconifyName(entity) ?: domainIconifyName(entity)
            if (mdi != null) IconifyIcon(mdi, tint, sizeDp, fallback) else fallback()
        }
    }
}

/** Fetches an entity's photo (entity_picture) and renders it filling its circular chip. */
@Composable
private fun EntityPhoto(path: String, repository: HaRepository, fallback: @Composable () -> Unit) {
    var bmp by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) { bmp = repository.fetchEntityPicture(path)?.asImageBitmap() }
    val b = bmp
    if (b != null) {
        Image(bitmap = b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
    } else {
        fallback()
    }
}

/**
 * Renders an Iconify/MDI icon (or an SVG icon URL) faithfully via Coil's SVG decoder, tinted to
 * [tint], showing [fallback] while loading or on error. Using a real SVG renderer fixes complex
 * icons (e.g. mdi:vector-square) the old hand-rolled parser mis-tessellated.
 */
@Composable
fun IconifyIcon(name: String, tint: Color, sizeDp: Int, fallback: @Composable () -> Unit) {
    val context = LocalContext.current
    // A plain painter, NOT SubcomposeAsyncImage: the latter runs a subcomposition + extra measure
    // pass per icon, which is the dominant cost when scrolling a list of many icon rows. But because
    // rememberAsyncImagePainter can't infer the layout size, the request MUST carry an explicit pixel
    // size or Coil's SVG decoder rasterizes at 0×0 and the icon renders blank — hence .size(px).
    val px = with(LocalDensity.current) { sizeDp.dp.roundToPx() }.coerceAtLeast(1)
    val request = remember(name, px) {
        ImageRequest.Builder(context).data(IconLoader.iconUrl(name)).size(px).build()
    }
    val painter = rememberAsyncImagePainter(model = request, imageLoader = IconLoader.get(context))
    when (painter.state) {
        is AsyncImagePainter.State.Success ->
            Image(
                painter = painter,
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(sizeDp.dp),
            )
        else -> fallback()
    }
}

/** Whether the given press action does nothing (so a row/tile can stay un-clickable feel). */
fun isNoAction(action: String): Boolean = action == PressAction.NONE

/**
 * Runs the configured press [action] for [entity]. [openCard] opens the control card;
 * `single` selects the default behaviour for [PressAction.DEFAULT].
 */
fun performPress(
    action: String,
    entity: Entity,
    actions: EntityControlActions,
    openCard: (Entity) -> Unit,
    single: Boolean,
) {
    when (action) {
        PressAction.DEFAULT ->
            if (single) {
                when {
                    entity.isButton -> actions.press(entity)
                    entity.isToggleable -> actions.toggle(entity)
                    else -> openCard(entity)
                }
            } else {
                openCard(entity)
            }
        PressAction.TOGGLE -> actions.toggle(entity)
        PressAction.MORE -> openCard(entity)
        PressAction.TURN_ON -> actions.turnOn(entity)
        PressAction.TURN_OFF -> actions.turnOff(entity)
        PressAction.RUN -> actions.run(entity)
        PressAction.NONE -> {}
    }
}
