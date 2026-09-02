package com.tvassist.data.assist

import android.content.Context
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import com.tvassist.data.ha.HaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * One press-to-talk exchange, however this device is able to listen.
 *
 * Two implementations exist because TV hardware differs in a way that cannot be papered over:
 *
 *  - [AssistVoiceSession] streams raw microphone PCM to Home Assistant and lets **HA's** speech-to-text
 *    transcribe it. The preferred route — it keeps the whole pipeline in HA — but it needs a
 *    microphone the app can actually open with `AudioRecord`.
 *  - [DeviceRecognizerSession] asks the **system recogniser** for text instead. On a Sony BRAVIA the
 *    remote's mic is wired to Google's voice app and never appears as a recordable input, so this is
 *    the only way to use it. The trade-off is real: transcription is Google's, not HA's, and only
 *    the resulting text goes to the conversation agent.
 */
interface VoiceSession {
    val state: StateFlow<VoiceState>

    /**
     * Live microphone loudness, 0..1, for the listening animation; 0 whenever the mic is shut.
     * Both routes can supply it — see [pcm16Rms] and [recognizerRmsToLevel].
     */
    val level: StateFlow<Float>

    /**
     * Opens the mic. [conversationId] continues a thread.
     *
     * [language] is what to *listen* in, and only the device recogniser uses it — where Home
     * Assistant does the hearing it uses the pipeline's own language, as it does for the agent and
     * the voice. No agent is passed either: both routes now address a pipeline, which is what
     * carries the assistant's agent, voice and local-first preference as one thing.
     */
    fun start(conversationId: String?, language: String)

    /** Stops listening but lets the answer finish arriving. */
    fun stopSpeaking()

    /** Abandons the exchange and closes the mic. */
    fun cancel()
}

/** Which way this device listens, for the card to explain itself. */
enum class VoiceBackend {
    HA_PIPELINE,
    DEVICE_RECOGNIZER,

    /**
     * A route exists but RECORD_AUDIO has not been granted. Kept separate from [NONE] because the
     * two need opposite messages — one is a missing tap, the other is missing hardware — and only a
     * Service-free Activity can ask for the grant.
     */
    NEEDS_PERMISSION,
    NONE,
}

/** True when RECORD_AUDIO has been granted to this process. */
fun hasRecordPermission(context: Context): Boolean =
    context.applicationContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Picks the listening route for this device.
 *
 * Hardware first, permission second: [voiceRouteFor] decides how this TV *could* listen, and a
 * missing RECORD_AUDIO downgrades that to [VoiceBackend.NEEDS_PERMISSION] rather than pretending
 * the hardware is absent. The system recogniser needs the grant too — it records on our behalf and
 * the platform attributes that to us — so both routes go through the same gate.
 */
fun voiceBackendFor(context: Context, micKey: String = MIC_AUTO): VoiceBackend {
    val appContext = context.applicationContext
    val route = voiceRouteFor(appContext, micKey)
    if (route == VoiceBackend.NONE) return VoiceBackend.NONE
    return if (hasRecordPermission(appContext)) route else VoiceBackend.NEEDS_PERMISSION
}

/**
 * How this device could listen, ignoring permission.
 *
 * An app-openable microphone wins, because streaming to HA keeps speech-to-text where the user
 * configured it. Failing that, a system recogniser can still reach mics the app cannot — which is
 * the normal case on a TV, where the only microphone is the one in the remote.
 *
 * Public because settings needs the route a device *has*, not the one it can use right now:
 * [voiceBackendFor] folds a missing grant into [VoiceBackend.NEEDS_PERMISSION], which would leave
 * the pipeline picker unable to tell a TV that transcribes for itself from one that does not.
 */
fun voiceRouteFor(context: Context, micKey: String = MIC_AUTO): VoiceBackend {
    val appContext = context.applicationContext
    val recognizer = SpeechRecognizer.isRecognitionAvailable(appContext)

    // An explicit choice wins, so long as it is still possible: picking a USB mic is also how you
    // force HA's own speech-to-text back on, and picking the remote is how you accept the TV's.
    if (micKey == MIC_RECOGNIZER) {
        return if (recognizer) VoiceBackend.DEVICE_RECOGNIZER else VoiceBackend.NONE
    }
    if (isDeviceMic(micKey) && resolveInputDevice(appContext, micKey) != null) {
        return VoiceBackend.HA_PIPELINE
    }
    // Chosen mic unplugged: fall through to Auto rather than leaving Speak dead.

    return when {
        hasRecordableMic(appContext) -> VoiceBackend.HA_PIPELINE
        recognizer -> VoiceBackend.DEVICE_RECOGNIZER
        else -> VoiceBackend.NONE
    }
}

/**
 * Builds the session for [voiceBackendFor]'s choice; null when this device cannot listen — either
 * for want of hardware or for want of the grant. The card reads the backend to say which.
 */
fun createVoiceSession(
    context: Context,
    repository: HaRepository,
    scope: CoroutineScope,
    micKey: String = MIC_AUTO,
    /** The assistant to answer with. Both routes run a pipeline; only the stage they start at differs. */
    pipelineId: String = "",
    /** Whether that pipeline has a voice, i.e. whether to run through synthesis. */
    endAtTts: Boolean = true,
): VoiceSession? = when (voiceBackendFor(context, micKey)) {
    VoiceBackend.HA_PIPELINE ->
        AssistVoiceSession(context, repository, scope, micKey, pipelineId, endAtTts)
    VoiceBackend.DEVICE_RECOGNIZER ->
        DeviceRecognizerSession(context, repository, pipelineId, endAtTts)
    VoiceBackend.NEEDS_PERMISSION, VoiceBackend.NONE -> null
}
