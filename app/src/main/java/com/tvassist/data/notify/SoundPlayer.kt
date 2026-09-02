package com.tvassist.data.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Plays a sound / audio file (chime, MP3, etc.) through the TV speakers, driven by Home Assistant (the
 * `/play` endpoint, or `sound:` on a notification). Optionally ducks/pauses the current TV audio while
 * it plays.
 *
 * Two engines:
 *  - **One-shot / non-loop, and long looping files:** ExoPlayer (must live on the main thread, so all
 *    ExoPlayer work is posted there).
 *  - **Short looping files:** the clip is decoded to raw PCM once, its silent head/tail (MP3 encoder
 *    delay/padding — the usual cause of an audible gap between loops) is trimmed, and it is looped
 *    sample-accurately with an [AudioTrack] in static mode. Decoding/AudioTrack run on [io]. If the
 *    file is too long to hold in memory, or can't be decoded, it falls back to the ExoPlayer loop.
 */
class SoundPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    // Decode + AudioTrack work; single-threaded so track access is naturally serialized.
    private val io = Executors.newSingleThreadExecutor()
    private var player: ExoPlayer? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var focusRequest: AudioFocusRequest? = null
    // Bumped on every play(); a caller's stop(token) is honored only while its token is still current,
    // so a lingering lifecycle watcher for an old sound can't stop a newer one that replaced it.
    @Volatile private var token = 0L
    // Run once when the current sound ends of its own accord. Lets a caller follow the audio rather
    // than guess its length — the Assist bar dismisses itself when the spoken reply finishes.
    @Volatile private var onFinished: (() -> Unit)? = null

    /**
     * [volume] is 0–100 (relative to the TV volume); [duckMode] = "off" / "duck" / "pause". When
     * [loop] is true the file repeats gaplessly until [stop] (or a new play) — used to sound for a
     * notification's whole on-screen life. Returns a token to pass to [stop].
     *
     * [onFinished] runs when playback ends on its own — not when [stop] or a newer play cuts it
     * short, and never for a [loop]ing sound, which by definition does not end.
     */
    fun play(
        url: String,
        volume: Int? = null,
        duckMode: String = "duck",
        loop: Boolean = false,
        onFinished: (() -> Unit)? = null,
    ): Long {
        if (url.isBlank()) return 0L
        val t = System.nanoTime()
        token = t
        this.onFinished = onFinished
        if (loop) {
            // Try the gapless PCM path off the main thread; fall back to ExoPlayer if unsuitable.
            io.execute {
                val pcm = decodePcm(url, MAX_PCM_BYTES)?.let(::trimSilence)
                if (token != t) return@execute
                if (pcm == null || pcm.bytes.isEmpty()) {
                    main.post { startExoPlayer(url, volume, duckMode, loop = true, t = t) }
                } else {
                    playLoopingPcm(url, pcm, volume, duckMode, t)
                }
            }
        } else {
            main.post { startExoPlayer(url, volume, duckMode, loop = false, t = t) }
        }
        return t
    }

    /** Stop the current sound (either engine), but only if [t] is still current (see [token]). */
    fun stop(t: Long) {
        // Stopped audio never "finished"; the caller cut it short deliberately.
        if (t == token) onFinished = null
        io.execute { if (t == token) releaseTrack() }
        main.post { if (t == token) finish() }
    }

    // --- ExoPlayer path (main thread) --------------------------------------------------------------

    private fun startExoPlayer(url: String, volume: Int?, duckMode: String, loop: Boolean, t: Long) {
        if (token != t) return
        runCatching {
            releaseTrack()
            requestFocus(duckMode)
            val p = ensurePlayer()
            // Reused, so anything the last sound set has to be undone rather than assumed.
            p.stop()
            p.clearMediaItems()
            val item = MediaItem.fromUri(url)
            if (loop) {
                // Loop via a 2-item playlist + REPEAT_MODE_ALL rather than REPEAT_MODE_ONE: ExoPlayer
                // restarts through its gapless item-transition path, more seamless than re-seeking one
                // item to 0. (Used only for files too long for the PCM path.)
                p.setMediaItems(listOf(item, item))
                p.repeatMode = Player.REPEAT_MODE_ALL
            } else {
                p.setMediaItem(item)
                // A previous looping sound would otherwise leave this player repeating forever.
                p.repeatMode = Player.REPEAT_MODE_OFF
            }
            p.volume = gain(volume)
            p.prepare()
            p.playWhenReady = true
        }.onFailure { Log.w(TAG, "sound play error", it); abandonFocus() }
    }

    // --- Gapless PCM loop path (io thread) ---------------------------------------------------------

    private fun playLoopingPcm(url: String, pcm: Pcm, volume: Int?, duckMode: String, t: Long) {
        main.post { stopPlayer() } // silence any ExoPlayer that was running
        releaseTrack()
        requestFocus(duckMode)
        val at = runCatching { buildLoopingTrack(pcm, volume) }.getOrNull()
        if (at == null) {
            // AudioTrack setup failed — fall back to the ExoPlayer loop.
            main.post { if (token == t) startExoPlayer(url, volume, duckMode, loop = true, t = t) }
            return
        }
        if (token != t) { runCatching { at.release() }; abandonFocus(); return }
        track = at
        runCatching { at.play() }.onFailure {
            Log.w(TAG, "AudioTrack play failed", it); releaseTrack(); abandonFocus()
        }
    }

    private fun buildLoopingTrack(pcm: Pcm, volume: Int?): AudioTrack {
        val channelMask =
            if (pcm.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(pcm.sampleRate)
            .setChannelMask(channelMask)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val at = AudioTrack(
            attrs, format, pcm.bytes.size, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        at.write(pcm.bytes, 0, pcm.bytes.size)
        val frames = pcm.bytes.size / (pcm.channels * 2)
        at.setLoopPoints(0, frames, -1) // -1 = loop forever, seamlessly (raw PCM has no padding)
        at.setVolume(gain(volume))
        return at
    }

    /** Decode [url]'s audio to 16-bit PCM. Returns null if it can't decode or exceeds [maxBytes]. */
    private fun decodePcm(url: String, maxBytes: Int): Pcm? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(url)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var inEos = false
            var outEos = false
            while (!outEos) {
                if (!inEos) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        val chunk = ByteArray(info.size)
                        buf.get(chunk); buf.clear()
                        out.write(chunk)
                        codec.releaseOutputBuffer(outIndex, false)
                        if (out.size() > maxBytes) { codec.stop(); codec.release(); return null }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        // We interpret the PCM as 16-bit; if the decoder emits anything else (e.g.
                        // float), bail so the caller falls back to ExoPlayer rather than play noise.
                        val enc = if (nf.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            nf.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        if (enc != AudioFormat.ENCODING_PCM_16BIT) { codec.stop(); codec.release(); return null }
                    }
                }
            }
            codec.stop(); codec.release()
            // AudioTrack path only handles mono/stereo 16-bit; anything else → ExoPlayer fallback.
            if (channels !in 1..2) return null
            Pcm(out.toByteArray(), sampleRate, channels)
        } catch (e: Exception) {
            Log.w(TAG, "decode failed: ${e.message}")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Trim contiguous near-silent frames (encoder padding) from the head and tail of the PCM. */
    private fun trimSilence(pcm: Pcm): Pcm {
        val bytesPerFrame = pcm.channels * 2
        val totalFrames = pcm.bytes.size / bytesPerFrame
        if (totalFrames == 0) return pcm
        val samples = ByteBuffer.wrap(pcm.bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun loud(frame: Int): Boolean {
            for (c in 0 until pcm.channels) {
                if (abs(samples.getShort(frame * bytesPerFrame + c * 2).toInt()) > SILENCE_THRESHOLD) return true
            }
            return false
        }
        var start = 0
        while (start < totalFrames && !loud(start)) start++
        var end = totalFrames - 1
        while (end > start && !loud(end)) end--
        if (start >= end) return pcm // essentially all silence — leave as-is
        val trimmed = pcm.bytes.copyOfRange(start * bytesPerFrame, (end + 1) * bytesPerFrame)
        return Pcm(trimmed, pcm.sampleRate, pcm.channels)
    }

    // --- shared ------------------------------------------------------------------------------------

    private fun gain(volume: Int?): Float = ((volume ?: 100).coerceIn(0, 100)) / 100f

    private fun finish() {
        abandonFocus()
        stopPlayer()
        releaseTrack()
        // Taken before invoking: a callback that starts new audio must not be re-run by it.
        onFinished.also { onFinished = null }?.invoke()
    }

    /**
     * The one player, built on first use and then kept.
     *
     * Building an [ExoPlayer] enumerates the device's codecs, and on a Mediatek BRAVIA that took
     * **2.6 seconds** — measured between `ExoPlayerImpl Init` and `MtkACodecPlugin createAPlugin` in
     * a real voice exchange — on the main thread, before a short MP3 could start. It was paid on
     * every notification sound and every spoken Assist reply, because the player was built and
     * released around each one.
     *
     * The renderers factory is audio-only for the same reason: the default one enumerates video
     * codecs too, and the log of that 2.6 seconds is mostly `Unsupported mime video/dolby-vision`,
     * `video/x-vp6`, `mpeg2 profile 4` and friends — none of which can appear in a doorbell chime.
     */
    @OptIn(UnstableApi::class)
    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer
        .Builder(appContext, AudioOnlyRenderersFactory(appContext))
        .build()
        .also { p ->
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    // A looping player never reports ENDED; it stops via stop()/a new play().
                    if (state == Player.STATE_ENDED) main.post { finish() }
                }
                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "sound playback failed: ${error.message}")
                    main.post { finish() }
                }
            })
            player = p
        }

    /**
     * Pays the codec enumeration ahead of time, so the first sound of the day is as quick as the
     * rest. Called at start-up beside the text-to-speech warm-up, which exists for the same reason.
     *
     * Two halves, because the cost is in two places. Building the player is cheap once the video
     * renderers are gone; the expensive part is the decoder lookup, which media3 does lazily inside
     * `prepare()` — on the main thread, while somebody is waiting for an answer. [MediaCodecUtil]
     * caches its results statically, so asking it here once, off the main thread, means the first
     * real playback finds them already there.
     */
    fun warmUp() {
        main.post { runCatching { ensurePlayer() }.onFailure { Log.w(TAG, "player warm-up failed", it) } }
        io.execute {
            // The two the TV will actually be handed: Home Assistant's tts_proxy serves mp3, and
            // notification media is commonly AAC.
            for (mime in listOf(MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_AAC)) {
                runCatching { warmDecoders(mime) }
                    .onFailure { Log.w(TAG, "decoder warm-up failed for $mime", it) }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun warmDecoders(mime: String) {
        // The return value is thrown away on purpose — the point is the static cache it fills.
        MediaCodecUtil.getDecoderInfos(mime, false, false)
    }

    /** Ends playback but keeps the player — see [ensurePlayer] for why it is not released. */
    private fun stopPlayer() {
        player?.let { runCatching { it.stop(); it.clearMediaItems() } }
    }

    private fun releaseTrack() {
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
    }

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
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
        io.execute { releaseTrack() }
        main.post { finish() }
        io.shutdown()
    }

    private class Pcm(val bytes: ByteArray, val sampleRate: Int, val channels: Int)

    companion object {
        private const val TAG = "SoundPlayer"
        private const val TIMEOUT_US = 10_000L
        // Cap the in-memory PCM for the gapless path (~10s stereo @48k/16-bit); longer → ExoPlayer.
        private const val MAX_PCM_BYTES = 4_000_000
        // 16-bit amplitude below this counts as silence when trimming padding (~-54 dBFS).
        private const val SILENCE_THRESHOLD = 64
    }
}

/**
 * An [ExoPlayer] renderers factory that builds no video renderers.
 *
 * This app's [SoundPlayer] plays chimes and synthesised speech and nothing else, but the default
 * factory asks the platform about every video codec it might ever need — dolby-vision, mpeg2, vp6,
 * wmv, mjpeg — and on a Mediatek BRAVIA that scan is where most of a 2.6 second cold start went.
 * Skipping it is the difference between a reply that answers and one that arrives late.
 */
@UnstableApi
private class AudioOnlyRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: android.os.Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        // Deliberately none.
    }
}
