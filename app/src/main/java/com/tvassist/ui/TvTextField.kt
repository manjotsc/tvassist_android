package com.tvassist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import kotlinx.coroutines.delay

/** Mask a secret to first-2 + last-4 (e.g. "ab••••wxyz"); anything ≤ 6 chars is fully dotted. */
internal fun mask2x4(s: String): String =
    if (s.length <= 6) "•".repeat(s.length)
    else s.take(2) + "•".repeat((s.length - 6).coerceAtMost(12)) + s.takeLast(4)

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    // When true the field is write-only: the saved value shows masked as first-2 + last-4 and is
    // NEVER loaded into the editor. Tapping to edit starts blank; leaving it blank keeps the old
    // value, so the secret can be replaced but never read back on-device.
    secret: Boolean = false,
) {
    // Click-to-edit: on a TV, a focused text field auto-pops the soft keyboard, which is
    // annoying while just navigating. So the field is a focusable display until you press OK;
    // only then does it become an editor and show the keyboard (closing on Done/back/focus-away).
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (!editing) text = value }
    val shape = RoundedCornerShape(12.dp)
    // Secret fields buffer input and commit only on exit (non-blank = replace, blank = keep); plain
    // fields report every keystroke as before.
    val finishEdit = {
        if (secret) { if (text.isNotBlank()) onValueChange(text) else text = value }
        editing = false
    }

    if (editing) {
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        // Only exit edit mode once focus has actually been gained and then lost — otherwise the
        // initial (not-yet-focused) callback would immediately cancel editing.
        var everFocused by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
            delay(80)
            keyboard?.show()
        }
        BackHandler { finishEdit() }
        Box(
            modifier = Modifier.fillMaxWidth().clip(shape).background(ChipDim)
                .border(1.5.dp, AppAccent, shape).padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            if (text.isEmpty()) {
                Text(if (secret && value.isNotEmpty()) "Paste to replace (blank keeps current)" else placeholder, color = TxtMuted, fontSize = 15.sp)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it; if (!secret) onValueChange(it) },
                singleLine = true,
                textStyle = TextStyle(color = TxtPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(AppAccent),
                // A secret you're entering is shown while typing (so you can verify a paste); it's the
                // saved value that's never revealed — the editor always starts blank for secrets.
                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { finishEdit() }),
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (it.isFocused) everFocused = true
                        else if (everFocused) finishEdit()
                    },
            )
        }
    } else {
        Surface(
            // Secrets start the editor blank so the saved value is never shown.
            onClick = { if (secret) text = ""; editing = true },
            modifier = Modifier.fillMaxWidth(),
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = ChipDim, focusedContainerColor = ChipDim,
                pressedContainerColor = ChipDim, contentColor = TxtPrimary, focusedContentColor = TxtPrimary,
            ),
            // A full-width field must not grow on focus (the default focusedScale is 1.1x, which makes
            // it spill past its box toward both edges); the accent border is the focus cue instead.
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(1.5.dp, AppAccent), shape = shape),
            ),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
                val shown = if (secret && text.isNotEmpty()) mask2x4(text) else text
                Text(
                    text = if (text.isEmpty()) placeholder else shown,
                    color = if (text.isEmpty()) TxtMuted else TxtPrimary,
                    fontSize = 15.sp, maxLines = 1,
                )
            }
        }
    }
}
