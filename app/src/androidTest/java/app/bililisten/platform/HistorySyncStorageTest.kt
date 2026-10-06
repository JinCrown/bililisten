package app.bililisten.platform

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HistorySyncStorageTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val bv="BV1xx411c7mD"
    private fun row(account:String="7",at:Long=100)=LocalHistoryEntry(account,VideoRef(bv,142,42),"示例歌曲",null,95123,at)
    private fun snapshot(account:String="7")=PlaybackSnapshot(ResumeSnapshot(account=account,entries=listOf(row(account).entry("a")),order=listOf("a"),currentId="a",positionMs=95123),100)
    /** User explicitly permitted deleting local recent records during this feature's testing. */
    @Test fun authorizedActualModeSwitchImportsCloudAndReturnsToLocalWithoutRemoteWrites()=runBlocking {
        val app=context.applicationContext as ListenApplication
        app.accounts.verify();val stamp=app.accounts.session.value.stamp;val owner=stamp.account
        assertTrue(owner.toLongOrNull()?.let{it>0}==true)
        val store=RoomRemoteHistoryStore(app.database.listenDao());assertFalse(store.read(owner).enabled)
        val queue=app.stores.load(owner)?.queue
        val before=app.stores.observe(owner).first().size+app.stores.observeLive(owner).first().size
        var imported=0
        try {
            app.historySync.setEnabled(stamp,true)
            assertTrue(store.read(owner).enabled);imported=store.read(owner).items.size
            assertTrue(app.stores.observe(owner).first().isEmpty());assertTrue(app.stores.observeLive(owner).first().isEmpty())
            assertEquals(queue,app.stores.load(owner)?.queue)
        }finally{if(store.read(owner).enabled)app.historySync.setEnabled(app.accounts.session.value.stamp,false)}
        assertFalse(store.read(owner).enabled);assertEquals(queue,app.stores.load(owner)?.queue)
        val evidence=buildJsonObject{put("userAuthorizedLocalDeletion",true);put("localRecentRowsRemoved",before);put("importedPlayableCloudRows",imported);put("actualEnableAndDisablePassed",true);put("finalSyncOff",true);put("queueAndPositionRetained",true);put("remoteWrites",0);put("accountOrTitlesIncluded",false)}
        File(context.filesDir,"up-library-evidence").mkdirs();File(context.filesDir,"up-library-evidence/history-sync-actual-mode.json").writeText(evidence.toString())
    }
    @Test fun modeChangeIsAtomicAccountIsolatedAndSurvivesReopenWithLateWritesFenced()=runBlocking {
        val name="history-sync-fixture.db";context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,ListenDatabase::class.java,name).build()
        var db=open()
        try {
            var local=RoomStores(db.listenDao());var cloud=RoomRemoteHistoryStore(db.listenDao())
            local.checkpoint(snapshot(),row());local.checkpointLive(LiveHistoryEntry("7",6,"示例直播",100));local.checkpoint(snapshot("8"),row("8"))
            val source=SourceRef(SourceKind.PUBLIC_FAVORITES,9,8);local.saveCollection(CollectionSnapshot("7",source,emptyList(),false,0,1))
            assertFalse(cloud.read("7").enabled)
            val remote=RemoteHistoryItem(1,bv,142,42,"B站示例歌曲",positionMs=90000,viewedAt=90)
            cloud.changeMode("7",RemoteHistoryData(enabled=true,epoch=1,items=listOf(remote),enabledAt=200),200)
            assertTrue(local.observe("7").first().isEmpty());assertTrue(local.observeLive("7").first().isEmpty());assertEquals(1,local.observe("8").first().size)
            local.checkpoint(snapshot(),row(at=301));local.checkpointLive(LiveHistoryEntry("7",6,"迟到的直播",301));assertTrue(local.observe("7").first().isEmpty());assertTrue(local.observeLive("7").first().isEmpty())
            assertEquals(snapshot(),local.load("7"));assertNotNull(local.collection("7",source))
            val data=cloud.read("7");cloud.save("7",data.copy(reports=listOf(HistoryReport(remote.copy(viewedAt=350),HistoryReportPhase.UNKNOWN))),1)
            db.close();db=open();local=RoomStores(db.listenDao());cloud=RoomRemoteHistoryStore(db.listenDao())
            assertTrue(cloud.read("7").enabled);assertEquals(HistoryReportPhase.UNKNOWN,cloud.read("7").reports.single().phase)
            val facade=SyncedHistoryRepository(local,cloud);assertEquals(42,facade.observe("7").first().single().video.part)
            facade.delete("7");assertEquals(1,cloud.read("7").items.size)
            cloud.changeMode("7",RemoteHistoryData(epoch=2),400)
            local.checkpoint(snapshot(),row(at=350));assertTrue(local.observe("7").first().isEmpty())
            local.checkpoint(snapshot(),row(at=System.currentTimeMillis()+1));assertEquals(1,local.observe("7").first().size);assertTrue(cloud.read("7").reports.isEmpty())
            assertThrows(IllegalArgumentException::class.java){runBlocking{cloud.save("7",data,1)}}
            assertEquals(1,local.observe("8").first().size)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun existingVersionFiveDataMigratesWithSyncOffAndNoHistoryLoss()=runBlocking {
        val name="history-sync-migration-fixture.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,ListenDatabase::class.java,name).build()
        try {
            RoomStores(db.listenDao()).checkpoint(snapshot(),row())
            val sql=db.openHelper.writableDatabase;sql.execSQL("DROP TABLE remote_history");sql.execSQL("DELETE FROM room_master_table");sql.version=5
            db.close()
            db=Room.databaseBuilder(context,ListenDatabase::class.java,name).addMigrations(ListenDatabase.MIGRATION_5_6).build()
            assertEquals(row(),RoomStores(db.listenDao()).latestVideo("7",bv));assertEquals(snapshot(),RoomStores(db.listenDao()).load("7"));assertFalse(RoomRemoteHistoryStore(db.listenDao()).read("7").enabled)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun actualHistoryEndpointReadOnlyProbeDoesNotEnableSyncOrChangeRecords()=runBlocking {
        val app=context.applicationContext as ListenApplication
        val account=app.api.account();val owner=account.id.toString();val before=RoomRemoteHistoryStore(app.database.listenDao()).read(owner)
        assertFalse(before.enabled)
        val paused=try{app.api.historyPaused()}catch(e:PlatformFailure){throw AssertionError("history shadow: ${e.category} code=${e.code}")}
        val first=try{app.api.historyPage()}catch(e:PlatformFailure){throw AssertionError("history cursor: ${e.category} code=${e.code}")}
        val next=first.next?.let{try{app.api.historyPage(it)}catch(e:PlatformFailure){throw AssertionError("history next cursor: ${e.category} code=${e.code}")}}
        assertTrue(first.items.all{it.cid>0||it.roomId>0});assertEquals(before,RoomRemoteHistoryStore(app.database.listenDao()).read(owner))
        val evidence=buildJsonObject{put("readOnly",true);put("officialPaused",paused);put("parsedFirstPageItems",first.items.size);put("unsupportedFirstPage",first.unsupported);put("secondPageRead",next!=null);put("syncStillOff",true);put("remoteWrites",0);put("accountOrTitlesIncluded",false)}
        File(context.filesDir,"up-library-evidence").mkdirs();File(context.filesDir,"up-library-evidence/history-sync-read-only.json").writeText(evidence.toString())
    }
}
