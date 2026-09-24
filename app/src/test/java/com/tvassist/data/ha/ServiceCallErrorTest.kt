package com.tvassist.data.ha

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading a `call_service` result for a checked call. The alarm keypad and the update card both
 * decide what to show from this, so the one mistake it must never make is calling a refusal a
 * success — the keypad would close as though the panel had disarmed.
 */
class ServiceCallErrorTest {

    @Test fun `success is null`() {
        assertNull(serviceCallError(buildJsonObject { put("id", 7); put("type", "result"); put("success", true) }))
    }

    @Test fun `a refusal carries HA's own words`() {
        // What the demo's manual panel answers a wrong code with.
        val msg = buildJsonObject {
            put("id", 7); put("type", "result"); put("success", false)
            putJsonObject("error") {
                put("code", "service_validation_error")
                put("message", "Invalid alarm code provided")
            }
        }
        assertEquals("Invalid alarm code provided", serviceCallError(msg))
    }

    @Test fun `a refusal without a message still reads as one`() {
        assertEquals(
            "Home Assistant refused the command",
            serviceCallError(buildJsonObject { put("success", false) }),
        )
        assertEquals(
            "Home Assistant refused the command",
            serviceCallError(buildJsonObject { put("success", false); putJsonObject("error") { put("message", " ") } }),
        )
    }

    @Test fun `a result missing success is not taken as one`() {
        assertEquals("Home Assistant refused the command", serviceCallError(buildJsonObject { put("id", 7) }))
    }

    @Test fun `a dropped socket reads as not connected`() {
        // failPendingResults wakes every waiter with null when the socket goes.
        assertEquals(NOT_CONNECTED_MESSAGE, serviceCallError(null))
    }
}
