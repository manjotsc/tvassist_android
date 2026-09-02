package com.tvassist.data.assist

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Microphone capture for the Assist pipeline: 16 kHz mono PCM16, which is the format HA's
 * speech-to-text stage expects.
 *
 * Deliberately blocking and thread-confined — [stream] runs the read loop on the caller's thread
 * (an IO coroutine) and returns when [stop] is called or the mic fails. Nothing here touches the
 * WebSocket; the caller decides what to do with each chunk.
 */
class AudioCapture(
    private val sampleRate: Int,
    /** Pin recording to this input; null lets the framework choose. */
    private val preferredDevice: AudioDeviceInfo? = null,
) {

    @Volatile
    private var running = false
    private var record: AudioRecord? = null

    /**
     * Records until [stop], handing each chunk to [onChunk] as (buffer, length). The buffer is
     * REUSED between calls — copy it if you need to keep it past the callback.
     *
     * [onChunk] returns false when it could not take the audio — a WebSocket that has gone away —
     * and the loop ends there. Recording on into a closed socket produces no transcript and no
     * voice-activity end, so nothing downstream ever hears that the utterance is over: the exchange
     * used to sit on "Listening" for the full sixty seconds of the watchdog before anyone said so.
     *
     * [onLevel] gets the same chunk's loudness as a 0..1 figure for the listening animation. It is
     * measured here rather than by the caller because this is the one place the samples are already
     * in hand — a second pass over the audio downstream would be pure waste.
     *
     * Returns null on success, or a human-readable reason the mic could not be opened. Requires
     * RECORD_AUDIO to have been granted already; without it [AudioRecord] yields no data.
     */
    @SuppressLint("MissingPermission") // Caller checks RECORD_AUDIO; see AssistVoiceSession.
    fun stream(onChunk: (ByteArray, Int) -> Boolean, onLevel: (Float) -> Unit = {}): String? {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return "This device cannot record at ${sampleRate} Hz."

        // Room for several reads so a slow send cannot drop samples on the floor.
        val bufferSize = minBuffer * 4
        // VOICE_RECOGNITION skips the "music" tuning (AGC/noise suppression aimed at speech), which
        // is what an STT engine wants. Not every HAL offers it, though, and one that refuses either
        // throws or hands back an uninitialised record: fall back to MIC rather than telling someone
        // with a working USB microphone that it could not be opened.
        var opened: AudioRecord? = null
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val candidate = try {
                AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord construction failed for source $source", t)
                null
            } ?: continue
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                opened = candidate
                break
            }
            Log.w(TAG, "AudioRecord source $source did not initialise")
            runCatching { candidate.release() }
        }
        // A val, not the loop's var: the preferred-device lambda below captures it, and a captured
        // var gets no smart cast.
        val rec = opened ?: return "The microphone is unavailable (in use by another app?)."

        // Honour the user's chosen microphone. Advisory by design: if the device has gone away
        // since it was picked, the framework falls back rather than failing the recording.
        preferredDevice?.let { runCatching { rec.setPreferredDevice(it) } }

        record = rec
        running = true
        return try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return "The microphone did not start."
            }
            val buf = ByteArray(minBuffer.coerceAtLeast(2048))
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    if (!onChunk(buf, n)) return "Lost the connection to Home Assistant while listening."
                    onLevel(rmsToLevel(pcm16Rms(buf, n)))
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error $n")
                    return "The microphone stopped unexpectedly."
                }
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "capture failed", t)
            "Recording failed: ${t.message ?: t::class.java.simpleName}"
        } finally {
            running = false
            runCatching { rec.stop() }
            runCatching { rec.release() }
            record = null
        }
    }

    /** Ends the read loop; [stream] returns shortly after. Safe to call from any thread. */
    fun stop() {
        running = false
    }

    private companion object {
        const val TAG = "AudioCapture"
    }
}
