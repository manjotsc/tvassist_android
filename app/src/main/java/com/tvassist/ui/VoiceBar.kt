package com.tvassist.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import com.tvassist.data.assist.VoiceState
import com.tvassist.data.assist.VoiceUi
import kotlin.math.PI
import kotlin.math.sin

/**
 * The Assist voice bar: a bottom-centred surface that appears when the mic opens and leaves when the
 * answer has been read.
 *
 * Drawn inside the notification overlay window rather than a window of its own, which is what makes
 * stacking exact — [onHeightChanged] reports the bar's measured height so bottom-anchored
 * notification pills shift up by precisely that much instead of by a guessed constant.
 *
 * Deliberately not focusable and not D-pad navigable: OK and BACK reach it through
 * [com.tvassist.keymap.KeyCaptureService], so the bar never takes focus from whatever is playing.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VoiceBar(
    ui: VoiceUi?,
    level: Float,
    theme: OverlayTheme,
    onHeightChanged: (Dp) -> Unit = {},
) {
    // Kept so the exit animation still has something to draw after the controller clears the state.
    var last by remember { mutableStateOf<VoiceUi?>(null) }
    LaunchedEffect(ui) { if (ui != null) last = ui }
    val shown = ui ?: last
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().padding(20.dp)) {
        AnimatedVisibility(
            visible = ui != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            // Rises and settles rather than sliding at constant speed. The spring is what separates
            // "a panel appeared" from "something is listening to me".
            enter = slideInVertically(
                spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
            ) { it / 3 } +
                fadeIn(tween(200)) +
                scaleIn(tween(260), initialScale = 0.94f, transformOrigin = BOTTOM_CENTER),
            exit = slideOutVertically(tween(200)) { it / 4 } +
                fadeOut(tween(160)) +
                scaleOut(tween(200), targetScale = 0.96f, transformOrigin = BOTTOM_CENTER),
        ) {
            // The pills below need the slot back the moment the bar is gone, not on its last frame.
            // Dropping [last] here too — this runs after the exit animation, once there is nothing
            // left to draw — so a dismissed exchange's transcript and answer are not held alive for
            // the lifetime of the overlay window, and cannot flash behind the next one.
            DisposableEffect(Unit) { onDispose { onHeightChanged(0.dp); last = null } }
            if (shown != null) {
                BarBody(
                    ui = shown,
                    level = level,
                    theme = theme,
                    modifier = Modifier.onSizeChanged {
                        onHeightChanged(with(density) { it.height.toDp() } + BAR_GAP)
                    },
                )
            }
        }
    }
}

/**
 * Halo's palette.
 *
 * Unlike the panel this replaced, the ground deliberately does **not** follow the theme's
 * light/dark split. Halo has no surface — the text sits on a feathered pool over whatever is
 * playing — and a pale pool would have to be dense enough to carry dark text, which is a plate by
 * another name. So the ground is near-black and the text light in all fourteen presets.
 *
 * The theme is not lost. [scrim] carries [SCRIM_TINT] of the accent, so Mint's ground is faintly
 * green, Linen's warm, Daylight's blue: a light preset lends its temperature, not its surface.
 */
private data class BarSkin(
    /** The tinted near-black the text sits on, and the colour its shadow is cast in. */
    val scrim: Color,
    /**
     * The ambient text style with the halo shadow folded in.
     *
     * Built here rather than at each call site for two reasons: a bare `TextStyle(shadow = …)`
     * *replaces* [LocalTextStyle] rather than extending it, silently dropping any font the theme
     * sets; and building one per recomposition allocates on every partial transcript.
     */
    val style: TextStyle,
    val text: Color,
    val subText: Color,
    val error: Color,
    /**
     * Always false. The ribbon and [accentPalette] still branch on it, and with the ground now
     * fixed dark there is exactly one correct branch — kept as a field so that stays legible
     * rather than becoming a bare `false` at three call sites.
     */
    val lightSurface: Boolean = false,
    val glowBlend: BlendMode = BlendMode.Plus,
    val glowAlpha: Float = 0.09f,
    val midAlpha: Float = 0.20f,
)

@Composable
private fun rememberBarSkin(theme: OverlayTheme): BarSkin {
    val ambient = LocalTextStyle.current
    return remember(theme.accent, ambient) {
        val scrim = lerp(HALO_GROUND, theme.accent, SCRIM_TINT)
        BarSkin(
            scrim = scrim,
            style = ambient.copy(
                shadow = Shadow(
                    color = scrim.copy(alpha = 0.92f),
                    offset = Offset(0f, 1f),
                    blurRadius = 10f,
                ),
            ),
            text = HALO_TEXT,
            subText = HALO_SUB,
            // Not theme.errorText: that one derives from the tile, because the typed card paints on
            // a themed tile. Halo's ground is always dark, so the pale red is the only branch that
            // can ever apply here — deriving it would just be a longer way of writing this constant.
            error = HALO_ERROR,
        )
    }
}

/**
 * The ground: a bloom rising from the bar's own bottom edge.
 *
 * Anchored inside the layout rather than spilling past it, because [AnimatedVisibility]'s slide
 * clips to the animating bounds and anything drawn outside them is cut mid-transition.
 */
private fun CacheDrawScope.haloBloom(accent: Color): DrawResult {
    // A wide, low ellipse — not a circle. Keyed to the width alone it swamped the bar; keyed to
    // the height alone it collapsed to a dot, because the bar is far wider than it is tall. The
    // glow spreads with the width and rises with the height, which is the shape light actually
    // makes when it comes from below.
    val rx = size.width * BLOOM_SPREAD
    val ry = size.height * BLOOM_RISE
    if (rx <= 0f || ry <= 0f) return onDrawBehind { }
    val centre = Offset(size.width / 2f, size.height)
    val brush = Brush.radialGradient(
        0.00f to accent.copy(alpha = 0.34f),
        0.55f to accent.copy(alpha = 0.12f),
        1.00f to Color.Transparent,
        center = centre,
        radius = rx,
    )
    val squash = ry / rx
    return onDrawBehind {
        withTransform({ scale(1f, squash, pivot = centre) }) {
            drawCircle(brush = brush, radius = rx, center = centre)
        }
    }
}

/**
 * The pool the words sit in — an ellipse that fades to nothing well inside its own bounds, so the
 * text has contrast without anything acquiring an edge. Scaled rather than drawn as a circle
 * because a round pool under a wide line of text is visibly a blob.
 */
private fun CacheDrawScope.haloTextPool(scrim: Color): DrawResult {
    val rx = size.width * 0.62f
    val ry = size.height * 1.05f
    if (rx <= 0f || ry <= 0f) return onDrawBehind { }
    val centre = Offset(size.width / 2f, size.height / 2f)
    val brush = Brush.radialGradient(
        0.00f to scrim.copy(alpha = 0.74f),
        0.52f to scrim.copy(alpha = 0.44f),
        1.00f to Color.Transparent,
        center = centre,
        radius = rx,
    )
    val squash = ry / rx
    return onDrawBehind {
        withTransform({ scale(1f, squash, pivot = centre) }) {
            drawCircle(brush = brush, radius = rx, center = centre)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BarBody(ui: VoiceUi, level: Float, theme: OverlayTheme, modifier: Modifier = Modifier) {
    val skin = rememberBarSkin(theme)

    // The ribbon owns the bar while listening and gives the space back once there is an answer to
    // read. A fixed width rather than wrap-content: a footprint that resizes under a full-width
    // animation jitters on every frame.
    val waveHeight by animateDpAsState(
        targetValue = if (ui.phase.wantsWave) 34.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "wave-height",
    )

    Column(
        modifier = modifier
            .width(HALO_WIDTH)
            // drawWithCache, not drawBehind: the brushes are rebuilt only when the size or the
            // accent changes. Built inside the draw lambda they would allocate on every frame the
            // ribbon animates — the same waste the wave's own paths are pooled to avoid.
            .drawWithCache { haloBloom(theme.accent) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Column(
            // The pool draw sits OUTSIDE verticalScroll: inside it, it would scroll away with the
            // text and be clipped to the viewport instead of staying put behind it.
            modifier = Modifier
                .drawWithCache { haloTextPool(skin.scrim) }
                .heightIn(max = 148.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            BarText(ui, skin)
        }
        if (waveHeight > 0.dp) {
            VoiceWave(
                phase = ui.phase,
                level = level,
                theme = theme,
                skin = skin,
                modifier = Modifier.fillMaxWidth(0.62f).height(waveHeight),
            )
        }
    }
}

/** Whether this phase is one the ribbon should be on screen for. */
private val VoiceState.wantsWave: Boolean
    get() = this is VoiceState.Starting || this is VoiceState.Listening ||
        this is VoiceState.Thinking || this is VoiceState.Answering

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BarText(ui: VoiceUi, skin: BarSkin) {
    when (val phase = ui.phase) {
        VoiceState.Idle -> Unit

        VoiceState.Starting -> Caption("Opening the mic", skin)

        // No headline here on purpose. This is the silence before the first word, and it is the
        // only phase with nothing to show — the ribbon carries it.
        VoiceState.Listening -> Caption("Listening", skin)

        is VoiceState.Thinking ->
            if (phase.transcript.isBlank()) {
                Caption("Thinking", skin)
            } else {
                // Partial results arrive while the user is still talking; showing them large is
                // the moment the bar reads as alive rather than merely busy.
                Caption("Hearing you", skin)
                Headline(phase.transcript, skin.text, skin)
            }

        // Mid-answer: the same layout Done uses, so the text does not jump when the last delta
        // arrives and the finished reply replaces it.
        is VoiceState.Answering -> {
            Caption("Home Assistant", skin)
            if (phase.transcript.isNotBlank()) Said(phase.transcript, skin)
            Headline(phase.partial, skin.text, skin)
        }

        is VoiceState.Done -> {
            Caption("Home Assistant", skin)
            // The question, quoted and quiet: without a caption bar to hold it, this is the only
            // thing that makes the answer read as one half of an exchange.
            if (phase.transcript.isNotBlank()) Said(phase.transcript, skin)
            Headline(
                text = phase.reply.displayText,
                color = if (phase.reply.isError) skin.error else skin.text,
                skin = skin,
            )
        }

        is VoiceState.Failed -> {
            Caption("Could not answer", skin)
            Headline(phase.reason, skin.error, skin, size = 14.sp, line = 19.sp)
        }
    }
}

/*
 * Every line carries its own shadow in the ground's colour — see [BarSkin.style]. With no surface
 * behind it, that is what keeps a glyph legible where the pool has already faded out: the pool
 * handles the mass, the shadow handles the edges.
 */

/** Small dim label above the content — the line that tells you which state you are in. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Caption(text: String, skin: BarSkin) {
    Text(
        text = text.uppercase(),
        color = skin.subText,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.4.sp,
        textAlign = TextAlign.Center,
        style = skin.style,
    )
}

/** What you asked, once it has been answered. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Said(text: String, skin: BarSkin) {
    Text(
        text = "“" + text + "”",
        color = skin.subText,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        textAlign = TextAlign.Center,
        style = skin.style,
    )
}

/** The line you actually read from the couch. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Headline(
    text: String,
    color: Color,
    skin: BarSkin,
    size: TextUnit = 16.sp,
    line: TextUnit = 21.sp,
) {
    Text(
        text = text,
        color = color,
        fontSize = size,
        lineHeight = line,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        style = skin.style,
    )
}

/**
 * The ribbon that answers to the microphone.
 *
 * Three curves weave through one another, sharing a gradient drawn across the accent's neighbouring
 * hues. What makes it read as expensive rather than as a meter:
 *
 *  - **Layered bloom.** Each curve is stroked three times — wide and near-transparent, medium, then
 *    a thin bright core — added with [BlendMode.Plus] so crossings brighten where they overlap.
 *    This is a stand-in for a real blur, which is not an option: `RenderEffect` is API 31+ and the
 *    target BRAVIA runs Android 10.
 *  - **A tapered envelope.** Amplitude is multiplied by `sin(pi x)`, so the ribbon dissolves into
 *    the surface at both ends rather than being sliced off — a hard-cut wave is the cheapest tell
 *    there is.
 *  - **Motion that never repeats.** Each curve sums two sines at frequencies with no common period
 *    and drifts at its own rate, so the eye cannot find the loop.
 *
 * Every path is remembered and rewound rather than rebuilt, because allocating three `Path`s per
 * frame at 60fps is exactly the kind of garbage this has to avoid over a busy launcher background.
 */
@Composable
private fun VoiceWave(
    phase: VoiceState,
    level: Float,
    theme: OverlayTheme,
    skin: BarSkin,
    modifier: Modifier = Modifier,
) {
    val palette = remember(theme.accent, skin.lightSurface) {
        accentPalette(theme.accent, skin.lightSurface)
    }
    // Transparent at both ends. The envelope only flattens the curves there — it does not hide
    // them, so without this the three converge into a bright cap and the ribbon looks cut off
    // rather than dissolved into the surface.
    val brush = remember(palette) {
        Brush.horizontalGradient(
            0f to palette[0].copy(alpha = 0f),
            0.14f to palette[0],
            0.38f to palette[1],
            0.62f to palette[2],
            0.86f to palette[3],
            1f to palette[3].copy(alpha = 0f),
        )
    }
    val paths = remember { List(CURVES) { Path() } }

    val transition = rememberInfiniteTransition(label = "voice-wave")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "drift",
    )
    // Where the travelling bloom sits while the agent is thinking, as a fraction of the width.
    val sweep by transition.animateFloat(
        initialValue = -0.15f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "sweep",
    )

    val listening = phase is VoiceState.Listening
    // Whether the exchange is still going. A finished one keeps the ribbon on screen for as long as
    // the answer is read aloud - up to SPEECH_DEADLINE_MS - and animating through all of that meant
    // a translucent panel invalidating at 60fps over whatever is behind it, which on the BRAVIA
    // launcher is the one case measurement flags as expensive. The transition still runs; what
    // matters is that the draw below only READS it while live, so a settled bar stops redrawing.
    val live = phase is VoiceState.Starting || phase is VoiceState.Listening ||
        phase is VoiceState.Thinking || phase is VoiceState.Answering
    // Springs rather than a tween: the ribbon should swell and settle like something physical,
    // and collapse smoothly to its resting line when the mic closes.
    val amp by animateFloatAsState(
        // A generous floor, not a whisper: a quiet room is the common case, and a ribbon that only
        // comes alive when shouted at reads as broken rather than as sensitive.
        targetValue = if (listening) 0.42f + level * 0.58f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 340f),
        label = "amplitude",
    )

    Canvas(modifier) {
        val midY = size.height / 2f
        val maxAmp = size.height * 0.42f
        val step = size.width / SAMPLES

        // Settled: one flat stroke, and crucially no read of t or sweep, so nothing invalidates.
        if (!live && amp < 0.01f) {
            val resting = Path().apply { moveTo(0f, midY); lineTo(size.width, midY) }
            drawPath(resting, brush, style = Stroke(CORE_WIDTH.toPx(), cap = StrokeCap.Round), alpha = 0.92f)
            return@Canvas
        }

        for (curve in 0 until CURVES) {
            val path = paths[curve].apply { reset() }
            val gain = CURVE_GAIN[curve]
            val speed = CURVE_SPEED[curve]
            for (s in 0..SAMPLES) {
                val x = s * step
                val u = s.toFloat() / SAMPLES
                // Fades to nothing at both ends so the ribbon has no visible cut.
                val envelope = sin(PI.toFloat() * u)
                // Each curve starts a third of a cycle apart, so the three cross each other instead
                // of collapsing into one thick line when the room is quiet.
                val offset = curve * 2.1f
                val shape = sin(u * 11.2f + t * speed + offset) * 0.62f +
                    sin(u * 17.3f - t * speed * 0.73f + offset) * 0.38f
                val y = midY + maxAmp * amp * gain * envelope * shape
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            // Wide glow, then mid, then core. Additive so the overlaps are where it is brightest.
            drawPath(path, brush, style = Stroke(GLOW_WIDTH.toPx(), cap = StrokeCap.Round), alpha = skin.glowAlpha, blendMode = skin.glowBlend)
            drawPath(path, brush, style = Stroke(MID_WIDTH.toPx(), cap = StrokeCap.Round), alpha = skin.midAlpha, blendMode = skin.glowBlend)
            drawPath(path, brush, style = Stroke(CORE_WIDTH.toPx(), cap = StrokeCap.Round), alpha = 0.92f)
        }

        // Resting line: while the agent is thinking there is no audio to show, and a flat wave reads
        // as broken. A bloom running along the line keeps the "working" signal the dots' chase gave,
        // without pretending to hear anything.
        if (live && !listening && amp < 0.06f) {
            val cx = size.width * sweep
            val radius = size.height * 0.55f
            drawCircle(
                brush = brush,
                radius = radius,
                center = Offset(cx, midY),
                alpha = skin.glowAlpha * 1.8f,
                blendMode = skin.glowBlend,
            )
            drawCircle(
                brush = brush,
                radius = radius * 0.34f,
                center = Offset(cx, midY),
                alpha = 0.5f,
                blendMode = skin.glowBlend,
            )
        }
    }
}

/**
 * Neighbouring hues spread from the user's accent colour, used as the gradient along the ribbon.
 *
 * The motion is what reads as assistant-grade, not the palette — so this keeps an assistant's
 * rhythm while staying in the user's theme, rather than reproducing Google's blue/red/yellow/green
 * mark in a third-party app. A grey or white accent has no hue to spread; a gradient between
 * identical colours is just a flat fill, so those brighten and dim along the ribbon instead.
 */
private fun accentPalette(accent: Color, lightSurface: Boolean): List<Color> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    // A near-white accent — Mono's, for one — leaves nothing to see against a pale panel, so cap how
    // bright the ribbon is allowed to get there. Dark surfaces keep the accent exactly as chosen.
    val value = if (lightSurface) hsv[2].coerceAtMost(0.82f) else hsv[2]
    if (hsv[1] < 0.15f) {
        // No hue to spread. On a dark panel the accent varies by opacity; on a light one it has to
        // be taken toward ink first, or a white accent is simply invisible whatever its alpha.
        val ink = if (lightSurface) lerp(accent, Color.Black, 0.62f) else accent
        return listOf(0.72f, 1f, 0.84f, 0.60f).map { ink.copy(alpha = it) }
    }
    return List(PALETTE_STOPS) { i ->
        val hue = (hsv[0] + i * HUE_SPREAD) % 360f
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, hsv[1], value)))
    }
}

private const val PALETTE_STOPS = 4
private const val HUE_SPREAD = 24f
// Three curves is the fewest that still reads as woven rather than as one line wobbling.
/** Halo's fixed ground, and how much of the theme's accent it carries. */
private val HALO_GROUND = Color(0xFF08090C)
private const val SCRIM_TINT = 0.14f
private val HALO_TEXT = Color(0xFFF2F4F7)
private val HALO_SUB = Color(0xFF9AA3AE)
private val HALO_ERROR = Color(0xFFEF8A8A)
private val HALO_WIDTH = 520.dp

/** Aura geometry: how far it spreads across the bar's width, and how far it rises up its height. */
private const val BLOOM_SPREAD = 0.42f
private const val BLOOM_RISE = 1.35f

private const val CURVES = 3
private val CURVE_GAIN = floatArrayOf(1f, 0.72f, 0.46f)
// Deliberately not multiples of each other, so the three never drift back into step.
private val CURVE_SPEED = floatArrayOf(1f, 1.37f, 0.71f)
// Enough points that the curve is smooth at 680dp wide without stroking a needlessly long path.
private const val SAMPLES = 56
private val GLOW_WIDTH = 13.dp
private val MID_WIDTH = 6.dp
private val CORE_WIDTH = 1.8.dp
private const val TWO_PI = (2.0 * PI).toFloat()
private val BOTTOM_CENTER = TransformOrigin(0.5f, 1f)
// Breathing room between the bar and anything stacked above it.
private val BAR_GAP = 10.dp
