package app.bililisten.playback

import android.app.NotificationManager
import android.content.*
import android.media.*
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.bililisten.*
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in, bounded real audio regression. No remote collection writes. */
@UnstableApi @RunWith(AndroidJUnit4::class)
class M2SystemRegression {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private val args=InstrumentationRegistry.getArguments()
    private fun <T> main(block:()->T):T {val out=AtomicReference<T>();val err=AtomicReference<Throwable>();i.runOnMainSync{try{out.set(block())}catch(e:Throwable){err.set(e)}};err.get()?.let{throw it};return out.get()}
    private fun command(c:MediaController,name:String,b:Bundle=Bundle.EMPTY):SessionResult=main{c.sendCustomCommand(PlaybackCommands.command(name),b)}.get(20,TimeUnit.SECONDS)
    private fun ok(c:MediaController,name:String,b:Bundle=Bundle.EMPTY){assertEquals(SessionResult.RESULT_SUCCESS,command(c,name,b).resultCode)}
    private fun connect()=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()}
    private fun waitFor(test:()->Boolean){val end=System.currentTimeMillis()+30000;while(System.currentTimeMillis()<end){if(test())return;Thread.sleep(100)};throw AssertionError("M2 condition timed out")}
    private fun shell(text:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(text)).use{it.readBytes().decodeToString().trim()}
    private fun evidence(name:String,data:JsonObject){File(app.filesDir,"m2-evidence").mkdirs();File(app.filesDir,"m2-evidence/$name.json").writeText(data.toString())}
    private fun cache()=app.cacheDir.walkTopDown().filter{it.isFile}.associate{it.relativeTo(app.cacheDir).path to it.length()}
    private suspend fun fixture():ResumeSnapshot {
        val account=app.accounts.verify()!!;app.accountKey=account.id.toString()
        if(args.getString("m5course")=="1"){
            val v=app.content.video("BV1c4411d7jb");val p=v.parts.single{it.number==2}
            val rows=(0..2).map{QueueEntry("m5-network-$it",v.bvid,p.cid,p.number,v.title)}
            return ResumeSnapshot(account=app.accountKey,entries=rows,order=rows.map{it.id},currentId=rows.first().id,positionMs=0)
        }
        val folder=app.favorites.folders(account.id).single{it.title=="音乐"}
        val source=SourceRef(SourceKind.OWN_FAVORITES,folder.id,account.id)
        val page=app.sources.content(source,account.id)
        val v=app.content.video(page.items.first().bvid);val p=v.parts.first()
        val rows=(0..2).map{QueueEntry("m2-$it",v.bvid,p.cid,p.number,v.title,source=source)}
        return ResumeSnapshot(account=app.accountKey,entries=rows,order=rows.map{it.id},currentId=rows.first().id,positionMs=0)
    }
    private fun replace(c:MediaController,s:ResumeSnapshot,play:Boolean=true)=ok(c,PlaybackCommands.REPLACE_QUEUE,Bundle().apply{putString("snapshot",SnapshotCodec.encode(s));putBoolean("play",play)})
    private fun edit(c:MediaController,e:QueueEdit):SessionResult=command(c,PlaybackCommands.EDIT_QUEUE,Bundle().apply{putString("edit",Json.encodeToString(e));putLong("version",main{c.sessionExtras.getLong("queueVersion")})})

    @Test fun realQueueBackgroundFocusMediaKeysAndNoDiskCache()=runBlocking {
        assumeTrue(args.getString("m2phase")=="system")
        val q=fixture();val original=app.stores.load(q.account)!!;val video=q.entries.first()
        val oldHistory=app.database.listenDao().historyEntry(q.account,video.bvid,video.cid)
        val beforeCache=cache();val future=connect();val c=future.get(15,TimeUnit.SECONDS)
        val secondFuture=connect();val second=secondFuture.get(15,TimeUnit.SECONDS)
        val audio=app.getSystemService(AudioManager::class.java)
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build()).setOnAudioFocusChangeListener({}).build()
        try {
            replace(c,q);waitFor{main{c.isPlaying}};Thread.sleep(2000)
            assertEquals(main{c.currentMediaItem!!.mediaId},main{second.currentMediaItem!!.mediaId})
            val at=main{c.currentPosition}
            assertEquals(SessionResult.RESULT_SUCCESS,edit(c,QueueEdit.Add(listOf(video.copy(id="m2-extra")),true)).resultCode)
            assertEquals(SessionResult.RESULT_SUCCESS,edit(c,QueueEdit.Move("m2-2",0)).resultCode)
            assertEquals(SessionResult.RESULT_SUCCESS,edit(c,QueueEdit.Remove("m2-extra")).resultCode)
            Thread.sleep(1200);assertTrue(main{c.currentPosition}>at+600);assertEquals("m2-0",main{c.currentMediaItem!!.mediaId})
            ok(c,PlaybackCommands.SET_MODE,Bundle().apply{putString("mode",PlayMode.SHUFFLE.name)})
            val order=main{(0 until c.mediaItemCount).map{c.getMediaItemAt(it).mediaId}}
            shell("input keyevent 87");waitFor{main{c.currentMediaItem!!.mediaId==order[1]}}
            shell("input keyevent 88");waitFor{main{c.currentMediaItem!!.mediaId==order[0]}}
            shell("input keyevent 127");waitFor{main{!c.playWhenReady}};assertFalse(main{second.playWhenReady})
            shell("input keyevent 126");waitFor{main{c.isPlaying}}
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,main{audio.requestAudioFocus(focus)})
            waitFor{main{!c.isPlaying}};main{c.pause();audio.abandonAudioFocusRequest(focus)};Thread.sleep(700)
            assertFalse(main{c.playWhenReady})
            main{c.play()};waitFor{main{c.isPlaying}}
            // Same controller survives leaving Activity and screen-off; no second app player is created.
            main{app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
            Thread.sleep(1000);shell("input keyevent 3");val start=main{c.currentPosition}
            shell("input keyevent 223");Thread.sleep(12000)
            assertTrue(main{c.isPlaying});assertTrue(main{c.currentPosition}>start+10000)
            ok(c,PlaybackCommands.FLUSH)
            val stored=app.stores.load(q.account)!!;assertEquals(order,stored.queue.order)
            assertTrue(stored.queue.positionMs>start+9000)
            val notification=app.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull{it.notification.contentIntent!=null}
            assertNotNull(notification)
            shell("input keyevent 224");shell("wm dismiss-keyguard")
            // Exercise a real System UI tap; background PendingIntent.send is not a user gesture.
            val title=main{c.mediaMetadata.title.toString().take(12)}
            shell("cmd statusbar expand-notifications")
            waitFor{i.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(title)?.isNotEmpty()==true}
            var node=i.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(title).first()
            repeat(10){if(!node.isClickable && node.parent!=null)node=node.parent}
            assertTrue(node.isClickable);assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            waitFor{main{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).isNotEmpty()}}
            val beforeRemove=main{c.currentPosition}
            main{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList().forEach{it.finishAndRemoveTask()}}
            Thread.sleep(1400);assertTrue(main{c.isPlaying});assertTrue(main{c.currentPosition}>beforeRemove+700)
            main{c.pause()};ok(c,PlaybackCommands.PAUSE_REASON,Bundle().apply{putString("reason",PauseReason.TIMER.name)})
            Thread.sleep(300);assertFalse(main{second.playWhenReady});assertEquals(PauseReason.TIMER,app.stores.load(q.account)!!.pauseReason)
            assertEquals(beforeCache,cache())
            evidence("system",buildJsonObject{put("twoControllersOneQueue",true);put("editsKeptProgress",true);put("systemMediaKeys",true);put("manualPauseSurvivedFocusReturn",true);put("backgroundScreenOffSeconds",12);put("notificationReturnedToApp",true);put("recentTaskRemovedKeptPlaying",true);put("timerPauseContract",true);put("cacheUnchanged",true);put("usbPowered",true);put("physicalHeadsetTested",false)})
        }finally{
            main{audio.abandonAudioFocusRequest(focus);c.pause()};ok(c,PlaybackCommands.CLEAR)
            main{MediaController.releaseFuture(secondFuture);MediaController.releaseFuture(future)}
            app.stores.checkpoint(original,null)
            if(oldHistory==null)app.database.listenDao().deleteHistory(q.account,video.bvid,video.cid)else app.database.listenDao().played(oldHistory)
            shell("input keyevent 224");shell("wm dismiss-keyguard")
        }
    }

    @Test fun disconnectStopsSameItemAndRecoveryRequiresExplicitPlay()=runBlocking {
        assumeTrue(args.getString("m2phase")=="network")
        val q=fixture();val original=app.stores.load(q.account)!!;val v=q.entries.first()
        val oldHistory=app.database.listenDao().historyEntry(q.account,v.bvid,v.cid)
        val wifi=shell("settings get global wifi_on");val mobile=shell("settings get global mobile_data")
        val future=connect();val c=future.get(15,TimeUnit.SECONDS)
        try {
            replace(c,q);waitFor{main{c.isPlaying}};Thread.sleep(1500)
            val id=main{c.currentMediaItem!!.mediaId}
            shell("svc data disable");shell("svc wifi disable")
            Thread.sleep(1000)
            i.sendStatus(0,Bundle().apply{putString("stream","Network diagnostic: wifi=${shell("settings get global wifi_on")}, mobile=${shell("settings get global mobile_data")}, kind=${PlaybackNetwork(app,{app.settings.settings.value},{}).kind()}\n")})
            waitFor{main{!c.playWhenReady}};Thread.sleep(1500)
            assertEquals(id,main{c.currentMediaItem!!.mediaId});assertEquals(3,main{c.mediaItemCount})
            assertEquals(PauseReason.NETWORK,app.stores.load(q.account)!!.pauseReason)
            shell("svc wifi enable");Thread.sleep(6000)
            assertFalse(main{c.playWhenReady})
            main{c.play()};waitFor{main{c.isPlaying}};val before=main{c.currentPosition};Thread.sleep(1800)
            assertTrue(main{c.currentPosition}>before+1000)
            evidence("network",buildJsonObject{put("offlineStoppedSameItem",true);put("queueCount",3);put("reconnectDidNotAutoplay",true);put("explicitRecoveryPlayed",true);put("realCellularPlaybackTested",false)})
        }finally{
            shell(if(wifi=="1")"svc wifi enable" else "svc wifi disable")
            shell(if(mobile=="1")"svc data enable" else "svc data disable")
            main{c.pause()};ok(c,PlaybackCommands.CLEAR);main{MediaController.releaseFuture(future)}
            app.stores.checkpoint(original,null)
            if(oldHistory==null)app.database.listenDao().deleteHistory(q.account,v.bvid,v.cid)else app.database.listenDao().played(oldHistory)
        }
    }
}
