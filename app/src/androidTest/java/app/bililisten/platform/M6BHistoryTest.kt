package app.bililisten.platform

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.shared.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M6BHistoryTest {
    @Test fun latestVideoProgressSurvivesReopenAndIsSeparatedByAccountAndClearedWithHistory()=runBlocking {
        val name="video-continuation-fixture.db";context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,ListenDatabase::class.java,name).build()
        var db=open()
        try {
            var stores=RoomStores(db.listenDao());val bv="BV1BDk2YCEHF"
            val parts=List(150){VideoPart(it+101L,it+1,"song ${it+1}",200)}
            val row=LocalHistoryEntry("7",VideoRef(bv,142,42),"song 42",null,95123,300,HistoryOrigin.SEARCH)
            val queue=ResumeSnapshot(account="7",entries=parts.map{QueueEntry("part-${it.number}",bv,it.cid,it.number,it.title)},order=parts.map{"part-${it.number}"},currentId="part-42",positionMs=95123)
            stores.checkpoint(PlaybackSnapshot(queue,300),row.copy(video=VideoRef(bv,149,49),playedAt=100))
            stores.checkpoint(PlaybackSnapshot(queue,300),row)
            assertNull(stores.latestVideo("8",bv));assertEquals(row,stores.latestVideo("7",bv))
            db.close();db=open();stores=RoomStores(db.listenDao())
            val durable=stores.latestVideo("7",bv)!!
            val start=VideoContinuation.prepare(Video(bv,1,"150 songs",parts),durable.video,durable.positionMs,UserSettings().continuousParts)
            assertEquals(41,start.currentIndex);assertEquals(95123L,start.positionMs);assertEquals(150,start.parts.size)
            assertEquals(queue,stores.load("7")!!.queue)
            stores.delete("7");assertNull(stores.latestVideo("7",bv));assertNotNull(stores.load("7"))
        }finally{db.close();context.deleteDatabase(name)}
    }
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun history(at:Long=100,cid:Long=1,account:String="7")=LocalHistoryEntry(account,VideoRef("BV1xx411c7mD",cid,1),"course",null,12345,at,HistoryOrigin.SEARCH)
    private fun snapshot(account:String="7")=PlaybackSnapshot(ResumeSnapshot(account=account,entries=listOf(history().entry("a")),order=listOf("a"),currentId="a",positionMs=12345,speeds=mapOf("BV1xx411c7mD" to 1.5f)),100,PauseReason.TIMER)
    @Test fun deletedAndClearedHistoryCannotBeResurrectedByLateCheckpointEvenAfterReopen()=runBlocking {
        val name="m6b-delete-fixture.db";context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,ListenDatabase::class.java,name).build()
        var db=open()
        try {
            var stores=RoomStores(db.listenDao());val saved=snapshot()
            stores.checkpoint(saved,history());stores.checkpointLive(LiveHistoryEntry("7",6,"room",100))
            db.listenDao().deleteHistory("7","BV1xx411c7mD",1,200)
            stores.checkpoint(saved,history());assertTrue(stores.observe("7").first().isEmpty())
            assertEquals(1,stores.observeLive("7").first().size)
            stores.checkpoint(saved,history(201));assertEquals(1,stores.observe("7").first().size)
            db.listenDao().clearHistory("7",300)
            db.close();db=open();stores=RoomStores(db.listenDao())
            stores.checkpoint(saved,history(201));stores.checkpointLive(LiveHistoryEntry("7",6,"late",100))
            assertTrue(stores.observe("7").first().isEmpty());assertTrue(stores.observeLive("7").first().isEmpty())
            assertEquals(saved,stores.load("7"))
            stores.checkpoint(saved,history(301));stores.checkpointLive(LiveHistoryEntry("7",6,"new listen",301))
            assertEquals(1,stores.observe("7").first().size);assertEquals(1,stores.observeLive("7").first().size)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun retentionCombinesLiveAndVideoPreservesAllWhenSelectedAndDoesNotAffectOtherStores()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ListenDatabase::class.java).build()
        try {
            val stores=RoomStores(db.listenDao());val saved=snapshot()
            val source=SourceRef(SourceKind.PUBLIC_FAVORITES,9,8)
            val mirror=CollectionSnapshot("7",source,listOf(ContentItem("BV1xx411c7mD","saved")),true,10,1)
            stores.saveCollection(mirror)
            repeat(40){n->stores.checkpoint(saved,history(n+1L,n+1L))}
            stores.checkpointLive(LiveHistoryEntry("7",6,"live",41));stores.checkpoint(snapshot("8"),history(1,1,"8"))
            stores.prune("7",HistoryRetentionPolicy(1,1,keepAll=true),1000)
            assertEquals(40,stores.observe("7").first().size);assertEquals(1,stores.observeLive("7").first().size)
            stores.prune("7",HistoryRetentionPolicy(2,100),50)
            assertEquals(listOf(40L),stores.observe("7").first().map{it.playedAt})
            stores.checkpoint(saved,history(1,1));assertEquals(1,stores.observe("7").first().size)
            db.listenDao().deleteLive("7",6,60);stores.checkpointLive(LiveHistoryEntry("7",6,"late",41))
            assertTrue(stores.observeLive("7").first().isEmpty())
            assertEquals(mirror,stores.collection("7",source));assertEquals(saved,stores.load("7"));assertEquals(1,stores.observe("8").first().size)
            stores.delete("7");assertEquals(mirror,stores.collection("7",source));assertEquals(saved,stores.load("7"))
        }finally{db.close()}
    }
    @Test fun everyOriginAndLiveIdentityPersistWithoutFakeCid()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ListenDatabase::class.java).build()
        try {
            val stores=RoomStores(db.listenDao())
            HistoryOrigin.entries.forEachIndexed { index,origin -> stores.checkpoint(snapshot(),history(100+index.toLong(),index+1L).copy(origin=origin)) }
            assertEquals(HistoryOrigin.entries.toSet(),stores.observe("7").first().map{it.origin}.toSet())
            stores.checkpointLive(LiveHistoryEntry("7",6,"room",200))
            val timeline=HistorySearch.filter(stores.observe("7").first(),stores.observeLive("7").first())
            assertNotNull(timeline.first().live);assertNull(timeline.first().video)
            assertEquals(HistoryOrigin.entries.size,db.listenDao().allHistory("7").size)
        }finally{db.close()}
    }
}
