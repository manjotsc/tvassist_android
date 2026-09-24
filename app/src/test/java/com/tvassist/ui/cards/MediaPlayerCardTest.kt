package com.tvassist.ui.cards

import com.tvassist.data.ha.Entity
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.cards.media.MediaPlayerCard
import com.tvassist.ui.cards.media.clock
import com.tvassist.ui.cards.media.livePosition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media player card, whose every control is gated on a feature bit because the domain covers a
 * speaker, an AV receiver and a TV and almost nothing is common to all three.
 */
class MediaPlayerCardTest {

    private fun player(
        state: String = "playing",
        features: Int = 0,
        title: String? = null,
        artist: String? = null,
        app: String? = null,
        position: Double? = null,
        updatedAt: String? = null,
        duration: Double? = null,
        volume: Double? = null,
        muted: Boolean? = null,
        sources: List<String> = emptyList(),
    ) = Entity(
        entityId = "media_player.living_room",
        state = state,
        friendlyName = "Living Room",
        attributes = buildJsonObject {
            put("supported_features", features)
            title?.let { put("media_title", it) }
            artist?.let { put("media_artist", it) }
            app?.let { put("app_name", it) }
            position?.let { put("media_position", it) }
            updatedAt?.let { put("media_position_updated_at", it) }
            duration?.let { put("media_duration", it) }
            volume?.let { put("volume_level", it) }
            muted?.let { put("is_volume_muted", it) }
            if (sources.isNotEmpty()) {
                put("source_list", JsonArray(sources.map { JsonPrimitive(it) }))
                put("source", sources.first())
            }
        },
    )

    // ---- the position clock ----

    /**
     * HA publishes a fixed reading plus the instant it was taken; the client adds the wall time
     * since. A bar built from `media_position` alone sits frozen wherever the track started.
     */
    @Test fun aPlayingTrackAdvancesFromWhenHaLastLooked() {
        val taken = "2026-09-22T03:00:00+00:00"
        val takenMs = java.time.OffsetDateTime.parse(taken).toInstant().toEpochMilli()
        val e = player(state = "playing", position = 30.0, updatedAt = taken, duration = 200.0)
        assertEquals(30.0, livePosition(e, takenMs), 0.01)
        assertEquals(42.0, livePosition(e, takenMs + 12_000), 0.01)
    }

    @Test fun aPausedTrackDoesNotAdvance() {
        val taken = "2026-09-22T03:00:00+00:00"
        val takenMs = java.time.OffsetDateTime.parse(taken).toInstant().toEpochMilli()
        val e = player(state = "paused", position = 30.0, updatedAt = taken, duration = 200.0)
        assertEquals(30.0, livePosition(e, takenMs + 60_000), 0.01)
    }

    /** A player that reports a position and no timestamp, or neither, must not throw or drift. */
    @Test fun missingOrUnparseableTimingsFallBackToWhatIsKnown() {
        assertEquals(0.0, livePosition(player(position = null)), 0.01)
        assertEquals(30.0, livePosition(player(position = 30.0, updatedAt = null)), 0.01)
        assertEquals(30.0, livePosition(player(position = 30.0, updatedAt = "not a date")), 0.01)
    }

    /** A clock skewed behind HA must never wind a track backwards. */
    @Test fun aClockBehindHaDoesNotRewind() {
        val taken = "2026-09-22T03:00:00+00:00"
        val takenMs = java.time.OffsetDateTime.parse(taken).toInstant().toEpochMilli()
        val e = player(state = "playing", position = 30.0, updatedAt = taken)
        assertEquals(30.0, livePosition(e, takenMs - 5_000), 0.01)
    }

    @Test fun timesReadAsTimes() {
        assertEquals("0:00", clock(0.0))
        assertEquals("0:07", clock(7.4))
        assertEquals("3:05", clock(185.0))
        assertEquals("1:00:00", clock(3600.0))
        assertEquals("2:03:04", clock(7384.0))
        assertEquals("0:00", clock(-5.0))
    }

    // ---- feature gating ----

    /** MediaPlayerEntityFeature: PAUSE 1, PREVIOUS 16, NEXT 32, VOLUME_SET 4, SELECT_SOURCE 2048. */
    @Test fun eachControlAnswersToItsOwnBit() {
        val bare = player(features = 0)
        assertFalse(bare.supportsPlayPause)
        assertFalse(bare.supportsMediaNext)
        assertFalse(bare.supportsVolumeSet)
        assertFalse(bare.supportsSelectSource)

        val full = player(features = 1 or 4 or 16 or 32 or 2048)
        assertTrue(full.supportsMediaPause)
        assertTrue(full.supportsPlayPause)
        assertTrue(full.supportsMediaPrevious)
        assertTrue(full.supportsMediaNext)
        assertTrue(full.supportsVolumeSet)
        assertTrue(full.supportsSelectSource)
        assertFalse("seek was not asked for", full.supportsMediaSeek)
    }

    /** PLAY (16384) and PAUSE (1) are separate upstream; one transport button needs either. */
    @Test fun playAndPauseAreSeparateBitsButOneButton() {
        assertTrue(player(features = 1).supportsPlayPause)
        assertTrue(player(features = 16384).supportsPlayPause)
        assertFalse(player(features = 2).supportsPlayPause)
    }

    /** The bits are namespaced by domain — a light with bit 1 set must not look pausable. */
    @Test fun theBitsDoNotLeakAcrossDomains() {
        val light = Entity(
            entityId = "light.kitchen", state = "on", friendlyName = "Kitchen",
            attributes = buildJsonObject { put("supported_features", 1 or 4 or 32) },
        )
        assertFalse(light.supportsPlayPause)
        assertFalse(light.supportsVolumeSet)
        assertFalse(light.supportsMediaNext)
    }

    // ---- what the tile says ----

    @Test fun theTileStatusSaysWhatIsPlayingNotThatItIsPlaying() {
        assertEquals(
            "Daft Punk · Get Lucky",
            MediaPlayerCard.status(player(title = "Get Lucky", artist = "Daft Punk"), compact = true),
        )
        // A TV between programmes reports an app and no title.
        assertEquals("Netflix", MediaPlayerCard.status(player(app = "Netflix"), compact = true))
        // Nothing loaded at all: the plain state is all there is.
        assertEquals("Idle", MediaPlayerCard.status(player(state = "idle"), compact = true))
    }

    /** Blank in the card header, whose body prints the title at a readable size instead. */
    @Test fun theRoomyStatusDefersToTheCardBody() {
        assertEquals("", MediaPlayerCard.status(player(title = "Get Lucky"), compact = false))
    }

    // ---- the rest of the registry contract ----

    @Test fun itOffersTheFullLadder() {
        val e = player()
        assertEquals(
            listOf(OverlayTile.STYLE_ICON, OverlayTile.STYLE_COMPACT, OverlayTile.STYLE_FULL),
            MediaPlayerCard.variants(e),
        )
    }

    /** Only a player that can be powered draws its own power, so only then is the footer dropped. */
    @Test fun theFooterToggleSurvivesWhereThePlayerCannotBePowered() {
        assertFalse(MediaPlayerCard.ownsToggle(player(features = 1)))
        assertTrue(MediaPlayerCard.ownsToggle(player(features = 128)))
        assertTrue(MediaPlayerCard.ownsToggle(player(features = 256)))
    }

    /** Upstream's rule, kept: anything but standby counts as active. */
    @Test fun everythingButStandbyLightsTheTile() {
        listOf("playing", "paused", "idle", "buffering", "on").forEach {
            assertTrue(it, MediaPlayerCard.isActive(player(state = it)))
        }
        assertFalse(MediaPlayerCard.isActive(player(state = "standby")))
    }
}
