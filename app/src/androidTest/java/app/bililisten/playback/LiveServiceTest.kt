package app.bililisten.playback

import android.content.ComponentName
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi @RunWith(AndroidJUnit4::class)
class LiveServiceTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private fun <T> main(block:()->T):T {val value=AtomicReference<T>();val error=AtomicReference<Throwable>();i.runOnMainSync{try{value.set(block())}catch(e:Throwable){error.set(e)}};error.get()?.let{throw it};return value.get()}
    private fun waitFor(check:()->Boolean){val until=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<until){if(check())return;Thread.sleep(40)};error("Live service condition timed out")}
    @Test fun explicitConsentDurableVodReturnFreshResumeAndPlatformStopUseOneSession()=runBlocking {
        val fixtureOwner="9000006039"
        val owner=app.accountKey;val realAccounts=app.accounts;val realLive=app.live;val settings=app.settings.current()
        check(owner!=fixtureOwner && app.stores.load(fixtureOwner)==null)
        val accountsField=ListenApplication::class.java.getDeclaredField("accounts").apply{isAccessible=true}
        val liveField=ListenApplication::class.java.getDeclaredField("live").apply{isAccessible=true}
        val marker=File(app.noBackupFilesDir,"m6h-test-preserve.json");check(!marker.exists());marker.writeText(Json.encodeToString(settings))
        val accounts=object:AccountRepository {
            override val session=MutableStateFlow(AccountSession(SessionStamp(fixtureOwner,1),SessionStatus.AUTHENTICATED,Account(fixtureOwner.toLong(),"示例账号")))
            override suspend fun verify()=session.value.account
            override suspend fun accept(cookie:String)=error("unused")
            override suspend fun logout(){}
            override fun requireCurrent(stamp:SessionStamp){check(stamp==session.value.stamp)}
        }
        var status=1;var platformBlocked=false;var pure=false;var reads=0;var streamReads=0
        val live=object:LiveRepository {
            override suspend fun room(id:Long):LiveRoom{reads++;if(platformBlocked)throw PlatformFailure("平台要求验证",-352);return LiveRoom(id,77,8,status,"静音连接边界样例","示例主播")}
            override suspend fun streams(id:Long):List<LiveStream>{streamReads++;return listOf(if(pure)LiveStream("https://m6h-fixture.bilivideo.com/no-media.aac","aac","aac",80,StreamKind.AUDIO_ONLY,"audio/aac") else LiveStream("https://m6h-fixture.bilivideo.com/no-media.flv","flv","avc",80))}
        }
        var first:com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
        var second:com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
        var c:MediaController?=null
        try {
            main{accountsField.set(app,accounts);liveField.set(app,live);app.accountKey=fixtureOwner;app.widgets.accountChanged(fixtureOwner)}
            app.settings.update(settings.copy(externalOutputOnly=false,preferredOutputType=null,storage=StorageSettings()))
            first=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()}
            second=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()}
            val controller=first.get(15,TimeUnit.SECONDS);c=controller;val other=second.get(15,TimeUnit.SECONDS)
            fun command(action:String,args:Bundle=Bundle.EMPTY)=main{controller.sendCustomCommand(PlaybackCommands.command(action),args)}.get(25,TimeUnit.SECONDS)
            fun state()=main{controller.sessionExtras.getString("liveExperience")?.let{Json.decodeFromString<LiveExperience>(it)} ?: LiveExperience()}
            val entries=listOf(QueueEntry("m6h-first","BV1xx411c7mD",1,1,"保留 P1"),QueueEntry("m6h-second","BV1xx411c7mD",2,2,"保留 P2"))
            val q=ResumeSnapshot(account=fixtureOwner,entries=entries,order=entries.map{it.id},currentId=entries.last().id,positionMs=34567,mode=PlayMode.REPEAT_ALL,speeds=mapOf("BV1xx411c7mD" to 1.5f))
            fun replace(queue:ResumeSnapshot)=command(PlaybackCommands.REPLACE_QUEUE,Bundle().apply{putString("snapshot",SnapshotCodec.encode(queue));putBoolean("play",false)})
            assertEquals(SessionResult.RESULT_SUCCESS,replace(q).resultCode)
            fun start(consent:Boolean)=command(PlaybackCommands.PLAY_LIVE,Bundle().apply{putLong("room",77);putBoolean("mixedConsent",consent);putLong("generation",app.vault.generation)})
            assertNotEquals(SessionResult.RESULT_SUCCESS,start(false).resultCode)
            assertEquals(q.currentId,main{controller.currentMediaItem?.mediaId});assertEquals(q.positionMs,main{controller.currentPosition});assertFalse(main{controller.playWhenReady})
            assertEquals(SessionResult.RESULT_SUCCESS,start(true).resultCode)
            main{controller.pause()};waitFor{main{!controller.playWhenReady&&!other.playWhenReady}}
            waitFor{state().phase==LivePhase.PAUSED}
            waitFor{main{!controller.isLoading}}
            assertEquals(77L,state().roomId);assertFalse(state().canSeekWindow);assertTrue(state().canReturnVod)
            assertEquals(Player.REPEAT_MODE_OFF,main{controller.repeatMode});assertEquals(1f,main{controller.playbackParameters.speed},0f)
            assertFalse(main{controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)})
            assertEquals("live:77",main{other.currentMediaItem?.mediaId})
            val saved=requireNotNull(app.stores.load(fixtureOwner)).queue
            assertEquals(q.entries,saved.entries);assertEquals(q.currentId,saved.currentId);assertEquals(q.positionMs,saved.positionMs);assertEquals(q.mode,saved.mode);assertEquals(q.speeds,saved.speeds)
            status=0;val before=reads;main{controller.play()};waitFor{state().phase==LivePhase.ENDED}
            assertTrue(reads>before);assertFalse(main{controller.playWhenReady})
            status=1;platformBlocked=true;main{controller.play()};waitFor{state().phase==LivePhase.FAILED}
            assertEquals("平台要求验证",state().notice);assertEquals(0,state().attempt)
            val blockedReads=reads;Thread.sleep(1500);assertEquals(blockedReads,reads)
            platformBlocked=false;pure=true;assertEquals(SessionResult.RESULT_SUCCESS,start(false).resultCode)
            main{controller.pause()};waitFor{state().phase==LivePhase.PAUSED};assertEquals(StreamKind.AUDIO_ONLY,state().kind)
            assertEquals(SessionResult.RESULT_SUCCESS,replace(saved).resultCode)
            waitFor{main{other.currentMediaItem?.mediaId==q.currentId}};assertFalse(main{controller.playWhenReady});assertEquals(q.positionMs,main{controller.currentPosition})
            val out=File(app.filesDir,"m6h-evidence").apply{mkdirs()}
            File(out,"service.json").writeText("""{"sameSessionControllers":true,"consentRefusalPreservesVod":true,"durableQueuePositionModeSpeedPreserved":true,"pauseStopsLiveLoading":true,"resumeRechecksRoom":true,"confirmedOfflineStops":true,"platformRestrictionDoesNotRetry":true,"explicitAudioOnlySelected":true,"vodReturnPaused":true,"actualMediaDecoded":false,"realRoomPlayed":false,"remoteWrites":0}""")
        }finally{
            c?.let{controller->runCatching{main{controller.pause();controller.sendCustomCommand(PlaybackCommands.command(PlaybackCommands.CLEAR),Bundle.EMPTY)}.get(12,TimeUnit.SECONDS)}}
            main{first?.let(MediaController::releaseFuture);second?.let(MediaController::releaseFuture);accountsField.set(app,realAccounts);liveField.set(app,realLive);app.accountKey=owner;app.widgets.accountChanged(owner)}
            app.settings.update(settings);marker.delete()
            app.database.listenDao().clearPlayback(fixtureOwner);app.database.listenDao().removeAllHistory(fixtureOwner);app.database.listenDao().removeAllLive(fixtureOwner)
        }
        Unit
    }
}
