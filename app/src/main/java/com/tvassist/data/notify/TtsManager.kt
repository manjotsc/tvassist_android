package com.tvassist.data.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Speaks text through the TV speakers via Android [TextToSpeech], driven by Home Assistant (the
 * `/speak` endpoint) or a notification. A notification can pass several utterances (e.g. title then
 * message) that are spoken in order, and optionally [repeat] the whole sequence until [stop] — used
 * to keep announcing for a notification's on-screen life. Optionally ducks the current TV audio while
 * speaking. If the device has no TTS engine, speak requests are logged and ignored rather than crashing.
 */
class TtsManager(context: Context) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ready = false
    // Requests that arrive before the engine finishes initialising are queued and flushed on init.
    private val pending = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile private var focusRequest: AudioFocusRequest? = null

    // Bumped on every speak(); a caller's stop(token) is honored only while its token is still
    // current, so a lingering lifecycle watcher for old speech can't stop newer speech.
    @Volatile private var token = 0L
    // Repeat state for the current utterance sequence.
    @Volatile private var repeat = false
    @Volatile private var repeatTexts: List<String> = emptyList()
    @Volatile private var repeatParams: Bundle = Bundle()
    // Utterance id of the final segment in a sequence; only its completion advances/repeats.
    @Volatile private var finalUid: String = ""
    // Run once when the current sequence finishes of its own accord. Lets a caller follow the
    // speech rather than guess at its duration — the Assist bar dismisses itself on it.
    @Volatile private var onFinished: (() -> Unit)? = null
    // Pause between repeats of the current sequence (set per speak() call).
    @Volatile private var repeatGapMs = DEFAULT_REPEAT_GAP_MS

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = onSegmentDone(utteranceId)
                @Deprecated("deprecated in API") override fun onError(utteranceId: String?) = onSegmentDone(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = onSegmentDone(utteranceId)
            })
            while (true) (pending.poll() ?: break).invoke()
        } else {
            Log.w(TAG, "No TextToSpeech engine available; speak requests will be ignored")
        }
    }

    /**
     * Pre-load the engine and its voice model so the first real announcement isn't delayed by a cold
     * start (Google TTS lazily loads its neural voice on the first synthesis — ~5s). Speaks a silent
     * ([volume] 0), focus-free utterance; queued until the engine finishes initialising.
     */
    fun warmUp() {
        val action: () -> Unit = {
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f) }
            runCatching { tts.speak("warm up", TextToSpeech.QUEUE_FLUSH, params, "tvassist-warmup") }
        }
        if (ready) action() else pending.add(action)
    }

    /**
     * Speak [utterances] in order (blank entries dropped). [language] is a BCP-47 tag (e.g. "en-US");
     * [volume] is 0–100; [duckMode] controls how current TV audio reacts ("off" = play over,
     * "duck" = OS lowers it, "pause" = pause it). When [repeat] is true the whole sequence repeats
     * (with a short gap) until [stop] or a newer speak. Returns a token to pass to [stop].
     *
     * [onFinished] runs on the TTS callback thread when the sequence ends of its own accord. It does
     * NOT run when speech is cut short — by [stop], by a newer speak, or because [repeat] means it
     * never ends — so a caller waiting on it wants a timeout of its own as well.
     */
    fun speak(
        utterances: List<String>,
        language: String? = null,
        volume: Int? = null,
        duckMode: String = "duck",
        interrupt: Boolean = true,
        repeat: Boolean = false,
        repeatGapMs: Long = DEFAULT_REPEAT_GAP_MS,
        onFinished: (() -> Unit)? = null,
    ): Long {
        val texts = utterances.map { it.trim() }.filter { it.isNotBlank() }
        if (texts.isEmpty()) return 0L
        val t = System.nanoTime()
        token = t
        val action: () -> Unit = {
            // Cancel any pending repeat from previous speech before starting this sequence.
            main.removeCallbacksAndMessages(null)
            language?.takeIf { it.isNotBlank() }?.let {
                runCatching { tts.language = Locale.forLanguageTag(it) }
            }
            requestFocus(duckMode)
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ((volume ?: 100).coerceIn(0, 100)) / 100f)
            }
            this.repeat = repeat
            this.repeatTexts = if (repeat) texts else emptyList()
            this.repeatParams = params
            this.repeatGapMs = repeatGapMs.coerceAtLeast(0L)
            this.finalUid = "tvassist-final-$t"
            this.onFinished = onFinished
            speakSequence(texts, params, flushFirst = interrupt)
        }
        if (ready) action() else pending.add(action)
        return t
    }

    /**
     * Speak one sequence. The first segment flushes prior speech (barge-in) when [flushFirst], else it
     * queues after it; the rest always queue so the sequence stays in order.
     */
    private fun speakSequence(texts: List<String>, params: Bundle, flushFirst: Boolean) {
        texts.forEachIndexed { i, text ->
            val mode = if (i == 0 && flushFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val uid = if (i == texts.lastIndex) finalUid else "tvassist-seg-$i-${System.nanoTime()}"
            tts.speak(text, mode, params, uid)
        }
    }

    /** Called when a segment finishes (or errors); only the final segment repeats or releases focus. */
    private fun onSegmentDone(utteranceId: String?) {
        if (utteranceId != finalUid) return
        val texts = repeatTexts
        if (repeat && texts.isNotEmpty()) {
            // Keep audio focus held across the gap so ducking doesn't flap between repeats.
            main.postDelayed({ speakSequence(texts, repeatParams, flushFirst = true) }, repeatGapMs)
        } else {
            abandonFocus()
            // Taken before invoking: a callback that starts new speech must not be re-run by it.
            onFinished.also { onFinished = null }?.invoke()
        }
    }

    /** Stop speech (and any repeat), but only if [t] is still the current sequence (see [token]). */
    fun stop(t: Long) {
        if (t != token) return
        repeat = false
        repeatTexts = emptyList()
        onFinished = null // stopped speech never "finished"; the caller cut it short deliberately
        main.removeCallbacksAndMessages(null)
        runCatching { tts.stop() }
        abandonFocus()
    }

    /**
     * Grab audio focus for the announcement: "duck" lowers other audio (OS-controlled amount) while
     * we play; "pause" pauses it (transient gain); "off" plays over without touching it.
     */
    private fun requestFocus(duckMode: String) {
        abandonFocus()
        val gain = when (duckMode.lowercase()) {
            "off" -> return
            "pause" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            else -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        focusRequest = req
        runCatching { audio.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        focusRequest?.let { req -> runCatching { audio.abandonAudioFocusRequest(req) } }
        focusRequest = null
    }

    fun shutdown() {
        repeat = false
        main.removeCallbacksAndMessages(null)
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        abandonFocus()
    }

    companion object {
        private const val TAG = "TtsManager"
        // Default pause between repeats of a repeating announcement, so it doesn't sound frantic.
        private const val DEFAULT_REPEAT_GAP_MS = 2000L
    }
}
