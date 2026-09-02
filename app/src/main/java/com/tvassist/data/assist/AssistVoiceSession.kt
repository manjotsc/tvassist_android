package com.tvassist.data.assist

import android.content.Context
import android.util.Log
import com.tvassist.data.ha.ConversationReply
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.ha.HaWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Where a voice run has got to, for the card to render. */
sealed interface VoiceState {
    /** Nothing running — the mic is closed. */
    data object Idle : VoiceState

    /** Pipeline requested; waiting for HA to hand back an audio handler. */
    data object Starting : VoiceState

    /** Mic is open and audio is streaming to HA. */
    data object Listening : VoiceState

    /** HA has the words and is running the agent. */
    data class Thinking(val transcript: String) : VoiceState

    /**
     * The agent is streaming its answer a piece at a time — an LLM writing, rather than an intent
     * matcher returning. Distinct from [Thinking] because there is something to read: without it a
     * conversation agent that takes ten seconds to compose a paragraph shows a working animation
     * for all ten and then the finished text, which reads as a stall followed by a jump.
     */
    data class Answering(val transcript: String, val partial: String) : VoiceState

    /**
     * Finished: what HA heard, what the agent answered, and — when the pipeline synthesised one —
     * the URL of the audio to play it back with.
     */
    data class Done(
        val transcript: String,
        val reply: ConversationReply,
        val audioUrl: String? = null,
    ) : VoiceState

    /** The run could not complete. */
    data class Failed(val reason: String) : VoiceState
}

/**
 * One press-to-talk exchange with a Home Assistant Assist pipeline.
 *
 * HA does the listening: this streams raw microphone PCM over the WebSocket and HA's configured
 * speech-to-text (Whisper, Cloud, …) transcribes it. That is why there is no on-device recogniser
 * here and no Google dependency.
 *
 * The run goes all the way to `tts`, so Home Assistant transcribes, answers, and synthesises the
 * spoken reply in one exchange — its voices are far better than the TV's built-in engine. The agent
 * is therefore the **pipeline's**: `assist_pipeline/run` takes no agent override, which is why the
 * pipeline picker is what decides who replies.
 *
 * One session drives one run. Create it, [start] it, and let it reach [VoiceState.Done] or
 * [VoiceState.Failed]; [cancel] tears down a run early.
 */
class AssistVoiceSession(
    context: Context,
    private val repository: HaRepository,
    private val scope: CoroutineScope,
    /** The user's chosen microphone key; blank lets the framework pick. */
    private val micKey: String = MIC_AUTO,
    /** The Assist pipeline to transcribe with; blank uses HA's preferred one. */
    private val pipelineId: String = "",
    /** Whether that pipeline has a voice to synthesise the reply with. */
    private val endAtTts: Boolean = true,
) : VoiceSession {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    private val capture = AudioCapture(
        HaWebSocketClient.ASSIST_SAMPLE_RATE,
        resolveInputDevice(appContext, micKey),
    )

    @Volatile private var runId: Int? = null
    @Volatile private var handlerId: Int? = null
    private var captureJob: Job? = null

    // Collected across the run's events: the transcript from `stt-end`, the answer from `intent-end`.
    // Published together by [finish] once the audio (or the run's end) arrives.
    @Volatile private var heard: String = ""
    @Volatile private var reply: ConversationReply? = null

    /** The answer so far, accumulated from `intent-progress` deltas. Blank when not streaming. */
    @Volatile private var streamed: String = ""

    /** The thread id HA gave us, so a spoken follow-up continues the same conversation. */
    @Volatile
    var conversationId: String? = null
        private set

    /**
     * Opens the mic and starts a run. [conversationId] continues an existing thread (including one
     * started by typing); [language] is passed through to the pipeline, blank meaning HA's default.
     *
     * Refuses with [VoiceState.Failed] when the device has no app-accessible microphone or
     * RECORD_AUDIO is not granted — see [precondition]. Callers need not pre-check.
     */
    override fun start(conversationId: String?, language: String) {
        if (_state.value == VoiceState.Listening || _state.value == VoiceState.Starting) return

        // Checked here rather than at the call sites so no caller can start a run that was never
        // going to produce audio: HA would sit waiting for a stream that stays silent, and the user
        // would see a pipeline that simply never answers.
        precondition()?.let {
            _state.value = VoiceState.Failed(it)
            return
        }

        this.heard = ""
        this.streamed = ""
        this.reply = null
        this.conversationId = conversationId
        _state.value = VoiceState.Starting

        val id = repository.startAssistPipeline(
            pipelineId = pipelineId,
            endAtTts = endAtTts,
            conversationId = conversationId,
            onEvent = { onEvent(it) },
        )
        if (id == null) {
            _state.value = VoiceState.Failed("Not connected to Home Assistant.")
            return
        }
        runId = id
    }

    /** Why voice cannot run right now, or null when it can. */
    private fun precondition(): String? {
        if (!hasRecordPermission(appContext)) {
            return "Microphone access is off. Grant it in Settings → Permissions on this TV."
        }
        // A chosen input that is still attached is proof enough on its own, and has to be checked
        // before the general test: a TV declares no microphone feature even with a USB mic plugged
        // in, so asking the PackageManager first would refuse the one setup this route exists for.
        if (resolveInputDevice(appContext, micKey) != null) return null
        // Plenty of TV hardware has no app-accessible mic at all — a Sony BRAVIA's remote mic is
        // wired to the system assistant, not to AudioRecord — and there the permission is granted
        // yet recording yields silence. Saying so beats an agent that never replies.
        if (!hasRecordableMic(appContext)) {
            return "This TV has no microphone available to apps. The remote's mic is reserved for " +
                "the system assistant; a USB microphone or a Home Assistant voice satellite works."
        }
        return null
    }

    private fun onEvent(event: JsonObject) {
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return
        val data = event["data"] as? JsonObject
        when (type) {
            "run-start" -> {
                val handler = (data?.get("runner_data") as? JsonObject)
                    ?.get("stt_binary_handler_id")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (handler == null) {
                    fail("Home Assistant did not open an audio channel.")
                    return
                }
                // The binary protocol carries the handler in a single leading byte, so an id past
                // 255 cannot be addressed at all and one past 127 would be sent as a negative.
                // Truncating either would stream this run's audio at some other run's id.
                if (handler !in 0..255) {
                    fail("Home Assistant returned an audio channel this app cannot address ($handler).")
                    return
                }
                handlerId = handler
                beginStreaming(handler)
            }

            // HA's voice-activity detector decided the user stopped talking. Close the mic
            // immediately: holding it open past this point only adds latency before the answer.
            "stt-vad-end" -> {
                stopMic()
                // And say so. Speech-to-text still has to run — seconds of it on Whisper — and
                // holding the caption on "Listening" through that asks for words nothing is
                // recording, over a ribbon that has already fallen still.
                if (_state.value is VoiceState.Listening) _state.value = VoiceState.Thinking("")
            }

            "stt-end" -> {
                val text = (data?.get("stt_output") as? JsonObject)
                    ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                stopMic()
                if (text.isBlank()) {
                    fail("Nothing was heard. Try again, a little closer to the microphone.")
                    return
                }
                heard = text
                _state.value = VoiceState.Thinking(text)
            }

            // A conversation agent that streams (any LLM-backed one) sends its answer in pieces
            // before `intent-end` arrives with the whole of it. The deltas are the same text, so
            // showing them costs nothing and is discarded the moment the real reply lands.
            "intent-progress" -> {
                val delta = intentProgressDelta(data)
                if (delta != null) {
                    streamed += delta
                    // A new instance per delta on purpose: the controller's watchdog re-arms on
                    // phase identity, so a long answer keeps resetting its own timeout instead of
                    // racing it.
                    _state.value = VoiceState.Answering(heard, streamed)
                }
            }

            "intent-end" -> {
                val parsed = intentOutputReply(data)
                parsed.conversationId?.let { conversationId = it }
                reply = parsed
                // Held rather than published: the answer is only shown once its audio is in hand, so
                // the text and the voice start together instead of the caption racing the speech.
            }

            "tts-end" -> finish(ttsOutputUrl(data))

            // Whatever stage it actually got to, the run is over — publish what we have. A pipeline
            // that skipped tts still has an answer worth showing.
            "run-end" -> {
                finish(null)
                // [finish] publishes only if an answer arrived. Reaching run-end without one means
                // no further events are coming, so the exchange has failed now — not in sixty
                // seconds when the watchdog notices the bar is still saying "Thinking". An already
                // failed run keeps its own error: that one says what actually went wrong.
                when (_state.value) {
                    is VoiceState.Done, is VoiceState.Failed, is VoiceState.Idle -> release()
                    else -> fail("Home Assistant ended the exchange without an answer.")
                }
            }

            "error" -> fail(pipelineErrorMessage(data))
        }
    }

    /**
     * Publishes the finished exchange, once. Both `tts-end` and `run-end` can get here — the first
     * with audio, the second as the backstop for a run that produced none — and only the first of
     * them should land.
     */
    private fun finish(audioUrl: String?) {
        if (_state.value is VoiceState.Done) return
        val answer = reply ?: return
        _state.value = VoiceState.Done(heard, answer, audioUrl)
    }


    private fun beginStreaming(handler: Int) {
        _state.value = VoiceState.Listening
        captureJob = scope.launch(Dispatchers.IO) {
            val failure = capture.stream(
                onChunk = { buf, len -> repository.sendAssistAudio(handler, buf, len) },
                onLevel = { _level.value = it },
            )
            // Closing the stream is what makes HA finish transcribing, so it must happen however the
            // loop ended — user stop, VAD, or mic error.
            repository.endAssistAudio(handler)
            if (failure != null) fail(failure)
        }
    }

    /**
     * Stops sending audio but leaves the run going, so HA can finish transcribing and answer.
     * This is what the mic button does on a second press.
     */
    override fun stopSpeaking() {
        if (_state.value !is VoiceState.Listening) return
        stopMic()
        _state.value = VoiceState.Thinking("")
    }

    private fun stopMic() {
        capture.stop()
        // The dots must settle the moment the mic shuts, not drift down from the last chunk's level.
        _level.value = 0f
    }

    /** Abandons the run entirely and closes the mic. */
    override fun cancel() {
        stopMic()
        release()
        _state.value = VoiceState.Idle
    }

    private fun fail(reason: String) {
        Log.w(TAG, "assist voice failed: $reason")
        stopMic()
        release()
        _state.value = VoiceState.Failed(reason)
    }

    /** Drops the event subscription and forgets the run; state is left for the UI to read. */
    private fun release() {
        runId?.let { repository.stopAssistPipeline(it) }
        runId = null
        handlerId = null
        captureJob = null
    }

    private companion object {
        const val TAG = "AssistVoice"
    }
}
