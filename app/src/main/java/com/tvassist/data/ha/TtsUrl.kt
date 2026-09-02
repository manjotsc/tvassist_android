package com.tvassist.data.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pulls the playable address out of a `/api/tts_get_url` response.
 *
 * Home Assistant answers with both an absolute `url` and a relative `path`, and **the relative one is
 * preferred**. The absolute one is built from whatever external or internal URL the instance is
 * configured with, which is not necessarily an address this TV can reach — the user here runs
 * split-horizon DNS, so HA's idea of its own address and the one the overlay is connected on can
 * differ. The path always resolves against the base URL we already know works.
 *
 * Returns null for a blank body, malformed JSON, or a response carrying neither field.
 */
fun parseTtsUrl(json: String?): String? {
    if (json.isNullOrBlank()) return null
    val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
    obj["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
    return obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}
