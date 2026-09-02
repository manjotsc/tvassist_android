package com.tvassist.data.ha

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One answer from a Home Assistant `conversation` agent (the text half of the Assist pipeline).
 *
 * Produced by parsing the reply to a `conversation.process` service call made with
 * `return_response: true`. See [fromResultMessage] for the shape — it is nested more deeply than
 * it looks, which is the whole reason this lives in its own tested file.
 */
data class ConversationReply(
    /** What the agent said, ready to display and speak. Blank only when nothing came back at all. */
    val speech: String,
    /**
     * HA's id for this exchange. Passing it back on the next call keeps the thread's context, so
     * "turn it off" after "is the kitchen light on?" resolves to the same entity.
     */
    val conversationId: String?,
    /** HA's `response_type`: action_done / query_answer / error (blank if absent). */
    val responseType: String,
    /**
     * The agent expects an answer to what it just said — HA's own `continue_conversation`.
     *
     * An agent that asks "which light did you mean?" sets this, and the exchange is only half
     * finished when the reply lands. Both routes get it: it sits beside `conversation_id` in the
     * service response AND in a pipeline run's `intent_output`, which is the same shape.
     */
    val continueConversation: Boolean = false,
    /** Set when the call itself failed (socket down, timeout, HA rejected it). */
    val error: String? = null,
) {
    /** True for a transport failure or an agent that answered with `response_type: error`. */
    val isError: Boolean get() = error != null || responseType == "error"

    /** What to put on screen: the agent's own words when it produced any, else the failure. */
    val displayText: String
        get() = speech.ifBlank { error ?: "No response from the agent." }

    companion object {
        fun failure(reason: String) =
            ConversationReply(speech = "", conversationId = null, responseType = "error", error = reason)

        /**
         * Parses the full `result` message from the WebSocket.
         *
         * On success HA sends `{"id":N,"type":"result","success":true,"result":{…}}`, and with
         * `return_response` the payload is wrapped twice over — `result.response` is the *service*
         * response, and `conversation.process`'s service response has its own `response` key:
         *
         * ```
         * result.response.response.speech.plain.speech   ← the sentence
         * result.response.conversation_id                ← the thread id
         * ```
         *
         * The outer `response` wrapper is tolerated rather than required, so a caller that hands us
         * an already-unwrapped service response (the REST API's `service_response`) still parses.
         *
         * `null` means the socket dropped or the request timed out before any reply arrived.
         */
        fun fromResultMessage(msg: JsonObject?): ConversationReply {
            if (msg == null) return failure("No reply from Home Assistant — check the connection.")
            val success = msg["success"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true
            if (!success) {
                val err = (msg["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                return failure(err ?: "Home Assistant rejected the request.")
            }
            val result = msg["result"] as? JsonObject
                ?: return failure("Home Assistant returned no result.")
            // Unwrap the service-response envelope if present; older/REST shapes hand it over flat.
            return fromServiceResponse(result["response"] as? JsonObject ?: result)
        }

        /**
         * Parses a service-response-shaped object — `{"response": {…}, "conversation_id": "…"}`.
         *
         * The inner half of [fromResultMessage], kept separate because the same shape turns up one
         * envelope shallower elsewhere: the REST API's `service_response`, and an Assist pipeline
         * run's `intent_output`. Both voice routes go through `conversation.process` and so arrive
         * via [fromResultMessage], but the shape is HA's, not this call's.
         */
        fun fromServiceResponse(serviceResponse: JsonObject): ConversationReply {
            val intent = serviceResponse["response"] as? JsonObject
            val speech = intent
                ?.obj("speech")?.obj("plain")?.get("speech")?.jsonPrimitive?.contentOrNull
                .orEmpty()
                .trim()
            val conversationId = serviceResponse["conversation_id"]?.jsonPrimitive?.contentOrNull
            val responseType = intent?.get("response_type")?.jsonPrimitive?.contentOrNull.orEmpty()
            // Read the same way as `success` above: HA sends a JSON bool, whose content is "true".
            val continueConversation =
                serviceResponse["continue_conversation"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            if (speech.isBlank() && intent == null) {
                return failure("Home Assistant returned an unexpected response.")
            }
            return ConversationReply(
                speech = speech,
                conversationId = conversationId?.takeIf { it.isNotBlank() },
                responseType = responseType,
                continueConversation = continueConversation,
            )
        }

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    }
}
