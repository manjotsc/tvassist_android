package com.tvassist.ui.cards.camera

import com.tvassist.data.settings.LocalCamera

/**
 * Which of the two video engines plays a stream. One place, because the camera screen
 * (`CameraPlayer`) and everything else (`StreamVideo`: notifications, sidebar tiles) used to carry
 * their own copy of the rule, and a rule that differs between the tile and its full-screen view
 * reads as a broken camera.
 */
internal enum class VideoEngine { EXOPLAYER, VLC }

/**
 * A direct camera's stored player, with "follow the default" resolved against the global setting.
 *
 * Issue #3: direct cameras used to ignore the global setting entirely, so a user switching to
 * ExoPlayer for seven go2rtc cameras had to edit all seven. New cameras now default to
 * [LocalCamera.PLAYER_DEFAULT]; existing ones keep the explicit value they were saved with.
 */
internal fun cameraPlayer(stored: String, global: String): String =
    if (stored.equals(LocalCamera.PLAYER_DEFAULT, ignoreCase = true)) global else stored

/**
 * The engine a stream starts on, for a player setting of auto / exoplayer / vlc.
 *
 * **Auto prefers ExoPlayer whenever the hardware decoder is free.** It used to send every `rtsp://`
 * stream to VLC, which was right while ExoPlayer could not reach go2rtc (#3, fixed by forcing TCP);
 * since then ExoPlayer played streams VLC failed on, with audio VLC never gave, and its decoder
 * failures step down to the low-res stream cleanly (`afterDecoderFailure`).
 *
 * **With the decoder taken, Auto still picks VLC.** ExoPlayer has no software fallback: beside
 * Netflix on a BRAVIA it can only fail, while VLC decodes a small stream in software perfectly well
 * (a camera's `/stream2`: 1 dropped frame in 92 s) and its size guard stops anything that would
 * not. Sending those to ExoPlayer would turn a doorbell that plays today into "unavailable".
 *
 * An explicit exoplayer / vlc is always honoured, busy or not.
 */
internal fun firstEngine(player: String, decoderBusy: Boolean): VideoEngine = when {
    player.equals("vlc", ignoreCase = true) -> VideoEngine.VLC
    player.equals("exoplayer", ignoreCase = true) -> VideoEngine.EXOPLAYER
    decoderBusy -> VideoEngine.VLC
    else -> VideoEngine.EXOPLAYER
}

/**
 * Whether a stream ExoPlayer just failed on should be tried again in VLC: only under Auto, and only
 * when the failure was the stream's rather than the decoder's — VLC reaches the cameras ExoPlayer
 * cannot (odd RTSP servers, codecs Media3 does not know). A decoder failure is not the stream's
 * fault and goes down the low-res ladder instead, where there is one ([hasDecoderLadder]); where
 * there is not (a notification), VLC is the only other chance and gets it, guard and all.
 */
internal fun vlcAfterExoFailure(player: String, decoderError: Boolean, hasDecoderLadder: Boolean): Boolean =
    player.equals("auto", ignoreCase = true) && (!decoderError || !hasDecoderLadder)
