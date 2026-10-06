package app.bililisten.platform

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureNanoTime

/** Dedicated synthetic database; never opens a user's history or credentials. */
@RunWith(AndroidJUnit4::class)
class M5HistoryStressTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun snapshot()=PlaybackSnapshot(ResumeSnapshot(account="fixture-a",entries=(1..100).map {
        QueueEntry("entry-$it","BV1xx411c7mD",it.toLong(),it,"fixture $it")
    },order=(100 downTo 1).map{"entry-$it"},currentId="entry-77",positionMs=12345,mode=PlayMode.SHUFFLE),1000,PauseReason.USER)

    @Test fun tenThousandRowsRemainBoundedAfterRetentionAndReadWithoutNetwork()=runBlocking {
        val name="m5-growth-fixture.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,ListenDatabase::class.java,name).build()
        try {
            var stores=RoomStores(db.listenDao());val wanted=snapshot()
            stores.checkpoint(wanted,null)
            val insertNs=measureNanoTime { db.withTransaction {
                repeat(10000){n->db.listenDao().played(HistoryRow("fixture-a","BV1xx411c7mD",n.toLong()+1,"fixture $n",1,null,1000,n.toLong()+1))}
                db.listenDao().played(HistoryRow("fixture-b","BV1xx411c7mD",1,"other account",1,null,5,1))
            } }
            val readMs=(0..24).map {measureNanoTime {
                assertEquals(10000,stores.observe("fixture-a").first().size)
                assertEquals(wanted,stores.load("fixture-a"))
            }/1e6}
            val pruneMs=measureNanoTime {stores.prune("fixture-a",HistoryRetentionPolicy(maxEntries=500,maxAgeMs=100000),10001)}/1e6
            val sql=db.openHelper.writableDatabase
            sql.query("SELECT COUNT(*) FROM history WHERE account='fixture-a'").use{assertTrue(it.moveToFirst());assertEquals(500,it.getInt(0))}
            assertEquals(1,stores.observe("fixture-b").first().size)
            db.close();db=Room.databaseBuilder(context,ListenDatabase::class.java,name).build();stores=RoomStores(db.listenDao())
            assertEquals(wanted,stores.load("fixture-a"));assertEquals(10000L,stores.observe("fixture-a").first().first().video.cid)
            stores.delete("fixture-a",null)
            assertTrue(stores.observe("fixture-a").first().isEmpty());assertEquals(wanted,stores.load("fixture-a"));assertEquals(1,stores.observe("fixture-b").first().size)
            val bytes=context.getDatabasePath(name).parentFile!!.listFiles()!!.filter{it.name.startsWith(name)}.sumOf{it.length()}
            val report=buildJsonObject {
                put("syntheticRows",10000);put("retainedRows",500);put("queueEntries",100);put("samples",readMs.size)
                put("insertMs",insertNs/1e6);put("readHistoryAndQueueMs",JsonArray(readMs.map{JsonPrimitive(it)}));put("pruneMs",pruneMs)
                put("dbAndWalBytesAfterDelete",bytes);put("reopenedExactShufflePartPosition",true);put("otherAccountPreserved",true)
            }
            File(context.filesDir,"m5-evidence").mkdirs();File(context.filesDir,"m5-evidence/history-stress.json").writeText(report.toString())
        } finally {db.close();context.deleteDatabase(name)}
    }

    @Test fun versionOneTypedResumeMigratesAndCanBeCheckpointedWithoutLosingOrder()=runBlocking {
        val name="m5-v1-fixture.db";context.deleteDatabase(name)
        val old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        val wanted=snapshot()
        old.execSQL("CREATE TABLE resume (account TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
        old.execSQL("CREATE TABLE history (account TEXT NOT NULL, bvid TEXT NOT NULL, cid INTEGER NOT NULL, title TEXT NOT NULL, part INTEGER NOT NULL, sourceFolder INTEGER, positionMs INTEGER NOT NULL, playedAt INTEGER NOT NULL, PRIMARY KEY(account,bvid,cid))")
        old.execSQL("INSERT INTO resume VALUES (?,?)",arrayOf(wanted.queue.account,SnapshotCodec.encode(wanted.queue)))
        old.version=1;old.close()
        val db=Room.databaseBuilder(context,ListenDatabase::class.java,name).addMigrations(ListenDatabase.MIGRATION_1_2,ListenDatabase.MIGRATION_2_3, ListenDatabase.MIGRATION_3_4,ListenDatabase.MIGRATION_4_5,ListenDatabase.MIGRATION_5_6).build()
        try {
            val store=RoomStores(db.listenDao());assertEquals(wanted.queue,store.load(wanted.queue.account)!!.queue)
            assertEquals(PauseReason.UNKNOWN,store.load(wanted.queue.account)!!.pauseReason)
            store.checkpoint(wanted,null);assertEquals(wanted,store.load(wanted.queue.account))
            assertEquals(wanted.queue.order,db.listenDao().queue(wanted.queue.account).map{it.entryId})
            assertNull(store.load("different-account"))
        }finally{db.close();context.deleteDatabase(name)}
    }
}
