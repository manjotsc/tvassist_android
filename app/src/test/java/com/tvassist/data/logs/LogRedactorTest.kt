package com.tvassist.data.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What may leave the TV in a log. The credential half came over from the parked log viewer
 * (`logproject/`); the host half is new, because logs now go to public issue threads.
 */
class LogRedactorTest {

    private val basic = LogRedactor.BASIC

    // --- credentials (ported) ----------------------------------------------------------------------

    @Test fun cameraCredentialsAreRemoved() {
        assertEquals(
            "libvlc stream: cannot connect to rtsp://«redacted»@192.168.13.67:554/stream2",
            basic.redact("libvlc stream: cannot connect to rtsp://admin:hunter2@192.168.13.67:554/stream2"),
        )
    }

    @Test fun bearerTokensAreRemoved() {
        val out = basic.redact("GET /api/states Authorization: Bearer eyJhbGciOi.J9abc-_123")
        assertFalse(out, "eyJhbGciOi" in out)
        assertTrue(out, "Bearer «redacted»" in out)
    }

    @Test fun credentialQueryParametersAreRemoved() {
        val out = basic.redact("connecting ws://ha.local/api/websocket?access_token=abc123XYZ&x=1")
        assertFalse(out, "abc123XYZ" in out)
        assertTrue(out, "access_token=«redacted»" in out)
        // A Google Maps key travels as `key=`.
        assertFalse(basic.redact("GET https://tile.googleapis.com/v1/2dtiles/1/2/3?session=s&key=AIzaSyA1").contains("AIzaSyA1"))
    }

    @Test fun signedTtsProxyPathsAreRemoved() {
        val out = basic.redact("playing http://ha.local:8123/api/tts_proxy/9f8e7d6c5b4a.mp3")
        assertTrue(out, "/api/tts_proxy/«redacted»" in out)
    }

    @Test fun jsonCredentialFieldsAreRemoved() {
        val out = basic.redact("""sending {"type":"auth","access_token":"supersecretvalue"}""")
        assertFalse(out, "supersecretvalue" in out)
    }

    @Test fun literalSecretsAreRemovedWhereverTheyAppear() {
        val token = "abcdefghijklmnopqrstuvwxyz0123456789"
        assertEquals("stored «redacted» for later", LogRedactor(listOf(token)).redact("stored $token for later"))
    }

    @Test fun shortSecretsAreNotRedactedAsLiterals() {
        // A 4-digit PIN would otherwise eat every timestamp and frame count in the file.
        val line = "11-16 07:19:12.631 I/X( 1): stop(1234): 1234 frames"
        assertEquals(line, LogRedactor(listOf("1234")).redact(line))
    }

    @Test fun ordinaryLinesAreLeftAlone() {
        val line = "11-16 07:19:12.631  4242  4242 I HaCamera: stream stats (vlc): 92 s, shown 2228, dropped 1"
        assertEquals(line, basic.redact(line))
    }

    // --- configured hosts --------------------------------------------------------------------------

    private val hosts = hostPlaceholders(
        haUrl = "https://tv2.manjot.net:8123",
        cameraUrls = listOf(
            listOf("rtsp://admin:pw@192.168.40.50:554/main", "http://192.168.40.50/snap.jpg", "rtsp://192.168.40.50/sub"),
            listOf("rtsp://doorbell.lan:8554/live", "", ""),
        ),
    )
    private val redactor = LogRedactor(hosts = hosts)

    @Test fun eachConfiguredHostGetsAStablePlaceholder() {
        assertEquals(
            mapOf(
                "tv2.manjot.net" to "<ha-host>",
                "192.168.40.50" to "<camera-1-host>",
                "doorbell.lan" to "<camera-2-host>",
            ),
            hosts,
        )
    }

    @Test fun hostsAreReplacedInUrlsAndProse() {
        assertEquals(
            "W HaCamera: stream failed (vlc): rtsp://«redacted»@<camera-1-host>:554/main — timed out",
            redactor.redact("W HaCamera: stream failed (vlc): rtsp://admin:pw@192.168.40.50:554/main — timed out"),
        )
        assertEquals(
            "connect -> wss://<ha-host>:8123/api/websocket",
            redactor.redact("connect -> wss://TV2.manjot.net:8123/api/websocket"),
        )
        assertEquals("cannot reach <camera-2-host>.", redactor.redact("cannot reach doorbell.lan."))
    }

    @Test fun aHostInsideALongerNameIsNotTouched() {
        // Neither a subdomain of the configured host nor a name that merely ends the same way.
        val line = "resolved cdn.tv2.manjot.net and mytv2.manjot.net and doorbell.lan.example"
        assertEquals(line, redactor.redact(line))
    }

    @Test fun veryShortHostsAreNotReplacedAsWords() {
        val r = LogRedactor(hosts = hostPlaceholders("http://ha:8123", emptyList()))
        val line = "ha said ha: connected to ha"
        assertEquals(line, r.redact(line))
    }

    @Test fun aCameraOnHomeAssistantsMachineReadsAsHa() {
        val h = hostPlaceholders("http://ha.lan:8123", listOf(listOf("rtsp://ha.lan:8554/front")))
        assertEquals(mapOf("ha.lan" to "<ha-host>"), h)
    }

    @Test fun hostOfToleratesWhatCamerasAccept() {
        assertEquals("ha.lan", hostOf("ha.lan:8123"))
        assertEquals("192.168.1.5", hostOf("rtsp://user:p#ss word@192.168.1.5:554/x"))
        assertEquals(null, hostOf(""))
    }
}
