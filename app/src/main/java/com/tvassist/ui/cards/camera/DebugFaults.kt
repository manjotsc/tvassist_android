package com.tvassist.ui.cards.camera

import com.tvassist.BuildConfig

/**
 * Debug builds only: fake a decoder failure on a stream that has started, to test what happens next.
 *
 * The real failure — the decoder refusing *after* playback began — needs a TV whose secure and
 * ordinary decoders can coexist (a MediaTek TCL, issue #3's follow-up) with Netflix playing 4K. The
 * BRAVIAs refuse at creation instead, which the busy check already catches, so neither TV here can
 * produce it. This stands in for it:
 *
 * ```
 * adb shell setprop debug.tvassist.decoderfail 3000        # every stream fails 3 s after starting
 * adb shell setprop debug.tvassist.decoderfail 3000:main   # only main streams (low-res survives)
 * adb shell setprop debug.tvassist.decoderfail 0           # off
 * ```
 *
 * Read when each stream opens, so a change applies to the next stream without a restart. Release
 * builds never read the property.
 */
internal object DebugFaults {
    private const val PROP = "debug.tvassist.decoderfail"

    /** Milliseconds after a stream starts to fail it, or null for no fault. [lowRes]: the stream is a camera's low-res one. */
    fun decoderFailAfterMs(lowRes: Boolean = false): Long? {
        if (!BuildConfig.DEBUG) return null
        val raw = systemProperty(PROP)?.trim().orEmpty()
        val ms = raw.substringBefore(':').toLongOrNull()?.takeIf { it > 0 } ?: return null
        val mainOnly = raw.substringAfter(':', "") == "main"
        return if (mainOnly && lowRes) null else ms
    }

    private const val KEYSTORE_PROP = "debug.tvassist.keystorefail"

    /**
     * Whether to fake Android's Keystore refusing to encrypt a secret (issue #2's failure path),
     * which no healthy TV will do on request:
     *
     * ```
     * adb shell setprop debug.tvassist.keystorefail 1   # every secret save fails
     * adb shell setprop debug.tvassist.keystorefail 0   # off
     * ```
     */
    fun keystoreFails(): Boolean =
        BuildConfig.DEBUG && systemProperty(KEYSTORE_PROP)?.trim() == "1"

    // android.os.SystemProperties is hidden API; reflection is fine for a debug-only test hook.
    @Suppress("PrivateApi")
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()
}
