package com.tvassist.data.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for base-URL → WebSocket-endpoint normalisation.
 *
 * Worth pinning because a scheme-less URL produces a *half-working* app rather than a clean failure:
 * the WebSocket connects (this function supplies `ws://`), but every REST call builds
 * `"$base/api/…"`, which isn't a valid absolute URL, so snapshots and entity pictures silently
 * return null. That combination is genuinely confusing to debug from the symptoms.
 */
class HaWebSocketUrlTest {

    private fun url(s: String) = HaWebSocketClient.toWebSocketUrl(s)

    @Test fun `http becomes ws`() {
        assertEquals("ws://10.0.2.2:8123/api/websocket", url("http://10.0.2.2:8123"))
    }

    @Test fun `https becomes wss`() {
        assertEquals("wss://ha.example.com:8123/api/websocket", url("https://ha.example.com:8123"))
    }

    @Test fun `an explicit ws url is left alone`() {
        assertEquals("ws://host:8123/api/websocket", url("ws://host:8123"))
    }

    @Test fun `an explicit wss url is left alone`() {
        assertEquals("wss://host:8123/api/websocket", url("wss://host:8123"))
    }

    @Test fun `a scheme-less url falls back to ws`() {
        // Documents the behaviour that makes the WebSocket work while REST quietly fails.
        assertEquals("ws://localhost:8123/api/websocket", url("localhost:8123"))
    }

    @Test fun `a trailing slash is trimmed so the path isn't doubled`() {
        assertEquals("ws://host:8123/api/websocket", url("http://host:8123/"))
    }

    @Test fun `surrounding whitespace is trimmed`() {
        assertEquals("ws://host:8123/api/websocket", url("  http://host:8123  "))
    }

    @Test fun `blank input yields null rather than a bogus endpoint`() {
        assertNull(url(""))
        assertNull(url("   "))
    }
}
