package com.tvassist.data.assist

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.speech.SpeechRecognizer

/** A microphone the user can pick for Assist. [key] is what gets persisted. */
data class MicChoice(val key: String, val label: String)

/** Let the app decide: an app-openable mic if there is one, otherwise the system recogniser. */
const val MIC_AUTO = ""

/**
 * Force the system speech recogniser — the only route to a TV remote's microphone, since that mic
 * is never exposed as a recordable input device.
 */
const val MIC_RECOGNIZER = "recognizer"

/** Prefix for a specific audio input device, encoded as `dev:<type>:<product name>`. */
private const val DEVICE_PREFIX = "dev:"

/**
 * The microphones Assist can use on this device.
 *
 * Always offers Auto, offers the remote/system recogniser when one is installed, and then every
 * audio input the framework reports. On a Sony BRAVIA the last group is usually empty — the remote's
 * mic belongs to the TV's voice app and never appears here — which is exactly why the recogniser
 * entry exists.
 */
fun listMicChoices(context: Context): List<MicChoice> {
    val appContext = context.applicationContext
    val out = mutableListOf(MicChoice(MIC_AUTO, "Auto"))
    if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
        out += MicChoice(MIC_RECOGNIZER, "TV remote (system recogniser)")
    }
    for (device in inputDevices(appContext)) {
        out += MicChoice(deviceKey(device), deviceLabel(device))
    }
    return out
}

/** Whether [key] names a real audio input device (as opposed to Auto or the recogniser). */
fun isDeviceMic(key: String): Boolean = key.startsWith(DEVICE_PREFIX)

/**
 * Whether this device has a microphone the app could actually open with `AudioRecord`.
 *
 * Asks the audio framework what inputs exist rather than trusting
 * `PackageManager.FEATURE_MICROPHONE`. That flag is static device metadata: a Sony BRAVIA declares
 * it false and keeps saying false with a USB microphone plugged in, so gating recording on it makes
 * the USB path unreachable on exactly the hardware it exists for. The feature flag is still
 * consulted as a fallback, for devices whose HAL enumerates no inputs until one is opened.
 */
fun hasRecordableMic(context: Context): Boolean {
    val appContext = context.applicationContext
    return inputDevices(appContext).isNotEmpty() ||
        appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)
}

/**
 * Finds the input device [key] refers to, or null when it is not currently attached — a USB mic
 * that has been unplugged, say. Matching is by type plus product name rather than the device id,
 * because ids are reassigned on every reconnect and would not survive a replug.
 *
 * Falls back to the first input of the same type when the name no longer matches: firmware updates
 * and hub changes rewrite product names, and a mic of the right kind is a better answer than
 * silently reverting to Auto.
 */
fun resolveInputDevice(context: Context, key: String): AudioDeviceInfo? {
    if (!isDeviceMic(key)) return null
    val parts = key.removePrefix(DEVICE_PREFIX).split(':', limit = 2)
    val type = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val name = parts.getOrNull(1).orEmpty()
    val devices = inputDevices(context.applicationContext)
    return devices.firstOrNull { it.type == type && it.productName?.toString() == name }
        ?: devices.firstOrNull { it.type == type }
}

/** The human-readable name for a stored [key], for settings screens. */
fun micChoiceLabel(context: Context, key: String): String = when {
    key == MIC_AUTO -> "Auto"
    key == MIC_RECOGNIZER -> "TV remote (system recogniser)"
    else -> resolveInputDevice(context, key)?.let { deviceLabel(it) }
        ?: key.removePrefix(DEVICE_PREFIX).substringAfter(':', "Unknown microphone")
}

private fun inputDevices(context: Context): List<AudioDeviceInfo> {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
    return runCatching {
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
            // Only things a person would call a microphone: the TV tuner and the cast loopback are
            // reported as inputs too, and recording from either is never what was meant.
            .filter { it.type in MIC_TYPES }
    }.getOrDefault(emptyList())
}

private fun deviceKey(device: AudioDeviceInfo): String =
    DEVICE_PREFIX + device.type + ":" + (device.productName?.toString().orEmpty())

private fun deviceLabel(device: AudioDeviceInfo): String {
    val name = device.productName?.toString()?.takeIf { it.isNotBlank() }
    val kind = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset mic"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE mic"
        else -> "Microphone"
    }
    return if (name != null && !name.equals(kind, ignoreCase = true)) "$kind — $name" else kind
}

private val MIC_TYPES = buildSet {
    add(AudioDeviceInfo.TYPE_BUILTIN_MIC)
    add(AudioDeviceInfo.TYPE_USB_DEVICE)
    add(AudioDeviceInfo.TYPE_USB_HEADSET)
    add(AudioDeviceInfo.TYPE_USB_ACCESSORY)
    add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}
