package com.tvassist.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.ui.text.font.FontWeight
import com.tvassist.data.update.UpdateChecker
import kotlinx.coroutines.launch
import com.tvassist.ui.AppAccent
import com.tvassist.ui.ChipButton
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AboutPage(onBack: () -> Unit) {
    PageScaffold("About", onBack) {
        Text("TV Assist", fontSize = 22.sp, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("Version ${com.tvassist.BuildConfig.VERSION_NAME} (${com.tvassist.BuildConfig.VERSION_CODE})",
            color = Color(0xFF999999), fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text("Home Assistant control overlays + remote key mapping for Android TV.",
            color = Color(0xFF999999), fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        UpdateRow()
    }
}

/**
 * Advisory "is there a newer release?" row. Checks once when the About page opens (answers are
 * cached for six hours) and on demand via the chip. Failures read as "Couldn't check" rather than
 * an error — a checker that shouts when the wifi blips is worse than no checker.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun UpdateRow() {
    val current = com.tvassist.BuildConfig.VERSION_NAME
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun check(force: Boolean) {
        if (checking) return
        checking = true
        scope.launch {
            state = UpdateChecker.check(current, force)
            checking = false
        }
    }
    LaunchedEffect(Unit) { check(force = false) }

    val s = state
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Updates", color = TxtPrimary, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        checking -> "Checking…"
                        s is UpdateChecker.Result.Available -> "Version ${s.version} available on GitHub"
                        s is UpdateChecker.Result.UpToDate -> "Up to date"
                        else -> "Couldn't check right now"
                    },
                    color = if (s is UpdateChecker.Result.Available) AppAccent else TxtMuted,
                    fontSize = 12.sp,
                )
                if (s is UpdateChecker.Result.Available) {
                    Spacer(Modifier.width(8.dp))
                    ReleaseTag(s.prerelease)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        ChipButton("Check now", selected = false, onClick = { check(force = true) })
    }
    if (s is UpdateChecker.Result.Available) {
        s.notes.takeIf { it.isNotBlank() }?.let { notes ->
            Spacer(Modifier.height(10.dp))
            Text(notes, color = TxtMuted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 8)
        }
    }
}

/** Non-interactive channel tag for a release: amber "Pre-release" vs green "Latest". */
@Composable
internal fun ReleaseTag(prerelease: Boolean) {
    val label = if (prerelease) "Pre-release" else "Latest"
    val fg = if (prerelease) Color(0xFFF39C12) else Color(0xFF27AE60)
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(fg.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
