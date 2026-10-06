package app.bililisten.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.TrafficStats
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class M0SystemProbe {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private val args=InstrumentationRegistry.getArguments()
    private fun <T> main(f:()->T):T { val v=AtomicReference<T>();val e=AtomicReference<Throwable>();i.runOnMainSync{try{v.set(f())}catch(t:Throwable){e.set(t)}};e.get()?.let{throw it};return v.get() }
    private fun command(c:MediaController,action:String,args:Bundle=Bundle.EMPTY){assertEquals(SessionResult.RESULT_SUCCESS,main{c.sendCustomCommand(PlaybackCommands.command(action),args)}.get(15,TimeUnit.SECONDS).resultCode)}
    private fun waitUntil(f:()->Boolean){val end=System.currentTimeMillis()+30000;while(System.currentTimeMillis()<end){if(f())return;Thread.sleep(200)};throw AssertionError("Playback condition timed out")}
    private fun save(name:String,data:JsonObject){File(app.filesDir,"m0-evidence").mkdirs();File(app.filesDir,"m0-evidence/$name.json").writeText(data.toString())}
    private fun cacheFiles():Map<String,Long> = app.cacheDir.walkTopDown().filter{it.isFile}.associate{it.relativeTo(app.cacheDir).path to it.length()}
    private fun shell(command:String) { android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(command)).use{it.readBytes()} }

    @Test fun approvedLiveDisconnectAndExplicitRecovery()=runBlocking {
        assumeTrue(args.getString("m0phase")=="live-network"&&args.getString("mixedConsent")=="true")
        val began=System.currentTimeMillis()
        val room=app.api.liveRoom(6);assertEquals(1,room.status)
        val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()};val c=future.get(15,TimeUnit.SECONDS)
        val before=cacheFiles();val rx=TrafficStats.getUidRxBytes(android.os.Process.myUid())
        try {
            command(c,PlaybackCommands.PLAY_LIVE,Bundle().apply{putLong("room",room.roomId);putString("title",room.title);putBoolean("mixedConsent",true)})
            waitUntil{main{c.isPlaying}};Thread.sleep(3000)
            // The host disables cellular beforehand and restores its original state in finally.
            shell("svc wifi disable")
            main{c.pause();c.stop();c.prepare();c.play()}
            Thread.sleep(7000)
            assertFalse("Unexpected playback with no network and a new source",main{c.isPlaying})
            val offlineState=main{c.playbackState}
            main{c.pause();c.stop()}
            shell("svc wifi enable");Thread.sleep(5000)
            main{c.play()};waitUntil{main{c.isPlaying}}
            val recovered=main{c.currentPosition};Thread.sleep(4000)
            assertTrue(main{c.currentPosition}>recovered+2500)
            main{c.pause();c.stop()}
            assertFalse(main{c.isPlaying});assertFalse(main{c.playWhenReady})
            val elapsed=System.currentTimeMillis()-began
            assertTrue("Exceeded approved two-minute window",elapsed<120000)
            save("live-network",buildJsonObject{put("wifiOnly",true);put("offlineFreshConnectionDidNotPlay",true);put("offlinePlaybackState",offlineState);put("explicitRecoveryPlayed",true);put("stoppedAfterTest",true);put("elapsedMs",elapsed);put("uidReceivedBytes",TrafficStats.getUidRxBytes(android.os.Process.myUid())-rx);put("cacheUnchanged",before==cacheFiles());put("actualBroadcasterEndObserved",false)})
        }finally{main{c.pause();c.stop();MediaController.releaseFuture(future)};shell("svc wifi enable")}
    }

    @Test fun fiveMinuteScreenOffPlayback()=runBlocking {
        assumeTrue(args.getString("m0phase")=="background-five-minutes")
        val account=app.api.account();main{app.accountKey=account.id.toString()}
        val saved=SnapshotCodec.decode(app.database.listenDao().resume(account.id.toString())!!.payload)
        val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()};val c=future.get(15,TimeUnit.SECONDS)
        val checkpoints=mutableListOf<JsonElement>()
        try {
            command(c,PlaybackCommands.REPLACE_QUEUE,Bundle().apply{putString("snapshot",SnapshotCodec.encode(saved));putBoolean("play",true)})
            waitUntil{main{c.isPlaying}};shell("input keyevent 223")
            var previous=main{c.currentPosition}
            repeat(10){index->
                Thread.sleep(30000)
                val now=main{c.currentPosition}
                assertTrue(main{c.isPlaying});assertTrue("Playback stalled",now>previous+25000)
                checkpoints+=buildJsonObject{put("elapsedSeconds",(index+1)*30);put("positionMs",now);put("isPlaying",true)}
                previous=now
                save("background-five-minutes",buildJsonObject{put("complete",index==9);put("usbPowered",true);put("instrumentationActive",true);put("physicalHeadsetTested",false);put("checkpoints",JsonArray(checkpoints))})
                if(index%2==1)i.sendStatus(0,Bundle().apply{putString("stream","Screen-off playback ${(index+1)*30}/300 seconds passed.\n")})
            }
        }finally{main{c.pause()};command(c,PlaybackCommands.FLUSH);main{MediaController.releaseFuture(future)};shell("input keyevent 224")}
    }

    @Test fun focusAndNoMediaDiskCache()=runBlocking {
        assumeTrue(args.getString("m0phase")=="system")
        val account=app.api.account();main{app.accountKey=account.id.toString()}
        val saved=SnapshotCodec.decode(app.database.listenDao().resume(account.id.toString())!!.payload)
        val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()};val c=future.get(15,TimeUnit.SECONDS)
        val audio=app.getSystemService(Context.AUDIO_SERVICE)as AudioManager
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build()).setOnAudioFocusChangeListener({}).build()
        val before=cacheFiles();val rx=TrafficStats.getUidRxBytes(android.os.Process.myUid())
        try{
            command(c,PlaybackCommands.REPLACE_QUEUE,Bundle().apply{putString("snapshot",SnapshotCodec.encode(saved));putBoolean("play",true)})
            waitUntil{main{c.isPlaying}};Thread.sleep(6000)
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,main{audio.requestAudioFocus(focus)})
            waitUntil{main{!c.isPlaying}};main{c.pause();audio.abandonAudioFocusRequest(focus)};Thread.sleep(800)
            assertFalse(main{c.playWhenReady});assertFalse(main{c.isPlaying})
            main{c.play()};waitUntil{main{c.isPlaying}};Thread.sleep(6000);main{c.pause()};command(c,PlaybackCommands.FLUSH)
            val after=cacheFiles()
            save("focus-cache",buildJsonObject{put("focusLossPaused",true);put("manualPauseSurvivedFocusReturn",true);put("cacheUnchanged",before==after);put("cacheBytesBefore",before.values.sum());put("cacheBytesAfter",after.values.sum());put("uidReceivedBytes",TrafficStats.getUidRxBytes(android.os.Process.myUid())-rx);put("mediaDiskCacheConfigured",false)})
            assertEquals(before,after)
        }finally{main{audio.abandonAudioFocusRequest(focus);c.pause();MediaController.releaseFuture(future)}}
    }

    @Test fun explicitlyApprovedLiveStream()=runBlocking {
        assumeTrue(args.getString("m0phase")=="live"&&args.getString("mixedConsent")=="true")
        val room=app.api.liveRoom(6);assertEquals(1,room.status)
        val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()};val c=future.get(15,TimeUnit.SECONDS)
        val before=cacheFiles();val rx=TrafficStats.getUidRxBytes(android.os.Process.myUid())
        try{
            command(c,PlaybackCommands.PLAY_LIVE,Bundle().apply{putLong("room",room.roomId);putString("title",room.title);putBoolean("mixedConsent",true)})
            waitUntil{main{c.isPlaying||c.playerError!=null}}
            if(main{c.playerError!=null}){save("live-playback",buildJsonObject{put("room",room.roomId);put("error",main{c.playerError!!.errorCodeName})});fail("Live playback failed")}
            val position=main{c.currentPosition};Thread.sleep(5000);assertTrue(main{c.currentPosition}>position+3000)
            main{c.pause()};Thread.sleep(1000);assertFalse(main{c.isPlaying})
            val pausedPosition=main{c.currentPosition}
            // Exercise the same play command used by the UI and system media controllers.
            main{c.play()};waitUntil{main{c.isPlaying}};Thread.sleep(2500)
            assertTrue("Live controller reused the paused buffer",main{c.currentPosition}<pausedPosition)
            save("live-playback",buildJsonObject{put("room",room.roomId);put("mixedConsent",true);put("actualPlayback",true);put("pauseResume",true);put("controllerPlayReconnects",true);put("dvrWindow",false);put("uidReceivedBytes",TrafficStats.getUidRxBytes(android.os.Process.myUid())-rx);put("cacheUnchanged",before==cacheFiles())})
        }finally{main{c.pause();MediaController.releaseFuture(future)}}
    }
}
