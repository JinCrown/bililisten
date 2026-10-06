package app.bililisten.playback

import android.net.ConnectivityManager
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.AudioQuality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient

/** Explicit opt-in: one public sample, short playback, no account/history/collection mutations. */
@UnstableApi @RunWith(AndroidJUnit4::class)
class AudioQualityOnlineProbe {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val app = i.targetContext.applicationContext as ListenApplication
    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>(); val error = AtomicReference<Throwable>()
        i.runOnMainSync { try { value.set(block()) } catch (e: Throwable) { error.set(e) } }
        error.get()?.let { throw it }; return value.get()
    }
    @Test fun realAvailableHighestStreamDecodesAndAdvances() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("audioOnline") == "true")
        val network = app.getSystemService(ConnectivityManager::class.java)
        assertNotNull(network.activeNetwork)
        assertFalse("Online probe requires an unmetered network", network.isActiveNetworkMetered)
        val bv = "BV1U1421r7SM"
        val video = app.content.video(bv)
        val cid = video.parts.first().cid
        val probe = app.entitlements.probe(bv, cid, true)
        val selected = requireNotNull(AudioQuality.best(probe.tracks, DeviceAudioSupport::supports))
        val actualUrl = app.content.audio(bv, cid)
        assertEquals(Uri.parse(selected.url).path, Uri.parse(actualUrl).path)
        val player = main {
            ExoPlayer.Builder(app).setMediaSourceFactory(DefaultMediaSourceFactory(
                OkHttpDataSource.Factory(OkHttpClient.Builder().cache(null).build())
                    .setUserAgent("BiliListen-M0/0.0.1")
                    .setDefaultRequestProperties(mapOf("Referer" to "https://www.bilibili.com/"))
            )).build()
        }
        try {
            main { player.setMediaItem(MediaItem.fromUri(actualUrl)); player.prepare(); player.play() }
            val deadline = System.currentTimeMillis() + 30000
            while (System.currentTimeMillis() < deadline && main { player.currentPosition < 3000 && player.playerError == null }) Thread.sleep(100)
            assertNull(main { player.playerError })
            assertTrue(main { player.isPlaying && player.currentPosition >= 3000 })
            assertNotNull(main { player.audioFormat })
            File(app.filesDir, "audio-quality-evidence.json").writeText(
                """{"selectedId":${selected.id},"bandwidth":${selected.bandwidth},"availableTracks":${probe.tracks.size},"decodedAndAdvanced":true}"""
            )
        } finally { main { player.release() } }
    }
}
