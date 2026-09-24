package com.tvassist.ui.cards.media

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.tvassist.data.ha.Entity
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.LocalOverlayTheme
import com.tvassist.ui.cap
import com.tvassist.ui.cards.AdjustableSliderRow
import com.tvassist.ui.cards.EntityCard
import com.tvassist.ui.cards.EntityControlActions
import com.tvassist.ui.cards.ModeContentButton
import com.tvassist.ui.cards.ModeIconButton
import com.tvassist.ui.cards.ModeTextRow
import com.tvassist.ui.cards.thenKeepInRow
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The `media_player` domain.
 *
 * It fell through to [com.tvassist.ui.cards.generic.GenericCard] until now, which offered one
 * "Turn on" button — so the only thing you could do to a playing speaker from the overlay was
 * switch it off. Every control here is gated on its own `MediaPlayerEntityFeature` bit, because the
 * domain covers a Chromecast, an AV receiver and a TV, and almost nothing is common to all three.
 */
internal object MediaPlayerCard : EntityCard {
    override val domains = setOf("media_player")

    /**
     * A press opens the card rather than acting.
     *
     * Every other domain's tile does something on a press, but a media player is too stateful to
     * poke blind: `homeassistant.toggle` on a playing speaker silences the room, and play/pause on
     * one that is idle does nothing visible. What you want first is to see what is on.
     */
    override fun pressOpens(e: Entity): Boolean = true

    /**
     * Icon tap, Compact, Full.
     *
     * - **Icon tap** is the artwork as a square button — press opens the card.
     * - **Compact** puts previous / play-pause / next beside the header, the three you reach for
     *   without looking.
     * - **Full** adds volume on a second line.
     *
     * Source, sound mode, shuffle, repeat and seek stay on the card: each is a scrolling row or a
     * slider, and a tile that carried them all would be taller than the panel.
     */
    override fun variants(e: Entity) = listOf(
        OverlayTile.STYLE_ICON,
        OverlayTile.STYLE_COMPACT,
        OverlayTile.STYLE_FULL,
    )

    /** The card draws its own power buttons where the player reports them. */
    override fun ownsToggle(e: Entity): Boolean = e.supportsMediaTurnOn || e.supportsMediaTurnOff

    /**
     * What is playing, not what the player is.
     *
     * The card header already prints the state ("Playing"), so repeating it here would waste the
     * one line that can say something useful. A title falls back to the app name — a TV on Netflix
     * reports no `media_title` until something starts.
     */
    override fun status(e: Entity, compact: Boolean): String {
        val title = e.mediaTitle ?: e.appName
        return when {
            title == null -> cap(e.state)
            !compact -> ""
            e.mediaArtist != null -> "${e.mediaArtist} · $title"
            else -> title
        }
    }

    @Composable
    override fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) =
        MediaControls(e, actions, firstFocus, trapHorizontal = true, full = true)

    /**
     * Compact: three transport keys beside the header, never five.
     *
     * The trailing slot shares its line with the header, which takes the remaining width — so every
     * key added here is width taken from the name and whatever is playing. All five squeezed the
     * header to nothing: the tile drew a row of buttons and no text at all, on a tile whose labels
     * were not even hidden. Stop and mute stay on the card and the Full rung, which have a line of
     * their own to spend.
     */
    @Composable
    override fun TileTrailing(e: Entity, actions: EntityControlActions, style: String) {
        if (style != OverlayTile.STYLE_COMPACT) return
        TransportButtons(e, actions, null, trapHorizontal = false, only = TRANSPORT_ESSENTIAL)
    }

    /** Full: transport on its own line, then volume. */
    @Composable
    override fun TileControls(e: Entity, actions: EntityControlActions, style: String) {
        if (style != OverlayTile.STYLE_FULL) return
        MediaControls(e, actions, null, trapHorizontal = false, full = false)
    }
}

/**
 * The card's body, and the Full tile's.
 *
 * [full] is what separates them: the tile gets what fits — now-playing, transport, volume — and the
 * card gets everything. They share one function so the two cannot describe different players, the
 * same arrangement climate and fan use.
 */
@Composable
private fun MediaControls(
    e: Entity,
    actions: EntityControlActions,
    firstFocus: FocusRequester?,
    trapHorizontal: Boolean,
    full: Boolean,
) {
    val th = LocalOverlayTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (full) {
            val title = e.mediaTitle ?: e.appName
            if (title != null) {
                Column {
                    Text(title, color = th.text, fontSize = 15.sp, maxLines = 2)
                    // Artist, then album, then the app — the most specific thing the player knows.
                    val under = e.mediaArtist ?: e.mediaAlbumName ?: e.mediaSeriesTitle ?: e.appName
                    if (under != null && under != title) {
                        Text(under, color = th.subText, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(th.segmentBg)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            TransportButtons(e, actions, firstFocus, trapHorizontal)
        }

        if (full && e.supportsMediaSeek && (e.mediaDuration ?: 0.0) > 0.0) {
            SeekRow(e, actions)
        }

        if (e.supportsVolumeSet) {
            // Full width: mute lives in the transport row, so nothing shares this line. The label
            // still says muted, because that row can be a long way up the card and a slider sitting
            // at 100% on a silent speaker is the one reading worth spelling out.
            AdjustableSliderRow(
                label = if (e.isVolumeMuted) "Volume (muted)" else "Volume",
                value = ((e.volumeLevel ?: 0.0) * 100).coerceIn(0.0, 100.0),
                min = 0.0, max = 100.0, step = 5.0,
                valueLabel = { "${it.roundToInt()}%" },
                // HA takes 0-1; the row speaks percent because that is what a person reads.
                onCommit = { actions.setVolume(e, it / 100.0) },
                resetKey = e.entityId,
            )
        }

        if (full) {
            if (e.supportsSelectSource) {
                ModeTextRow(
                    label = "Source",
                    items = e.sourceList,
                    selected = e.source,
                    onSelect = { actions.selectSource(e, it) },
                    trapHorizontal = trapHorizontal,
                )
            }
            if (e.supportsSelectSoundMode) {
                ModeTextRow(
                    label = "Sound",
                    items = e.soundModeList,
                    selected = e.soundMode,
                    onSelect = { actions.selectSoundMode(e, it) },
                    trapHorizontal = trapHorizontal,
                )
            }
            PlaybackRow(e, actions, trapHorizontal)
        }
    }
}

/**
 * Previous · play/pause · stop · next · mute, each drawn only where the player claims its bit.
 *
 * Built as a list rather than five nested `if`s, because the keep-in-row edges depend on what
 * actually rendered: the old version had each button naming every button that might follow it to
 * decide whether it was last, and mute was in a different place depending on whether the player
 * could also set a volume level.
 *
 * Mute belongs here for the same reason it does on a remote — it is an audio key, not a setting —
 * and the row already carries one sticky state in play/pause, so a lit mute is nothing new.
 */
@Composable
private fun TransportButtons(
    e: Entity,
    actions: EntityControlActions,
    firstFocus: FocusRequester?,
    trapHorizontal: Boolean,
    /** Which keys may appear. [TRANSPORT_ESSENTIAL] is the set that fits beside a tile header. */
    only: Set<String> = TRANSPORT_ALL,
) {
    val keys = buildList {
        if (PREVIOUS in only && e.supportsMediaPrevious) {
            add(TransportKey(Icons.Rounded.SkipPrevious, false) { actions.previousTrack(e) })
        }
        if (PLAY_PAUSE in only && e.supportsPlayPause) {
            val icon = if (e.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
            // The glyph is what the press will *do*; the fill is what the player is doing. Two
            // conventions in one button, kept deliberately: every remote draws it this way.
            add(TransportKey(icon, e.isPlaying) { actions.playPause(e) })
        }
        if (STOP in only && e.supportsMediaStop) {
            add(TransportKey(Icons.Rounded.Stop, false) { actions.stopMedia(e) })
        }
        if (NEXT in only && e.supportsMediaNext) {
            add(TransportKey(Icons.Rounded.SkipNext, false) { actions.nextTrack(e) })
        }
        if (MUTE in only && e.supportsVolumeMute) {
            val icon = if (e.isVolumeMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp
            // Larger on purpose — see ModeIconButton.iconSize. The speaker is drawn small inside
            // its viewport and matched the transport keys at about two-thirds their width.
            add(TransportKey(icon, e.isVolumeMuted, MUTE_ICON) { actions.setMuted(e, !e.isVolumeMuted) })
        }
    }
    keys.forEachIndexed { i, key ->
        ModeIconButton(
            icon = key.icon,
            selected = key.selected,
            onClick = key.onClick,
            iconSize = key.iconSize,
            modifier = Modifier
                .then(
                    if (i == 0 && firstFocus != null) {
                        Modifier.focusRequester(firstFocus)
                    } else {
                        Modifier
                    },
                )
                .thenKeepInRow(trapHorizontal, isFirst = i == 0, isLast = i == keys.lastIndex),
        )
    }
}

/** One transport key, so the row can be a list and the edges fall out of its size. */
private class TransportKey(
    val icon: ImageVector,
    val selected: Boolean,
    val iconSize: Dp = 20.dp,
    val onClick: () -> Unit,
)

/** The speaker, drawn up to the optical weight of the transport glyphs beside it. */
private val MUTE_ICON = 24.dp

/**
 * Position within the track, ticking while it plays.
 *
 * Home Assistant does not stream the position. It publishes `media_position` with the instant that
 * reading was taken and expects the client to add the wall time since — which is why a progress bar
 * built from the attribute alone sits frozen at wherever the track was when it started.
 */
@Composable
private fun SeekRow(e: Entity, actions: EntityControlActions) {
    var tick by remember(e.entityId) { mutableIntStateOf(0) }
    LaunchedEffect(e.entityId, e.state, e.mediaPositionUpdatedAt) {
        while (e.isPlaying) {
            delay(1000)
            tick++
        }
    }
    val duration = e.mediaDuration ?: 0.0
    val position = remember(e.mediaPosition, e.mediaPositionUpdatedAt, e.state, tick) {
        livePosition(e).coerceIn(0.0, duration)
    }
    AdjustableSliderRow(
        label = "Position",
        value = position,
        min = 0.0, max = duration,
        // Ten seconds a press, so crossing a long track does not need forty of them.
        step = 10.0,
        valueLabel = { clock(it) + " / " + clock(duration) },
        onCommit = { actions.seek(e, it) },
        resetKey = e.entityId,
    )
}

/** [Entity.mediaPosition] advanced by the time since HA took it, or 0 when it reports none. */
internal fun livePosition(e: Entity, nowMillis: Long = System.currentTimeMillis()): Double {
    val base = e.mediaPosition ?: return 0.0
    if (!e.isPlaying) return base
    val takenAt = e.mediaPositionUpdatedAt?.let {
        runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    } ?: return base
    return base + ((nowMillis - takenAt).coerceAtLeast(0L) / 1000.0)
}

/** Seconds as m:ss, or h:mm:ss once a track runs past the hour. */
internal fun clock(seconds: Double): String {
    val total = seconds.roundToLong().coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Shuffle and repeat on one line, because they are one question: how the queue behaves.
 *
 * They were two labelled rows — 130dp to set two things nobody changes mid-song. Shuffle stays a
 * chip (it is on or it is not) and repeat stays three segments (off / all / one cannot be a chip
 * that is merely on), with a gap between so the two do not read as one four-way choice.
 *
 * The heading follows what is actually drawn: a player with only one of the two bits gets that
 * word, rather than "Playback" over a single control.
 */
@Composable
private fun PlaybackRow(e: Entity, actions: EntityControlActions, trapHorizontal: Boolean) {
    if (!e.supportsShuffleSet && !e.supportsRepeatSet) return
    val th = LocalOverlayTheme.current
    val modes = listOf(OFF, "all", "one")
    Column {
        Text(
            text = when {
                e.supportsShuffleSet && e.supportsRepeatSet -> "Playback"
                e.supportsShuffleSet -> "Shuffle"
                else -> "Repeat"
            },
            color = th.subText,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(th.segmentBg)
                .horizontalScroll(rememberScrollState())
                .padding(6.dp),
        ) {
            if (e.supportsShuffleSet) {
                ToggleChip(
                    label = "Shuffle",
                    on = e.shuffle,
                    onClick = { actions.setShuffle(e, !e.shuffle) },
                    modifier = Modifier.padding(horizontal = 3.dp).thenKeepInRow(
                        trapHorizontal,
                        isFirst = true,
                        isLast = !e.supportsRepeatSet,
                    ),
                )
                if (e.supportsRepeatSet) Spacer(Modifier.width(10.dp))
            }
            if (e.supportsRepeatSet) {
                modes.forEachIndexed { i, mode ->
                    ModeContentButton(
                        selected = mode.equals(e.repeatMode ?: OFF, ignoreCase = true),
                        // HA's RepeatMode, sent verbatim.
                        onClick = { actions.setRepeat(e, mode) },
                        modifier = Modifier.padding(horizontal = 3.dp).thenKeepInRow(
                            trapHorizontal,
                            isFirst = i == 0 && !e.supportsShuffleSet,
                            isLast = i == modes.lastIndex,
                        ),
                    ) { c -> Text(cap(mode), color = c, fontSize = 13.sp) }
                }
            }
        }
    }
}

/** A sticky yes/no as one chip: lit is on, and the word is what it is rather than its state. */
@Composable
private fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier) {
    ModeContentButton(selected = on, onClick = onClick, modifier = modifier) { c ->
        Text(label, color = c, fontSize = 13.sp)
    }
}

private const val OFF = "off"

private const val PREVIOUS = "previous"
private const val PLAY_PAUSE = "play_pause"
private const val STOP = "stop"
private const val NEXT = "next"
private const val MUTE = "mute"

/** Everything the player claims, for a row with a line to itself. */
private val TRANSPORT_ALL = setOf(PREVIOUS, PLAY_PAUSE, STOP, NEXT, MUTE)

/** What fits beside a tile header without crushing it: skip back, play/pause, skip on. */
private val TRANSPORT_ESSENTIAL = setOf(PREVIOUS, PLAY_PAUSE, NEXT)

// --- Service calls ---------------------------------------------------------------------------

internal fun EntityControlActions.playPause(e: Entity) =
    repository.callService("media_player", "media_play_pause", e.entityId)

internal fun EntityControlActions.stopMedia(e: Entity) =
    repository.callService("media_player", "media_stop", e.entityId)

internal fun EntityControlActions.nextTrack(e: Entity) =
    repository.callService("media_player", "media_next_track", e.entityId)

internal fun EntityControlActions.previousTrack(e: Entity) =
    repository.callService("media_player", "media_previous_track", e.entityId)

/** [level] is HA's 0.0-1.0, not percent. */
internal fun EntityControlActions.setVolume(e: Entity, level: Double) = repository.callService(
    "media_player", "volume_set", e.entityId,
    mapOf("volume_level" to JsonPrimitive(level.coerceIn(0.0, 1.0))),
)

internal fun EntityControlActions.setMuted(e: Entity, muted: Boolean) = repository.callService(
    "media_player", "volume_mute", e.entityId,
    mapOf("is_volume_muted" to JsonPrimitive(muted)),
)

internal fun EntityControlActions.selectSource(e: Entity, source: String) = repository.callService(
    "media_player", "select_source", e.entityId,
    mapOf("source" to JsonPrimitive(source)),
)

internal fun EntityControlActions.selectSoundMode(e: Entity, mode: String) = repository.callService(
    "media_player", "select_sound_mode", e.entityId,
    mapOf("sound_mode" to JsonPrimitive(mode)),
)

internal fun EntityControlActions.setShuffle(e: Entity, on: Boolean) = repository.callService(
    "media_player", "shuffle_set", e.entityId,
    mapOf("shuffle" to JsonPrimitive(on)),
)

internal fun EntityControlActions.setRepeat(e: Entity, mode: String) = repository.callService(
    "media_player", "repeat_set", e.entityId,
    mapOf("repeat" to JsonPrimitive(mode)),
)

/** [seconds] from the start of the track. */
internal fun EntityControlActions.seek(e: Entity, seconds: Double) = repository.callService(
    "media_player", "media_seek", e.entityId,
    mapOf("seek_position" to JsonPrimitive(seconds.coerceAtLeast(0.0))),
)
