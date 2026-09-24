package com.tvassist.ui.cards.alarm

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.TvTextField
import com.tvassist.ui.alarmStateColor
import com.tvassist.ui.cap
import com.tvassist.ui.cards.EntityCard
import com.tvassist.ui.cards.EntityControlActions
import com.tvassist.ui.cards.ModeContentButton
import com.tvassist.ui.cards.ModeTextRow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `alarm_control_panel` domain: arm in any mode the panel supports, disarm, and enter the code
 * on a keypad the remote can drive.
 *
 * **Trigger is deliberately absent**, though `supported_features` may offer it. It sets off the
 * siren, a TV remote is pressed by accident far more often than a phone screen, and holding OK on
 * a tile has already been caught firing a card's first control thirteen times (`OkBurstFilter`).
 * A panic button belongs somewhere harder to reach than the sidebar.
 *
 * Activity is upstream's (`stateActive`: anything but disarmed), which already agrees with the
 * colours — lit and green when armed, dark and purple when disarmed — so unlike
 * [com.tvassist.ui.cards.lock.LockCard] nothing needs overriding.
 */
internal object AlarmCard : EntityCard {
    override val domains = setOf("alarm_control_panel")

    /** Disarm and the arm modes are the control; see [EntityCard.ownsToggle]. */
    override fun ownsToggle(e: Entity): Boolean = true

    /** The roomy header adds who last changed it, where the integration says. */
    override fun status(e: Entity, compact: Boolean): String {
        val by = e.alarmChangedBy
        return if (compact || by == null) cap(e.state) else "${cap(e.state)} · by $by"
    }

    @Composable
    override fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) =
        AlarmControls(e, actions, firstFocus)
}

/** The mode row's id for disarming; every other id is an arm mode's suffix (`away`). */
internal const val DISARM = "disarm"

/** A keypad key's width and the gap between keys, which together size Cancel to the grid. */
private const val KEY_DP = 56
private const val GAP_DP = 6

/** Long enough for any real panel's PIN, short enough that a stuck key cannot run away. */
private const val MAX_CODE = 12

/** Whether [target] needs the code before it is sent. */
internal fun needsCode(e: Entity, target: String): Boolean =
    e.alarmCodeFormat != null && (target == DISARM || e.alarmCodeArmRequired)

/** Which segment the panel's state fills: none while arming, pending or triggered. */
internal fun selectedFor(state: String): String? = when {
    state == "disarmed" -> DISARM
    state.startsWith("armed_") -> state.removePrefix("armed_")
    else -> null
}

@Composable
private fun AlarmControls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) {
    val th = LocalOverlayTheme.current
    val scope = rememberCoroutineScope()
    // The command waiting for its code — DISARM or an arm mode. Null shows the mode row.
    var awaiting by remember(e.entityId) { mutableStateOf<String?>(null) }
    var code by remember(e.entityId) { mutableStateOf("") }
    var error by remember(e.entityId) { mutableStateOf<String?>(null) }
    var sending by remember(e.entityId) { mutableStateOf(false) }

    fun send(target: String, withCode: String?) {
        if (sending) return
        sending = true
        error = null
        scope.launch {
            val refusal = actions.alarmCommand(e, target, withCode)
            sending = false
            code = ""
            if (refusal == null) {
                awaiting = null
            } else {
                // A wrong code keeps the keypad up for another go; HA's own words say why.
                error = refusal
            }
        }
    }

    // The keypad is centred under the card's own centred Done; the mode row keeps to the left edge
    // like every other card's segmented row.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = if (awaiting == null) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        val target = awaiting
        if (target == null) {
            val current = selectedFor(e.state)
            ModeTextRow(
                label = "",
                items = listOf(DISARM) + e.alarmArmModes,
                selected = current,
                onSelect = { v ->
                    if (v == current) return@ModeTextRow
                    if (needsCode(e, v)) {
                        error = null
                        code = ""
                        awaiting = v
                    } else {
                        send(v, null)
                    }
                },
                activeColor = alarmStateColor(e.state),
                firstFocus = firstFocus,
            )
            // Returning from the keypad: the row is new, so focus has to be put back on it.
            LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
        } else {
            Text(
                if (target == DISARM) "Code to disarm" else "Code to arm ${cap(target).lowercase()}",
                color = th.subText,
                fontSize = 13.sp,
            )
            // Masked, always: the TV is the one screen in the house everyone in the room can read.
            Text(
                if (code.isEmpty()) "—" else "•".repeat(code.length),
                color = th.text,
                fontSize = 24.sp,
                letterSpacing = 4.sp,
            )
            if (e.alarmCodeFormat == "number") {
                NumberPad(
                    onDigit = { d -> if (code.length < MAX_CODE) code += d },
                    onBack = { code = code.dropLast(1) },
                    onSubmit = { if (code.isNotEmpty()) send(target, code) },
                    onCancel = { awaiting = null; code = ""; error = null },
                )
            } else {
                // A text code needs letters, which only the system keyboard has.
                TvTextField(value = code, onValueChange = { code = it.take(MAX_CODE * 4) }, placeholder = "Code")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyButton("Send", Modifier, width = 100) { if (code.isNotEmpty()) send(target, code) }
                    KeyButton("Cancel", Modifier, width = 100) { awaiting = null; code = ""; error = null }
                }
            }
            if (sending) Text("Sending…", color = th.subText, fontSize = 13.sp)
        }
        error?.let { Text(it, color = Color(0xFFF44336), fontSize = 13.sp, textAlign = TextAlign.Center) }
    }
}

/**
 * Digits, backspace and OK, in a phone's layout — and the remote's own number keys work too, which
 * on a remote that has them is far quicker than walking the D-pad over a grid.
 */
@Composable
private fun NumberPad(
    onDigit: (Char) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        modifier = Modifier.onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val k = ev.key.nativeKeyCode
            when (k) {
                in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                    onDigit('0' + (k - AndroidKeyEvent.KEYCODE_0)); true
                }
                in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> {
                    onDigit('0' + (k - AndroidKeyEvent.KEYCODE_NUMPAD_0)); true
                }
                AndroidKeyEvent.KEYCODE_DEL -> { onBack(); true }
                else -> false
            }
        },
    ) {
        listOf("123", "456", "789").forEachIndexed { r, keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
                keys.forEachIndexed { c, d ->
                    KeyButton(
                        d.toString(),
                        if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier,
                    ) { onDigit(d) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
            KeyButton("⌫", Modifier, onClick = onBack)
            KeyButton("0", Modifier) { onDigit('0') }
            KeyButton("OK", Modifier, onClick = onSubmit)
        }
        KeyButton("Cancel", Modifier, width = 3 * KEY_DP + 2 * GAP_DP, onClick = onCancel)
    }
}

@Composable
private fun KeyButton(label: String, modifier: Modifier, width: Int = KEY_DP, onClick: () -> Unit) {
    ModeContentButton(selected = false, onClick = onClick, modifier = modifier.width(width.dp)) { c ->
        Text(label, color = c, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.width(width.dp))
    }
}

// --- Service calls -------------------------------------------------------------------------

/**
 * Disarm, or arm in [target] mode, returning HA's refusal (null on success).
 *
 * Checked rather than fire-and-forget because a wrong code is the ordinary failure here, and
 * without the answer the panel would simply stay as it was with nothing on screen saying why.
 */
internal suspend fun EntityControlActions.alarmCommand(e: Entity, target: String, code: String?): String? =
    repository.callServiceChecked("alarm_control_panel", alarmService(target), e.entityId, alarmServiceData(code))

/** The service for a mode-row id: `alarm_disarm`, or `alarm_arm_<mode>`. */
internal fun alarmService(target: String): String =
    if (target == DISARM) "alarm_disarm" else "alarm_arm_$target"

/** Service data carrying [code], or none at all — a panel with no code rejects an empty one. */
internal fun alarmServiceData(code: String?): Map<String, JsonPrimitive>? =
    code?.let { mapOf("code" to JsonPrimitive(it)) }
