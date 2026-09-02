package com.tvassist.data.assist

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Turning microphone loudness into the 0..1 the listening animation reacts to.
 *
 * Both voice routes can report a level, but they report different things — raw PCM on one side, the
 * recogniser's own dB figure on the other — so the conversion lives here and the animation never has
 * to know which route it is watching.
 */

/** RMS amplitude (0..32768) over the first [length] bytes of a little-endian PCM16 buffer. */
fun pcm16Rms(buffer: ByteArray, length: Int): Float {
    var sum = 0.0
    var i = 0
    val end = length - 1
    while (i < end) {
        val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
        sum += sample.toDouble() * sample.toDouble()
        i += 2
    }
    return sqrt(sum / (length / 2).coerceAtLeast(1)).toFloat()
}

/**
 * Maps [pcm16Rms] onto 0..1 through dBFS rather than linearly.
 *
 * Loudness is perceived logarithmically, and speech from a couch sits around -35 dBFS — under two
 * percent of full scale. Mapped linearly the dots would sit almost still while someone talks and
 * only move if they shouted, so the useful range is stretched over the quiet end instead.
 */
fun rmsToLevel(rms: Float): Float {
    if (rms <= 1f) return 0f
    val dbfs = 20.0 * ln(rms / FULL_SCALE) / LN_10
    return ((dbfs - QUIET_DBFS) / (LOUD_DBFS - QUIET_DBFS)).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Maps [android.speech.RecognitionListener.onRmsChanged] onto 0..1. Its unit is unspecified and
 * engine-dependent; Google's recogniser reports roughly -2 when quiet and 10 for loud speech.
 */
fun recognizerRmsToLevel(rmsdB: Float): Float =
    ((rmsdB - RECOGNIZER_QUIET) / (RECOGNIZER_LOUD - RECOGNIZER_QUIET)).coerceIn(0f, 1f)

private const val FULL_SCALE = 32768.0
private val LN_10 = ln(10.0)
// Floor and ceiling for speech at couch distance from a TV's microphone, in dBFS.
private const val QUIET_DBFS = -50.0
private const val LOUD_DBFS = -12.0
private const val RECOGNIZER_QUIET = -2f
private const val RECOGNIZER_LOUD = 10f
