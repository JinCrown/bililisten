package app.bililisten.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** Navigation may duck the music; exclusive focus loss must leave listening paused. */
@UnstableApi
internal fun configureListeningAudioFocus(player: ExoPlayer, pauseForFocusLoss: () -> Unit) {
    // MUSIC allows Android's automatic duck/unduck ramps for navigation MAY_DUCK requests.
    // Media3 also handles CAN_DUCK callbacks on devices that do not duck in the system mixer.
    player.setAudioAttributes(
        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
        true,
    )
    player.addListener(object : Player.Listener {
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            // Ducking only changes output gain; it does not suppress playback. For an interruption
            // that requires silence, make the pause explicit so focus return cannot start music.
            if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE && player.playWhenReady) {
                pauseForFocusLoss()
            }
        }
    })
}
