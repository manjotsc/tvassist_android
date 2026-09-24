package com.tvassist.ui.cards

import com.tvassist.ui.cards.camera.CameraSource
import com.tvassist.ui.cards.camera.chooseCameraSource
import com.tvassist.ui.cards.camera.tooLargeForSoftware
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a camera plays when another app (Netflix) holds the TV's hardware decoder. Measured on the
 * UR3: a camera's small stream in software played beside Netflix with 1 frame dropped in 92 s; its
 * 4K stream in software dropped 32% and locked the TV up. These pin the choice that follows.
 */
class CameraSourceTest {

    @Test fun aFreeDecoderAlwaysPlaysTheMainStream() {
        // Nothing changes for the ordinary case, whatever else the camera has configured.
        assertEquals(CameraSource.MAIN, chooseCameraSource(decoderBusy = false, hasLowRes = true, inOverlay = true))
        assertEquals(CameraSource.MAIN, chooseCameraSource(decoderBusy = false, hasLowRes = false, inOverlay = false))
    }

    @Test fun aBusyDecoderPrefersTheSmallStreamSoNetflixKeepsPlaying() {
        assertEquals(CameraSource.LOW_RES, chooseCameraSource(decoderBusy = true, hasLowRes = true, inOverlay = true))
        // In TV Assist's own screen too: the small stream is the one that plays without a fight.
        assertEquals(CameraSource.LOW_RES, chooseCameraSource(decoderBusy = true, hasLowRes = true, inOverlay = false))
    }

    @Test fun withNoSmallStreamTheOverlayShowsSnapshotsNotFourK() {
        // Not "take the screen for 4K": measured, 4K beside a Netflix still loaded in memory stalled
        // the whole TV for a minute. The overlay never asks for that.
        assertEquals(CameraSource.UNAVAILABLE, chooseCameraSource(decoderBusy = true, hasLowRes = false, inOverlay = true))
    }

    @Test fun tvAssistsOwnScreenWaitsForTheDecoderInstead() {
        // There the other app has just gone to the background and is about to release it.
        assertEquals(CameraSource.MAIN_GUARDED, chooseCameraSource(decoderBusy = true, hasLowRes = false, inOverlay = false))
    }

    @Test fun fourKIsTooLargeForSoftwareAndTheMeasuredSmallStreamsAreNot() {
        assertTrue(tooLargeForSoftware(3840, 2160))
        assertTrue(tooLargeForSoftware(2560, 1440))
        assertFalse(tooLargeForSoftware(1920, 1080))
        assertFalse(tooLargeForSoftware(1920, 1088)) // 1080p as a decoder aligns it
        assertFalse(tooLargeForSoftware(1280, 720))
        assertFalse(tooLargeForSoftware(352, 240)) // the short looped videos measured beside Netflix
    }

    @Test fun anUnknownSizeIsNotTreatedAsTooLarge() {
        // libVLC reports 0x0 until it has parsed the stream; the guard keeps looking instead.
        assertFalse(tooLargeForSoftware(0, 0))
        assertFalse(tooLargeForSoftware(3840, 0))
    }
}
