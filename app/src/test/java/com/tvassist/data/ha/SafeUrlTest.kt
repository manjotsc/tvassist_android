package com.tvassist.data.ha

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Camera credentials must not reach a log line in the first place.
 *
 * The stakes are why this is tested at all: `rtsp://admin:hunter2@cam/stream2` is the shape camera
 * UIs hand people, both players print the URL they failed on, and a log gets screenshotted and sent
 * to strangers.
 */
class SafeUrlTest {

    @Test fun `camera credentials in a stream URL are removed`() {
        assertEquals(
            "rtsp://«redacted»@cam.local/stream2",
            safeUrlForLog("rtsp://admin:hunter2@cam.local/stream2"),
        )
    }

    @Test fun `a URL without credentials is left alone`() {
        // The character class stops at `/`, so an @ in a path is not an authority.
        assertEquals("rtsp://192.168.13.67:554/stream2", safeUrlForLog("rtsp://192.168.13.67:554/stream2"))
        assertEquals("http://ha.local/local/a@b.jpg", safeUrlForLog("http://ha.local/local/a@b.jpg"))
    }
}
