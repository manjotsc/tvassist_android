package com.tvassist.data.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading `/api/tts_get_url`. The recogniser route depends entirely on this: with no pipeline run to
 * hand it audio, a mis-read response is the difference between a spoken answer and a silent one.
 */
class TtsUrlTest {

    @Test
    fun `prefers the relative path over the absolute url`() {
        // Not a stylistic choice. HA builds `url` from its own configured external/internal address,
        // which under split-horizon DNS need not be the one this TV is connected on; `path` always
        // resolves against the base URL that is already known to work.
        val parsed = parseTtsUrl(
            """{"url":"http://homeassistant.local:8123/api/tts_proxy/a.mp3",
                "path":"/api/tts_proxy/a.mp3"}""",
        )
        assertEquals("/api/tts_proxy/a.mp3", parsed)
    }

    @Test
    fun `falls back to the absolute url when no path is given`() {
        val parsed = parseTtsUrl("""{"url":"https://ha.example/api/tts_proxy/b.mp3"}""")
        assertEquals("https://ha.example/api/tts_proxy/b.mp3", parsed)
    }

    @Test
    fun `a blank path does not shadow a usable url`() {
        val parsed = parseTtsUrl("""{"path":"","url":"https://ha.example/c.mp3"}""")
        assertEquals("https://ha.example/c.mp3", parsed)
    }

    @Test
    fun `a response carrying neither field yields null`() {
        assertNull(parseTtsUrl("""{"detail":"engine not found"}"""))
    }

    @Test
    fun `malformed or empty bodies yield null rather than throwing`() {
        assertNull(parseTtsUrl(null))
        assertNull(parseTtsUrl(""))
        assertNull(parseTtsUrl("not json at all"))
        assertNull(parseTtsUrl("[1,2,3]"))
    }
}
