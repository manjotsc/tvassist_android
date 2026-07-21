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
    /** Reload the URL when the clip ends, for "rolling clip" cameras that return a short finite MP4
     *  per request (e.g. Québec 511) instead of a continuous stream — keeps them live. */
    val refresh: Boolean = false,
)
