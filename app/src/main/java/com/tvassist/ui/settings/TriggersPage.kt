package com.tvassist.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SettingsRemote
import com.tvassist.ui.AccentButton
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ConnectionViewModel

/**
 * Chooses which Assist pipeline transcribes speech, and says plainly when the chosen one cannot.
 *
 * The warning is the point. A pipeline with no speech-to-text engine rejects every run, and until
 * this screen said so the only symptom was Speak failing with Home Assistant's own terse "the
 * pipeline does not support speech-to-text" — after the mic had already appeared to open.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AssistPipelinePicker(
    pipelines: com.tvassist.data.ha.AssistPipelines?,
    loading: Boolean,
    selected: String,
    /**
     * True when the TV's own recogniser transcribes, so the pipeline's speech-to-text is not in the
     * path at all. Both BRAVIAs are this case — the remote's mic is never a recordable input — and
     * calling a working setup broken because the pipeline has no `stt_engine` sent the owner of one
     * to fix a stage that was never going to run.
     */
    transcribedOnDevice: Boolean,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
) {
    if (pipelines == null) {
        if (loading) {
            Text("Loading pipelines…", color = Color(0xFF999999), fontSize = 13.sp)
        } else {
            Text(
                "Could not read the pipeline list from Home Assistant.",
                color = Color(0xFFFFB74D), fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            ChipButton("Retry", selected = false, onClick = onRetry)
        }
        return
    }
    if (pipelines.pipelines.isEmpty()) {
        Text(
            "Home Assistant reports no Assist pipelines. Add one under Settings → Voice assistants.",
            color = Color(0xFFFFB74D), fontSize = 13.sp,
        )
        return
    }

    OptionChips(
        options = listOf("" to "Auto (preferred)") +
            pipelines.pipelines.map { p ->
                val flaw = when {
                    !p.supportsSpeech && !transcribedOnDevice -> " · no speech-to-text"
                    !p.supportsVoice -> " · no voice"
                    else -> ""
                }
                p.id to p.name + flaw
            },
        selected = selected,
        onSelect = onSelect,
    )

    Spacer(Modifier.height(8.dp))
    // Reports the pipeline that will actually run, not the chip that is lit: on Auto those differ,
    // and it is the effective one whose engines decide what works.
    val effective = pipelines.resolve(selected) ?: return
    when {
        // Fatal: without speech-to-text the run is rejected outright and nothing happens at all.
        // Unless this TV never asks it to transcribe, in which case the missing engine is moot.
        !effective.supportsSpeech && !transcribedOnDevice -> Text(
            "${effective.name} has no speech-to-text engine, so Speak will fail on it. Pick a " +
                "pipeline that has one, or add an engine in Home Assistant under " +
                "Settings → Voice assistants.",
            color = Color(0xFFFFB74D), fontSize = 12.sp,
        )
        // Survivable: you still get an answer, just written rather than spoken.
        !effective.supportsVoice -> Text(
            "${effective.name} has no text-to-speech engine, so replies will appear on screen but " +
                "will not be read aloud. Add a voice in Home Assistant under Settings → " +
                "Voice assistants.",
            color = Color(0xFFFFB74D), fontSize = 12.sp,
        )
        // Names the stages that actually run: on the recogniser route the TV hears, and only the
        // agent and the voice come from the pipeline.
        transcribedOnDevice -> Text(
            "Using ${effective.name} — this TV's own recogniser hears you, " +
                "${effective.conversationEngine ?: "its default agent"} answers, and it speaks " +
                "with ${effective.ttsEngine}.",
            color = Color(0xFF8AB4F8), fontSize = 12.sp,
        )
        else -> Text(
            "Using ${effective.name} — hears with ${effective.sttEngine}, answers with " +
                "${effective.conversationEngine ?: "its default agent"}, speaks with ${effective.ttsEngine}.",
            color = Color(0xFF8AB4F8), fontSize = 12.sp,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TriggersPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val micContext = LocalContext.current
    // Re-enumerated per visit: a USB mic can be plugged in between openings of this page.
    val micChoices = remember { com.tvassist.data.assist.listMicChoices(micContext) }
    val pipelines by viewModel.assistPipelines.collectAsStateWithLifecycle()
    val pipelinesLoading by viewModel.assistPipelinesLoading.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadAssistPipelines() }
    PageScaffold("Triggers & keys", onBack) {
        Text(
            "The remote button that opens the Home Assistant control overlay from any app.",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text("Current: ${keyName(settings.triggerKeyCode)}", color = Color(0xFF8AB4F8), fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        TriggerKeyCapture(onCaptured = viewModel::setTriggerKey)

        Spacer(Modifier.height(26.dp))
        Text("Assist microphone", fontSize = 18.sp, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            "A second button that opens Assist with the microphone already listening, so you can " +
                "just talk. Audio is streamed to Home Assistant, which transcribes it, answers, and " +
                "speaks the reply back. Needs microphone access (Settings → Permissions).",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (settings.micKeyCode == 0) "Current: not set" else "Current: ${keyName(settings.micKeyCode)}",
            color = Color(0xFF8AB4F8), fontSize = 16.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The voice bar is drawn by the keep-alive service — make sure it's running, the same
            // way the dim/clock toggles do on the On-screen display page.
            TriggerKeyCapture(
                onCaptured = { code ->
                    com.tvassist.overlay.KeepAliveService.start(micContext)
                    viewModel.setMicKeyCode(code)
                },
                label = "Set mic key",
            )
            if (settings.micKeyCode != 0) {
                ChipButton("Clear", selected = false, onClick = { viewModel.setMicKeyCode(0) })
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("Assist pipeline", fontSize = 16.sp, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            "The pipeline runs the whole exchange: it transcribes what you say, answers with its " +
                "own conversation agent, and speaks the reply back in its own voice. That is why " +
                "there is no separate agent setting — a run is addressed by pipeline and takes no " +
                "agent override. Auto uses whichever pipeline Home Assistant prefers.",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        AssistPipelinePicker(
            pipelines = pipelines,
            loading = pipelinesLoading,
            selected = settings.assistPipelineId,
            transcribedOnDevice = com.tvassist.data.assist.voiceRouteFor(
                micContext,
                settings.assistMicId,
            ) == com.tvassist.data.assist.VoiceBackend.DEVICE_RECOGNIZER,
            onSelect = viewModel::setAssistPipelineId,
            onRetry = viewModel::loadAssistPipelines,
        )

        Spacer(Modifier.height(22.dp))
        Text("Microphone", fontSize = 16.sp, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            "Which microphone Assist listens with. A USB or built-in mic streams audio to Home " +
                "Assistant, which runs the whole pipeline and speaks the reply in its own voice. " +
                "\"TV remote\" goes through the TV's own recogniser — the only way to reach the " +
                "remote's mic — so the TV transcribes, but the pipeline's agent still answers and " +
                "the reply is still read back in the pipeline's voice. Auto prefers a real mic " +
                "when one exists.",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        OptionChips(
            options = micChoices.map { it.key to it.label },
            selected = settings.assistMicId,
            onSelect = viewModel::setAssistMicId,
        )
        val micChoice = settings.assistMicId
        if (micChoice.isNotBlank() && micChoices.none { it.key == micChoice }) {
            Spacer(Modifier.height(8.dp))
            Text(
                "The chosen microphone is not connected — Assist will fall back to Auto.",
                color = Color(0xFFFFB74D), fontSize = 12.sp,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TriggerKeyCapture(onCaptured: (Int) -> Unit, label: String = "Set trigger key") {
    var capturing by remember { mutableStateOf(false) }
    var lastCaptured by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(capturing) { if (capturing) runCatching { focus.requestFocus() } }

    Column {
        AccentButton(
            label = if (capturing) "Press a remote button…  (Back to cancel)" else label,
            onClick = { capturing = true },
            leadingIcon = Icons.Rounded.SettingsRemote,
            modifier = Modifier
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (capturing && e.type == KeyEventType.KeyDown) {
                        val code = e.nativeKeyEvent.keyCode
                        capturing = false
                        if (code != KeyEvent.KEYCODE_BACK) {
                            lastCaptured = code
                            onCaptured(code)
                        }
                        true
                    } else {
                        false
                    }
                },
        )
        if (lastCaptured != null) {
            Spacer(Modifier.height(8.dp))
            Text("Captured: ${keyName(lastCaptured!!)}", color = Color(0xFF6FCF7F), fontSize = 14.sp)
        }
    }
}

/** Human-readable name for a keycode (0 = unset → defaults to MENU in the service). */
internal fun keyName(code: Int): String =
    if (code == 0) "Not set (defaults to MENU)" else KeyEvent.keyCodeToString(code)
