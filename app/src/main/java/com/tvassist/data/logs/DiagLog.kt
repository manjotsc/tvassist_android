package com.tvassist.data.logs

import android.util.Log

/**
 * "Detailed logging": extra lines about camera and notification playback that release builds do
 * not normally write — which engine opened which stream, when the first frame came, the video size,
 * an error's full cause chain, when each player was let go.
 *
 * Exists because release builds are quiet on purpose and that left issue reports unanswerable: a
 * notification whose expanded view was black (#4) logged one line, `stream failed (vlc)`, with
 * nothing about the card's own player being torn down a moment earlier. Debug builds always write
 * these; release builds only while the switch in Settings → Logs is on, which turns itself off
 * after [DURATION_MS] so nobody leaves it running for months.
 *
 * Deliberately limited to playback. Home Assistant state and service calls stay debug-only
 * (`HaWebSocketClient`): they describe the household, not the bug. URLs must still go through
 * `safeUrlForLog`, and the collector's [LogRedactor] hides configured hosts on top of that.
 */
object DiagLog {
    /** Epoch ms the switch turns itself off at; 0 = off. Set from settings by [LogCollector]'s owner. */
    @Volatile
    var untilMs: Long = 0L

    const val DURATION_MS = 24L * 60 * 60 * 1000

    val enabled: Boolean
        get() = com.tvassist.BuildConfig.DEBUG || System.currentTimeMillis() < untilMs

    fun i(tag: String, msg: String) {
        if (enabled) Log.i(tag, msg)
    }

    /**
     * With [error], appends its chain of causes on the same line — where a decoder's own error code
     * lives (#5's `errcode=12`). The causes, not the stack: a full trace was ~40 lines per failed
     * stream (seen on the emulator), and in a 2,000-line memory log the frames were noise while the
     * one line that said `ECONNREFUSED` was what mattered.
     */
    fun w(tag: String, msg: String, error: Throwable? = null) {
        if (enabled) Log.w(tag, if (error == null) msg else "$msg — ${causeChain(error)}")
    }

    /** `ExoPlaybackException: Source error <- ConnectException: failed to connect …`, at most 6 deep. */
    internal fun causeChain(error: Throwable): String =
        generateSequence(error) { it.cause?.takeIf { c -> c !== it } }
            .take(6)
            .joinToString(" <- ") { e ->
                // Release builds rename classes (R8): `n: Source error` names nothing, so a one- or
                // two-letter name is left out and the message stands alone.
                val name = e.javaClass.simpleName.takeIf { it.length > 2 }
                listOfNotNull(name, e.message?.take(200)).joinToString(": ")
            }
}
