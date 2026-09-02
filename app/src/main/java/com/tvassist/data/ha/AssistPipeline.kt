package com.tvassist.data.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One Home Assistant Assist pipeline: the chain of speech-to-text, conversation agent and
 * text-to-speech that `assist_pipeline/run` executes.
 *
 * The app runs the whole chain, so all three stages matter: [sttEngine] transcribes,
 * [conversationEngine] answers, and [ttsEngine] reads the answer back. A run is addressed by
 * pipeline and takes no agent override, so the pipeline is what decides who replies.
 */
data class AssistPipeline(
    val id: String,
    val name: String,
    /** The speech-to-text engine, or null when the pipeline has none configured. */
    val sttEngine: String?,
    /** The voice used to read replies back, or null when the pipeline has none configured. */
    val ttsEngine: String?,
    val conversationEngine: String?,
    val language: String,
) {
    /**
     * Whether this pipeline can transcribe at all.
     *
     * A pipeline with no speech-to-text engine rejects a run outright — HA answers "the pipeline
     * does not support speech-to-text" and sends no events — so offering one for voice without
     * saying so just reproduces that failure at press time.
     */
    val supportsSpeech: Boolean get() = !sttEngine.isNullOrBlank()

    /**
     * Whether this pipeline can read a reply back. Home Assistant refuses a run that ends at `tts`
     * with no engine, exactly as it does for a missing speech-to-text one — so this is worth saying
     * before the press rather than after it.
     */
    val supportsVoice: Boolean get() = !ttsEngine.isNullOrBlank()
}

/** HA's pipeline list, plus which one it treats as preferred. */
data class AssistPipelines(
    val pipelines: List<AssistPipeline>,
    val preferredId: String?,
) {
    /**
     * The pipeline a stored id selects: the chosen one, else HA's preferred, else whatever is
     * first. Mirrors what a blank `pipeline` on a run actually gets you, so the settings screen can
     * warn about the pipeline that will really be used rather than the one that was picked.
     */
    fun resolve(chosenId: String): AssistPipeline? =
        (if (chosenId.isNotBlank()) pipelines.firstOrNull { it.id == chosenId } else null)
            ?: pipelines.firstOrNull { it.id == preferredId }
            ?: pipelines.firstOrNull()

    companion object {
        /**
         * Parses the `result` message of `assist_pipeline/pipeline/list`. Null means the request
         * failed or the socket dropped — distinct from an instance that genuinely has no pipelines.
         */
        fun fromResultMessage(msg: JsonObject?): AssistPipelines? {
            if (msg == null) return null
            // Absent counts as failure here, the opposite of ConversationReply's default, and the
            // asymmetry is deliberate: a missing list falls back to the previously cached one, so
            // guessing wrong costs nothing, whereas refusing to parse a reply that did arrive would
            // put a spurious error on screen. HA always sends the key; this only decides which way
            // to be wrong about a malformed message.
            val success = msg["success"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            if (!success) return null
            val result = msg["result"] as? JsonObject ?: return null
            val array = result["pipelines"] as? JsonArray ?: return null
            val pipelines = array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                AssistPipeline(
                    id = id,
                    // A pipeline is always named in the UI, but fall back to the id rather than
                    // rendering a blank chip nobody can identify.
                    name = obj.str("name")?.takeIf { it.isNotBlank() } ?: id,
                    sttEngine = obj.str("stt_engine"),
                    ttsEngine = obj.str("tts_engine"),
                    conversationEngine = obj.str("conversation_engine"),
                    language = obj.str("language").orEmpty(),
                )
            }
            return AssistPipelines(pipelines, result.str("preferred_pipeline")?.takeIf { it.isNotBlank() })
        }

        private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    }
}
