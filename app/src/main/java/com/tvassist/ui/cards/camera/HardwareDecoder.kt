package com.tvassist.ui.cards.camera

import android.media.MediaCodec
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * Whether this app can get a hardware video decoder right now.
 *
 * Why it has to ask: while a DRM app (Netflix, Prime, Disney+) has a video open — **even paused** —
 * the UR3 refuses every other app a hardware decoder. Its `media_codecs_4k_with_dv.xml` declares
 * `supports-secure-with-non-secure-codec = false`, and Android only reclaims a decoder from a
 * lower-priority process, which the app on screen never is for an overlay. The refusal is silent to
 * libVLC's callers: it falls back to software, and 4K HEVC in software on this CPU froze the picture,
 * dropped 32% of frames in 5 s, grew the app to ~426 MB and locked the TV up until Android killed ~25
 * apps. Knowing *before* opening a stream is what lets a camera pick a stream the CPU can carry.
 */
internal object HardwareDecoder {

    private const val TAG = "HaCamera"

    /**
     * The first hardware H.264 decoder on the device, found once. H.264 because every TV has one;
     * the rule being tested is "any non-secure decoder while a secure one runs", which the codec
     * type does not change — measured, AVC and HEVC were refused alike.
     */
    private val avcHardware: String? by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder &&
                    info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) } &&
                    !info.name.endsWith(".secure") &&
                    !isSoftware(info)
            }?.name
        }.getOrNull()
    }

    private fun isSoftware(info: android.media.MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isSoftwareOnly
        } else {
            info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
        }

    /**
     * True when the hardware decoder is taken. Creates the codec by *name* and releases it at once
     * (tens of milliseconds on the UR3). By name, not by type: `createDecoderByType` quietly moves on
     * to the next match, which is a software decoder, and would report "free" every time.
     *
     * Blocking; call it off the main thread. A device with no hardware H.264 decoder we can find
     * reports "not busy", which leaves playback exactly as it was before this existed.
     */
    fun isBusy(): Boolean {
        val name = avcHardware ?: return false
        val codec = runCatching { MediaCodec.createByCodecName(name) }.getOrElse {
            Log.i(TAG, "hardware decoder busy ($name): ${it.javaClass.simpleName}")
            return true
        }
        runCatching { codec.release() }
        return false
    }
}

/** What a camera plays when opened, given whether the hardware decoder was free. */
internal enum class CameraSource {
    /** The main stream, as always. */
    MAIN,
    /** The camera's smaller stream, in software beside whatever holds the decoder. */
    LOW_RES,
    /**
     * No live picture: the camera's snapshot, refreshed, and a note saying why.
     *
     * Deliberately not "take the screen so the other app lets go of the decoder". That was built
     * and measured on the UR3: Netflix did hand its decoder over within 2 s, but it stays loaded in
     * the background (~315 MB) while a 4K decode wants ~260 MB of buffers, and on a 2.2 GB TV whose
     * swap was already full the whole TV stalled for about a minute — 65 frames in 69 s — until
     * Android killed Netflix to recover. It had worked once earlier the same evening; memory headroom
     * decides it, and nothing on screen can.
     */
    UNAVAILABLE,
    /** The main stream anyway, after waiting for the decoder; the size guard stands behind it. */
    MAIN_GUARDED,
}

/**
 * The choice, kept pure so it can be tested.
 *
 * [inOverlay]: the camera is a popup over another app, which is what holds the decoder and is not
 * going to let go. Outside the overlay the camera is TV Assist's own screen, so the app that held
 * the decoder has just been sent to the background and is about to release it; that case waits.
 */
internal fun chooseCameraSource(decoderBusy: Boolean, hasLowRes: Boolean, inOverlay: Boolean): CameraSource =
    when {
        !decoderBusy -> CameraSource.MAIN
        hasLowRes -> CameraSource.LOW_RES
        inOverlay -> CameraSource.UNAVAILABLE
        else -> CameraSource.MAIN_GUARDED
    }

/**
 * The largest picture decoded in software before a stream is stopped instead: 1080p.
 *
 * Measured on the UR3 beside Netflix: a camera's `/stream2` in software, 92 s, 1 frame dropped of
 * 2228; its 3840x2160 main stream, 32% dropped within 5 s and the TV locked up inside a minute. The
 * line sits at 1080p because 4K is four times that and was the failure; nothing between the two was
 * measured, so this is the conservative side of an unmeasured gap, not a tuned number.
 */
internal const val MAX_SOFTWARE_PIXELS = 1920 * 1088

internal fun tooLargeForSoftware(width: Int, height: Int): Boolean =
    width > 0 && height > 0 && width.toLong() * height > MAX_SOFTWARE_PIXELS
