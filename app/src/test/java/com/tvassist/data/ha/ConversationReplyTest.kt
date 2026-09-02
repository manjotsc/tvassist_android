package com.tvassist.data.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `conversation.process` reply is wrapped twice (call_service response envelope, then the
 * service's own `response` key), which is exactly the kind of shape that silently parses to an
 * empty string if a level is miscounted. These pin the real payloads down.
 */
class ConversationReplyTest {

    private fun msg(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `parses the doubly nested speech and thread id`() {
        val reply = ConversationReply.fromResultMessage(
            msg(
                """
                {"id":7,"type":"result","success":true,"result":{
                  "context":{"id":"01H"},
                  "response":{
                    "response":{
                      "speech":{"plain":{"speech":"Turned on the kitchen light","extra_data":null}},
                      "card":{},
                      "language":"en",
                      "response_type":"action_done",
                      "data":{"targets":[]}
                    },
                    "conversation_id":"01JABC"
                  }
                }}
                """.trimIndent(),
            ),
        )
        assertEquals("Turned on the kitchen light", reply.speech)
        assertEquals("01JABC", reply.conversationId)
        assertEquals("action_done", reply.responseType)
        assertFalse(reply.isError)
    }

    @Test
    fun `accepts an already unwrapped service response`() {
        // The REST API hands back the service response without the call_service envelope.
        val reply = ConversationReply.fromResultMessage(
            msg(
                """
                {"id":9,"type":"result","success":true,"result":{
                  "response":{
                    "response":{"speech":{"plain":{"speech":"It is 21 degrees"}},"response_type":"query_answer"},
                    "conversation_id":"abc"
                  }
                }}
                """.trimIndent(),
            ),
        )
        assertEquals("It is 21 degrees", reply.speech)
        assertEquals("query_answer", reply.responseType)
    }

    @Test
    fun `an agent error keeps its sentence but is flagged`() {
        val reply = ConversationReply.fromResultMessage(
            msg(
                """
                {"id":3,"type":"result","success":true,"result":{"response":{
                  "response":{
                    "speech":{"plain":{"speech":"Sorry, I am not aware of any device called foo"}},
                    "response_type":"error",
                    "data":{"code":"no_intent_match"}
                  },
                  "conversation_id":"z1"
                }}}
                """.trimIndent(),
            ),
        )
        assertTrue(reply.isError)
        // The agent's own wording is far more useful on screen than a generic failure line.
        assertEquals("Sorry, I am not aware of any device called foo", reply.displayText)
    }

    @Test
    fun `surfaces the error message when the call itself fails`() {
        val reply = ConversationReply.fromResultMessage(
            msg(
                """
                {"id":4,"type":"result","success":false,
                 "error":{"code":"not_found","message":"Agent conversation.nope not found"}}
                """.trimIndent(),
            ),
        )
        assertTrue(reply.isError)
        assertEquals("Agent conversation.nope not found", reply.displayText)
        assertEquals("", reply.speech)
    }

    @Test
    fun `a dropped socket or timeout reports a connection failure`() {
        val reply = ConversationReply.fromResultMessage(null)
        assertTrue(reply.isError)
        assertTrue(reply.displayText.contains("connection"))
        assertEquals(null, reply.conversationId)
    }

    @Test
    fun `a blank thread id is not carried forward`() {
        // Keeping "" would send conversation_id:"" on the next turn, which HA rejects; null means
        // "start a thread" and is the correct reading of an absent id.
        val reply = ConversationReply.fromResultMessage(
            msg(
                """
                {"id":5,"type":"result","success":true,"result":{"response":{
                  "response":{"speech":{"plain":{"speech":"Done"}},"response_type":"action_done"},
                  "conversation_id":""
                }}}
                """.trimIndent(),
            ),
        )
        assertEquals("Done", reply.speech)
        assertEquals(null, reply.conversationId)
    }

    @Test
    fun `parses an already unwrapped intent_output`() {
        // HA hands this shape over flat in more than one place — the REST `service_response`, and a
        // pipeline run's `intent_output` — so the parser has to take it without the outer envelope.
        val reply = ConversationReply.fromServiceResponse(
            msg(
                """
                {"response":{"speech":{"plain":{"speech":"Turned on the lamp"}},
                 "response_type":"action_done","data":{"targets":[]}},
                 "conversation_id":"voice-1"}
                """.trimIndent(),
            ),
        )
        assertEquals("Turned on the lamp", reply.speech)
        assertEquals("voice-1", reply.conversationId)
        assertFalse(reply.isError)
    }

    @Test
    fun `a follow-up question is flagged so the mic can reopen`() {
        // HA puts continue_conversation beside conversation_id, OUTSIDE the intent response — the
        // one field that decides whether the exchange is over or the agent is waiting on an answer.
        val reply = ConversationReply.fromResultMessage(
            msg(
                """
                {"id":11,"type":"result","success":true,"result":{"response":{
                  "response":{
                    "speech":{"plain":{"speech":"Which light did you mean?"}},
                    "response_type":"query_answer"
                  },
                  "conversation_id":"c9",
                  "continue_conversation":true
                }}}
                """.trimIndent(),
            ),
        )
        assertTrue(reply.continueConversation)
        assertFalse(reply.isError)
        assertEquals("c9", reply.conversationId)
    }

    @Test
    fun `an absent continue_conversation ends the exchange`() {
        // Every reply HA sent before the field existed parses as "finished", which is the only safe
        // default: guessing the other way would reopen the microphone after every answer.
        val reply = ConversationReply.fromServiceResponse(
            msg(
                """
                {"response":{"speech":{"plain":{"speech":"Turned on the lamp"}},
                 "response_type":"action_done"},"conversation_id":"v2"}
                """.trimIndent(),
            ),
        )
        assertFalse(reply.continueConversation)
    }

    @Test
    fun `a pipeline intent_output carries the flag too`() {
        // The voice route reads it from intent_output, which is the same shape one envelope
        // shallower — so the streamed half must not need its own parser for this.
        val reply = ConversationReply.fromServiceResponse(
            msg(
                """
                {"response":{"speech":{"plain":{"speech":"What would you like me to do?"}},
                 "response_type":"query_answer"},
                 "conversation_id":"voice-9","continue_conversation":true}
                """.trimIndent(),
            ),
        )
        assertTrue(reply.continueConversation)
        assertEquals("What would you like me to do?", reply.speech)
    }

    @Test
    fun `a transport failure never asks for a follow-up`() {
        assertFalse(ConversationReply.fromResultMessage(null).continueConversation)
        assertFalse(ConversationReply.failure("nope").continueConversation)
    }

    @Test
    fun `an unrecognisable payload does not pass as an empty answer`() {
        val reply = ConversationReply.fromResultMessage(
            msg("""{"id":6,"type":"result","success":true,"result":{"unexpected":true}}"""),
        )
        assertTrue(reply.isError)
        assertTrue(reply.displayText.isNotBlank())
    }
}
