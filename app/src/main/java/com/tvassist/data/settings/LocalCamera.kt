package com.tvassist.data.settings

import kotlinx.serialization.Serializable

/**
 * A camera defined in the app (not in Home Assistant), pointing straight at a stream URL so the
 * TV can play it directly — avoiding Home Assistant's HLS start-up delay.
 */
@Serializable
data class LocalCamera(
    /** Stable slug, used to build the synthetic entity id "camera.ta_<id>". */
    val id: String,
    val name: String,
    /** rtsp://…, an HLS .m3u8, or a progressive http(s) video URL (creds may be embedded). */
    val streamUrl: String,
    /** Optional still-image URL (JPEG snapshot) for the tile preview + snapshot-first playback. */
    val snapshotUrl: String = "",
    /** auto / exoplayer / vlc. */
    val player: String = "auto",
    /** Reload the URL when the clip ends, for "rolling clip" cameras that return a short looped
     *  video per request instead of a continuous stream — keeps them live. */
    val refresh: Boolean = false,
    /**
     * Optional smaller stream from the same camera (a Tapo's `/stream2`, a Hikvision's `/102`),
     * played instead of [streamUrl] while another app holds the TV's hardware video decoder.
     *
     * That is the whole of its job. While Netflix (or any DRM app) has a video open — even paused —
     * this TV refuses every other app a hardware decoder, so a camera decodes in software. Measured
     * on the UR3: the 4K main stream froze, dropped 32% of frames and took the TV's memory down
     * with it within a minute; this camera's `/stream2`, in software beside Netflix, played 92 s
     * with one dropped frame. Blank means none, and a busy decoder then shows the camera's snapshot
     * instead of live video (see CameraSource.UNAVAILABLE).
     */
    val lowResUrl: String = "",
)
