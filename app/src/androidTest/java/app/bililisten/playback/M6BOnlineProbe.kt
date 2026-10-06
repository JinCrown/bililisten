package app.bililisten.playback

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Read-only platform requests; test history is isolated from the user's account. */
@UnstableApi @RunWith(AndroidJUnit4::class)
class M6BOnlineProbe {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
    @Test fun realAudioTimerAndLiveHistoryWithOriginalQueueRestored()=runBlocking {
        val originalAccount=app.accounts.session.value.stamp.account
        val original=app.stores.load(originalAccount);val settings=app.settings.settings.first()
        val account="m6b-online-fixture";app.accountKey=account
        val port=withContext(Dispatchers.Main){ControllerPlaybackPort(app)}
        suspend fun command(action:suspend ()->Unit)=withContext(Dispatchers.Main){action()}
        var videoPassed=false;var livePassed=false;var liveStatus=-1
        try {
            withTimeout(15000){port.state.first{it.connected}}
            app.settings.update(settings.copy(historyEnabled=true,historyKeepAll=true,historyDeleteOnExit=false))
            val video=app.content.video("BV1xx411c7mD");val part=video.parts.first()
            val entry=QueueEntry("m6b-audio",video.bvid,part.cid,part.number,video.title,origin=HistoryOrigin.SEARCH)
            command {port.replace(ResumeSnapshot(account=account,entries=listOf(entry),order=listOf(entry.id),currentId=entry.id,positionMs=0),true)}
            withTimeout(30000){port.state.first{it.playing}}
            command{port.speed(1.5f);port.sleepTimer(3)}
            withTimeout(10000){port.state.first{it.pauseReason==PauseReason.TIMER&&!it.requested}}
            command{port.flush()}
            val saved=app.stores.load(account)!!
            assertEquals(PauseReason.TIMER,saved.pauseReason);assertTrue(saved.queue.positionMs>0)
            assertEquals(1.5f,saved.queue.speeds[video.bvid]!!,0f)
            val row=app.stores.observe(account).first().single();assertEquals(HistoryOrigin.SEARCH,row.origin)
            assertEquals(row.video.cid,part.cid);assertTrue(row.positionMs>0)
            val at=row.playedAt
            delay(700);command{port.flush()};assertEquals(at,app.stores.observe(account).first().single().playedAt)
            videoPassed=true
            // The existing test room is used only if currently live. Never fabricate a room/CID.
            val room=app.live.room(6);liveStatus=room.status
            if(room.status==1) {
                command{port.live(room,true)}
                withTimeout(30000){port.state.first{it.live&&it.playing}}
                assertEquals(1f,port.state.value.speed,0f);assertFalse(port.state.value.canSeek)
                command{port.sleepTimer(3)}
                withTimeout(10000){port.state.first{it.pauseReason==PauseReason.TIMER&&!it.requested}}
                command{port.flush()}
                assertEquals(room.roomId,app.stores.observeLive(account).first().single().roomId)
                assertEquals(saved.queue.currentId,app.stores.load(account)!!.queue.currentId)
                assertEquals(1,app.stores.observe(account).first().size)
                livePassed=true
            }
        }finally {
            command{port.pause();port.flush();port.clear()}
            app.settings.update(settings);app.accountKey=originalAccount
            if(original!=null)command{port.replace(original.queue,false)}
            app.database.listenDao().clearPlayback(account);app.database.listenDao().clearHistory(account)
            command{port.close()}
            File(app.filesDir,"m6b-evidence").apply{mkdirs()}.resolve("online.json").writeText(buildJsonObject {
                put("realVodTimerPassed",videoPassed);put("liveRoomStatus",liveStatus);put("realLiveHistoryAndTimerPassed",livePassed)
                put("originalQueueRestoredPaused",original!=null);put("settingsRestored",app.settings.settings.value==settings)
            }.toString())
        }
    }
}
