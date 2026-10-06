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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi @RunWith(AndroidJUnit4::class)
class M6BServiceTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private fun <T> main(f:()->T):T { val out=AtomicReference<T>();val error=AtomicReference<Throwable>();i.runOnMainSync{try{out.set(f())}catch(e:Throwable){error.set(e)}};error.get()?.let{throw it};return out.get() }
    @Test fun timerExpiresWhilePausedSavesReasonSpeedIsPerVideoAndExplicitExitClearsOnlyFixtureHistory()=runBlocking {
        val oldAccount=app.accounts.session.value.stamp.account
        val original=app.stores.load(oldAccount);val settings=app.settings.settings.first()
        val account="m6b-service-fixture";app.accountKey=account
        val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()}
        val c=future.get(15,TimeUnit.SECONDS)
        fun command(action:String,args:Bundle=Bundle.EMPTY)=main{c.sendCustomCommand(PlaybackCommands.command(action),args)}.get(15,TimeUnit.SECONDS).resultCode
        fun replace(q:ResumeSnapshot)=command(PlaybackCommands.REPLACE_QUEUE,Bundle().apply{putString("snapshot",SnapshotCodec.encode(q));putBoolean("play",false)})
        fun speed(value:Float)=command(PlaybackCommands.SET_SPEED,Bundle().apply{putFloat("speed",value)})
        fun timer(seconds:Int)=command(PlaybackCommands.SLEEP_TIMER,Bundle().apply{putInt("seconds",seconds)})
        val a=QueueEntry("a","BV1xx411c7mD",1,1,"fixture",origin=HistoryOrigin.SEARCH)
        val b=a.copy(id="b",cid=2,part=2);val other=a.copy(id="c",bvid="BV1rHh16CESS")
        val q=ResumeSnapshot(account=account,entries=listOf(a,b,other),order=listOf("a","b","c"),currentId="a",positionMs=12345)
        try {
            app.settings.update(settings.copy(historyEnabled=true,historyKeepAll=true,historyDeleteOnExit=true))
            assertEquals(0,replace(q));assertEquals(0,speed(1.5f));assertEquals(1.5f,main{c.playbackParameters.speed},0f)
            main{c.seekToNextMediaItem()};delay(250);assertEquals(1.5f,main{c.playbackParameters.speed},0f)
            main{c.seekToNextMediaItem()};delay(250);assertEquals(1f,main{c.playbackParameters.speed},0f)
            assertEquals(SessionError.ERROR_BAD_VALUE,speed(3f))
            replace(q.copy(speeds=mapOf(a.bvid to 1.75f)));assertEquals(1.75f,main{c.playbackParameters.speed},0f)
            assertEquals(0,timer(2));assertTrue(main{c.sessionExtras.getLong("timerRemainingMs")}>0)
            delay(2600);command(PlaybackCommands.FLUSH)
            assertFalse(main{c.playWhenReady});assertEquals("TIMER",main{c.sessionExtras.getString("pauseReason")})
            val saved=app.stores.load(account)!!;assertEquals(PauseReason.TIMER,saved.pauseReason);assertEquals(12345L,saved.queue.positionMs)
            assertEquals(1.75f,saved.queue.speeds[a.bvid]!!,0f);assertTrue(app.stores.observe(account).first().isEmpty())
            timer(1);timer(0);delay(1200);assertEquals(0L,main{c.sessionExtras.getLong("timerRemainingMs")})
            app.stores.checkpoint(saved,LocalHistoryEntry(account,VideoRef(a.bvid,1,1),"fixture",null,1,System.currentTimeMillis()))
            app.stores.checkpointLive(LiveHistoryEntry(account,6,"fixture live",System.currentTimeMillis()))
            assertEquals(0,command(PlaybackCommands.EXIT_LISTENING))
            assertTrue(app.stores.observe(account).first().isEmpty());assertTrue(app.stores.observeLive(account).first().isEmpty());assertNotNull(app.stores.load(account))
            assertFalse(main{c.playWhenReady})
        }finally {
            command(PlaybackCommands.CLEAR)
            app.settings.update(settings);app.accountKey=oldAccount
            if(original!=null)replace(original.queue)
            main{MediaController.releaseFuture(future)}
            app.database.listenDao().clearPlayback(account);app.database.listenDao().clearHistory(account)
        }
    }
}
