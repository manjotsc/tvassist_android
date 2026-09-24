package com.tvassist.ui.cards

/**
 * Decides which OK presses a freshly opened control card must ignore.
 *
 * **The bug.** Opening a card is one physical press *held down*. `onLongClick` fires part-way
 * through it, the card replaces the tile, focus lands on the card's first control — and the rest of
 * that same hold then lands on the new control. Captured on the emulator: one hold on a lock tile
 * opened the card and pressed its Lock button **thirteen times** in 280ms; a cover's Open fired
 * five times. On a real front door that is thirteen commands nobody asked for.
 *
 * **Why it cannot be solved structurally.** The first attempt was an exact rule — "an OK-up with no
 * preceding OK-down cannot be ours" — and it fails, because the held key arrives as discrete
 * down/up pairs each carrying `repeatCount == 0`. Every repeat is byte-for-byte indistinguishable
 * from a deliberate fresh press. Nothing about a single event can classify it; only timing can.
 *
 * **Two tests, because one is not enough.** The second attempt used a single quiet window of 400ms
 * and still let all fourteen through: the *first* repeat arrived 433ms after the card opened, since
 * it waits out the host's initial key-repeat delay — a user-configurable setting on both Windows
 * and Android, so no tight number is safe. [CARD_SETTLE_MS] covers that opening gap;
 * [OK_REPEAT_GAP_MS] catches every repeat after it, however long the key is held. A press must fail
 * both to get through.
 *
 * Deliberately a plain object rather than Compose state: this decides key handling, not what is
 * drawn, and holding it in `mutableStateOf` would recompose the card on every key event.
 */
internal class OkBurstFilter(private val openedAt: Long) {

    /** Falls on the first press that gets through; after that this card never filters again. */
    private var armed = true
    private var lastOk = 0L
    private var passedDown = false

    /** True to swallow this key-down. */
    fun consumeDown(now: Long): Boolean {
        val inGrace = now - openedAt < CARD_SETTLE_MS
        val machineCadence = lastOk != 0L && now - lastOk < OK_REPEAT_GAP_MS
        val storming = armed && (inGrace || machineCadence)
        lastOk = now
        passedDown = !storming
        if (!storming) armed = false
        return storming
    }

    /**
     * True to swallow this key-up.
     *
     * Judged purely by whether its own key-down was allowed, never re-tested against the clock: a
     * legitimate press would otherwise be split in half, since androidx.tv's `Surface` fires its
     * `onClick` on the UP and the DOWN that preceded it is always only milliseconds old.
     */
    fun consumeUp(now: Long): Boolean {
        lastOk = now
        return !passedDown
    }

    companion object {
        /**
         * How long a freshly opened card refuses OK outright. Has to clear the host's *initial*
         * key-repeat delay — measured at 433ms on the emulator, and configurable by the user. A
         * second of deadness on a card you just opened is not noticeable; fourteen commands are.
         */
        const val CARD_SETTLE_MS = 1200L

        /**
         * Below this gap, consecutive OK events are a machine repeating rather than a person
         * pressing twice. Observed repeats land 5-25ms apart; a deliberate double press is never
         * under ~150ms.
         */
        const val OK_REPEAT_GAP_MS = 150L
    }
}
