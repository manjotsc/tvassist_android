package com.tvassist.data.logs

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.tvassist.data.settings.LocalCamera
import com.tvassist.data.settings.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The header on every exported log: what an issue thread otherwise spends three round-trips asking
 * for. Nothing in it identifies the household — no URLs, hosts, names or tokens; cameras are
 * described by number, engine and whether they have a low-res stream.
 *
 * The decoder section is there for #4 and #5: which video decoders exist, which are hardware and
 * which secure, how many may run at once, and the vendor's `supports-secure-with-non-secure-codec`
 * flag — the one difference that made a MediaTek TCL fail where both BRAVIAs refuse cleanly.
 */
object DeviceReport {

    fun build(context: Context, settings: Settings, collector: LogCollector): String = buildString {
        val now = Date()
        appendLine("TV Assist log — ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now)}")
        appendLine("Only this app's own process is included. Tokens, passwords and configured hosts are")
        appendLine("replaced with placeholders such as «redacted» and <ha-host>.")
        appendLine()

        section("App")
        appendLine("version ${com.tvassist.BuildConfig.VERSION_NAME} (${com.tvassist.BuildConfig.VERSION_CODE}), ${com.tvassist.BuildConfig.BUILD_TYPE}")
        val upMin = (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 60_000
        appendLine("process up ${upMin} min")
        appendLine(
            "log: " + (if (settings.persistLogs) "kept across restarts" else "memory only") +
                ", ${collector.lineCount()} lines in memory" +
                (if (collector.restarts > 0) ", capture resumed ${collector.restarts}x" else "") +
                (collector.problem?.let { ", PROBLEM: $it" } ?: ""),
        )
        appendLine("detailed logging: " + detailedState(settings.detailedLogsUntil, now.time))
        appendLine()

        section("Device")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), hardware ${Build.HARDWARE}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appendLine("SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        am?.let {
            val mi = android.app.ActivityManager.MemoryInfo().also(it::getMemoryInfo)
            appendLine("memory ${mi.availMem / MB} MB free of ${mi.totalMem / MB} MB, low=${mi.lowMemory}")
        }
        appendLine()

        section("Video decoders")
        appendLine("supports-secure-with-non-secure-codec: ${secureWithNonSecureFlag()}")
        runCatching { videoDecoders().forEach(::appendLine) }
            .onFailure { appendLine("(could not list decoders: ${it.javaClass.simpleName})") }
        appendLine()

        section("Cameras and notifications")
        appendLine("global player: ${settings.streamPlayer}")
        cameraSummary(settings.localCameras, settings.streamPlayer).forEach(::appendLine)
        appendLine("notifications ${if (settings.notificationsEnabled) "on, port ${settings.notificationPort}" else "off"}")
        appendLine()

        section("Log")
        append(collector.body())
    }

    private fun StringBuilder.section(name: String) = appendLine("--- $name ---")

    internal fun detailedState(untilMs: Long, nowMs: Long): String =
        if (untilMs > nowMs) {
            "on until ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(untilMs))}"
        } else {
            "off"
        }

    /**
     * One line per local camera, by number: the engine, RTSP transport, the stream's scheme, and
     * whether a low-res stream and a snapshot are set. Numbered the same way as the `<camera-N-host>`
     * placeholders, so a line in the log can be matched to its camera.
     */
    internal fun cameraSummary(cameras: List<LocalCamera>, globalPlayer: String = "auto"): List<String> =
        if (cameras.isEmpty()) {
            listOf("local cameras: none")
        } else {
            cameras.mapIndexed { i, c ->
                // "default" alone would hide which engine actually ran; say what it resolved to.
                val player = if (c.player == LocalCamera.PLAYER_DEFAULT) "default ($globalPlayer)" else c.player
                "camera ${i + 1}: ${scheme(c.streamUrl)}, player $player, " +
                    "rtsp ${if (c.rtspTcp) "tcp" else "udp-first"}, " +
                    "low-res ${if (c.lowResUrl.isNotBlank()) scheme(c.lowResUrl) else "none"}, " +
                    "snapshot ${if (c.snapshotUrl.isNotBlank()) "yes" else "no"}" +
                    if (c.refresh) ", rolling clip" else ""
            }
        }

    private fun scheme(url: String): String =
        url.trim().substringBefore("://", missingDelimiterValue = "?").lowercase().ifBlank { "?" }

    private fun videoDecoders(): List<String> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.startsWith("video/") } }
            .map { info ->
                val type = info.supportedTypes.first { it.startsWith("video/") }
                val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
                val video = caps?.videoCapabilities
                val size = video?.let { "${it.supportedWidths.upper}x${it.supportedHeights.upper}" } ?: "?"
                val secure = caps?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) == true
                // Android 10's OMX list flags neither for Google's own decoders (seen on the UR3),
                // so their names decide when the flags are silent: the same rule HardwareDecoder uses.
                val byName = info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
                val kind = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated -> "hw"
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly -> "sw"
                    byName -> "sw"
                    else -> "hw?"
                }
                "${info.name}: ${info.supportedTypes.joinToString(",")} $kind" +
                    (if (secure) " secure" else "") +
                    ", max $size, instances ${caps?.maxSupportedInstances ?: "?"}"
            }

    /**
     * The vendor's `supports-secure-with-non-secure-codec` setting, read from its `media_codecs*.xml`
     * (world-readable on both BRAVIAs). "false" means a playing DRM app blocks every other hardware
     * decoder, which the busy check catches; "true" means creation succeeds and decoding can still
     * fail later (#5). Absent from the files = the platform default, true.
     */
    internal fun secureWithNonSecureFlag(): String {
        val dirs = listOf("/vendor/etc", "/odm/etc", "/system/etc")
        var readAny = false
        for (d in dirs) {
            val files = File(d).listFiles { f -> f.name.startsWith("media_codecs") && f.name.endsWith(".xml") } ?: continue
            for (f in files.sortedBy { it.name }) {
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                readAny = true
                parseSecureFlag(text)?.let { return "$it (${f.path})" }
            }
        }
        return if (readAny) "not set (platform default: true)" else "could not read the vendor codec files"
    }

    /** The flag's value in one `media_codecs*.xml`, or null if that file does not set it. */
    internal fun parseSecureFlag(xml: String): String? =
        Regex("""name\s*=\s*"supports-secure-with-non-secure-codec"\s+value\s*=\s*"([^"]*)"""")
            .find(xml)?.groupValues?.get(1)

    private const val MB = 1024 * 1024
}
