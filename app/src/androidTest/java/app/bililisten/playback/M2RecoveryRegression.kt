package app.bililisten.playback

import android.content.ComponentName
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Two explicitly invoked stages. Host force-stops the process between them while audio is active. */
@UnstableApi @RunWith(AndroidJUnit4::class)
class M2RecoveryRegression {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private val phase=InstrumentationRegistry.getArguments().getString("m2phase")
    private val backup get()=File(app.noBackupFilesDir,"m2-recovery-original.json")
    private suspend fun confirmedAccount():String {
        val account=app.accounts.verify()
        check(account!=null || InstrumentationRegistry.getArguments().getString("m5allowGuest")=="1") { "Existing account required unless explicitly running the M5 guest fixture" }
        return account?.id?.toString() ?: "guest"
    }
    private fun <T> main(block:()->T):T {val out=AtomicReference<T>();val err=AtomicReference<Throwable>();i.runOnMainSync{try{out.set(block())}catch(e:Throwable){err.set(e)}};err.get()?.let{throw it};return out.get()}
    private fun connect()=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()}
    private fun command(c:MediaController,action:String,b:Bundle=Bundle.EMPTY){assertEquals(SessionResult.RESULT_SUCCESS,main{c.sendCustomCommand(PlaybackCommands.command(action),b)}.get(20,TimeUnit.SECONDS).resultCode)}
    private fun waitFor(test:()->Boolean){val end=System.currentTimeMillis()+30000;while(System.currentTimeMillis()<end){if(test())return;Thread.sleep(100)};throw AssertionError("Recovery condition timed out")}
    private fun save(name:String,value:JsonObject){File(app.filesDir,"m2-evidence").mkdirs();File(app.filesDir,"m2-evidence/$name.json").writeText(value.toString())}
    private suspend fun restoreOriginal(data:JsonObject) {
        app.stores.checkpoint(Json.decodeFromJsonElement<PlaybackSnapshot>(data.getValue("snapshot")),null)
        app.settings.update(Json.decodeFromJsonElement<UserSettings>(data.getValue("settings")))
        check(backup.delete())
    }
    @Test fun prepareActiveShuffleForExternalForceStop()=runBlocking {
        assumeTrue(phase=="prepare-kill")
        check(!backup.exists()){ "Previous recovery cleanup must complete first" }
        app.accountKey=confirmedAccount()
        val original=app.stores.load(app.accountKey)!!
        val settings=withTimeout(5000){app.settings.settings.first()}
        val video=app.content.video("BV1c4411d7jb");val part=video.parts.single{it.number==2}
        val music=original.queue.entries.first().copy(id="m2-other")
        val entries=listOf(QueueEntry("m2-p2",video.bvid,part.cid,2,"M2 P2",source=null),music,music.copy(id="m2-duplicate"))
        val queue=ResumeSnapshot(account=app.accountKey,entries=entries,order=entries.map{it.id},currentId="m2-p2",positionMs=28000).withMode(PlayMode.SHUFFLE)
        val data=buildJsonObject{put("snapshot",Json.encodeToJsonElement(original));put("settings",Json.encodeToJsonElement(settings));put("expected",Json.encodeToJsonElement(queue))}
        backup.writeText(data.toString())
        app.settings.update(settings.copy(historyEnabled=true));withTimeout(5000){app.settings.settings.first{it.historyEnabled}}
        val future=connect();val c=future.get(15,TimeUnit.SECONDS);var ready=false
        try {
            command(c,PlaybackCommands.REPLACE_QUEUE,Bundle().apply{putString("snapshot",SnapshotCodec.encode(queue));putBoolean("play",true)})
            waitFor{main{c.isPlaying}};Thread.sleep(6500)
            val saved=app.stores.load(app.accountKey)!!
            assertTrue(saved.queue.positionMs>=32000);assertEquals(2,main{c.currentMediaItem!!.queueEntry()!!.part})
            save("before-kill",buildJsonObject{put("playing",main{c.isPlaying});put("savedPositionMs",saved.queue.positionMs);put("actualPositionMs",main{c.currentPosition});put("queueVersion",saved.queue.queueVersion);put("currentPart",2);put("savedWithoutDestroy",true)})
            ready=true
            // Host kills the process while this test and actual audio are still running.
            i.sendStatus(0,Bundle().apply{putString("stream","M2 checkpoint ready; awaiting host force-stop.\n")})
            Thread.sleep(20000)
        }finally{
            if(!ready){main{c.pause()};command(c,PlaybackCommands.CLEAR);restoreOriginal(data)}
            main{MediaController.releaseFuture(future)}
        }
    }
    @Test fun coldStartIsSilentAndExplicitResumeKeepsPartOrderAndProgress()=runBlocking {
        assumeTrue(phase=="restore-kill")
        val data=Json.parseToJsonElement(backup.readText()).jsonObject
        val expected=Json.decodeFromJsonElement<ResumeSnapshot>(data.getValue("expected"))
        app.accountKey=confirmedAccount()
        val future=connect();val c=future.get(15,TimeUnit.SECONDS)
        try {
            assertFalse(main{c.playWhenReady});assertEquals(0,main{c.mediaItemCount})
            val restored=ResumeCoordinator(app.stores,app.accounts,app.sources,app.clock).prepare()!!
            assertFalse(restored.autoplay);assertEquals(expected.order,restored.snapshot.queue.order)
            assertEquals(expected.entries,restored.snapshot.queue.entries);assertEquals(expected.queueVersion,restored.snapshot.queue.queueVersion)
            assertTrue(restored.snapshot.queue.positionMs>=32000)
            command(c,PlaybackCommands.REPLACE_QUEUE,Bundle().apply{putString("snapshot",SnapshotCodec.encode(restored.snapshot.queue));putBoolean("play",false)})
            assertFalse(main{c.playWhenReady});assertEquals(restored.snapshot.queue.positionMs,main{c.currentPosition})
            main{c.play()};waitFor{main{c.isPlaying}};val start=main{c.currentPosition};Thread.sleep(2000)
            assertTrue(main{c.currentPosition}>start+1200);assertEquals(2,main{c.currentMediaItem!!.queueEntry()!!.part})
            save("after-kill",buildJsonObject{put("silentStart",true);put("partPreserved",true);put("exactShuffleOrder",true);put("queueVersion",restored.snapshot.queue.queueVersion);put("restoredPositionMs",restored.snapshot.queue.positionMs);put("explicitResumeAdvanced",true);put("originalDataRestoredInFinally",true)})
        }finally{main{c.pause()};command(c,PlaybackCommands.CLEAR);main{MediaController.releaseFuture(future)};restoreOriginal(data)}
    }
}
