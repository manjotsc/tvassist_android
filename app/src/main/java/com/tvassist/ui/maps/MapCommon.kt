package com.tvassist.ui.maps

/*
 * Fullscreen location maps. The sidebar *tile* that opens one lives in
 * ui/cards/map/ — this package is the screens themselves.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.tvassist.data.settings.MapSource
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

/**
 * The key hint over a map, top-right. It names only keys that do something on this map: the
 * OpenStreetMap vector map has no satellite or traffic, so ◀ is not offered there at all.
 */
internal fun mapKeyHint(vector: Boolean, dark: Boolean, googleLook: String, traffic: Boolean): String =
    if (vector) {
        "▲▼ zoom · ▶ ${if (dark) "dark" else "light"} · BACK to close"
    } else {
        "▲▼ zoom · ▶ $googleLook · ◀ traffic ${if (traffic) "on" else "off"} · BACK to close"
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

/**
 * Drawn in place of a map that was set to Google Maps with no API key ([MapSource.missingKey]),
 * rather than quietly drawing OpenStreetMap instead.
 */
@Composable
internal fun MapKeyRequired(modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(MapSource.KEY_REQUIRED_TEXT, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(8.dp))
        Text(MapSource.KEY_REQUIRED_HINT, color = Color(0xFFB6C0CC), fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

/** Bounds on a follow glide: never a snap, and never so long a rarely-reporting tracker drags it out. */
internal const val FOLLOW_GLIDE_MIN_MS = 1000
internal const val FOLLOW_GLIDE_MAX_MS = 15_000

/** Fixes closer than this to the last one are GPS jitter, not movement, and are not chased. */
internal const val FOLLOW_DEADBAND_M = 5.0

/** Below this reported speed (m/s, HA's `speed` attribute) a tracker is treated as stopped: no prediction. */
internal const val FOLLOW_STOPPED_MPS = 1.0

/** The furthest a prediction may run ahead of the last real fix — bounds the overshoot at a turn or a stop. */
internal const val FOLLOW_PREDICT_MAX_M = 400.0

/** Where a follow glide is headed and how long it should take to get there. `durMs == 0` is a snap. */
internal data class FollowPlan(val lat: Double, val lng: Double, val durMs: Int)

/**
 * Turns a tracker's fixes into glide plans that keep a marker level with the person rather than
 * one reporting interval behind them.
 *
 * Gliding to each fix over the gap since the previous one (the old rule) is smooth but always late:
 * the marker reaches a fix just as the next one lands. So instead each glide aims at where the
 * person *will* be when the next fix is due — the new fix plus the velocity between the last two,
 * carried on for one interval. Under steady movement the marker then passes each real fix at about
 * the moment it is reported.
 *
 * **Slowing down is extrapolated; speeding up is not.** Constant velocity alone made the marker
 * sail on past every stop and then snap back once a fix said the car was parked — and a tracker
 * that reports less often while stationary left it sitting in the wrong place until then. So when
 * the last step was slower than the one before, the deceleration between them is assumed to
 * continue: the prediction shrinks as the car brakes, and ends where it would stop. Acceleration
 * is deliberately *not* carried on, since overshooting is the visible failure. What overshoot is
 * left (a turn, a sudden stop) is bounded by [FOLLOW_PREDICT_MAX_M] and corrected by the next fix:
 * a jitter-sized fix (inside [FOLLOW_DEADBAND_M]) or a reported speed under [FOLLOW_STOPPED_MPS]
 * aims back at the real point.
 *
 * Plain class, no Compose, so the arithmetic is unit-tested (`FollowPredictorTest`).
 */
internal class FollowPredictor {
    private var lastLat = Double.NaN
    private var lastLng = Double.NaN
    private var lastAtMs = 0L
    private var started = false
    /** Speed over the previous step, m/s; null when there was none (first move, or after standing still). */
    private var lastSpeedMps: Double? = null

    fun onFix(lat: Double, lng: Double, nowMs: Long, speedMps: Double?): FollowPlan {
        if (!started) {
            started = true
            lastLat = lat; lastLng = lng; lastAtMs = nowMs
            return FollowPlan(lat, lng, 0)
        }
        // Jitter while standing still: not movement. Aim at the last real fix, which also pulls a
        // prediction that ran on past a stop back to where the person actually is. The clock is
        // not restarted, so the next real move measures its gap from the last real one.
        if (distanceKm(lat, lng, lastLat, lastLng) * 1000.0 <= FOLLOW_DEADBAND_M) {
            lastSpeedMps = null
            return FollowPlan(lastLat, lastLng, FOLLOW_GLIDE_MIN_MS)
        }
        val rawGapMs = (nowMs - lastAtMs).coerceAtLeast(1L)
        val durMs = rawGapMs.coerceIn(FOLLOW_GLIDE_MIN_MS.toLong(), FOLLOW_GLIDE_MAX_MS.toLong())
        val prevLat = lastLat; val prevLng = lastLng
        val stepM = distanceKm(prevLat, prevLng, lat, lng) * 1000.0
        val v = stepM / (rawGapMs / 1000.0)
        val vPrev = lastSpeedMps
        lastLat = lat; lastLng = lng; lastAtMs = nowMs; lastSpeedMps = v

        val stopped = speedMps != null && speedMps < FOLLOW_STOPPED_MPS
        if (stopped) return FollowPlan(lat, lng, durMs.toInt())
        // Never stretch a step over more than its own length of time: a burst of fixes 200 ms
        // apart must not be extrapolated across a whole second.
        val t = minOf(durMs, rawGapMs) / 1000.0
        val aheadM = if (vPrev != null && v < vPrev) {
            // Braking: carry the same deceleration on, and stop where it reaches zero.
            val decel = (vPrev - v) / (rawGapMs / 1000.0)
            if (v - decel * t > 0) v * t - 0.5 * decel * t * t else v * v / (2 * decel)
        } else {
            v * t
        }.coerceAtMost(FOLLOW_PREDICT_MAX_M)
        val k = if (stepM > 0) aheadM / stepM else 0.0
        return FollowPlan(lat + (lat - prevLat) * k, lng + (lng - prevLng) * k, durMs.toInt())
    }
}

/** Feeds [lat],[lng] (and HA's `speed`, m/s) through a [FollowPredictor]; null until a fix exists. */
@Composable
internal fun rememberFollowPlan(lat: Double?, lng: Double?, speedMps: Double?): FollowPlan? {
    val predictor = remember { FollowPredictor() }
    var plan by remember { mutableStateOf<FollowPlan?>(null) }
    LaunchedEffect(lat, lng) {
        if (lat != null && lng != null) plan = predictor.onFix(lat, lng, System.currentTimeMillis(), speedMps)
    }
    return plan
}

/**
 * The point a marker or camera is drawn at while following [plan]: a constant-speed glide from
 * wherever it is to the plan's target. A new plan arriving mid-glide re-aims it from where it is,
 * so it carries on without stopping. Constant speed rather than easing — an ease-out stops at
 * every fix, and a per-frame fraction (the raster person map's old camera) also ran faster on a
 * faster display.
 */
@Composable
internal fun rememberGlide(plan: FollowPlan?): Pair<Double, Double>? {
    var shown by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(plan) {
        val p = plan ?: run { shown = null; return@LaunchedEffect }
        val from = shown
        if (from == null || p.durMs <= 0) {
            shown = p.lat to p.lng
            return@LaunchedEffect
        }
        val durMs = p.durMs.toDouble()
        val start = withFrameNanos { it }
        while (true) {
            val f = withFrameNanos { ((it - start) / 1_000_000.0 / durMs).coerceAtMost(1.0) }
            shown = (from.first + (p.lat - from.first) * f) to (from.second + (p.lng - from.second) * f)
            if (f >= 1.0) break
        }
    }
    return shown
}

/**
 * Where to draw a tracker whose reported fix is [lat],[lng]: predicted with [FollowPredictor] and
 * glided with [rememberGlide], so a marker moves continuously and level with the person instead
 * of hopping, or trailing a reporting interval behind. The map card's counterpart of the person
 * map's camera, which MapLibre animates itself.
 */
@Composable
internal fun rememberGlidingFix(lat: Double?, lng: Double?, speedMps: Double? = null): Pair<Double, Double>? {
    val plan = rememberFollowPlan(lat, lng, speedMps)
    return rememberGlide(if (lat == null || lng == null) null else plan)
}

/**
 * "Updated Ns ago" for a location entity. Callers pass `lastUpdated ?: lastChanged`, never
 * `lastChanged` alone: HA bumps `last_changed` only when the state *string* changes, so a car
 * driving around while `not_home` kept "Updated 13h ago" on screen as its coordinates moved, and
 * read as a map that had stopped following. `last_updated` moves with the attributes.
 */
internal fun updatedAgo(timestamp: String?, nowMs: Long): String {
    val ts = timestamp?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
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
