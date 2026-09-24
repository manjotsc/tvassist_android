package com.tvassist.ui.cards.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.ui.AccentButton
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.cap
import com.tvassist.ui.cards.EntityCard
import com.tvassist.ui.cards.EntityControlActions
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * The `update` domain — firmware, add-ons, Home Assistant itself. The state is `on` when an update
 * is available and `off` when up to date, so upstream's `stateActive` already lights exactly the
 * tiles that want attention and nothing here overrides it.
 *
 * The body shows the two versions, the release summary, and a progress bar while an install runs.
 * Progress may be fractional — HA's demo has "Update with Decimal Progress" for exactly that — so
 * it is drawn and printed as a fraction rather than rounded to a whole percent that would sit
 * still for minutes on a slow flash.
 */
internal object UpdateCard : EntityCard {
    override val domains = setOf("update")

    /** Install is the control; the generic toggle would call `turn_on`, which an update lacks. */
    override fun ownsToggle(e: Entity): Boolean = true

    override fun status(e: Entity, compact: Boolean): String = updateStatus(e, compact)

    @Composable
    override fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) =
        UpdateControls(e, actions, firstFocus)
}

/**
 * `42.7%`, or `42%` when whole — a decimal only where the integration actually sent one.
 *
 * Nothing short of 100 prints as 100: rounded plainly, 99.96 reads "100.0%" on a bar that is still
 * filling, which says the install finished when it has not.
 */
internal fun formatPercent(p: Double): String = when {
    p % 1.0 == 0.0 -> "${p.toInt()}%"
    else -> String.format(Locale.US, "%.1f%%", if (p < 100.0) minOf(p, 99.9) else p)
}

/** Whether Install is offered: an update is waiting, the entity can install it, and none is running. */
internal fun canInstall(e: Entity): Boolean =
    e.state == "on" && e.supportsUpdateInstall && !e.updateInProgress

internal fun updateStatus(e: Entity, compact: Boolean): String = when {
    e.updateInProgress -> e.updatePercentage?.let { "Installing ${formatPercent(it)}" } ?: "Installing…"
    e.state == "on" -> {
        val latest = e.updateLatestVersion
        when {
            latest == null -> "Update available"
            compact -> "$latest available"
            else -> "Update available · $latest"
        }
    }
    e.state == "off" -> {
        val installed = e.updateInstalledVersion
        if (compact || installed == null) "Up to date" else "Up to date · $installed"
    }
    else -> cap(e.state)
}

@Composable
private fun UpdateControls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) {
    val th = LocalOverlayTheme.current
    val scope = rememberCoroutineScope()
    var error by remember(e.entityId) { mutableStateOf<String?>(null) }
    var sending by remember(e.entityId) { mutableStateOf(false) }

    fun install(backup: Boolean) {
        if (sending) return
        sending = true
        error = null
        scope.launch {
            // Checked: an install that fails to start (no space, device offline) would otherwise
            // leave the card showing "Update available" with nothing to say the press was refused.
            error = actions.installUpdate(e, backup)
            sending = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        e.updateTitle?.let { Text(it, color = th.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        VersionLine("Installed", e.updateInstalledVersion)
        if (e.state == "on" || e.updateInProgress) VersionLine("Latest", e.updateLatestVersion)

        if (e.updateInProgress) {
            val pct = e.updatePercentage
            ProgressBar(pct)
            Text(
                pct?.let { "Installing · ${formatPercent(it)}" } ?: "Installing…",
                color = th.subText,
                fontSize = 13.sp,
            )
        }

        e.updateReleaseSummary?.let {
            Text(it, color = th.subText, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }

        if (canInstall(e) && !sending) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton(
                    label = "Install",
                    onClick = { install(backup = false) },
                    modifier = Modifier.focusRequester(firstFocus),
                )
                if (e.supportsUpdateBackup) {
                    AccentButton(label = "Back up & install", onClick = { install(backup = true) })
                }
            }
        }
        if (sending) Text("Starting…", color = th.subText, fontSize = 13.sp)
        error?.let { Text(it, color = Color(0xFFF44336), fontSize = 13.sp) }
    }
}

@Composable
private fun VersionLine(label: String, version: String?) {
    val th = LocalOverlayTheme.current
    Row {
        Text(label, color = th.subText, fontSize = 13.sp, modifier = Modifier.width(80.dp))
        Text(version ?: "—", color = th.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The install's progress, fractional. [TrackBar][com.tvassist.ui.cards.TrackBar] takes a whole
 * percent, which is the rounding this card exists to avoid. With no percentage at all the bar is
 * drawn empty rather than guessed; the text under it says the install is running.
 */
@Composable
private fun ProgressBar(pct: Double?) {
    val th = LocalOverlayTheme.current
    val pill = RoundedCornerShape(4.dp)
    Box(Modifier.fillMaxWidth().height(8.dp).clip(pill).background(th.trackBg)) {
        val f = ((pct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
        if (f > 0f) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(pill).background(th.trackFill))
        }
    }
}

// --- Service calls -------------------------------------------------------------------------

/** Starts the install, returning HA's refusal (null when it started). */
internal suspend fun EntityControlActions.installUpdate(e: Entity, backup: Boolean): String? =
    repository.callServiceChecked(
        "update",
        "install",
        e.entityId,
        if (backup) mapOf("backup" to JsonPrimitive(true)) else null,
    )
