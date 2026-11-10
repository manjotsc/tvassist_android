package com.tvassist.data.settings

import com.tvassist.data.ha.Entity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-camera "RTSP over TCP" switch (issue #3). TCP must be what a camera gets unless someone
 * turned it off: go2rtc/Frigate refuse UDP, and every camera already played over TCP in VLC before
 * the setting existed — so a camera saved by an older version must come back as TCP, not UDP.
 */
class LocalCameraRtspTcpTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `a camera saved before the setting existed decodes as TCP`() {
        val old = """{"id":"cam_1","name":"Door","streamUrl":"rtsp://192.0.2.10:8554/door","player":"exoplayer"}"""
        assertTrue(json.decodeFromString(LocalCamera.serializer(), old).rtspTcp)
    }

    @Test fun `turning it off survives a round-trip`() {
        val cam = LocalCamera(id = "cam_1", name = "Door", streamUrl = "rtsp://x/y", rtspTcp = false)
        val back = json.decodeFromString(LocalCamera.serializer(), json.encodeToString(LocalCamera.serializer(), cam))
        assertEquals(cam, back)
    }

    private fun camera(vararg attrs: Pair<String, JsonPrimitive>) = Entity(
        entityId = "camera.ta_cam_1",
        state = "streaming",
        friendlyName = "Door",
        attributes = buildJsonObject {
            put("ta_stream_url", JsonPrimitive("rtsp://192.0.2.10:8554/door"))
            attrs.forEach { (k, v) -> put(k, v) }
        },
    )

    @Test fun `an entity without the attribute plays over TCP`() {
        assertTrue(camera().localRtspTcp)
    }

    @Test fun `an entity carrying false plays over UDP`() {
        assertFalse(camera("ta_rtsp_tcp" to JsonPrimitive(false)).localRtspTcp)
    }
}
