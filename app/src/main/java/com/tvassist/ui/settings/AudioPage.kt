package com.tvassist.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.ui.graphics.Brush
import com.tvassist.ui.cards.ColorSliderRow
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.tvassist.ui.AppAccent
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ChipDim
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.TvTextField
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary

/** Per-TV audio defaults for TTS + sound announcements (HA calls can override per announcement). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AudioPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    PageScaffold("Audio & announcements", onBack) {
        Text(
            "Defaults for text-to-speech and sound files on this TV. A Home Assistant service call can " +
                "override the volume, ducking and language per announcement.",
            color = TxtMuted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable announcements", color = TxtPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            ChipButton(
                if (settings.announceEnabled) "On" else "Off",
                selected = settings.announceEnabled,
                onClick = { viewModel.setAnnounceEnabled(!settings.announceEnabled) },
            )
        }
        if (settings.announceEnabled) {
            Spacer(Modifier.height(20.dp))
            val track = Brush.horizontalGradient(listOf(ChipDim, AppAccent))
            ColorSliderRow(
                "Volume", settings.announceVolume.toDouble(), 0.0, 100.0, 5.0, track,
                { "${it.roundToInt()}%" }, { viewModel.setAnnounceVolume(it.roundToInt()) }, resetKey = "announceVol",
            )
            Spacer(Modifier.height(20.dp))
            Text("Speak title & message", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OptionChips(
                options = listOf(
                    "both" to "Together",
                    "separate" to "Separate",
                    "message" to "Message only",
                    "title" to "Title only",
                ),
                selected = settings.announceSpeakMode.ifBlank { "both" },
                onSelect = { viewModel.setAnnounceSpeakMode(it) },
            )
            Text(
                "How a notification is voiced: Together reads \"Title. Message\" in one breath; Separate " +
                    "speaks the title, then the message, as two announcements; or read just one of them.",
                color = TxtMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text("Repeat speech", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OptionChips(
                options = listOf("once" to "Read once", "loop" to "Repeat until dismissed"),
                selected = settings.announceSpeakRepeat.ifBlank { "once" },
                onSelect = { viewModel.setAnnounceSpeakRepeat(it) },
            )
            Text(
                "Repeat re-reads the notification (with a pause) until it leaves the screen. " +
                    "A notification pinned on screen (duration 0) is capped at 60 seconds.",
                color = TxtMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
            if (settings.announceSpeakRepeat == "loop") {
                Spacer(Modifier.height(16.dp))
                val gapTrack = Brush.horizontalGradient(listOf(ChipDim, AppAccent))
                ColorSliderRow(
                    "Pause between repeats", settings.announceRepeatGap.toDouble(), 0.0, 15.0, 1.0, gapTrack,
                    { "${it.roundToInt()}s" }, { viewModel.setAnnounceRepeatGap(it.roundToInt()) },
                    resetKey = "announceGap",
                )
                Text(
                    "Waited after each full read before repeating. The next read only starts once the " +
                        "current one finishes, so it never overlaps.",
                    color = TxtMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text("While speaking", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OptionChips(
                options = listOf("off" to "Play over", "duck" to "Duck TV audio", "pause" to "Pause TV audio"),
                selected = settings.announceDuckMode.ifBlank { "duck" },
                onSelect = { viewModel.setAnnounceDuckMode(it) },
            )
            Text(
                "Duck lowers the current TV audio while speaking (amount decided by the TV); Pause pauses it and resumes after.",
                color = TxtMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text("Default language", fontSize = 14.sp, color = TxtMuted)
            Spacer(Modifier.height(2.dp))
            Text("BCP-47 tag (e.g. en-US, fr-CA). Blank = device default.", color = TxtMuted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            var lang by remember(settings.announceLanguage) { mutableStateOf(settings.announceLanguage) }
            TvTextField(value = lang, onValueChange = { lang = it }, placeholder = "device default")
            LaunchedEffect(lang) {
                if (lang == settings.announceLanguage) return@LaunchedEffect
                delay(500)
                viewModel.setAnnounceLanguage(lang)
            }
            Spacer(Modifier.height(20.dp))
            Text("Sound file playback", color = TxtMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OptionChips(
                options = listOf("once" to "Play once", "loop" to "Loop until dismissed"),
                selected = settings.announceSoundRepeat.ifBlank { "once" },
                onSelect = { viewModel.setAnnounceSoundRepeat(it) },
            )
            Text(
                "Applies to a sound file sent with a notification. Loop replays it until the notification " +
                    "leaves the screen; a notification pinned on screen (duration 0) is capped at 60 seconds.",
                color = TxtMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
