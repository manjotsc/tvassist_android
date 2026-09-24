package com.tvassist.ui.cards

import com.tvassist.ui.cards.camera.CLIP_REFRESH_MS
import com.tvassist.ui.cards.camera.CLIP_RETRY_MAX_MS
import com.tvassist.ui.cards.camera.clipRetryDelayMs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rolling-clip pacing. Re-requesting on every ~5 s loop, and retrying a refused request every
 * second, is what kept a public camera site's Cloudflare returning 403 to the whole household.
 */
class ClipRetryTest {

    @Test fun aWorkingSourceIsAskedOncePerPublishCycle() {
        assertEquals(30_000L, CLIP_REFRESH_MS)
        assertEquals(CLIP_REFRESH_MS, clipRetryDelayMs(0))
    }

    @Test fun failuresDoubleUpToFiveMinutes() {
        assertEquals(
            listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L),
            (1..6).map { clipRetryDelayMs(it) },
        )
    }

    @Test fun aLongRunOfFailuresNeverOverflowsPastTheCap() {
        assertEquals(CLIP_RETRY_MAX_MS, clipRetryDelayMs(1_000))
    }
}
