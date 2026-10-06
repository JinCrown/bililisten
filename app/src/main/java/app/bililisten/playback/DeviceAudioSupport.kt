package app.bililisten.playback

import android.media.MediaCodecList
import android.media.MediaFormat
import app.bililisten.shared.AudioQuality
import app.bililisten.shared.AudioTrack

/** Decoder preflight; does not claim lossless or Atmos rendering on the physical output route. */
object DeviceAudioSupport {
    fun supports(track: AudioTrack): Boolean {
        val mime = AudioQuality.decoderMime(track) ?: return false
        return try {
            val format = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, mime)
                track.sampleRate?.let { setInteger(MediaFormat.KEY_SAMPLE_RATE, it) }
                track.channels?.let { setInteger(MediaFormat.KEY_CHANNEL_COUNT, it) }
            }
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
        } catch (_: Exception) { false }
    }
}
