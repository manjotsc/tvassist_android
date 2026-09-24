package com.tvassist.ui.cards

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.ui.AccentButton
import com.tvassist.ui.EntityIconContent
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.domainIcon
import kotlinx.coroutines.delay

/**
 * A Home-Assistant-style control card for a single entity, centred over a dim scrim.
 * Dispatches to a domain-specific layout (light/climate) with a generic on/off fallback.
 * D-pad: Up/Down move between rows, Left/Right adjust a focused slider, OK selects a mode.
 *
 * This is the *centred* form, which is what a full screen wants: the in-app route, and the
 * overlay's Assist card. The overlay's ordinary cards use [EntityControlPanel] directly, docked
 * into the bar's own slot — see [com.tvassist.overlay.SidebarContent].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EntityControlCard(
    entity: Entity,
    actions: EntityControlActions,
    onDismiss: () -> Unit,
) {
    // The card must follow the user's palette: the rows it contains (AdjustableSliderRow etc.) are
    // themed, so a hardcoded dark card left light sliders sitting on a dark panel under a light theme.
    val th = LocalOverlayTheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Scrim stays dark-on-light and light-on-dark so the card lifts off whatever is behind it.
            .background(if (th.background.luminance() > 0.5f) Color(0x66000000) else Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        EntityControlPanel(
            entity = entity,
            actions = actions,
            onDismiss = onDismiss,
            modifier = Modifier
                // The Assist transcript needs more room to read than a stack of sliders does.
                .widthIn(min = 360.dp, max = if (entity.isConversation) 560.dp else 440.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(th.background),
        )
    }
}

/**
 * The card itself: header, the domain's controls, Toggle/Close. No scrim, and no size or surface
 * of its own.
 *
 * **[modifier] must carry the width, the shape and the background** — this composable adds only
 * scrolling and padding. That is the point of the split: the overlay dresses it in the floating
 * panel's own width, corner radius, gradient and border so that opening an entity reads as the bar
 * changing content, rather than a centred 440dp card and a full-screen dim landing on top of
 * whatever is playing. [EntityControlCard] is the same body wearing the centred scrim instead.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EntityControlPanel(
    entity: Entity,
    actions: EntityControlActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    // Where focus goes when the card body has nowhere to put it. See the effect below.
    val footerFocus = remember { FocusRequester() }
    // Retried, not fired once: docked in the overlay this swaps in where the bar was, so on the
    // frame the effect runs the first control may not be placed yet and requestFocus() throws into
    // the runCatching — leaving a card on screen with a dead D-pad over whatever is playing. The
    // sidebar's own first-tile focus does the same thing for the same reason.
    LaunchedEffect(entity.entityId) {
        repeat(20) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
        // A readout card never attaches [firstFocus] — [AirQualityCard] has no control to attach it
        // to — so every attempt above throws into the runCatching and focus ends up nowhere at all,
        // leaving the D-pad dead and Done unreachable. Fall back to the footer, which every card
        // has. Any read-only domain added later lands here too rather than rediscovering it.
        runCatching { footerFocus.requestFocus() }
    }
    val th = LocalOverlayTheme.current

    // See [OkBurstFilter] for what this is defending against and why it takes two tests.
    val okFilter = remember(entity.entityId) { OkBurstFilter(SystemClock.uptimeMillis()) }

    Column(
        modifier = modifier
            /*
             * Ignores the tail of the long press that opened this card.
             *
             * Opening a card is one physical press held down. `onLongClick` fires part-way through
             * it, the card replaces the tile, focus lands on the card's first control — and the
             * *rest of that same hold* then lands on the new control. Measured on the emulator: one
             * hold on a lock tile opened the card and pressed its Lock button **thirteen times**
             * over 280ms; a cover's Open fired five times. On a real door that is thirteen commands
             * nobody asked for.
             *
             * It has to be a quiet period rather than a structural test. The first attempt here
             * tried the exact rule "an OK-up with no preceding OK-down cannot be ours" — and it
             * does not work, because the held key arrives as discrete down/up pairs each carrying
             * `repeatCount == 0`. Every repeat is byte-for-byte indistinguishable from a deliberate
             * fresh press, so nothing about a single event can classify it. Only the timing can.
             *
             * So: OK is swallowed until it has been quiet for [CARD_SETTLE_MS], and each swallowed
             * event pushes that window out, which makes the guard independent of how long the key
             * is held. The first press that gets through disarms it permanently, so quick repeat
             * presses inside an open card are never slowed. A key-up is always judged by whether
             * its own key-down was let through, never re-tested, or a legitimate press would be
             * split — androidx.tv's Surface fires its onClick on the UP.
             */
            .onPreviewKeyEvent { e ->
                if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) {
                    false
                } else when (e.type) {
                    KeyEventType.KeyDown -> okFilter.consumeDown(SystemClock.uptimeMillis())
                    KeyEventType.KeyUp -> okFilter.consumeUp(SystemClock.uptimeMillis())
                    else -> false
                }
            }
            // Same chain as the bar's own panel, and for the same reason: a card taller than the
            // slot it was given scrolls instead of being clipped. A light with brightness, warmth,
            // hue and saturation gets there on its own at a large size setting.
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // A locked door reads as the "active/secure" (green) state, like on for other domains.
        val activeGreen = if (entity.isLock) entity.isLocked else entity.isOn
        // Header: icon chip + name + status.
        Row(verticalAlignment = Alignment.CenterVertically) {
            HaIconChip(
                icon = domainIcon(entity),
                on = activeGreen || entity.domain == "climate",
                tint = stateTint(entity),
                size = 40,
                iconContent = { tint -> EntityIconContent(entity, null, tint, 21, repository = actions.repository) },
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(entity.friendlyName, color = th.text, fontSize = 17.sp, maxLines = 1)
                Text(entity.entityId, color = th.subText, fontSize = 11.sp, maxLines = 1)
            }
            // Blank means the card puts the reading in its own body - see [AirQualityCard], whose
            // headline *is* the status. Drawing it here as well printed the same number twice and
            // squeezed the name down to an ellipsis on a 320dp column.
            val headerStatus = entityStatus(entity, compact = false)
            if (headerStatus.isNotBlank()) {
                Text(
                    text = headerStatus,
                    // Green stays semantic (on/locked); the inactive case follows the palette.
                    color = if (activeGreen) Color(0xFF6FCF7F) else th.subText,
                    fontSize = 14.sp,
                )
            }
        }

        cardFor(entity).Controls(entity, actions, firstFocus)

        Spacer(Modifier.height(2.dp))
        // Centred: every card's body is centred or full-width, and a footer pinned left read as
        // the one thing on the card that had been forgotten about.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            // Only where the card's own body offers no way to turn the thing on or off. See
            // [EntityCard.ownsToggle] for what this condition used to be and why it was wrong.
            if (entity.isToggleable && !cardFor(entity).ownsToggle(entity)) {
                AccentButton("Toggle", { actions.toggle(entity) })
            }
            // "Done", not "Close": a cover's own action is Close, and a card carrying both had two
            // buttons with the same word meaning opposite things — one shuts the blind, one dismisses
            // the card. The generic one is the one that gives way.
            AccentButton("Done", onDismiss, modifier = Modifier.focusRequester(footerFocus))
        }
    }
}
