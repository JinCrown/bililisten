package app.bililisten.playback

import android.content.ComponentName
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.platform.HistoryRow
import app.bililisten.shared.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi @RunWith(AndroidJUnit4::class)
class M2ServiceTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private fun <T> main(f:()->T):T{val out=AtomicReference<T>();val err=AtomicReference<Throwable>();i.runOnMainSync{try{out.set(f())}catch(e:Throwable){err.set(e)}};err.get()?.let{throw it};return out.get()}
    @Test fun editCommandsSynchronizeControllersRejectStaleAndClearOnlyResume()=runBlocking {
        val oldAccount=app.accountKey;val account="m2-service-fixture";app.accountKey=account
        val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()}
        val secondFuture=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()}
        val c=future.get(15,TimeUnit.SECONDS);val second=secondFuture.get(15,TimeUnit.SECONDS)
        fun command(name:String,b:Bundle=Bundle.EMPTY)=main{c.sendCustomCommand(PlaybackCommands.command(name),b)}.get(15,TimeUnit.SECONDS).resultCode
        fun edit(e:QueueEdit,v:Long)=command(PlaybackCommands.EDIT_QUEUE,Bundle().apply{putString("edit",Json.encodeToString(e));putLong("version",v)})
        val e=QueueEntry("a","BV1xx411c7mD",1,2,"fixture")
        val q=ResumeSnapshot(account=account,entries=listOf(e),order=listOf("a"),currentId="a",positionMs=12345)
        try{
            app.database.listenDao().played(HistoryRow(account,e.bvid,e.cid,e.title,e.part,null,12345,System.currentTimeMillis()))
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackCommands.REPLACE_QUEUE,Bundle().apply{putString("snapshot",SnapshotCodec.encode(q));putBoolean("play",false)}))
            assertEquals(1L,main{c.sessionExtras.getLong("queueVersion")})
            assertEquals(SessionResult.RESULT_SUCCESS,edit(QueueEdit.Add(listOf(e.copy(id="b"))),1))
            assertEquals(2,main{second.mediaItemCount});assertEquals(2L,main{second.sessionExtras.getLong("queueVersion")})
            assertEquals(SessionError.ERROR_BAD_VALUE,edit(QueueEdit.Remove("b"),1))
            assertEquals(2,main{c.mediaItemCount});assertFalse(main{c.playWhenReady})
            for(reason in listOf(PauseReason.USER,PauseReason.NOISY,PauseReason.FOCUS_LOSS,PauseReason.EXTERNAL_VIDEO,PauseReason.NETWORK,PauseReason.TIMER)){
                assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackCommands.PAUSE_REASON,Bundle().apply{putString("reason",reason.name)}))
                assertEquals(reason,app.stores.load(account)!!.pauseReason);assertFalse(main{second.playWhenReady})
            }
            assertEquals(SessionResult.RESULT_SUCCESS,edit(QueueEdit.Clear,2))
            assertNull(app.stores.load(account));assertTrue(app.database.listenDao().queue(account).isEmpty())
            assertEquals(1,app.database.listenDao().history(account).first().size);assertEquals(0,main{second.mediaItemCount})
        }finally{
            command(PlaybackCommands.CLEAR);main{MediaController.releaseFuture(secondFuture);MediaController.releaseFuture(future)}
            app.database.listenDao().clearPlayback(account);app.database.listenDao().clearHistory(account);app.accountKey=oldAccount
        }
    }
}
