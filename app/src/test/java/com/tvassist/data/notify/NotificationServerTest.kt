package com.tvassist.data.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the wire-field → object mapping and speak-mode logic. */
class NotificationServerTest {

    private fun server(
        speakMode: String = "both",
        onSpeak: (List<String>, Map<String, String>) -> Unit = { _, _ -> },
    ) = NotificationServer(
        port = 0,
        store = NotificationStore(),
        defaultDuration = { 8 },
        speakMode = { speakMode },
        speak = onSpeak,
    )

    // ---- field parsing / aliases ----

    @Test fun `border_color alias maps to borderColor`() {
        val n = server().fieldsToNotification(mapOf("message" to "hi", "border_color" to "#F00"))
        assertNotNull(n)
        assertEquals("#F00", n!!.borderColor)
    }

    @Test fun `legacy color still populates borderColor`() {
        val n = server().fieldsToNotification(mapOf("message" to "hi", "color" to "#0F0"))
        assertEquals("#0F0", n!!.borderColor)
    }

    @Test fun `camelCase aliases are accepted`() {
        val n = server().fieldsToNotification(
            mapOf("message" to "x", "cameraStream" to "camera.front", "iconUrl" to "http://i/x.png"),
        )!!
        assertEquals("camera.front", n.cameraStream)
        assertEquals("http://i/x.png", n.icon)
    }

    @Test fun `source2 accepts subsource and snake aliases`() {
        assertEquals("RestAPI", server().fieldsToNotification(mapOf("message" to "x", "subsource" to "RestAPI"))!!.source2)
        assertEquals("B", server().fieldsToNotification(mapOf("message" to "x", "source_2" to "B"))!!.source2)
    }

    @Test fun `media_type is parsed`() {
        val n = server().fieldsToNotification(mapOf("media_url" to "http://x/y", "media_type" to "video"))!!
        assertEquals("video", n.mediaType)
    }

    @Test fun `enlarge_timeout parses and defaults to -1`() {
        assertEquals(45, server().fieldsToNotification(mapOf("message" to "x", "enlarge_timeout" to "45"))!!.enlargeTimeout)
        assertEquals(-1, server().fieldsToNotification(mapOf("message" to "x"))!!.enlargeTimeout)
    }

    @Test fun `duration falls back to the default provider`() {
        assertEquals(8, server().fieldsToNotification(mapOf("message" to "x"))!!.durationSec)
        assertEquals(3, server().fieldsToNotification(mapOf("message" to "x", "duration" to "3"))!!.durationSec)
    }

    @Test fun `empty fields yield a null notification`() {
        assertNull(server().fieldsToNotification(emptyMap()))
    }

    // ---- id, and Home Assistant's nested `data:` block ----

    @Test fun `fields nested under HA's data block are flattened`() {
        // How HA's own notify platform shapes a call: message/title at the top, everything else
        // inside data. Nested keys used to be dropped whole, so the notification showed but with a
        // generated id and the default duration.
        val f = server().jsonToFields(
            """{"message":"Someone at the door","data":{"id":"doorbell","duration":20}}""",
        )
        assertEquals("Someone at the door", f["message"])
        assertEquals("doorbell", f["id"])
        assertEquals("20", f["duration"])
    }

    @Test fun `a top-level field wins over a nested one of the same name`() {
        val f = server().jsonToFields("""{"id":"outer","data":{"id":"inner"}}""")
        assertEquals("outer", f["id"])
    }

    @Test fun `a supplied id is used verbatim and a generated one is marked`() {
        assertEquals("doorbell", server().fieldsToNotification(mapOf("message" to "x", "id" to "doorbell"))!!.id)
        assertTrue(server().fieldsToNotification(mapOf("message" to "x"))!!.id.startsWith("auto-"))
    }

    @Test fun `a bare icon is enough content`() {
        assertNotNull(server().fieldsToNotification(mapOf("icon" to "mdi:bell")))
    }

    @Test fun `pill parses border_color and entity aliases`() {
        val p = server().fieldsToFixed("p1", mapOf("message" to "x", "border_color" to "#00F", "entity_id" to "sensor.a"))
        assertEquals("#00F", p.borderColor)
        assertEquals("sensor.a", p.entity)
        assertEquals("p1", p.id)
    }

    // ---- speak_mode → utterance sequence ----

    private fun spoken(mode: String, perCall: Map<String, String> = emptyMap()): List<String> {
        var captured: List<String> = emptyList()
        val s = server(speakMode = mode, onSpeak = { utterances, _ -> captured = utterances })
        s.speakNotification(TvNotification(id = "1", message = "Msg", title = "Ttl"), perCall)
        return captured
    }

    @Test fun `both mode joins title and message`() = assertEquals(listOf("Ttl. Msg"), spoken("both"))
    @Test fun `separate mode yields two utterances`() = assertEquals(listOf("Ttl", "Msg"), spoken("separate"))
    @Test fun `message mode speaks only the message`() = assertEquals(listOf("Msg"), spoken("message"))
    @Test fun `title mode speaks only the title`() = assertEquals(listOf("Ttl"), spoken("title"))

    @Test fun `per-call speak_mode overrides the default`() =
        assertEquals(listOf("Ttl"), spoken("both", mapOf("speak_mode" to "title")))

    @Test fun `separate mode drops a blank segment`() {
        var captured: List<String> = emptyList()
        val s = server(speakMode = "separate", onSpeak = { u, _ -> captured = u })
        s.speakNotification(TvNotification(id = "1", message = "Msg", title = ""), emptyMap())
        assertEquals(listOf("Msg"), captured)
    }
}
