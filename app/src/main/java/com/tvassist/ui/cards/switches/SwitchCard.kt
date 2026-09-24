package com.tvassist.ui.cards.switches

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.tvassist.data.ha.Entity
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.cards.EntityCard
import com.tvassist.ui.cards.EntityControlActions

// The package is `switches`, not `switch`, only because `switch` is a reserved word in Java and a
// package segment becomes a JVM package name. Everything else follows the usual convention.

private val TRACK_W = 132.dp
private val TRACK_H = 68.dp
private val KNOB = 56.dp
private val KNOB_PAD = 6.dp

/**
 * The switch itself: a pill whose knob slides between the two ends.
 *
 * One focusable thing, so OK flips it and nothing else on the card competes for the press. The
 * position and the colour both carry the state, which is what makes it readable from a sofa in a
 * way a button labelled with the action it will perform is not.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BigSwitch(on: Boolean, onFlip: () -> Unit, modifier: Modifier = Modifier) {
    val th = LocalOverlayTheme.current
    // Deliberately not `by`: read inside the offset lambda below, the animation drives layout
    // alone instead of recomposing this subtree on every one of its frames.
    val knobX = animateDpAsState(
        targetValue = if (on) TRACK_W - KNOB - KNOB_PAD else KNOB_PAD,
        animationSpec = tween(180),
        label = "knob",
    )
    val trackColor by animateColorAsState(
        targetValue = if (on) th.accent else th.segmentItem,
        animationSpec = tween(180),
        label = "track",
    )
    Surface(
        onClick = onFlip,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(TRACK_H / 2)),
        // The track draws its own colour below, and it animates; letting the Surface paint one too
        // would put a second, unanimated pill behind it that shows at the corners.
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent,
            contentColor = th.text,
            focusedContentColor = th.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                BorderStroke(2.dp, th.focus),
                shape = RoundedCornerShape(TRACK_H / 2),
            ),
        ),
    ) {
        Box(
            modifier = Modifier
                .width(TRACK_W)
                .height(TRACK_H)
                .clip(RoundedCornerShape(TRACK_H / 2))
                .background(trackColor),
            // contentAlignment, not a Row with verticalAlignment: a fixed-height box does not
            // centre its content on its own, and the knob would sit top-start inside the track.
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .offset { IntOffset(knobX.value.roundToPx(), 0) }
                    .size(KNOB)
                    .clip(CircleShape)
                    .background(Color.White)
                    // Outlined for the same reason the colour-slider thumb is: on a light palette
                    // the off-track is pale and a bare white knob disappears into it.
                    .border(2.dp, Color(0x33000000), CircleShape),
            )
        }
    }
}

/**
 * The `switch` and `input_boolean` domains — entities that are a boolean and nothing else.
 *
 * They used to fall through to [com.tvassist.ui.cards.generic.GenericCard], which said the same
 * thing three times: the header's status, a `State: On` line under it, and then *two* buttons —
 * `Turn off` from the generic body and `Toggle` from the card footer — that were the identical
 * `toggle` call under different labels, because neither knew about the other.
 *
 * Sends `turn_on` / `turn_off` rather than `toggle`: the press acts on the state shown on screen,
 * so a stale value cannot invert what the button appeared to promise.
 */
internal object SwitchCard : EntityCard {
    override val domains = setOf("switch", "input_boolean")

    /** The switch is the on/off control; the footer must not add a second one. */
    override fun ownsToggle(e: Entity): Boolean = true

    /**
     * Icon tap, and nothing else.
     *
     * The tile is a button: one press flips it, hold opens the card. Nothing about this
     * domain
     * belongs on a sidebar tile beyond that, so there is no Compact or Full rung to offer — those
     * carry a domain's controls, and a switch's whole control surface is the on/off it has.
     *
     * `Auto` is untouched and stays the labelled row, so no existing tile changes appearance.
     */
    override fun variants(e: Entity) = listOf(OverlayTile.STYLE_ICON)

    @Composable
    override fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) {
        Column(
            // Weighted toward the bottom of the card rather than tucked under the header. A switch
            // card holds one control, so it is mostly air; hanging that control off the title line
            // left the space pooled underneath and the composition looking top-heavy.
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BigSwitch(
                on = e.isOn,
                onFlip = { if (e.isOn) actions.turnOff(e) else actions.turnOn(e) },
                modifier = Modifier.focusRequester(firstFocus),
            )
        }
    }
}
