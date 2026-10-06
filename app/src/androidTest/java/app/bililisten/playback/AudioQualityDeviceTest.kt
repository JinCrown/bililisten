package app.bililisten.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.bililisten.shared.AudioQuality
import app.bililisten.shared.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioQualityDeviceTest {
    @Test fun decoderPreflightSelectsHighestSupportedAacAndRejectsUnknownCodec() {
        val low = AudioTrack(30216, "https://example.org/low", "audio/mp4", "mp4a.40.2", 64000, 44100, 2)
        val high = low.copy(id = 30280, bandwidth = 192000, url = "https://example.org/high")
        assertTrue(DeviceAudioSupport.supports(high))
        assertEquals(high, AudioQuality.best(listOf(high, low), DeviceAudioSupport::supports))
        assertFalse(DeviceAudioSupport.supports(high.copy(codec = "unknown")))
        assertFalse(DeviceAudioSupport.supports(high.copy(sampleRate = 999999999)))
    }
}
