package com.tvassist.data.assist

import com.tvassist.data.ha.ConversationReply
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reading the back half of an `assist_pipeline/run` — the part after the words are known.
 *
 * Shared because both voice routes now run a pipeline and see exactly these events: the streamed
 * route from `stt`, and the recogniser route from `intent` with the TV's transcript as input. The
 * field names are Home Assistant's, they are nested more deeply than they look, and having two
 * copies of them was how a reworded key would have broken one route silently.
 */

/**
 * The next fragment of a streamed answer, or null when the delta carries no text.
 *
 * Home Assistant opens the stream with a delta naming the role and no content, so "null" here is
 * routine rather than a failure.
 */
fun intentProgressDelta(data: JsonObject?): String? =
    (data?.get("chat_log_delta") as? JsonObject)
        ?.get("content")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotEmpty() }

/**
 * The agent's answer from an `intent-end` event.
 *
 * `intent_output` is the same shape a `conversation.process` service response has one envelope
 * further in, which is why [ConversationReply.fromServiceResponse] takes it directly.
 */
fun intentOutputReply(data: JsonObject?): ConversationReply {
    val output = data?.get("intent_output") as? JsonObject
        ?: return ConversationReply.failure("Home Assistant returned no answer.")
    return ConversationReply.fromServiceResponse(output)
}

/** The path to the synthesised reply from a `tts-end` event, or null if the run produced none. */
fun ttsOutputUrl(data: JsonObject?): String? =
    (data?.get("tts_output") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull

/** Why the run failed, preferring HA's own sentence over its error code. */
fun pipelineErrorMessage(data: JsonObject?): String =
    data?.get("message")?.jsonPrimitive?.contentOrNull
        ?: data?.get("code")?.jsonPrimitive?.contentOrNull
        ?: "The Assist pipeline failed."
