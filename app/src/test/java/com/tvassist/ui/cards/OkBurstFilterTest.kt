package com.tvassist.ui.cards

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the real capture that found this bug.
 *
 * Holding OK on a lock tile opened its card and then pressed the card's Lock button thirteen times
 * in 280ms, because the rest of the hold landed on the newly focused control. Two earlier fixes
 * failed — one testing the wrong property, one with a window 33ms too short — so the timings below
 * are the measured ones, not invented.
 */
class OkBurstFilterTest {

    /** The captured burst: the first repeat 433ms after the card opened, then every 5-25ms. */
    private val burstAfterOpen = listOf(433L, 442, 451, 456, 462, 474, 493, 501, 525, 532, 552, 573, 588, 606)

    @Test fun `the whole captured burst is swallowed`() {
        val f = OkBurstFilter(openedAt = 0L)
        burstAfterOpen.forEach { t ->
            assertTrue("down at ${t}ms should be swallowed", f.consumeDown(t))
            assertTrue("up at ${t + 2}ms should be swallowed", f.consumeUp(t + 2))
        }
    }

    @Test fun `the first repeat alone would defeat a 400ms window`() {
        // Why the second attempt failed: 433 > 400, so a lone grace window let it through and the
        // pass-through then disarmed the guard for the other thirteen.
        assertTrue(burstAfterOpen.first() > 400L)
        assertTrue(burstAfterOpen.first() < OkBurstFilter.CARD_SETTLE_MS)
    }

    @Test fun `a hold long past the grace window stays swallowed`() {
        // The cadence test, not the clock, is what carries this: repeats every 20ms for four
        // seconds run far beyond CARD_SETTLE_MS and must still be ignored.
        val f = OkBurstFilter(openedAt = 0L)
        var t = 500L
        while (t < 4_000L) {
            assertTrue("repeat at ${t}ms", f.consumeDown(t))
            t += 20
        }
    }

    @Test fun `a deliberate press after the burst gets through`() {
        val f = OkBurstFilter(openedAt = 0L)
        burstAfterOpen.forEach { f.consumeDown(it); f.consumeUp(it + 2) }
        // Key released, user reaches for a control.
        assertFalse(f.consumeDown(2_000L))
        assertFalse("its up must not be re-tested", f.consumeUp(2_010L))
    }

    @Test fun `once a press is through the filter never interferes again`() {
        val f = OkBurstFilter(openedAt = 0L)
        assertFalse(f.consumeDown(2_000L))
        assertFalse(f.consumeUp(2_010L))
        // A quick second press, well inside OK_REPEAT_GAP_MS, must still work.
        assertFalse("rapid second press", f.consumeDown(2_060L))
        assertFalse(f.consumeUp(2_070L))
    }

    @Test fun `a press inside the grace window is refused`() {
        val f = OkBurstFilter(openedAt = 0L)
        assertTrue(f.consumeDown(OkBurstFilter.CARD_SETTLE_MS - 1))
    }

    @Test fun `a press just past the grace window is allowed`() {
        val f = OkBurstFilter(openedAt = 0L)
        assertFalse(f.consumeDown(OkBurstFilter.CARD_SETTLE_MS + 1))
    }
}
