package com.tvassist.ui.cards

import com.tvassist.data.settings.LocalCamera
import com.tvassist.ui.cards.camera.VideoEngine
import com.tvassist.ui.cards.camera.cameraPlayer
import com.tvassist.ui.cards.camera.firstEngine
import com.tvassist.ui.cards.camera.vlcAfterExoFailure
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #3's follow-ups: direct cameras can follow the global player, and Auto prefers ExoPlayer. */
class PlayerChoiceTest {

    // --- direct cameras and the global setting -------------------------------------------------------

    @Test fun aNewCameraFollowsTheGlobalPlayer() {
        val cam = LocalCamera(id = "a", name = "Door", streamUrl = "rtsp://x/1")
        assertEquals(LocalCamera.PLAYER_DEFAULT, cam.player)
        assertEquals("exoplayer", cameraPlayer(cam.player, global = "exoplayer"))
        assertEquals("vlc", cameraPlayer(cam.player, global = "vlc"))
    }

    @Test fun aCameraWithItsOwnPlayerKeepsIt() {
        assertEquals("vlc", cameraPlayer("vlc", global = "exoplayer"))
        assertEquals("auto", cameraPlayer("auto", global = "vlc"))
    }

    /**
     * The upgrade promise: a camera saved before "default" existed keeps the player it was saved
     * with. Stored JSON always carries `player` (encodeDefaults), so it never reads as the new default.
     */
    @Test fun aCameraSavedByAnOlderVersionKeepsItsPlayer() {
        val json = Json { ignoreUnknownKeys = true }
        val stored = """{"id":"a","name":"Door","streamUrl":"rtsp://x/1","player":"auto"}"""
        assertEquals("auto", json.decodeFromString(LocalCamera.serializer(), stored).player)
    }

    // --- which engine Auto picks ----------------------------------------------------------------------

    @Test fun autoPrefersExoPlayerWhenTheDecoderIsFree() {
        assertEquals(VideoEngine.EXOPLAYER, firstEngine("auto", decoderBusy = false))
    }

    /** ExoPlayer cannot decode without the hardware decoder; VLC can, in software, behind its size guard. */
    @Test fun autoPicksVlcWhileAnotherAppHoldsTheDecoder() {
        assertEquals(VideoEngine.VLC, firstEngine("auto", decoderBusy = true))
    }

    @Test fun anExplicitPlayerIsAlwaysHonoured() {
        assertEquals(VideoEngine.EXOPLAYER, firstEngine("exoplayer", decoderBusy = true))
        assertEquals(VideoEngine.VLC, firstEngine("vlc", decoderBusy = false))
    }

    // --- the second chance ---------------------------------------------------------------------------

    @Test fun autoRetriesInVlcWhenTheStreamDefeatedExoPlayer() {
        assertTrue(vlcAfterExoFailure("auto", decoderError = false, hasDecoderLadder = true))
        assertTrue(vlcAfterExoFailure("auto", decoderError = false, hasDecoderLadder = false))
    }

    /** The camera screen's low-res ladder handles decoder failures; a notification has none, so VLC is its only other chance. */
    @Test fun aDecoderFailureGoesDownTheLadderWhereThereIsOne() {
        assertFalse(vlcAfterExoFailure("auto", decoderError = true, hasDecoderLadder = true))
        assertTrue(vlcAfterExoFailure("auto", decoderError = true, hasDecoderLadder = false))
    }

    @Test fun anExplicitExoPlayerIsNeverSwitchedBehindTheUsersBack() {
        assertFalse(vlcAfterExoFailure("exoplayer", decoderError = false, hasDecoderLadder = false))
    }
}
