package com.tvassist.data.assist

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * The TV's speech recogniser, kept bound between exchanges.
 *
 * Process-wide on purpose, because the thing being conserved is not the object — it is the *other*
 * process. Binding a [SpeechRecognizer] starts Google's recognition service; destroying it drops
 * the last client, and Android promptly kills that service's process. A session that created one
 * per exchange therefore paid a full cold start on **every** press.
 *
 * Measured on the UR3, two presses 28 seconds apart:
 *
 * ```
 * 00:00:21.203  Start proc 3154:com.google.android.katniss:search   ← press 1
 * 00:00:23.354  Soda - start listening                              (+2.2s)
 * 00:00:28.125  #onError ONLINE_NO_PROGRESS, grpc CANCELLED
 * 00:00:49.385  Start proc 4141:com.google.android.katniss:search   ← press 2, a NEW process
 * ```
 *
 * Two seconds of that is the bar saying "Opening the mic" while the microphone is not open yet, so
 * anyone who starts talking when the bar appears is talking into nothing — which is what the first
 * press's "no progress" failure is: a stream that never received any audio.
 *
 * So the binding is held. Nothing else here is stateful; the listener is replaced per exchange by
 * whichever session is running, and sessions never overlap.
 */
object SharedRecognizer {
    private const val TAG = "DeviceRecognizer"
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var recognizer: SpeechRecognizer? = null

    /**
     * The shared recogniser, creating it if this is the first use. Main thread only —
     * [SpeechRecognizer] requires it. Null when this device has no recogniser at all.
     */
    fun acquire(context: Context): SpeechRecognizer? {
        val app = context.applicationContext
        if (!SpeechRecognizer.isRecognitionAvailable(app)) return null
        recognizer?.let { return it }
        return runCatching { SpeechRecognizer.createSpeechRecognizer(app) }
            .onFailure { Log.w(TAG, "could not create the TV's recogniser", it) }
            .getOrNull()
            ?.also { recognizer = it }
    }

    /*
     * There is deliberately no warm-up here, and that is not for want of trying.
     *
     * The other warm-ups in this app work because building the thing is the expensive part. This
     * one does not: `createSpeechRecognizer` only constructs an object, and the service is not
     * bound — the process not started — until `startListening`. Warming it would mean opening the
     * microphone behind the user's back, which is not a trade worth making to save two seconds.
     * `checkRecognitionSupport` would bind without recording, but it is API 33 and both TVs are
     * older.
     *
     * So the first press of a process still pays the cold start. What [DeviceRecognizerSession]
     * does instead is notice when that cold start is what failed, and listen again — by which time
     * this binding exists and the service stays up.
     */

    /**
     * Drops the binding, so the next [acquire] builds a fresh one.
     *
     * Only for a recogniser that has gone wrong — `ERROR_CLIENT` and `ERROR_RECOGNIZER_BUSY` are
     * states it does not always come back from. Ordinary end-of-exchange keeps the binding: that is
     * the entire point.
     */
    fun discard() {
        main.post {
            recognizer?.let { runCatching { it.destroy() } }
            recognizer = null
        }
    }
}
