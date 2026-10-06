package app.bililisten.platform

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Offline fixtures only: no real account, no network, no production DB changes. */
@RunWith(AndroidJUnit4::class)
class M1FoundationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = SourceRef(SourceKind.UP_COLLECTION,42,8,CollectionKind.SEASON)
    private val clock = object : Clock { override fun nowMs()=1000L }
    private fun snapshot() = PlaybackSnapshot(ResumeSnapshot(account="7",entries=listOf(
        QueueEntry("a","BV1xx411c7mD",1,2,"fixture",source=source),QueueEntry("b","BV1xx411c7mD",1,2,"duplicate",source=source)),
        order=listOf("b","a"),currentId="a",positionMs=12345,mode=PlayMode.SHUFFLE),1000,PauseReason.EXTERNAL_VIDEO)
    @Test fun versionTwoUpgradeKeepsTypedHistoryAndOldResume() = runBlocking {
        val name="m1-v2-fixture.db"; context.deleteDatabase(name)
        val old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        old.execSQL("CREATE TABLE resume (account TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
        old.execSQL("CREATE TABLE history (account TEXT NOT NULL, bvid TEXT NOT NULL, cid INTEGER NOT NULL, title TEXT NOT NULL, part INTEGER NOT NULL, sourceFolder INTEGER, positionMs INTEGER NOT NULL, playedAt INTEGER NOT NULL, sourceJson TEXT, PRIMARY KEY(account,bvid,cid))")
        old.execSQL("INSERT INTO resume VALUES (?,?)",arrayOf("7",SnapshotCodec.encode(snapshot().queue)))
        old.execSQL("INSERT INTO history VALUES ('7','BV1xx411c7mD',1,'fixture',2,NULL,12345,1000,?)",arrayOf(SourceCodec.encode(source)))
        old.version=2; old.close()
        val db=Room.databaseBuilder(context,ListenDatabase::class.java,name).addMigrations(ListenDatabase.MIGRATION_2_3, ListenDatabase.MIGRATION_3_4,ListenDatabase.MIGRATION_4_5,ListenDatabase.MIGRATION_5_6).build()
        try {
            val stores=RoomStores(db.listenDao())
            assertEquals(snapshot().queue,stores.load("7")!!.queue)
            assertEquals(PauseReason.UNKNOWN,stores.load("7")!!.pauseReason)
            assertEquals(source,stores.observe("7").first().single().source)
            assertEquals(4,db.openHelper.writableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun atomicQueueCheckpointRollsBackOnInvalidOrderAndRetentionIsAccountScoped() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ListenDatabase::class.java).build()
        try {
            val dao=db.listenDao(); val stores=RoomStores(dao); val saved=snapshot()
            stores.checkpoint(saved,LocalHistoryEntry("7",VideoRef("BV1xx411c7mD",1,2),"fixture",source,12345,1000))
            assertEquals(listOf("b","a"),dao.queue("7").map{it.entryId}); assertEquals(saved,stores.load("7"))
            try {
                dao.persistPlayback(ResumeRow("7","must-rollback"),null,PlaybackMetaRow("7",2000,"USER",1),listOf(QueueRow("7","x",0,"x"),QueueRow("7","y",0,"y")))
                fail("unique order must reject")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertEquals(saved,stores.load("7")); assertEquals(listOf("b","a"),dao.queue("7").map{it.entryId})
            dao.played(HistoryRow("8","BV1xx411c7mD",1,"other",2,null,1,1))
            dao.played(HistoryRow("7","BV1xx411c7mD",2,"old",2,null,1,1))
            stores.prune("7",HistoryRetentionPolicy(1,100),1000)
            assertEquals(1,stores.observe("7").first().size); assertEquals(1,stores.observe("8").first().size)
            stores.delete("7"); assertEquals(saved,stores.load("7"))
        } finally { db.close() }
    }
    @Test fun mirrorsMutationsAndHeardCheckpointsSurviveDatabaseReopen() = runBlocking {
        val name="m1-journal-fixture.db"; context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,ListenDatabase::class.java,name).build()
        var db=open()
        try {
            var stores=RoomStores(db.listenDao())
            val mirror=CollectionSnapshot("7",source,listOf(ContentItem("BV1xx411c7mD","fixture")),true,100,3)
            stores.saveCollection(mirror); stores.saveCollection(mirror.copy(revision=2,items=emptyList()))
            stores.saveMutation(MutationRecord("op","7",1,2,true,100))
            val checkpoint=SourceCheckpoint("7",source,setOf("one","two"),setOf("one"))
            stores.saveUpdate(checkpoint); stores.markHeard("7",source,"two")
            stores.saveUpdate(checkpoint) // A refresh carrying old heard state must not erase the playback write.
            db.close(); db=open(); stores=RoomStores(db.listenDao())
            assertEquals(mirror,stores.collection("7",source)); assertNull(stores.collection("8",source))
            assertEquals("op",stores.mutation("7")!!.id); assertNull(stores.mutation("8"))
            assertEquals(checkpoint.copy(heard=setOf("one","two")),stores.updateCheckpoint("7",source))
            stores.removeMutation("7"); assertNull(stores.mutation("7"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun legacyPendingWriteImportsOnceWithoutReplaying() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ListenDatabase::class.java).build()
        val file=File(context.cacheDir,"m1-pending-fixture.json")
        try {
            file.writeText("""{"account":"7","aid":1,"folder":2,"add":true}""")
            val stores=RoomStores(db.listenDao(),file)
            assertNull(stores.mutation("8")); assertFalse(file.exists()); assertEquals(2L,stores.mutation("7")!!.folder)
            assertEquals("legacy",stores.mutation("7")!!.id)
        } finally { db.close(); file.delete() }
    }
    @Test fun dataStorePersistsOrdinarySettingsAndRecoversCorruptionWithoutCredentials() = runBlocking {
        val file=File(context.cacheDir,"m1-settings-fixture.json"); file.delete()
        var job=SupervisorJob(); val diagnostics=DiagnosticLog(clock)
        try {
            var store=SettingsStore(file,CoroutineScope(job+Dispatchers.IO),diagnostics)
            val wanted=UserSettings(false,30,123,Theme.DARK,mobilePlayback=true)
            val migrated=wanted.copy(historyEnabled=true)
            store.update(wanted); assertEquals(migrated,withTimeout(5000){store.settings.first{it.historyLimit==123}})
            assertFalse(file.readText().contains("SESSDATA")); assertFalse(file.readText().contains("Token"))
            job.cancelAndJoin(); file.writeText(kotlinx.serialization.json.Json.encodeToString(wanted)); job=SupervisorJob()
            store=SettingsStore(file,CoroutineScope(job+Dispatchers.IO),diagnostics)
            assertEquals(migrated,withTimeout(5000){store.settings.first{it.historyLimit==123}})
            try { store.update(wanted.copy(historyDays=0)); fail("invalid settings") } catch (_: IllegalArgumentException) {}
            job.cancelAndJoin(); file.writeText("corrupt"); job=SupervisorJob()
            store=SettingsStore(file,CoroutineScope(job+Dispatchers.IO),diagnostics)
            withTimeout(5000){while(!diagnostics.preview().contains("SETTINGS_RESET"))delay(20)}
            assertTrue(store.settings.value.historyEnabled)
        } finally { job.cancelAndJoin(); file.delete() }
    }
}
