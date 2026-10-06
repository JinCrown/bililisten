package app.bililisten.playback

import android.net.ConnectivityManager
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in read-only requests and short VOD playback. Guest requests use a separate cookie-free client. */
@UnstableApi @RunWith(AndroidJUnit4::class)
class M6COnlineProbe {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
    @Test fun sameContentGuestAndLoggedTracksSwitchRealServicePreserveQueueSpeedTimerAndPause()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m6conline")=="1")
        val network=app.getSystemService(ConnectivityManager::class.java)
        assertNotNull(network.activeNetwork);assertFalse(network.isActiveNetworkMetered)
        val owner=app.accounts.session.value.stamp.account
        val original=app.stores.load(owner);val settings=app.settings.current()
        val account="m6c-online-fixture";app.accountKey=account
        val port=withContext(Dispatchers.Main){ControllerPlaybackPort(app)}
        suspend fun command(block:suspend ()->Unit)=withContext(Dispatchers.Main){block()}
        val guestClient=HttpClient(OkHttp){install(HttpTimeout){requestTimeoutMillis=15000};engine{config{cache(null)}}}
        val guestApi=BiliApi(guestClient,searchClock=app.clock,searchMd5={bytes->java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString(""){"%02x".format(it)}}){null}
        var result=buildJsonObject{put("complete",false)}
        try {
            withTimeout(15000){port.state.first{it.connected}}
            app.settings.update(settings.copy(historyEnabled=false,externalOutputOnly=false,preferredOutputType=null,audioChoice=AudioChoice()))
            delay(300)
            val video=app.content.video("BV1U1421r7SM");val part=video.parts.first()
            val logged=app.entitlements.probe(video.bvid,part.cid,true)
            val guest=guestApi.audioProbe(video.bvid,part.cid,true)
            assertNotNull(AudioQuality.best(logged.tracks,DeviceAudioSupport::supports))
            assertNotNull(AudioQuality.best(guest.tracks,DeviceAudioSupport::supports))
            val entry=QueueEntry("m6c-online",video.bvid,part.cid,part.number,video.title)
            command{port.replace(ResumeSnapshot(account=account,entries=listOf(entry),order=listOf(entry.id),currentId=entry.id,positionMs=10000),true)}
            withTimeout(30000){port.state.first{it.playing&&it.audio.selected!=null}}
            command{port.speed(1.25f)}
            val initial=port.state.value
            val low=initial.audio.options.filter{it.supported&&it.choice.codec.startsWith("mp4a")}.minBy{it.bitrate}
            command{port.audioQuality(low.choice)}
            withTimeout(30000){port.state.first{it.playing&&it.audio.selected==low.choice}}
            assertEquals(initial.currentId,port.state.value.currentId);assertEquals(initial.queue,port.state.value.queue)
            assertEquals(initial.queueVersion,port.state.value.queueVersion)
            assertEquals(1.25f,port.state.value.speed,0f)
            assertTrue(port.state.value.positionMs in initial.positionMs-1000..initial.positionMs+8000)
            command{port.sleepTimer(2)}
            withTimeout(10000){port.state.first{it.pauseReason==PauseReason.TIMER&&!it.requested}}
            command{port.flush()}
            val paused=port.state.value.positionMs
            command{port.audioQuality(AudioChoice());port.flush()}
            assertFalse(port.state.value.requested);assertEquals(PauseReason.TIMER,port.state.value.pauseReason)
            assertEquals(paused,port.state.value.positionMs)
            command{port.audioQuality()}
            assertFalse(port.state.value.requested)
            assertTrue(app.stores.observe(account).first().isEmpty())
            assertEquals(owner,app.accounts.session.value.stamp.account)
            result=buildJsonObject{
                put("complete",true);put("bvid",video.bvid);put("sameContentPairedGuestAndLogged",true)
                put("guestTrackCount",guest.tracks.size);put("loggedTrackCount",logged.tracks.size)
                put("guestOptions",Json.encodeToJsonElement(AudioExperienceRules.options(guest.tracks,DeviceAudioSupport::supports)))
                put("loggedOptions",Json.encodeToJsonElement(initial.audio.options))
                put("manualAacChoice",Json.encodeToJsonElement(low.choice));put("positionPreserved",true)
                put("queueAndSpeedPreserved",true);put("timerPausePreserved",true);put("output",Json.encodeToJsonElement(initial.output))
                put("loggedHighTierReturned",logged.tracks.any{it.codec.lowercase() in setOf("flac","ec-3","ec+3")})
                put("ordinaryAccountPaired",false);put("physicalHeadsetTested",false)
            }
        }finally {
            command{port.pause();port.flush();port.clear()}
            app.settings.update(settings);app.accountKey=owner
            if(original!=null)command{port.replace(original.queue,false)}
            command{port.close()};guestClient.close()
            app.database.listenDao().clearPlayback(account);app.database.listenDao().clearHistory(account)
            File(app.filesDir,"m6c-evidence").apply{mkdirs()}.resolve("online.json").writeText(result.toString())
        }
    }
}
