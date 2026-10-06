package app.bililisten.playback

import android.media.AudioManager
import android.os.ParcelFileDescriptor
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Tests real system ducking from a different UID with silent PCM, leaving the user queue untouched. */
@UnstableApi @RunWith(AndroidJUnit4::class)
class ListeningAudioFocusTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val helper = app.packageName + ".test"
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    private suspend fun waitFor(check: suspend () -> Boolean) = withTimeout(10000) {
        while (!check()) delay(50)
    }

    private suspend fun focus(kind: String) {
        val token = System.nanoTime().toString()
        shell("am start -W -f 0x20000000 -n $helper/app.bililisten.playback.FocusRequestActivity --es kind $kind --es token $token")
        val expected = "$kind:${if (kind == "release") 0 else AudioManager.AUDIOFOCUS_REQUEST_GRANTED}:$token"
        waitFor { shell("run-as $helper cat files/focus-result.txt").trim() == expected }
    }

    private fun ducked(uid: Int): Boolean {
        val section = shell("dumpsys audio").substringAfter("ducked players piids:").substringBefore("faded out players piids:")
        return Regex("\\buid:?\\s*$uid\\b").containsMatchIn(section)
    }

    @Test fun navigationDucksRestoresAndNeverResumesUserPauseOrExclusiveLoss() = runBlocking<Unit> {
        val rate = 48000
        val size = rate * 8 * 4
        val wave = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(text: String) { wave.put(text.toByteArray(Charsets.US_ASCII)) }
        tag("RIFF"); wave.putInt(36 + size); tag("WAVE"); tag("fmt "); wave.putInt(16)
        wave.putShort(1); wave.putShort(2); wave.putInt(rate); wave.putInt(rate * 4)
        wave.putShort(4); wave.putShort(16); tag("data"); wave.putInt(size)
        // The remaining PCM samples are zero: real AudioTrack/mixer, no audible playback.
        val file = File(app.cacheDir, "focus-own-silent-fixture.wav").apply { writeBytes(wave.array()) }
        val audio = app.getSystemService(AudioManager::class.java)
        val systemVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val uid = android.os.Process.myUid()
        lateinit var player: ExoPlayer
        withContext(Dispatchers.Main) {
            player = ExoPlayer.Builder(app).build()
            configureListeningAudioFocus(player) { player.pause() }
            player.volume = .65f
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            player.prepare(); player.play()
        }
        try {
            waitFor { withContext(Dispatchers.Main) { player.isPlaying } }
            repeat(3) {
                focus("duck"); waitFor { ducked(uid) }
                val before = withContext(Dispatchers.Main) { assertTrue(player.isPlaying); player.currentPosition }
                delay(350)
                withContext(Dispatchers.Main) {
                    assertTrue(player.isPlaying)
                    assertTrue(player.currentPosition != before)
                    assertEquals(.65f, player.volume, 0f)
                    assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, player.playbackSuppressionReason)
                }
                focus("release"); waitFor { !ducked(uid) }
                withContext(Dispatchers.Main) { assertTrue(player.isPlaying); assertEquals(.65f, player.volume, 0f) }
            }
            // A volume change made during guidance survives restoration.
            focus("duck"); waitFor { ducked(uid) }
            withContext(Dispatchers.Main) { player.volume = .37f }
            focus("release"); waitFor { !ducked(uid) }
            withContext(Dispatchers.Main) { assertTrue(player.isPlaying); assertEquals(.37f, player.volume, 0f) }
            // Finishing a navigation announcement must not restart a user's manual pause.
            focus("duck"); waitFor { ducked(uid) }
            withContext(Dispatchers.Main) { player.pause() }
            focus("release"); delay(500)
            withContext(Dispatchers.Main) { assertFalse(player.playWhenReady); assertFalse(player.isPlaying); player.play() }
            waitFor { withContext(Dispatchers.Main) { player.isPlaying } }
            // Silence-required interruptions and another music app retain explicit pause.
            for (kind in listOf("transient", "music")) {
                focus(kind)
                waitFor { withContext(Dispatchers.Main) { !player.playWhenReady && !player.isPlaying } }
                focus("release"); delay(500)
                withContext(Dispatchers.Main) {
                    assertFalse(player.playWhenReady); assertFalse(player.isPlaying)
                    assertEquals(.37f, player.volume, 0f); player.play()
                }
                waitFor { withContext(Dispatchers.Main) { player.isPlaying } }
            }
            assertEquals(systemVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            File(app.filesDir, "focus-evidence/navigation.json").apply {
                parentFile!!.mkdirs()
                writeText("""{"differentUidRequester":true,"silentGeneratedPcm":true,"systemMixerDuckAndUnduck":true,"repeatedAnnouncements":3,"playbackContinuesDuringDuck":true,"userVolumeRetained":true,"manualPauseNotResumed":true,"transientLossPauses":true,"exclusiveMusicLossPauses":true,"systemVolumeUnchanged":true,"userQueueAccessed":false,"realMapAppTested":false}""")
            }
        } finally {
            withContext(Dispatchers.Main) { player.release() }
            focus("release")
            file.delete()
        }
    }
}
