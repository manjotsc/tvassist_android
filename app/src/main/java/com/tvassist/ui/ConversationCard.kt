package com.tvassist.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvassist.TvAssistApp
import com.tvassist.data.assist.ConversationTurn
import com.tvassist.data.ha.Entity
import com.tvassist.data.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Talk to a Home Assistant `conversation` agent (Assist) from the couch.
 *
 * Slots into [EntityControlCard]'s domain dispatch like the light/climate controls, so the same card
 * works on the Home screen and inside the overlay.
 *
 * This is the **typing** half of Assist; speaking is the voice bar, which is a window of its own so
 * it can appear over whatever is playing without a card or the sidebar. The two share a thread
 * through [com.tvassist.data.assist.VoiceController], which is what makes a typed follow-up to a
 * spoken question — or the reverse — still resolve what "it" meant.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ConversationControls(
    entity: Entity,
    actions: EntityControlActions,
    firstFocus: FocusRequester,
) {
    val th = LocalOverlayTheme.current
    val app = LocalContext.current.applicationContext as TvAssistApp
    val scope = rememberCoroutineScope()

    // The transcript is the controller's, like the thread it belongs to — so what was asked out
    // loud is here too, and it survives this card being closed and reopened.
    val allTurns by app.voice.turns.collectAsState()
    // Another agent's conversation is not this card's to render; asking clears it (beginExchange).
    val turns = if (app.voice.ownsHistory(entity.entityId)) allTurns else emptyList()
    var draft by remember(entity.entityId) { mutableStateOf("") }
    // Bumped when the agent asks something back, to put the editor straight back up.
    var reopen by remember(entity.entityId) { mutableStateOf(0) }
    // The thread is the controller's, not this card's, so it survives the card closing and is the
    // same one the voice bar is using. Collected only so "New thread" can appear; what actually
    // gets sent goes through threadFor(), which drops another agent's thread.
    val threadId by app.voice.threadId.collectAsState()
    var busy by remember(entity.entityId) { mutableStateOf(false) }

    // Read once when the card opens, for the language to ask in and the mic-key hint below. A modal
    // card is short-lived, so there is nothing to gain from collecting reactively.
    var announce by remember { mutableStateOf<Settings?>(null) }
    LaunchedEffect(Unit) { announce = runCatching { app.settingsStore.settings.first() }.getOrNull() }

    val transcript = rememberScrollState()
    // Keep the newest turn in view as the exchange grows past the transcript's height. maxValue is
    // read after a frame has been produced: on the same frame the new turn was added it still holds
    // the previous extent, which leaves the line that was just added scrolled just short of view.
    LaunchedEffect(turns.size, busy) {
        withFrameNanos {}
        transcript.animateScrollTo(transcript.maxValue)
    }


    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || busy) return
        // Opened before the question is recorded: a change of agent clears the history, and doing
        // that after appending would drop the line just typed.
        val thread = app.voice.beginExchange(entity.entityId)
        app.voice.record(mine = true, text = q)
        draft = ""
        busy = true
        scope.launch {
            // busy gates every entry point, so anything thrown on the way to a reply would leave
            // the card permanently unable to ask again — stuck on "Asking…" until it is reopened.
            try {
                val reply = actions.repository.converse(
                    agentId = entity.entityId,
                    text = q,
                    // Not the raw threadId: only a thread this agent actually issued — see
                    // beginExchange above, which is the same gate the voice route asks.
                    conversationId = thread,
                    language = announce?.announceLanguage.orEmpty(),
                )
                // Only adopt a new thread id; a reply that omits one must not drop the thread we had.
                app.voice.adoptThread(entity.entityId, reply.conversationId)
                app.voice.record(mine = false, text = reply.displayText, error = reply.isError)
                // The agent expects an answer. The voice bar reopens the mic for this; the typed
                // half reopens the editor, rather than leaving a question hanging on a row the user
                // has to click into again.
                if (reply.continueConversation && !reply.isError) reopen++
            } catch (c: CancellationException) {
                throw c // the card closed mid-question; leaving is the correct outcome
            } catch (t: Throwable) {
                app.voice.record(
                    mine = false,
                    text = "Could not reach Assist: ${t.message}",
                    error = true,
                )
            } finally {
                busy = false
            }
        }
    }


    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // --- Transcript ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 84.dp, max = 210.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(th.segmentBg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(transcript),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (turns.isEmpty()) {
                    Text(
                        text = "Press OK and ask Home Assistant something — " +
                            "\"turn off the kitchen light\", \"is the garage door open?\"",
                        color = th.subText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
                turns.forEach { turn -> TurnLine(turn, th) }
                if (busy) Text("Thinking…", color = th.subText, fontSize = 13.sp)
            }
        }

        // --- Input ---
        AssistInput(
            value = draft,
            onValueChange = { draft = it },
            onSubmit = { ask(it) },
            enabled = !busy,
            focusRequester = firstFocus,
            reopenSignal = reopen,
        )

        // No Ask button: the keyboard's Send key is the submit path, so this row exists only to
        // offer a reset once there is a thread to reset. Rendered conditionally rather than always,
        // because an empty Row still takes the Column's 8.dp spacing and leaves a visible gap.
        if (turns.isNotEmpty() || threadId != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // newThread() clears the transcript as well as the id — the two are one thing.
                AccentButton("New thread", { draft = ""; app.voice.newThread() })
            }
        }

        // The card cannot start the mic itself — voice is the bar, in its own window — so point at
        // the button that can, and say how to get one when none is bound.
        Text(
            text = if ((announce?.micKeyCode ?: 0) != 0) {
                "Press the mic key on your remote to talk instead of typing."
            } else {
                "Assign a mic key in Settings → Triggers & keys to talk instead of typing."
            },
            color = th.subText,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun TurnLine(turn: ConversationTurn, th: OverlayTheme) {
    Column {
        Text(if (turn.mine) "You" else "Assist", color = th.subText, fontSize = 10.sp)
        Text(
            text = turn.text,
            // Errors are the one case that must not look like an ordinary answer.
            color = when {
                turn.error -> th.errorText
                turn.mine -> th.subText
                else -> th.text
            },
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
    }
}

/**
 * Click-to-edit text entry, matching the settings screens' behaviour: a focused field on a TV
 * auto-pops the soft keyboard, which is maddening while merely navigating with the D-pad — so the
 * row stays a focusable display until OK, and only then becomes a live editor.
 *
 * Deliberately does NOT use `BackHandler`: this card also renders inside the overlay window, which
 * has no `OnBackPressedDispatcherOwner` — the IME swallows BACK itself, and losing focus commits.
 */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun AssistInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    enabled: Boolean,
    focusRequester: FocusRequester,
    /** Incremented to put the editor back up — the agent asked a follow-up. Zero means never. */
    reopenSignal: Int = 0,
) {
    val th = LocalOverlayTheme.current
    val shape = RoundedCornerShape(12.dp)
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (!editing) text = value }

    // Skips the initial composition: a card opening with a live thread must not pop the keyboard.
    LaunchedEffect(reopenSignal) { if (reopenSignal > 0) editing = true }

    // Leaving the editor destroys the text field, and with it the focus — which on a D-pad leaves
    // the whole card with nothing selected, so the next OK press does nothing at all. Put focus
    // back on the row so asking a follow-up is just OK-type-Send again.
    var wasEditing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (!editing && wasEditing) {
            delay(50) // let the display row compose before its requester is used
            runCatching { focusRequester.requestFocus() }
        }
        wasEditing = editing
    }

    if (editing) {
        val editFocus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        // Only leave edit mode once focus has actually been gained and then lost — the first
        // (not-yet-focused) callback would otherwise cancel editing immediately.
        var everFocused by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            runCatching { editFocus.requestFocus() }
            delay(80)
            runCatching { keyboard?.show() }
        }
        Box(
            modifier = Modifier.fillMaxWidth().clip(shape).background(th.chip)
                .border(1.5.dp, th.accent, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (text.isEmpty()) {
                Text("Ask Assist…", color = th.subText, fontSize = 15.sp)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it; onValueChange(it) },
                singleLine = true,
                textStyle = TextStyle(color = th.text, fontSize = 15.sp),
                cursorBrush = SolidColor(th.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                // Send asks straight away — on a remote, making the user navigate to a separate
                // button after typing is the slowest part of the whole interaction.
                keyboardActions = KeyboardActions(onSend = {
                    editing = false
                    onSubmit(text)
                }),
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(editFocus)
                    .onFocusChanged {
                        if (it.isFocused) everFocused = true
                        else if (everFocused) editing = false
                    },
            )
        }
    } else {
        Surface(
            onClick = { if (enabled) editing = true },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            shape = ClickableSurfaceDefaults.shape(shape),
            // A full-width field must not grow on focus: the default focusedScale is 1.1x, which
            // makes it spill past the card's padding toward both edges. The border is the cue.
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = th.chip,
                focusedContainerColor = th.tileFocused,
                pressedContainerColor = th.tileFocused,
                contentColor = th.text,
                focusedContentColor = th.text,
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(1.5.dp, th.focus), shape = shape),
            ),
        ) {
            Text(
                text = value.ifBlank { "Ask Assist…" },
                color = if (value.isBlank()) th.subText else th.text,
                fontSize = 15.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            )
        }
    }
}
