package com.tvassist.data.assist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both voice routes read a pipeline run through these, so a miscounted level here breaks the
 * streamed route and the recogniser route at once — which is exactly why there is only one copy.
 */
class PipelineEventsTest {

    private fun data(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `a content delta is the next piece of the answer`() {
        assertEquals(
            " kitchen",
            intentProgressDelta(data("""{"chat_log_delta":{"content":" kitchen"}}""")),
        )
    }

    @Test
    fun `the role delta that opens a stream carries nothing to show`() {
        // HA opens with {"role":"assistant"} and no content. Rendering that would print an empty
        // line before the answer; treating it as a failure would be worse.
        assertNull(intentProgressDelta(data("""{"chat_log_delta":{"role":"assistant"}}""")))
        assertNull(intentProgressDelta(data("""{"chat_log_delta":{"content":""}}""")))
        assertNull(intentProgressDelta(data("""{}""")))
        assertNull(intentProgressDelta(null))
    }

    @Test
    fun `intent_output is read as an answer, thread id and all`() {
        val reply = intentOutputReply(
            data(
                """
                {"intent_output":{
                  "response":{"speech":{"plain":{"speech":"Turned on Kitchen Light"}},
                              "response_type":"action_done"},
                  "conversation_id":"c1",
                  "continue_conversation":false}}
                """.trimIndent(),
            ),
        )
        assertEquals("Turned on Kitchen Light", reply.speech)
        assertEquals("c1", reply.conversationId)
        assertEquals(false, reply.continueConversation)
    }

    @Test
    fun `a run that answered nothing is an error, not an empty answer`() {
        val reply = intentOutputReply(data("""{}"""))
        assertTrue(reply.isError)
        assertTrue(reply.displayText.isNotBlank())
    }

    @Test
    fun `the audio to play comes from tts_output`() {
        assertEquals(
            "/api/tts_proxy/abc.mp3",
            ttsOutputUrl(data("""{"tts_output":{"url":"/api/tts_proxy/abc.mp3","mime_type":"audio/mpeg"}}""")),
        )
        // A pipeline with no voice ends without one, and that is not a failure.
        assertNull(ttsOutputUrl(data("""{}""")))
    }

    @Test
    fun `an error prefers Home Assistant's own sentence over its code`() {
        assertEquals(
            "the pipeline does not support speech-to-text",
            pipelineErrorMessage(data("""{"code":"not_supported","message":"the pipeline does not support speech-to-text"}""")),
        )
        assertEquals("not_supported", pipelineErrorMessage(data("""{"code":"not_supported"}""")))
        assertTrue(pipelineErrorMessage(null).isNotBlank())
    }
}
