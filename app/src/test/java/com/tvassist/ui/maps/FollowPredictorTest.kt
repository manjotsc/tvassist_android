package com.tvassist.ui.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FollowPredictor] is what keeps the map's avatar level with the person instead of one reporting
 * interval behind. These pin the three things that make it safe: it predicts under steady movement,
 * it stops predicting when the person stops, and an overshoot is bounded.
 */
class FollowPredictorTest {

    // ~111 m of latitude per 0.001°.
    private val step = 0.001

    @Test
    fun firstFixSnaps() {
        val p = FollowPredictor().onFix(45.0, -75.0, 1_000, null)
        assertEquals(FollowPlan(45.0, -75.0, 0), p)
    }

    @Test
    fun steadyMovementAimsOneStepAhead() {
        val f = FollowPredictor()
        f.onFix(45.0, -75.0, 10_000, null)
        val p = f.onFix(45.0 + step, -75.0, 20_000, 11.0)
        // Moving 0.001° per 10 s → by the next fix (10 s on) the person should be at +0.002°.
        assertEquals(45.0 + 2 * step, p.lat, 1e-9)
        assertEquals(-75.0, p.lng, 1e-9)
        assertEquals(10_000, p.durMs)
    }

    @Test
    fun reportedStopAimsAtTheRealFix() {
        val f = FollowPredictor()
        f.onFix(45.0, -75.0, 10_000, null)
        val p = f.onFix(45.0 + step, -75.0, 20_000, 0.0)
        assertEquals(45.0 + step, p.lat, 1e-9)
    }

    @Test
    fun jitterAfterMovingPullsBackToTheLastRealFix() {
        val f = FollowPredictor()
        f.onFix(45.0, -75.0, 10_000, null)
        f.onFix(45.0 + step, -75.0, 20_000, null) // predicts on to +2 steps
        val p = f.onFix(45.0 + step + 0.00001, -75.0, 30_000, null) // ~1 m: standing still
        assertEquals(45.0 + step, p.lat, 1e-9)
        assertEquals(FOLLOW_GLIDE_MIN_MS, p.durMs)
    }

    @Test
    fun predictionIsCapped() {
        val f = FollowPredictor()
        f.onFix(45.0, -75.0, 10_000, null)
        val p = f.onFix(45.01, -75.0, 20_000, null) // ~1.1 km in 10 s
        val aheadM = distanceKm(45.01, -75.0, p.lat, p.lng) * 1000.0
        assertEquals(FOLLOW_PREDICT_MAX_M, aheadM, 1.0)
    }

    @Test
    fun brakingToAStopPredictsTheStoppingPointNotPastIt() {
        val f = FollowPredictor()
        f.onFix(45.0, -75.0, 0, null)
        f.onFix(45.0 + step, -75.0, 10_000, null)        // ~11.1 m/s
        val p = f.onFix(45.0 + 1.5 * step, -75.0, 20_000, null) // ~5.6 m/s: halved
        // Same deceleration reaches zero exactly 10 s on, covering half of the last step — not
        // the whole step that constant velocity would have run on past the stop.
        assertEquals(45.0 + 1.75 * step, p.lat, 1e-6)
    }

    @Test
    fun hardBrakingStopsShortOfTheInterval() {
        val f = FollowPredictor()
        f.onFix(45.0, -75.0, 0, null)
        f.onFix(45.0 + step, -75.0, 10_000, null)          // ~11.1 m/s
        val p = f.onFix(45.0 + 1.1 * step, -75.0, 20_000, null) // ~1.1 m/s
        // v²/2a: 1.11² / (2 · 1.0) ≈ 0.62 m — the marker barely moves past the fix.
        val aheadM = distanceKm(45.0 + 1.1 * step, -75.0, p.lat, p.lng) * 1000.0
        assertTrue("ran on $aheadM m", aheadM < 1.0)
    }

    @Test
    fun speedingUpIsNotExtrapolated() {
        val f = FollowPredictor()
        f.onFix(45.0, -75.0, 0, null)
        f.onFix(45.0 + step, -75.0, 10_000, null)
        val p = f.onFix(45.0 + 3 * step, -75.0, 20_000, null) // doubled
        assertEquals(45.0 + 5 * step, p.lat, 1e-6) // one step at the new speed, no more
    }

    @Test
    fun burstOfFixesIsNotStretched() {
        val f = FollowPredictor()
        f.onFix(45.0, -75.0, 10_000, null)
        // 200 ms apart: the glide floors at 1 s, but the prediction stays one step, not five.
        val p = f.onFix(45.0 + step, -75.0, 10_200, null)
        assertEquals(45.0 + 2 * step, p.lat, 1e-9)
        assertTrue(p.durMs >= FOLLOW_GLIDE_MIN_MS)
    }
}
