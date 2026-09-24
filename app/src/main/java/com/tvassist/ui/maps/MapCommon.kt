package com.tvassist.ui.maps

/*
 * Fullscreen location maps. The sidebar *tile* that opens one lives in
 * ui/cards/map/ — this package is the screens themselves.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Global pixel coordinate (256 px/tile) of a lat/lng at zoom [z] — matches fetchPersonMap. */
internal fun worldPx(lat: Double, lng: Double, z: Int = 16): Pair<Double, Double> {
    val n = (1 shl z).toDouble()
    val gx = (lng + 180.0) / 360.0 * n * 256.0
    val gy = (1.0 - kotlin.math.asinh(kotlin.math.tan(Math.toRadians(lat))) / Math.PI) / 2.0 * n * 256.0
    return gx to gy
}

internal fun fmt1(v: Double): String = ((v * 10).roundToInt() / 10.0).toString()

/** Haversine great-circle distance in km. */
internal fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

internal fun updatedAgo(lastChanged: String?, nowMs: Long): String {
    val ts = lastChanged?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
        ?: return "Live"
    val s = ((nowMs - ts) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "Updated ${s}s ago"
        s < 3600 -> "Updated ${s / 60}m ago"
        else -> "Updated ${s / 3600}h ago"
    }
}

// ---------------------------------------------------------------------------------------------
// Multi-entity "map card": one map framing several people/device-trackers relative to home.
// ---------------------------------------------------------------------------------------------

@Composable
internal fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color(0xFF8A94A3), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, color = Color(0xFFD7DEE7), fontSize = 14.sp)
    }
}

/**
 * Dark rounded backing for text floating over map imagery. Map tiles are light, so plain white text
 * washes out over them — the zoom indicator and the person info card already use this treatment, so
 * labels and hints match rather than being the one unreadable element on the screen.
 */
internal fun Modifier.mapPill(): Modifier = this
    .clip(RoundedCornerShape(12.dp))
    .background(Color(0xC00E141C))
    .padding(horizontal = 10.dp, vertical = 6.dp)
