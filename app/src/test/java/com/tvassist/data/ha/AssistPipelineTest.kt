package com.tvassist.data.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing `assist_pipeline/pipeline/list`, and the fallback chain the settings screen uses to say
 * which pipeline will *actually* run — which is not always the one whose chip is lit.
 */
class AssistPipelineTest {

    private fun msg(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    private fun listMessage() = msg(
        """
        {"id":4,"type":"result","success":true,"result":{
          "pipelines":[
            {"id":"pipe-a","name":"Home Assistant","language":"en",
             "conversation_engine":"conversation.home_assistant",
             "stt_engine":null,"tts_engine":null},
            {"id":"pipe-b","name":"Whisper","language":"en",
             "conversation_engine":"conversation.openai_conversation",
             "stt_engine":"stt.faster_whisper","tts_engine":"tts.piper"}
          ],
          "preferred_pipeline":"pipe-a"}}
        """.trimIndent(),
    )

    @Test
    fun `parses pipelines and the preferred id`() {
        val parsed = AssistPipelines.fromResultMessage(listMessage())!!
        assertEquals(2, parsed.pipelines.size)
        assertEquals("pipe-a", parsed.preferredId)
        assertEquals("Whisper", parsed.pipelines[1].name)
        assertEquals("stt.faster_whisper", parsed.pipelines[1].sttEngine)
    }

    @Test
    fun `a null stt engine means the pipeline cannot be used for speech`() {
        // This is the whole reason the picker annotates entries: HA reports the pipeline happily,
        // and only rejects it once a run is attempted.
        val parsed = AssistPipelines.fromResultMessage(listMessage())!!
        assertFalse(parsed.pipelines[0].supportsSpeech)
        assertTrue(parsed.pipelines[1].supportsSpeech)
    }

    @Test
    fun `a missing voice is reported separately from a missing ear`() {
        // The two failures are not equivalent: no speech-to-text means the run is refused outright,
        // while no text-to-speech still answers — just on screen instead of aloud.
        val parsed = AssistPipelines.fromResultMessage(listMessage())!!
        assertFalse(parsed.pipelines[0].supportsVoice)
        assertTrue(parsed.pipelines[1].supportsVoice)
        assertEquals("tts.piper", parsed.pipelines[1].ttsEngine)
    }

    @Test
    fun `a pipeline can hear without having a voice`() {
        val parsed = AssistPipelines.fromResultMessage(
            msg(
                """
                {"id":4,"type":"result","success":true,"result":{
                  "pipelines":[{"id":"p","name":"Whisper only",
                   "stt_engine":"stt.faster_whisper","tts_engine":null}],
                  "preferred_pipeline":"p"}}
                """.trimIndent(),
            ),
        )!!
        val only = parsed.pipelines.single()
        assertTrue(only.supportsSpeech)
        assertFalse(only.supportsVoice)
    }

    @Test
    fun `a blank choice resolves to the preferred pipeline`() {
        val parsed = AssistPipelines.fromResultMessage(listMessage())!!
        assertEquals("pipe-a", parsed.resolve("")?.id)
    }

    @Test
    fun `an explicit choice wins over the preferred one`() {
        val parsed = AssistPipelines.fromResultMessage(listMessage())!!
        assertEquals("pipe-b", parsed.resolve("pipe-b")?.id)
    }

    @Test
    fun `a pipeline that no longer exists falls back rather than resolving to nothing`() {
        // Pipelines are edited in HA, so a stored id can outlive the pipeline it names. Falling
        // back matches what a run would really do, which is what the warning has to describe.
        val parsed = AssistPipelines.fromResultMessage(listMessage())!!
        assertEquals("pipe-a", parsed.resolve("deleted-pipeline")?.id)
    }

    @Test
    fun `an unnamed pipeline falls back to its id rather than a blank chip`() {
        val parsed = AssistPipelines.fromResultMessage(
            msg(
                """
                {"id":4,"type":"result","success":true,"result":{
                  "pipelines":[{"id":"pipe-x","name":"","stt_engine":"stt.x"}],
                  "preferred_pipeline":"pipe-x"}}
                """.trimIndent(),
            ),
        )!!
        assertEquals("pipe-x", parsed.pipelines.single().name)
    }

    @Test
    fun `a rejected request is not mistaken for an instance with no pipelines`() {
        val parsed = AssistPipelines.fromResultMessage(
            msg("""{"id":4,"type":"result","success":false,"error":{"message":"nope"}}"""),
        )
        assertNull(parsed)
    }

    @Test
    fun `a dropped socket reports null`() {
        assertNull(AssistPipelines.fromResultMessage(null))
    }

    @Test
    fun `an instance with no pipelines parses as an empty list, not a failure`() {
        val parsed = AssistPipelines.fromResultMessage(
            msg("""{"id":4,"type":"result","success":true,"result":{"pipelines":[]}}"""),
        )
        assertEquals(emptyList<AssistPipeline>(), parsed?.pipelines)
        assertNull(parsed?.preferredId)
    }
}
