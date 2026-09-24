package com.tvassist.ui.notify

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.notify.FixedPill
import com.tvassist.ui.IconifyIcon
import com.tvassist.ui.cap

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
        e.domain == "cover" -> if (e.isOpen) amber else green
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
