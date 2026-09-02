package com.tvassist.data.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.tvassist.data.ha.ConversationReply
import com.tvassist.data.ha.HaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Voice input via the **system speech recogniser**, for devices whose microphone the app cannot open
 * itself.
 *
 * This is the route that makes a Sony remote's mic usable. The remote's microphone is not an audio
 * input device at all — the audio HAL reports none, and `android.hardware.microphone` is absent — so
 * `AudioRecord` has nothing to open. But the TV's voice app (Katniss, the default
 * `RecognitionService`) does have privileged access to it, and binding to it as a recogniser makes
 * it record on our behalf and hand back text.
 *
 * The cost is that transcription happens in Google's recogniser rather than Home Assistant's
 * speech-to-text; only the finished sentence reaches HA. Everything *after* the words is still the
 * pipeline's, though — the sentence is fed to `assist_pipeline/run` at its `intent` stage, so the
 * assistant's own agent answers, its own voice speaks, and its own "prefer handling commands
 * locally" setting applies.
 *
 * That last part is why this is not `conversation.process`. Addressing the pipeline's agent
 * directly, as this used to, threw away everything else the assistant was configured with: a TV
 * with a local agent preferred for simple commands sent "turn on the kitchen light" to an LLM and
 * waited five seconds for it. Measured on the UR3 before the change.
 *
 * [SpeechRecognizer] is main-thread-only, hence the handler hops.
 */
class DeviceRecognizerSession(
    context: Context,
    private val repository: HaRepository,
    /** The assistant that answers the transcript; blank uses whichever HA prefers. */
    private val pipelineId: String,
    /** Whether that assistant has a voice to read the reply back with. */
    private val endAtTts: Boolean,
) : VoiceSession {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var threadId: String? = null
    private var language: String = ""

    /** The run answering the transcript, for [stopRun]. */
    @Volatile private var runId: Int? = null

    /** What was heard, and the answer as it arrives — see [onPipelineEvent]. */
    @Volatile private var heard: String = ""
    @Volatile private var streamed: String = ""
    @Volatile private var reply: ConversationReply? = null

    /**
     * True from the moment a transcript is handed to the agent until its answer lands. The
     * recogniser keeps emitting after [RecognitionListener.onResults] — a trailing NO_MATCH or
     * SPEECH_TIMEOUT is routine — and reporting one here would push an error into the transcript
     * moments before the real answer arrives behind it.
     */
    @Volatile
    private var answering = false

    /**
     * Whether this exchange has already listened a second time after a cold recognition service.
     *
     * Once per exchange, so a genuinely unreachable speech service still reports itself rather than
     * looping. See [onError].
     */
    @Volatile
    private var listenedAgain = false

    override fun start(conversationId: String?, language: String) {
        if (_state.value == VoiceState.Listening || _state.value == VoiceState.Starting) return
        answering = false
        listenedAgain = false
        heard = ""
        streamed = ""
        reply = null
        threadId = conversationId
        this.language = language
        _state.value = VoiceState.Starting
        // Directly when we are already on the main thread, and that is not a micro-optimisation.
        //
        // A post lands *behind* the frame the line above just scheduled — and that frame is the
        // voice bar appearing for the first time, whose halo gradients cost 4.5 seconds to draw on
        // the UR3's GPU. Measured: mic key at 00:14:00.041, `Skipped 233 frames` and a
        // `Davey! duration=4474ms` at 00:14:04, and the recognition service not asked to start
        // until 00:14:03.973 — four seconds during which nothing was listening.
        //
        // Binding here instead puts the request in before the renderer gets the thread back, so the
        // microphone opens while the bar draws rather than after it.
        if (Looper.myLooper() == Looper.getMainLooper()) beginListening() else main.post { beginListening() }
    }

    private fun beginListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _state.value = VoiceState.Failed("This TV has no speech recogniser available.")
            return
        }
        // Shared, not created here: building one per exchange killed and restarted Google's whole
        // recognition service between presses. See [SharedRecognizer].
        val rec = SharedRecognizer.acquire(appContext)
        if (rec == null) {
            _state.value = VoiceState.Failed("Could not start the TV's speech recogniser.")
            return
        }
        recognizer = rec
        rec.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // Partial results give the card something to show while the user is still talking;
            // without them a long sentence looks like nothing is happening.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            if (language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        }
        runCatching { rec.startListening(intent) }
            .onFailure { _state.value = VoiceState.Failed("Could not open the microphone: ${it.message}") }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = VoiceState.Listening
        }

        override fun onBeginningOfSpeech() {}

        // The recogniser records on our behalf, so this is the only view we get of the remote mic's
        // loudness — and it is enough to drive the same animation the streamed route drives.
        override fun onRmsChanged(rmsdB: Float) {
            _level.value = recognizerRmsToLevel(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // Audio is done; the recogniser is still turning it into words.
            _level.value = 0f
            if (_state.value is VoiceState.Listening) _state.value = VoiceState.Thinking("")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (text.isNotBlank()) _state.value = VoiceState.Thinking(text)
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            if (text.isBlank()) {
                fail("Nothing was heard. Try again, closer to the remote's microphone.")
                return
            }
            heard = text
            _state.value = VoiceState.Thinking(text)
            // The words are ours now and the pipeline does the rest, so give the microphone back
            // rather than holding it for however long an agent takes to think.
            releaseOnMain()
            runPipeline(text)
        }

        override fun onError(error: Int) {
            // A no-match/timeout after speech was already transcribed would clobber a good result;
            // only report errors while we are still waiting for one.
            if (answering || _state.value is VoiceState.Done) return
            // These two are states it does not reliably come back from, so the shared binding is
            // dropped and the next press builds a new one. Everything else keeps it.
            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                SharedRecognizer.discard()
            }
            // The first press of a process is what starts Google's recognition service, and for the
            // ~2 seconds that takes the microphone is not open — so anyone who speaks when the bar
            // appears is speaking into nothing, and the stream dies having received no audio at all
            // (`ONLINE_NO_PROGRESS`, surfaced here as a network error). Measured on the UR3.
            //
            // By now the service is up and [SharedRecognizer] is holding it there, so listening
            // again works. Better than an error telling someone to press the key they just pressed.
            val coldService = error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
            if (coldService && !listenedAgain) {
                listenedAgain = true
                Log.i(TAG, "recogniser heard nothing on a cold service; listening again")
                _level.value = 0f
                _state.value = VoiceState.Starting
                main.post { beginListening() }
                return
            }
            fail(errorMessage(error))
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * The recogniser gives the words; the pipeline does everything after them.
     *
     * Starting at the `intent` stage means the assistant answers with its own agent and speaks with
     * its own voice, and the audio URL comes back in this same run — where the old path needed a
     * second REST call to `/api/tts_get_url` after the answer was already known.
     */
    private fun runPipeline(text: String) {
        answering = true
        Log.i(TAG, "heard ${text.length} chars; running pipeline=${pipelineId.ifBlank { "<preferred>" }}")
        val id = repository.startAssistPipeline(
            pipelineId = pipelineId,
            endAtTts = endAtTts,
            conversationId = threadId,
            text = text,
            onEvent = { onPipelineEvent(it) },
        )
        if (id == null) {
            fail("Not connected to Home Assistant.")
            return
        }
        runId = id
    }

    /**
     * The back half of the run. Identical in shape to [AssistVoiceSession]'s, and reading the same
     * fields through the same parsers — the only difference is that there was never any audio.
     */
    private fun onPipelineEvent(event: JsonObject) {
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return
        val data = event["data"] as? JsonObject
        when (type) {
            // A streaming agent writes its answer a piece at a time; showing it as it arrives is
            // the difference between five seconds of nothing and five seconds of watching it think.
            "intent-progress" -> {
                val delta = intentProgressDelta(data) ?: return
                streamed += delta
                _state.value = VoiceState.Answering(heard, streamed)
            }

            "intent-end" -> {
                val parsed = intentOutputReply(data)
                parsed.conversationId?.let { threadId = it }
                reply = parsed
                // Held, not published: the answer appears with its audio rather than ahead of it.
            }

            "tts-end" -> publish(ttsOutputUrl(data))

            "run-end" -> {
                publish(null)
                // Reaching the end with nothing published means no answer is coming. Say so now
                // rather than leaving the bar in Thinking until the watchdog notices.
                when (_state.value) {
                    is VoiceState.Done, is VoiceState.Failed, is VoiceState.Idle -> stopRun()
                    else -> fail("Home Assistant ended the exchange without an answer.")
                }
            }

            "error" -> fail(pipelineErrorMessage(data))
        }
    }

    /** Publishes the finished exchange, once — `tts-end` and `run-end` can both get here. */
    private fun publish(audioUrl: String?) {
        if (_state.value is VoiceState.Done) return
        val answer = reply ?: return
        answering = false
        Log.i(
            TAG,
            "answered type=${answer.responseType.ifBlank { "<none>" }} error=${answer.isError} " +
                "chars=${answer.speech.length} audio=${if (audioUrl == null) "none" else "yes"}",
        )
        _state.value = VoiceState.Done(heard, answer, audioUrl)
    }

    /** Stops routing events for a finished or abandoned run. */
    private fun stopRun() {
        runId?.let { repository.stopAssistPipeline(it) }
        runId = null
    }

    override fun stopSpeaking() {
        if (_state.value !is VoiceState.Listening) return
        // stopListening (not cancel) keeps whatever has been said and asks for a final result.
        main.post { runCatching { recognizer?.stopListening() } }
        _level.value = 0f
        _state.value = VoiceState.Thinking("")
    }

    override fun cancel() {
        stopRun()
        main.post {
            runCatching { recognizer?.cancel() }
            release()
        }
        _level.value = 0f
        _state.value = VoiceState.Idle
    }

    private fun fail(reason: String) {
        Log.w(TAG, "device recogniser failed: $reason")
        stopRun()
        _level.value = 0f
        _state.value = VoiceState.Failed(reason)
        releaseOnMain()
    }

    private fun releaseOnMain() = main.post { release() }

    /**
     * Ends this exchange's use of the recogniser without destroying it.
     *
     * `cancel()` rather than `destroy()`: the binding is what keeps the recognition service's
     * process alive, and dropping it is what made every press pay a cold start.
     */
    private fun release() {
        recognizer?.let { r -> runCatching { r.cancel() } }
        recognizer = null
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty().trim()

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "The TV could not record audio."
        SpeechRecognizer.ERROR_CLIENT -> "The speech recogniser refused the request."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone access is off. Grant it in Settings → Permissions on this TV."
        // Names the TV's own speech service, not "the network". This error comes from Katniss
        // failing its gRPC stream to Google's speech backend (io.grpc.StatusException: CANCELLED,
        // reported as ONLINE_NO_PROGRESS) — measured on the BRAVIA while Home Assistant was
        // reachable throughout and other exchanges in the same minute succeeded. The old wording
        // sent the user to check a network that was working.
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "The TV's speech service could not reach Google. Home Assistant is not what " +
                "transcribes here — try again, or switch to a microphone Home Assistant can hear."
        SpeechRecognizer.ERROR_NO_MATCH -> "That was not understood. Try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The TV's recogniser is busy — try again."
        SpeechRecognizer.ERROR_SERVER -> "The speech service returned an error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was heard."
        else -> "Speech recognition failed (code $error)."
    }

    private companion object {
        const val TAG = "DeviceRecognizer"
    }
}
