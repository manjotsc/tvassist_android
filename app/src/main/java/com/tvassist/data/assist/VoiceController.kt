package com.tvassist.data.assist

import android.content.Context
import android.util.Log
import com.tvassist.data.ha.ConnectionState
import com.tvassist.data.ha.HaRepository
import com.tvassist.data.notify.SoundPlayer
import com.tvassist.data.settings.Settings
import com.tvassist.data.settings.SettingsStore
import com.tvassist.overlay.KeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** What the voice bar is showing. Null means no bar. */
data class VoiceUi(val phase: VoiceState)

/**
 * The one live voice exchange, owned by the process rather than by any window.
 *
 * The bar is drawn by [com.tvassist.overlay.KeepAliveService] and driven by the remote's mic key
 * through [com.tvassist.keymap.KeyCaptureService], neither of which can hold Compose state across a
 * window being added and removed — so the state lives here, and both are just views onto it.
 *
 * It also owns [threadId]. That is the part that could not stay in the card: a conversation id
 * remembered inside a composable dies with the card, so speaking a follow-up after typing (or after
 * the bar has come and gone once) would silently have started a new thread and lost what "it" meant.
 */
class VoiceController(
    private val context: Context,
    private val repository: HaRepository,
    settingsStore: SettingsStore,
    private val sound: SoundPlayer,
    appScope: CoroutineScope,
) {
    /**
     * Everything here runs on the main thread.
     *
     * [dismissJob], [watchJob], [watchdogJob], [session] and [spokenToken] are written from three
     * directions: coroutines started here, [SoundPlayer]'s completion callback (main), and the key
     * router calling [trigger]/[dismiss] straight off a key event (main). The app scope carries no
     * dispatcher, so those coroutines would otherwise land on Dispatchers.Default and race the other
     * two. Nothing in this class blocks: the mic read loop takes Dispatchers.IO explicitly, and
     * every repository call suspends.
     */
    private val scope: CoroutineScope =
        CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow<VoiceUi?>(null)
    val ui: StateFlow<VoiceUi?> = _ui.asStateFlow()

    /** Microphone loudness, 0..1, for the listening animation. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /**
     * The live conversation — thread id, whose it is, how stale, and what has been said.
     *
     * Held by the controller rather than by a window for the same reason everything else here is:
     * a conversation id remembered inside a composable dies with the card, so speaking a follow-up
     * after typing (or after the bar has come and gone once) would silently start a new thread and
     * lose what "it" meant. See [ConversationThread] for the rules; the delegating methods below
     * are the surface the card and the key router use.
     */
    private val conversation = ConversationThread()

    /** The live thread id, for the card's "New thread" button. Never sent blind — see [threadFor]. */
    val threadId: StateFlow<String?> = conversation.id

    /** The conversation so far, spoken and typed turns together. */
    val turns: StateFlow<List<ConversationTurn>> = conversation.turns

    @Volatile
    private var settings: Settings? = null

    /**
     * The pipeline list, cached.
     *
     * Needed because the recogniser route never runs an HA pipeline — the TV transcribes and the app
     * calls `conversation.process` itself — so the only way it can answer as the *same* agent the
     * pipeline would use is to look that agent up and pass it along. Fetched in the background so a
     * mic press never waits on it; an unresolved agent falls back to HA's default.
     */
    @Volatile
    private var pipelines: com.tvassist.data.ha.AssistPipelines? = null

    /** The agent the exchange now on screen was addressed to, for filing its thread id. */
    @Volatile
    private var askedAgent: String = ""

    private var session: VoiceSession? = null
    private var watchJob: Job? = null
    private var watchdogJob: Job? = null
    private var dismissJob: Job? = null
    private var spokenToken = 0L

    init {
        scope.launch { settingsStore.settings.collect { settings = it } }
        // Re-read on every (re)connection rather than once at start-up. Fetching once would race the
        // WebSocket and lose — the list would stay null for the whole session, which on the recogniser
        // route means no agent and no voice, so the BRAVIA's remote would answer as HA's default and
        // never speak. Pipelines are also edited in HA, so a reconnect is a fair moment to refresh.
        scope.launch {
            repository.connectionState.collectLatest { state ->
                if (state == ConnectionState.Connected) refreshPipelines()
            }
        }
    }

    /**
     * Asks for the pipeline list until it arrives, for as long as this connection lasts.
     *
     * One attempt was not enough. The fetch is a WebSocket request with a 15 s deadline, and it can
     * lose to a socket that is up but not yet settled — most likely exactly once, on the first
     * connect after a reboot. Nothing else would have asked again: the only other trigger is a
     * reconnect, which on a TV that then stays connected for days never comes. So a single lost
     * request left [pipelines] null for the whole uptime.
     *
     * Null is not a graceful degradation. On the recogniser route it means no agent — Home
     * Assistant's default intent matcher answers instead of the pipeline's own conversation engine
     * — and [pipelineVoice] blank, so no spoken reply either. That reads as Assist having gone
     * stupid and silent, with nothing on screen to say why. Opening the app did not repair it
     * either: the settings picker keeps its own copy of the list, and the repository's connect() is
     * a no-op when it is already connected, so no new [ConnectionState.Connected] edge ever fired.
     *
     * [collectLatest] cancels this the moment the connection state changes, so a dropped socket ends
     * the loop rather than retrying into a dead one. An instance with genuinely no pipelines answers
     * with an empty list rather than null, so that settles on the first attempt instead of looping.
     */
    private suspend fun refreshPipelines() {
        var wait = PIPELINE_RETRY_MS
        while (true) {
            val fetched = runCatching { repository.fetchAssistPipelines() }.getOrNull()
            if (fetched != null) {
                pipelines = fetched
                return
            }
            Log.w(TAG, "assist pipeline list unavailable; retrying in ${wait / 1000}s")
            delay(wait)
            wait = (wait * 2).coerceAtMost(PIPELINE_RETRY_MAX_MS)
        }
    }

    /** The conversation agent the configured pipeline answers with, or blank if not yet known. */
    private fun pipelineAgent(current: Settings): String =
        pipelines?.resolve(current.assistPipelineId)?.conversationEngine.orEmpty()

    /**
     * The language to listen in.
     *
     * The assistant's own language first: with several configured, each may hear a different one,
     * and a single app-wide announcement setting has no business overriding all of them. It is
     * still the fallback for the moment before the pipeline list has loaded.
     *
     * Only the TV's recogniser uses this. Where Home Assistant does the hearing, the pipeline's
     * language applies without anyone passing it.
     */
    private fun listeningLanguage(current: Settings): String =
        pipelines?.resolve(current.assistPipelineId)?.language.orEmpty()
            .ifBlank { current.announceLanguage }

    /** The voice the configured pipeline speaks with, or blank if it has none / is not yet known. */
    private fun pipelineVoice(current: Settings): String =
        pipelines?.resolve(current.assistPipelineId)?.ttsEngine.orEmpty()

    /**
     * Whether to run the pipeline through synthesis. Unknown counts as yes: most instances have a
     * voice, and the list is usually cached within a second of start-up — where guessing wrong the
     * other way would leave every reply silent until it loaded.
     */
    private fun pipelineHasVoice(current: Settings): Boolean =
        pipelines?.resolve(current.assistPipelineId)?.supportsVoice ?: true

    /** True while the bar is up — the cue for the window host and the key router. */
    val isActive: Boolean get() = _ui.value != null

    /**
     * What the mic key does, which depends on where the exchange has got to: open the mic, close it
     * and let the answer arrive, or — on a finished exchange — go straight into a follow-up rather
     * than making the user dismiss the answer first.
     */
    fun trigger() {
        when (_ui.value?.phase) {
            null, is VoiceState.Idle, is VoiceState.Done, is VoiceState.Failed -> start()
            is VoiceState.Listening -> finish()
            // Already in flight, answer included: interrupting a reply the agent is still writing
            // to ask something else is what BACK is for.
            is VoiceState.Starting, is VoiceState.Thinking, is VoiceState.Answering -> Unit
        }
    }

    /**
     * What OK does, which is not "the same as the mic key".
     *
     * The bar is not focusable, so [com.tvassist.keymap.KeyCaptureService] consumes OK for as long
     * as one is up and routes it here — and this used to be [finish], which does nothing outside
     * [VoiceState.Listening]. A finished exchange stays on screen while its answer is read aloud,
     * up to [SPEECH_DEADLINE_MS], so OK was a dead key for a minute and a half over whatever was
     * playing: it neither dismissed the bar nor reached the app underneath.
     *
     * Dismissing is also the "stop talking" button — [dismiss] silences a reply mid-sentence.
     * Starting and Thinking still swallow it deliberately: the answer is seconds away and bounded
     * by the watchdog, and a stray press must not act on the app behind the bar.
     */
    fun confirm() {
        when (_ui.value?.phase) {
            is VoiceState.Listening -> finish()
            is VoiceState.Done, is VoiceState.Failed -> dismiss()
            else -> Unit
        }
    }

    /** Opens the mic and runs the configured pipeline. Shows the reason in the bar when it cannot. */
    fun start() {
        // The bar is drawn by [KeepAliveService]'s overlay window and by nothing else — VoiceBar has
        // exactly one call site — so a press that lands before that service is up produces no bar, no
        // error and no clue why, including every failure message below. Measured after a reboot on a
        // BRAVIA VH21: the accessibility service was capturing keys 28 s in, but BOOT_COMPLETED, the
        // only thing that started KeepAlive, did not land until 109 s — 80 seconds where voice was
        // dead and silent about it while the sidebar (its own window) worked fine.
        //
        // Idempotent, so the normal case where the service is already up costs one re-posted
        // notification. Wrapped because a background foreground-service start can be refused: the app
        // is exempt while it holds SYSTEM_ALERT_WINDOW, which it needs for the overlay anyway, but a
        // refusal must not take the voice path down with it.
        runCatching { KeepAliveService.start(context) }
            .onFailure { Log.w(TAG, "could not start the service that hosts the voice bar", it) }

        val current = settings
        if (current == null) {
            show(VoiceState.Failed("Still starting up — try again in a moment."))
            return
        }
        val created = createVoiceSession(
            context, repository, scope, current.assistMicId, current.assistPipelineId,
            pipelineHasVoice(current),
        )
        if (created == null) {
            show(VoiceState.Failed(unavailableReason(current)))
            return
        }

        teardown()
        session = created
        _ui.value = VoiceUi(VoiceState.Starting)
        armWatchdog(VoiceState.Starting)
        watchJob = scope.launch {
            launch { created.level.collect { _level.value = it } }
            created.state.collect { onPhase(it) }
        }
        // Not sent anywhere any more — both routes address a pipeline, which carries its own agent.
        // Still needed here, though: it is who the thread belongs to, and handing one agent's
        // conversation id to another is what [ConversationThread] exists to prevent.
        val agent = pipelineAgent(current)
        askedAgent = agent
        val thread = beginExchange(agent)
        // Follow-ups need BOTH halves: the pipeline's agent (blank falls back to HA's default, an
        // intent matcher with no memory) and a thread that same agent issued. Says which is missing.
        Log.i(
            TAG,
            "voice start agent=${agent.ifBlank { "<default>" }} " +
                "pipelines=${if (pipelines == null) "unfetched" else "cached"} " +
                "thread=${if (thread == null) "new" else "continued"}",
        )
        created.start(thread, listeningLanguage(current))
    }

    /** Stops listening but lets the answer arrive — what OK does while the mic is open. */
    fun finish() {
        session?.stopSpeaking()
    }

    /** Abandons the exchange and takes the bar down — what BACK does. */
    fun cancel() = dismiss()

    /** Forgets the thread and the transcript, so the next question starts fresh. */
    fun newThread() = conversation.reset()

    /** Appends one turn to [turns]. See [ConversationThread.record]. */
    fun record(mine: Boolean, text: String, error: Boolean = false) =
        conversation.record(mine, text, error)

    /** Whether the history in [turns] is this agent's to show. See [ConversationThread.ownsHistory]. */
    fun ownsHistory(agentId: String): Boolean = conversation.ownsHistory(agentId)

    /** Opens an exchange with [agentId]. See [ConversationThread.begin]. */
    fun beginExchange(agentId: String): String? = conversation.begin(agentId)

    /** Adopts the thread id an answer came back with. See [ConversationThread.adopt]. */
    fun adoptThread(agentId: String, id: String?) = conversation.adopt(agentId, id)

    /**
     * The thread to continue when asking [agentId], or null to start a new one.
     *
     * Public because the typed card needs the same gate the voice route uses: it reads the raw
     * [threadId] flow to decide whether to offer "New thread", but must not send that id blind —
     * the card asks whichever conversation entity is open, so a thread started by the pipeline's
     * agent would otherwise be handed to a different one.
     */
    fun threadFor(agentId: String): String? = conversation.forAgent(agentId)

    private fun onPhase(phase: VoiceState) {
        armWatchdog(phase)
        when (phase) {
            // The session's own teardown. The bar's lifetime is this controller's to decide, so a
            // session going Idle must not yank an answer off screen before it has been read.
            is VoiceState.Idle -> return

            is VoiceState.Done -> {
                adoptThread(askedAgent, phase.reply.conversationId)
                // Recorded as the exchange finishes, so a question asked out loud is in the
                // transcript the card renders rather than only in the bar that is about to leave.
                record(mine = true, text = phase.transcript)
                record(mine = false, text = phase.reply.displayText, error = phase.reply.isError)
                _ui.value = VoiceUi(phase)
                playThenDismiss(phase)
            }

            is VoiceState.Failed -> {
                _ui.value = VoiceUi(phase)
                dismissAfter(FAILURE_LINGER_MS)
            }

            else -> _ui.value = VoiceUi(phase)
        }
    }

    /**
     * Bounds however long the exchange may sit in [phase] before the bar gives up.
     *
     * Re-armed on every phase change rather than set once at the start. A single start-only timer
     * left three ways to strand the bar open over live TV, dismissable only with BACK: a run that
     * reaches `run-end` with no `intent-end` never publishes and stays Thinking; a socket that drops
     * mid-utterance silently swallows the audio so no VAD end ever arrives and it stays Listening;
     * and a system recogniser that goes quiet after onReadyForSpeech leaves it Listening too. None
     * of those is reachable from the Starting state the old timer watched.
     *
     * Identity, not equality, decides whether the phase is still current: Thinking carries the
     * transcript and Answering the reply so far, so each partial result is a new instance and
     * re-arms the timer — which is what keeps a long dictation, or a long answer, from timing out
     * mid-sentence.
     */
    private fun armWatchdog(phase: VoiceState) {
        watchdogJob?.cancel()
        val limit = when (phase) {
            is VoiceState.Starting -> START_TIMEOUT_MS
            is VoiceState.Listening -> LISTENING_TIMEOUT_MS
            is VoiceState.Thinking -> THINKING_TIMEOUT_MS
            // Same bound, re-armed per delta: a streamed answer that keeps arriving is not stalled,
            // however long it runs, and one that stops mid-sentence should not hold the bar open.
            is VoiceState.Answering -> THINKING_TIMEOUT_MS
            else -> null // Done and Failed have their own exits; Idle is not ours to time
        } ?: return
        watchdogJob = scope.launch {
            delay(limit)
            if (_ui.value?.phase !== phase) return@launch
            Log.w(TAG, "voice exchange stalled in $phase after ${limit}ms")
            session?.cancel()
            _ui.value = VoiceUi(VoiceState.Failed(stallMessage(phase)))
            dismissAfter(FAILURE_LINGER_MS)
        }
    }

    /**
     * Why the bar gave up, in terms of the route that actually stalled.
     *
     * The old single message named Home Assistant and Assist pipelines for every stall. On the
     * recogniser route HA is not involved in listening at all (the TV transcribes), so that sent a
     * BRAVIA owner to check speech-to-text settings that were never in the path.
     */
    private fun stallMessage(phase: VoiceState): String {
        val onDevice = settings?.let {
            voiceBackendFor(context, it.assistMicId) == VoiceBackend.DEVICE_RECOGNIZER
        } ?: false
        return when {
            // The stream started and then stopped: whatever is wrong is downstream of the agent
            // having understood the question, so none of the advice below applies.
            phase is VoiceState.Answering ->
                "Home Assistant stopped part-way through its answer."
            phase is VoiceState.Thinking && onDevice ->
                "The agent did not answer. Check that Home Assistant is reachable."
            phase is VoiceState.Thinking ->
                "Home Assistant did not answer. Check that the pipeline's conversation agent is working."
            onDevice ->
                "The TV's speech recogniser did not respond. Try again — if it keeps happening, " +
                    "check that the TV's voice service is enabled."
            else -> START_TIMEOUT_MESSAGE
        }
    }

    /**
     * Speaks the answer and schedules the bar's exit.
     *
     * Audio arrives one of two ways. A streamed run ends at `tts` and hands back a URL directly. The
     * recogniser route has none — it never runs a pipeline at all — so the reply text is sent to the
     * pipeline's voice to be synthesised, which is what gives a BRAVIA's remote a spoken answer
     * rather than only a written one.
     *
     * There is deliberately no fall back to the TV's own speech engine: the point of all this is
     * Home Assistant's voice, and a pipeline with none is called out in Settings before the press.
     */
    private fun playThenDismiss(done: VoiceState.Done) {
        // The agent asked something back — "which light did you mean?" — so the exchange ends by
        // opening the mic again rather than by leaving. Without this the bar reads the question out
        // and then vanishes on a timer, and answering means finding the mic key and starting over.
        // An errored reply is not a question however HA flags it, so it never re-opens the mic.
        val followUp = done.reply.continueConversation && !done.reply.isError
        val current = settings ?: run {
            endAfter(readingTime(done.reply.displayText), followUp)
            return
        }
        done.audioUrl?.let { repository.absoluteUrl(it) }?.let { play(it, current, followUp); return }

        // Nothing worth synthesising: an error belongs on screen, not read aloud in a stranger's voice.
        val engine = pipelineVoice(current)
        if (done.reply.isError || done.reply.speech.isBlank() || engine.isBlank()) {
            endAfter(readingTime(done.reply.displayText), followUp)
            return
        }
        // Hold the bar while the request is out — a reading timer could otherwise take the answer off
        // screen before its audio ever arrived.
        dismissAfter(SYNTHESIS_DEADLINE_MS)
        scope.launch {
            val url = repository.ttsUrl(engine, done.reply.speech, current.announceLanguage)
            // The exchange can be cancelled or replaced while the request is in flight; only speak
            // for the answer still on screen.
            if (_ui.value?.phase !== done) return@launch
            if (url != null) {
                play(url, current, followUp)
            } else {
                endAfter(readingTime(done.reply.displayText), followUp)
            }
        }
    }

    /**
     * Starts playback and ties the bar's exit to it. Dismissal follows the audio rather than a fixed
     * timer, so a long answer is never cut off mid sentence — but a player that never reports
     * completion must not pin the bar open forever, hence the outer deadline as well.
     */
    private fun play(url: String, current: Settings, followUp: Boolean) {
        // Armed first: a file that fails to open reports completion almost immediately, and setting
        // the outer deadline afterwards would overwrite that short exit with the full 90 seconds,
        // leaving a finished bar parked over whatever is playing.
        dismissAfter(SPEECH_DEADLINE_MS)
        spokenToken = runCatching {
            sound.play(
                url = url,
                volume = current.announceVolume,
                duckMode = current.announceDuckMode,
                // The outer deadline above stays a plain dismissal: a player that never reports
                // completion is a failure, and reopening the mic 90 seconds later would be worse.
                onFinished = { endAfter(SPOKEN_LINGER_MS, followUp) },
            )
        }.onFailure { Log.w(TAG, "playing the reply failed", it) }.getOrDefault(0L)
    }

    private fun show(phase: VoiceState) {
        teardown()
        _ui.value = VoiceUi(phase)
        dismissAfter(FAILURE_LINGER_MS)
    }

    private fun dismissAfter(ms: Long) = endAfter(ms, followUp = false)

    /**
     * Ends the exchange after [ms] — by taking the bar down, or by opening the mic again when the
     * agent is waiting on an answer. See [playThenDismiss] for when [followUp] is set.
     */
    private fun endAfter(ms: Long, followUp: Boolean) {
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(ms)
            // Both branches run teardown(), which cancels dismissJob — this coroutine. Dropping the
            // handle first means it cancels nothing instead of cancelling itself part-way through.
            dismissJob = null
            if (followUp) start() else dismiss()
        }
    }

    /** Takes the bar down, closes the mic and silences a reply still being read. */
    fun dismiss() {
        teardown()
        _ui.value = null
    }

    private fun teardown() {
        dismissJob?.cancel()
        dismissJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        watchJob?.cancel()
        watchJob = null
        session?.cancel()
        session = null
        _level.value = 0f
        if (spokenToken != 0L) {
            runCatching { sound.stop(spokenToken) }
            spokenToken = 0L
        }
    }


    private fun unavailableReason(current: Settings): String =
        when (voiceBackendFor(context, current.assistMicId)) {
            VoiceBackend.NEEDS_PERMISSION ->
                "Microphone access is off. Open TV Assist and allow the microphone under " +
                    "Permissions on the Home screen."
            else ->
                "This TV has no microphone available to apps and no speech recogniser. A USB " +
                    "microphone or a Home Assistant voice satellite works."
        }

    /** Roughly how long the answer needs to stay up to be read, when nothing is speaking it. */
    private fun readingTime(text: String): Long =
        (READING_BASE_MS + text.length * READING_PER_CHAR_MS).coerceIn(MIN_LINGER_MS, MAX_LINGER_MS)

    private companion object {
        const val TAG = "VoiceController"
        const val FAILURE_LINGER_MS = 5_000L
        // How long to wait for Home Assistant to open the audio channel before giving up.
        const val START_TIMEOUT_MS = 10_000L
        // A minute of unbroken speech is past any question asked from a sofa; reaching this means
        // the audio is going nowhere rather than that someone is still talking.
        const val LISTENING_TIMEOUT_MS = 60_000L
        // Longer than HaWebSocketClient.CONVERSATION_TIMEOUT_MS (45s), so a slow cloud agent fails
        // with its own error rather than being cut off by this.
        const val THINKING_TIMEOUT_MS = 60_000L
        const val START_TIMEOUT_MESSAGE =
            "Home Assistant did not start listening. Check that an Assist pipeline with " +
                "speech-to-text is set up, under Settings → Voice assistants."
        // A short beat after the voice stops, so the bar does not vanish on the last syllable.
        const val SPOKEN_LINGER_MS = 1_200L
        // Outer bound in case the engine never reports completion; longer than any sane reply.
        const val SPEECH_DEADLINE_MS = 90_000L
        // Long enough for a cloud voice to synthesise a paragraph, short enough that a hung request
        // does not strand the bar.
        const val SYNTHESIS_DEADLINE_MS = 25_000L
        // First retry is quick, because the likeliest cause is a socket that is up but not settled.
        // The backoff is what keeps an instance too old to know the command — which fails every
        // time, not just the first — down to one request every few minutes rather than one every 2 s.
        const val PIPELINE_RETRY_MS = 2_000L
        const val PIPELINE_RETRY_MAX_MS = 5 * 60_000L
        const val READING_BASE_MS = 1_500L
        const val READING_PER_CHAR_MS = 55L
        const val MIN_LINGER_MS = 3_000L
        const val MAX_LINGER_MS = 15_000L
    }
}
