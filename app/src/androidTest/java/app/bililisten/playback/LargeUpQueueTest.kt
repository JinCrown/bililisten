package app.bililisten.playback

import android.os.Bundle
import android.os.Parcel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.platform.ListenDatabase
import app.bililisten.platform.RoomStores
import app.bililisten.platform.RoomOrganizerStore
import app.bililisten.platform.LocalTransferRepository
import app.bililisten.platform.TransferCodec
import app.bililisten.shared.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@UnstableApi @RunWith(AndroidJUnit4::class)
class LargeUpQueueTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block:()->T):T {val out=AtomicReference<T>();val err=AtomicReference<Throwable>();i.runOnMainSync{try{out.set(block())}catch(e:Throwable){err.set(e)}};err.get()?.let{throw it};return out.get()}
    private val source=SourceRef(SourceKind.UP_UPLOADS,8,8)
    private fun snapshot():ResumeSnapshot {
        val rows=List(1887){QueueEntry("up-song-$it","BV"+(it+1).toString().padStart(10,'0'),if(it==0)1 else 0,1,"示例歌曲 $it",source=source)}
        return ResumeSnapshot(account="7",entries=rows,order=rows.map{it.id},currentId=rows.first().id,positionMs=12345)
    }
    @Test fun privateFileHandoffAndRemoteTimelineKeepAll1887AndResolveWithoutDecoderReload()=runBlocking<Unit> {
        val directory=File(i.targetContext.cacheDir,"up-queue-native-fixture")
        val handoff=QueueHandoff(directory);val initial=snapshot();val name=handoff.write(initial)
        val opened=AtomicInteger()
        val factory=DataSource.Factory{opened.incrementAndGet();DefaultDataSource.Factory(i.targetContext).createDataSource()}
        val player=main{ExoPlayer.Builder(i.targetContext).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()}
        val queue=PlaybackQueue(player)
        val session=main{MediaSession.Builder(i.targetContext,player).setId("up-native-${System.nanoTime()}").build()}
        val future=main{MediaController.Builder(i.targetContext,session.token).buildAsync()}
        val controller=future.get(15,TimeUnit.SECONDS)
        try {
            val parcel=Parcel.obtain();val binderBytes:Int
            try{parcel.writeBundle(Bundle().apply{putString("queueFile",name);putBoolean("play",false)});binderBytes=parcel.dataSize();assertTrue(binderBytes<2048)}finally{parcel.recycle()}
            val recovered=handoff.read(name);assertEquals(initial,recovered)
            main{queue.replace(recovered,false)}
            val end=System.currentTimeMillis()+15000
            while(System.currentTimeMillis()<end&&main{controller.mediaItemCount!=1887})Thread.sleep(50)
            main {
                assertEquals(1887,controller.mediaItemCount)
                assertEquals("up-song-1886",controller.getMediaItemAt(1886).mediaId)
                assertFalse(player.playWhenReady);assertEquals(Player.STATE_IDLE,player.playbackState)
                queue.edit(QueueEdit.Move("up-song-1886",1),queue.snapshot()!!.queueVersion)
                assertEquals("up-song-0",player.currentMediaItem!!.mediaId);assertEquals(12345L,player.currentPosition)
                val pending=player.getMediaItemAt(1);val uri=pending.localConfiguration!!.uri
                assertTrue(queue.resolveVideo(Video("BV0000001887",1,"已解析的示例",listOf(VideoPart(987,1,"P1")),owner=9,collaborators=setOf(8))))
                assertEquals(uri,player.getMediaItemAt(1).localConfiguration!!.uri)
                assertEquals(987L,queue.snapshot()!!.entries.first{it.id=="up-song-1886"}.cid)
                assertEquals(987L,player.getMediaItemAt(1).queueEntry()!!.cid)
                assertEquals(12345L,player.currentPosition);assertEquals("up-song-0",player.currentMediaItem!!.mediaId)
                queue.edit(QueueEdit.Remove("up-song-1000"),queue.snapshot()!!.queueVersion);assertEquals(1886,player.mediaItemCount)
            }
            assertEquals(0,opened.get())
            val folder=File(i.targetContext.filesDir,"up-library-evidence").apply{mkdirs()}
            File(folder,"large-queue.json").writeText("""{"entries":1887,"controllerEntries":1887,"binderBytes":$binderBytes,"fileHandoff":true,"nativeReorderAndRemove":true,"metadataResolutionRetainsUriAndPosition":true,"audioDataSourcesCreated":0,"userPlaybackTouched":false}""")
        } finally {main{controller.release();session.release();player.release()};handoff.discard(name);directory.delete()}
    }
    @Test fun roomPersistsLazyQueueAndUpBookmarksWithoutChangingEarlierSources()=runBlocking<Unit> {
        val db=Room.inMemoryDatabaseBuilder(i.targetContext,ListenDatabase::class.java).build()
        try {
            val store=RoomStores(db.listenDao());val queue=snapshot()
            store.checkpoint(PlaybackSnapshot(queue,100000,PauseReason.USER),null)
            assertEquals(queue,store.load("7")!!.queue)
            assertNull(store.load("8"))
            val up=ContentSource(source,"音乐 UP 的投稿","音乐 UP",1887,"//i0.hdslb.com/avatar.jpg")
            val other=ContentSource(SourceRef(SourceKind.PUBLIC_FAVORITES,9,10),"旧收藏","旧 UP",20)
            store.bookmark("7",other,true);store.bookmark("7",up,true)
            assertEquals(2,store.bookmarks("7").size);assertTrue(store.bookmarks("8").isEmpty())
            assertEquals(up.cover,store.bookmarks("7").first{it.source==source}.cover)
            store.markHeard("7",source,"BV0000000001")
            assertEquals(setOf("BV0000000001"),store.updateCheckpoint("7",source)!!.heard)
            store.bookmark("7",up,false);assertEquals(other.ref,store.bookmarks("7").single().source)
            assertEquals(queue,store.load("7")!!.queue)
            val folder=File(i.targetContext.filesDir,"up-library-evidence").apply{mkdirs()}
            File(folder,"storage.json").writeText("""{"queueEntries":1887,"lazyResume":true,"upBookmarkAvatar":true,"accountIsolation":true,"removeBookmarkKeepsQueueAndOtherSource":true,"realDatabaseTouched":false}""")
        } finally {db.close()}
    }
    @Test fun portableUpCollectionRoundTripPreservesAvatarHeardAndHistoryOrigin()=runBlocking<Unit> {
        val first=Room.inMemoryDatabaseBuilder(i.targetContext,ListenDatabase::class.java).build()
        val second=Room.inMemoryDatabaseBuilder(i.targetContext,ListenDatabase::class.java).build()
        fun transfer(db:ListenDatabase):LocalTransferRepository {
            val legacy=object:OrganizerStore {override suspend fun read(account:String)=OrganizerData();override suspend fun write(account:String,value:OrganizerData){}}
            return LocalTransferRepository(db,RoomOrganizerStore(db.listenDao(),legacy),{SessionStamp("7",1)},{100000})
        }
        try {
            val old=RoomStores(first.listenDao());val fresh=RoomStores(second.listenDao())
            val bvid="BV0000000001";val up=ContentSource(source,"示例 UP 的投稿","示例 UP",1887,"https://i0.hdslb.com/example.jpg")
            old.bookmark("7",up,true);old.markHeard("7",source,bvid)
            old.checkpoint(PlaybackSnapshot(snapshot(),90000,PauseReason.USER),LocalHistoryEntry("7",VideoRef(bvid,1,1),"示例歌曲",source,12345,90000,HistoryOrigin.UP_UPLOADS))
            fresh.checkpoint(PlaybackSnapshot(snapshot(),80000,PauseReason.USER),null)
            val encoded=TransferCodec.encode(transfer(first).export(SessionStamp("7",1)))
            val result=transfer(second).import(TransferCodec.decode(encoded),SessionStamp("7",1),HistoryRetentionPolicy(keepAll=true))
            assertEquals(1,result.bookmarks);assertEquals(1,result.histories)
            assertEquals(up.cover,fresh.bookmarks("7").single().cover)
            assertEquals(1887,fresh.bookmarks("7").single().total)
            assertEquals(setOf(bvid),fresh.updateCheckpoint("7",source)!!.heard)
            assertEquals(HistoryOrigin.UP_UPLOADS,fresh.observe("7").first().single().origin)
            assertEquals(snapshot(),fresh.load("7")!!.queue);assertTrue(fresh.bookmarks("8").isEmpty())
            assertEquals(0,transfer(second).import(TransferCodec.decode(encoded),SessionStamp("7",1),HistoryRetentionPolicy(keepAll=true)).bookmarks)
            val folder=File(i.targetContext.filesDir,"up-library-evidence").apply{mkdirs()}
            File(folder,"transfer.json").writeText("""{"upBookmarkAndAvatar":true,"heardAndHistoryOrigin":true,"idempotent":true,"accountIsolation":true,"existingQueueRetained":true,"realDatabaseTouched":false}""")
        } finally {first.close();second.close()}
    }
}
