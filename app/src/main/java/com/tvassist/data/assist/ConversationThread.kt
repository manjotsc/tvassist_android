package com.tvassist.data.assist

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One line of the conversation. [mine] is what was asked; otherwise the agent answering. */
data class ConversationTurn(val mine: Boolean, val text: String, val error: Boolean = false)

/**
 * The conversation the two halves of Assist share: which Home Assistant thread is live, whose it
 * is, how long since anyone touched it, and what has been said.
 *
 * Split out of [VoiceController] so it can be tested. Everything here is decided from three fields
 * and a clock — no microphone, no socket, no Android — and it is the part whose failures are silent:
 * a thread handed to the wrong agent produces a plausible answer to the wrong question, and a
 * transcript left behind from a finished conversation reads as though it were still going.
 *
 * Not thread-safe by design: every caller is on the main thread (the voice route from the
 * controller's own dispatcher, the typed card from composition).
 */
class ConversationThread(
    /** Monotonic milliseconds. Injected so tests can age a thread without waiting for it. */
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val _id = MutableStateFlow<String?>(null)

    /** HA's id for the live thread, or null when the next question starts a fresh one. */
    val id: StateFlow<String?> = _id.asStateFlow()

    /**
     * The conversation so far — spoken and typed turns in one list, for whoever wants to show it.
     *
     * The bar renders one exchange and leaves; the typed card used to keep its own list, which died
     * with the card. So the two halves shared a thread id and nothing else: you could ask three
     * things by voice and find an empty transcript when you opened the card, having just had the
     * conversation it was supposedly continuing.
     *
     * Bounded — this is a couch conversation, not a transcript of the day.
     */
    private val _turns = MutableStateFlow<List<ConversationTurn>>(emptyList())
    val turns: StateFlow<List<ConversationTurn>> = _turns.asStateFlow()

    /**
     * Which agent [id] belongs to.
     *
     * Home Assistant scopes a conversation id to the agent that issued it, and the two halves of
     * Assist do not necessarily address the same one: the typed card asks the entity whose card is
     * open, while the recogniser route asks the *pipeline's* conversation engine. Sharing the thread
     * across a change of agent hands one agent's id to another, which HA at best ignores. Tracking
     * the owner lets the thread be shared where it is genuinely the same conversation and dropped
     * where it is not.
     */
    private var agent: String = ""

    /** When the thread was last spoken to, for [IDLE_MS]. */
    private var touchedAt: Long = 0L

    /**
     * The thread to continue when asking [agentId], or null to start a new one.
     *
     * Null for three reasons, all of them "this is not that conversation": nothing has been asked
     * yet, the last thread belongs to a different agent, or it has gone cold. Home Assistant expires
     * its own conversation history on roughly this timescale, so a thread older than [IDLE_MS] is
     * one HA has very likely already forgotten — and "turn it off" resolved against yesterday's
     * context is a worse answer than asking afresh.
     */
    fun forAgent(agentId: String): String? {
        val current = _id.value ?: return null
        if (agent.isNotBlank() && agent != agentId) return null
        if (now() - touchedAt > IDLE_MS) return null
        return current
    }

    /**
     * Opens an exchange with [agentId], returning the thread to continue or null to start one.
     *
     * The gate [forAgent] puts on the thread applies to the transcript too: turns belong to a single
     * agent's conversation, and leaving one conversation's turns above another's answer renders two
     * as one. A thread that has changed hands or gone cold is dropped here rather than merely
     * ignored, so its stale id can never be sent later.
     */
    fun begin(agentId: String): String? {
        val thread = forAgent(agentId)
        if (thread == null && _id.value != null) reset()
        touchedAt = now()
        return thread
    }

    /**
     * Adopts the thread id an answer came back with, keeping the two halves on one conversation —
     * but only while both are talking to the same agent. A reply that omits an id leaves the thread
     * we had rather than dropping it.
     */
    fun adopt(agentId: String, threadId: String?) {
        if (threadId.isNullOrBlank()) return
        _id.value = threadId
        agent = agentId
        touchedAt = now()
    }

    /** Appends one turn. Blank text is dropped rather than rendered as an empty line. */
    fun record(mine: Boolean, text: String, error: Boolean = false) {
        if (text.isBlank()) return
        _turns.value = (_turns.value + ConversationTurn(mine, text, error)).takeLast(MAX_TURNS)
    }

    /**
     * Whether the history in [turns] is this agent's to show — the same test [forAgent] applies to
     * the id, and for the same reason.
     *
     * Read at render time as well as at ask time, because [begin] only fires when a question is
     * actually put: without this, opening a card on one agent would display the conversation held
     * with another until the first word was typed, and then watch it vanish. A transcript with no
     * thread behind it (an agent that returned no id) is still the live conversation, so it stays.
     */
    fun ownsHistory(agentId: String): Boolean = _id.value == null || forAgent(agentId) != null

    /** Forgets the thread and everything said in it. The card's "New thread" button. */
    fun reset() {
        _id.value = null
        agent = ""
        touchedAt = 0L
        _turns.value = emptyList()
    }

    private companion object {
        // Home Assistant's own conversation history expires on this timescale; matching it keeps the
        // app from sending an id the server has already dropped.
        const val IDLE_MS = 5 * 60_000L

        // Enough to scroll back through a couch conversation; not a transcript of the day.
        const val MAX_TURNS = 40
    }
}
