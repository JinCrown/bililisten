package app.bililisten.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
class M0PlaybackProbe {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext.applicationContext as ListenApplication
    private val args=InstrumentationRegistry.getArguments()
    private fun <T> main(action:()->T):T {
        val value=AtomicReference<T>();val error=AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { value.set(action()) } catch(e:Throwable){error.set(e)} }
        error.get()?.let{throw it};return value.get()
    }
    private fun command(c:MediaController, action:String, args:Bundle=Bundle.EMPTY) {
        assertEquals(SessionResult.RESULT_SUCCESS,main{c.sendCustomCommand(PlaybackCommands.command(action),args)}.get(15,TimeUnit.SECONDS).resultCode)
    }
    private fun save(name:String,value:JsonElement){File(app.filesDir,"m0-evidence").mkdirs();File(app.filesDir,"m0-evidence/$name.json").writeText(value.toString())}
    private fun awaitPlayback(c:MediaController) {
        val end=System.currentTimeMillis()+30000
        while(System.currentTimeMillis()<end){if(main{c.playerError!=null})throw AssertionError(main{c.playerError!!.errorCodeName});if(main{c.isPlaying})return;Thread.sleep(200)}
        throw AssertionError("No actual playback within 30 seconds")
    }
    @Test fun actualAudioAndCompleteShuffleSnapshot()=runBlocking {
        assumeTrue(args.getString("m0phase")=="playback")
        val account=app.api.account();main{app.accountKey=account.id.toString()}
        val folders=app.api.folders(account.id)
        val music=folders.single{it.title=="音乐"}
        val samples=listOf("BV1U1421r7SM" to 1,"BV17ghy6AEEs" to 1,"BV1Qtht61EiG" to 1,"BV1rHh16CESS" to 1,"BV1c4411d7jb" to 2)
        val entries=samples.mapIndexed { index,(bv,p) ->
            val v=app.api.video(bv);val part=v.parts.single{it.number==p}
            QueueEntry("m0-real-$index",bv,part.cid,p,v.title+" · "+part.title,if(bv=="BV1rHh16CESS")music.id else null)
        }
        val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()}
        val c=future.get(15,TimeUnit.SECONDS);val evidence=mutableListOf<JsonElement>()
        try {
            val snapshot=ResumeSnapshot(account=account.id.toString(),entries=entries,order=entries.map{it.id},currentId=entries.first().id,positionMs=0)
            command(c,PlaybackCommands.REPLACE_QUEUE,Bundle().apply{putString("snapshot",SnapshotCodec.encode(snapshot));putBoolean("play",true)})
            entries.forEachIndexed { index,entry ->
                if(index>0)main{c.seekTo(index,0);c.prepare();c.play()}
                awaitPlayback(c);val before=main{c.currentPosition};Thread.sleep(2500)
                val after=main{c.currentPosition};assertTrue(after>before+1500)
                evidence+=buildJsonObject{put("bvid",entry.bvid);put("cid",entry.cid);put("part",entry.part);put("advancedMs",after-before);put("audioActive",(app.getSystemService(Context.AUDIO_SERVICE)as AudioManager).isMusicActive)}
                save("actual-playback",JsonArray(evidence))
            }
            main{c.pause();c.seekTo(76543)}
            command(c,PlaybackCommands.SET_MODE,Bundle().apply{putString("mode",PlayMode.SHUFFLE.name)})
            command(c,PlaybackCommands.FLUSH)
            val saved=SnapshotCodec.decode(app.database.listenDao().resume(account.id.toString())!!.payload)
            assertEquals(PlayMode.SHUFFLE,saved.mode);assertEquals(76543L,saved.positionMs);assertEquals(entries.last().id,saved.currentId)
            assertEquals(entries,saved.entries);assertEquals(entries.last().id,saved.order.first())
            assertTrue(app.database.listenDao().history(account.id.toString()).first().map{it.cid}.containsAll(entries.map{it.cid}))
            save("complete-resume",buildJsonObject{put("entries",entries.size);put("currentPart",2);put("positionMs",saved.positionMs);put("sourceFolderPreserved",saved.entries.any{it.sourceFolder==music.id});put("mode",saved.mode.name);put("order",JsonArray(saved.order.map{JsonPrimitive(it)}));put("historyFromActualPlayback",true)})
        } finally {main{c.pause();MediaController.releaseFuture(future)}}
    }

    @Test fun threeSourcesActuallyPlayAndPersist() = runBlocking {
        assumeTrue(args.getString("m0phase") == "source-playback")
        val account = app.api.account(); main { app.accountKey = account.id.toString() }
        val original = app.database.listenDao().resume(account.id.toString())
        // Keep the original checkpoint in app-private storage for the later cold-restore test's cleanup.
        original?.let { File(app.noBackupFilesDir, "m0-source-original-resume.json").writeText(it.payload) }
        val music = app.api.folders(account.id).single { it.title == "音乐" }
        val own = app.api.favoriteSource(music.id, account = account.id)
        val other = app.api.favoriteSource(2578744971, expectedOwner = 17340771)
        val season = app.api.seasonSource(57445, expectedOwner = 2142762)
        val pages = listOf(own, other, season)
        val entries = pages.mapIndexed { i, page ->
            val v = app.api.video(page.items.first().bvid)
            val part = v.parts.first()
            QueueEntry("m0-source-$i", v.bvid, part.cid, part.number, v.title, source = page.source.ref)
        }
        val future = main { MediaController.Builder(app, SessionToken(app, ComponentName(app, ListenService::class.java))).buildAsync() }
        val c = future.get(15, TimeUnit.SECONDS)
        val results = mutableListOf<JsonElement>()
        try {
            val snapshot = ResumeSnapshot(account = account.id.toString(), entries = entries, order = entries.map { it.id }, currentId = entries.first().id, positionMs = 0)
            command(c, PlaybackCommands.REPLACE_QUEUE, Bundle().apply { putString("snapshot", SnapshotCodec.encode(snapshot)); putBoolean("play", true) })
            entries.forEachIndexed { i, entry ->
                if (i > 0) main { c.seekTo(i, 0); c.prepare(); c.play() }
                awaitPlayback(c); val start = main { c.currentPosition }; Thread.sleep(2000)
                val advanced = main { c.currentPosition } - start
                assertTrue(advanced >= 1500)
                assertEquals(entry.source, main { c.currentMediaItem!!.queueEntry()!!.source })
                main { c.pause() }; command(c, PlaybackCommands.FLUSH)
                val history = app.database.listenDao().history(account.id.toString()).first().first { it.bvid == entry.bvid && it.cid == entry.cid }
                assertEquals(entry.source, history.entry("restored").source)
                results += buildJsonObject { put("sourceKind", entry.source!!.kind.name); put("advancedMs", advanced); put("mediaSessionSource", true); put("historySource", true) }
                save("source-playback", JsonArray(results))
            }
            main { c.seekTo(23456) }; command(c, PlaybackCommands.SET_MODE, Bundle().apply { putString("mode", PlayMode.SHUFFLE.name) })
            command(c, PlaybackCommands.FLUSH)
            val saved = SnapshotCodec.decode(app.database.listenDao().resume(account.id.toString())!!.payload)
            assertEquals(entries, saved.entries); assertEquals(23456L, saved.positionMs)
            File(app.noBackupFilesDir, "m0-source-expected.json").writeText(SnapshotCodec.encode(saved))
        } finally { main { c.pause(); MediaController.releaseFuture(future) } }
    }

    @Test fun coldRestorePreservesAllSourceIdentitiesAndRestoresUserQueue() = runBlocking {
        assumeTrue(args.getString("m0phase") == "source-restore")
        val account = app.api.account(); main { app.accountKey = account.id.toString() }
        val expectedFile = File(app.noBackupFilesDir, "m0-source-expected.json")
        val expected = SnapshotCodec.decode(expectedFile.readText())
        val saved = SnapshotCodec.decode(app.database.listenDao().resume(account.id.toString())!!.payload)
        assertEquals(expected.entries, saved.entries); assertEquals(expected.order, saved.order); assertEquals(expected.positionMs, saved.positionMs)
        val future = main { MediaController.Builder(app, SessionToken(app, ComponentName(app, ListenService::class.java))).buildAsync() }
        val c = future.get(15, TimeUnit.SECONDS)
        try {
            assertFalse(main { c.playWhenReady })
            command(c, PlaybackCommands.REPLACE_QUEUE, Bundle().apply { putString("snapshot", SnapshotCodec.encode(saved)); putBoolean("play", false) })
            assertFalse(main { c.isPlaying }); assertEquals(saved.positionMs, main { c.currentPosition })
            assertEquals(saved.playbackEntries().map { it.source }, main { (0 until c.mediaItemCount).map { c.getMediaItemAt(it).queueEntry()!!.source } })
            main { c.prepare(); c.play() }; awaitPlayback(c); Thread.sleep(2000)
            assertTrue(main { c.currentPosition } >= saved.positionMs + 1000)
            main { c.pause() }
            save("source-cold-restore", buildJsonObject { put("sourceKinds", 3); put("silentRestore", true); put("realResume", true); put("shufflePreserved", true); put("positionMs", saved.positionMs) })
        } finally {
            main { c.pause() }
            val original = File(app.noBackupFilesDir, "m0-source-original-resume.json")
            if (original.exists()) {
                command(c, PlaybackCommands.REPLACE_QUEUE, Bundle().apply { putString("snapshot", original.readText()); putBoolean("play", false) })
                command(c, PlaybackCommands.FLUSH); original.delete()
            }
            expectedFile.delete(); main { MediaController.releaseFuture(future) }
        }
    }

    @Test fun coldRestoreIsSilentAndOrderSurvives()=runBlocking {
        assumeTrue(args.getString("m0phase")=="restore")
        val account=app.api.account();main{app.accountKey=account.id.toString()}
        val saved=SnapshotCodec.decode(app.database.listenDao().resume(account.id.toString())!!.payload)
        val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()}
        val c=future.get(15,TimeUnit.SECONDS)
        try {
            assertFalse(main{c.playWhenReady});assertEquals(0,main{c.mediaItemCount})
            command(c,PlaybackCommands.REPLACE_QUEUE,Bundle().apply{putString("snapshot",SnapshotCodec.encode(saved));putBoolean("play",false)})
            assertFalse(main{c.isPlaying});assertEquals(saved.positionMs,main{c.currentPosition})
            assertEquals(saved.order,main{(0 until c.mediaItemCount).map{c.getMediaItemAt(it).mediaId}})
            main{c.seekToNextMediaItem()};assertEquals(saved.order[1],main{c.currentMediaItem!!.mediaId})
            main{c.seekToPreviousMediaItem()};assertEquals(saved.order[0],main{c.currentMediaItem!!.mediaId})
            main{c.seekTo(saved.positionMs)};command(c,PlaybackCommands.FLUSH)
            save("cold-restore",buildJsonObject{put("silent",true);put("positionPreserved",true);put("orderPreserved",true);put("previousNextMatch",true)})
        } finally {main{MediaController.releaseFuture(future)}}
    }
    @Test fun deletedHistorySurvivesPausedCheckpointAndServiceTeardown()=runBlocking {
        assumeTrue(args.getString("m0phase")=="history-delete")
        val previousAccount=app.accountKey
        val account="m0-history-delete-test"
        main{app.accountKey=account}
        val entry=QueueEntry("delete-test", "BV1U1421r7SM",1599921141,1,"历史删除测试",null)
        val saved=ResumeSnapshot(account=account,entries=listOf(entry),order=listOf(entry.id),currentId=entry.id,positionMs=0)
        val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()}
        val c=future.get(15,TimeUnit.SECONDS)
        val dao=app.database.listenDao()
        try {
            command(c,PlaybackCommands.REPLACE_QUEUE,Bundle().apply{putString("snapshot",SnapshotCodec.encode(saved));putBoolean("play",true)})
            awaitPlayback(c);Thread.sleep(1800);main{c.pause()};command(c,PlaybackCommands.FLUSH)
            assertTrue(dao.history(account).first().any{it.bvid==entry.bvid})
            command(c,PlaybackCommands.FORGET_HISTORY,Bundle().apply{putString("bvid",entry.bvid);putLong("cid",entry.cid)})
            dao.deleteHistory(account,entry.bvid,entry.cid)
            command(c,PlaybackCommands.FLUSH)
            assertTrue(dao.history(account).first().isEmpty())
            assertNotNull(dao.resume(account))
            main{MediaController.releaseFuture(future);app.stopService(android.content.Intent(app,ListenService::class.java))}
            instrumentation.waitForIdleSync();Thread.sleep(500)
            assertTrue(dao.history(account).first().isEmpty())
            save("history-delete",buildJsonObject{put("realPlaybackCreatedHistory",true);put("deletionSurvivedPausedFlush",true);put("deletionSurvivedServiceTeardown",true);put("resumePreserved",true);put("isolatedTestAccount",true)})
        }finally{main{MediaController.releaseFuture(future);app.stopService(android.content.Intent(app,ListenService::class.java));app.accountKey=previousAccount};dao.clearHistory(account)}
    }
    @Test fun coldResumeReallyStartsAtSavedAudioPosition()=runBlocking {
        assumeTrue(args.getString("m0phase")=="resume-audio")
        val account=app.api.account();main{app.accountKey=account.id.toString()}
        val saved=SnapshotCodec.decode(app.database.listenDao().resume(account.id.toString())!!.payload)
        require(saved.positionMs>30000)
        val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()}
        val c=future.get(15,TimeUnit.SECONDS)
        try {
            assertFalse(main{c.playWhenReady})
            command(c,PlaybackCommands.REPLACE_QUEUE,Bundle().apply{putString("snapshot",SnapshotCodec.encode(saved));putBoolean("play",true)})
            awaitPlayback(c);Thread.sleep(3000)
            val actual=main{c.currentPosition}
            save("resumed-audio-position",buildJsonObject{put("savedMs",saved.positionMs);put("playingMs",actual);put("part",saved.entries.first{it.id==saved.currentId}.part);put("isPlaying",main{c.isPlaying})})
            assertTrue("Actual playback lost saved position",actual>=saved.positionMs&&actual<saved.positionMs+15000)
        }finally{main{c.pause()};command(c,PlaybackCommands.FLUSH);main{MediaController.releaseFuture(future)}}
    }
}
