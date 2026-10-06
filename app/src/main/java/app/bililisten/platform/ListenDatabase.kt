package app.bililisten.platform

import androidx.room.*
import app.bililisten.shared.QueueEntry
import app.bililisten.shared.SourceCodec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import app.bililisten.shared.SourceCheckpoint
import kotlinx.serialization.json.Json

@Entity(tableName = "resume")
data class ResumeRow(@PrimaryKey val account: String, val payload: String)
@Entity(tableName = "history", primaryKeys = ["account", "bvid", "cid"], indices = [Index(value = ["account", "playedAt"])])
data class HistoryRow(val account: String, val bvid: String, val cid: Long, val title: String, val part: Int, val sourceFolder: Long?, val positionMs: Long, val playedAt: Long, val sourceJson: String? = null, @ColumnInfo(defaultValue = "'UNKNOWN'") val origin: String = "UNKNOWN") {
    fun entry(id: String): QueueEntry {
        val entry = QueueEntry(id, bvid, cid, part, title, sourceFolder, sourceJson?.let(SourceCodec::decode), runCatching { app.bililisten.shared.HistoryOrigin.valueOf(origin) }.getOrDefault(app.bililisten.shared.HistoryOrigin.UNKNOWN))
        return entry.copy(source = entry.resolvedSource(account))
    }
}
@Entity(tableName = "collections", primaryKeys = ["account", "sourceKey"])
data class CollectionRow(val account: String, val sourceKey: String, val payload: String, val revision: Long)
@Entity(tableName = "mutations")
data class MutationRow(@PrimaryKey val account: String, val payload: String)
@Entity(tableName = "playback_meta")
data class PlaybackMetaRow(@PrimaryKey val account: String, val savedAt: Long, val pauseReason: String, val shuffleVersion: Int)
@Entity(tableName = "queue_entries", primaryKeys = ["account", "entryId"], indices = [Index(value = ["account", "orderIndex"], unique = true)])
data class QueueRow(val account: String, val entryId: String, val orderIndex: Int, val payload: String)
@Entity(tableName = "source_updates", primaryKeys = ["account", "sourceKey"])
data class UpdateRow(val account: String, val sourceKey: String, val payload: String)
@Entity(tableName = "live_history", primaryKeys = ["account", "roomId"])
data class LiveHistoryRow(val account: String, val roomId: Long, val title: String, val playedAt: Long)
@Entity(tableName = "history_fences", primaryKeys = ["account", "entryKey"])
data class HistoryFenceRow(val account: String, val entryKey: String, val deletedThrough: Long)
@Entity(tableName = "organizer_local")
data class OrganizerRow(@PrimaryKey val account:String,val payload:String)
@Entity(tableName="remote_history")
data class RemoteHistoryRow(@PrimaryKey val account:String,val enabled:Boolean,val epoch:Long,val payload:String)
@Dao
interface ListenDao {
    @Query("SELECT * FROM remote_history WHERE account=:account") fun remoteHistoryFlow(account:String):Flow<RemoteHistoryRow?>
    @Query("SELECT * FROM remote_history WHERE account=:account") suspend fun remoteHistory(account:String):RemoteHistoryRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun writeRemoteHistory(row:RemoteHistoryRow)
    @Transaction suspend fun changeHistoryMode(row:RemoteHistoryRow,cutoff:Long) {
        val prior=remoteHistory(row.account)
        require(row.epoch==(prior?.epoch ?: 0)+1)
        clearHistory(row.account,cutoff)
        writeRemoteHistory(row)
    }
    @Transaction suspend fun saveRemoteHistory(row:RemoteHistoryRow,expectedEpoch:Long) {
        val prior=remoteHistory(row.account)
        require(prior?.enabled==true&&prior.epoch==expectedEpoch&&row.epoch==expectedEpoch&&row.enabled)
        writeRemoteHistory(row)
    }
    @Query("SELECT * FROM organizer_local WHERE account=:account") suspend fun organizer(account:String):OrganizerRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun organizer(row:OrganizerRow)
    @Query("SELECT * FROM source_updates WHERE account=:account") suspend fun allUpdates(account:String):List<UpdateRow>
    @Query("SELECT * FROM resume WHERE account = :account LIMIT 1") suspend fun resume(account: String): ResumeRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(row: ResumeRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun played(row: HistoryRow)
    @Transaction suspend fun checkpoint(snapshot: ResumeRow, actualPlayback: HistoryRow?) {
        save(snapshot)
        if (actualPlayback != null && remoteHistory(actualPlayback.account)?.enabled!=true && actualPlayback.playedAt > (historyFence(actualPlayback.account, "video:${actualPlayback.bvid}:${actualPlayback.cid}") ?: -1)) played(actualPlayback)
    }
    @Query("SELECT * FROM history WHERE account = :account ORDER BY playedAt DESC, bvid, cid") fun history(account: String): Flow<List<HistoryRow>>
    @Query("SELECT * FROM history WHERE account=:account AND bvid=:bvid AND cid=:cid") suspend fun historyEntry(account: String, bvid: String, cid: Long): HistoryRow?
    @Query("SELECT * FROM history WHERE account=:account AND bvid=:bvid ORDER BY playedAt DESC,cid DESC LIMIT 1") suspend fun latestVideoHistory(account:String,bvid:String):HistoryRow?
    @Query("SELECT * FROM history WHERE account=:account") suspend fun allHistory(account: String): List<HistoryRow>
    @Query("SELECT * FROM live_history WHERE account=:account ORDER BY playedAt DESC,roomId") fun liveHistory(account: String): Flow<List<LiveHistoryRow>>
    @Query("SELECT * FROM live_history WHERE account=:account") suspend fun allLiveHistory(account: String): List<LiveHistoryRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLive(row: LiveHistoryRow)
    @Query("SELECT MAX(deletedThrough) FROM history_fences WHERE account=:account AND entryKey IN ('*',:key)") suspend fun historyFence(account: String, key: String): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun fence(row: HistoryFenceRow)
    @Query("DELETE FROM history WHERE account=:account AND bvid=:bvid AND cid=:cid") suspend fun removeHistory(account: String, bvid: String, cid: Long)
    @Query("DELETE FROM live_history WHERE account=:account AND roomId=:room") suspend fun removeLive(account: String, room: Long)
    @Query("DELETE FROM history WHERE account=:account") suspend fun removeAllHistory(account: String)
    @Query("DELETE FROM live_history WHERE account=:account") suspend fun removeAllLive(account: String)
    @Transaction suspend fun playedLive(row: LiveHistoryRow) {
        if (remoteHistory(row.account)?.enabled!=true && row.playedAt > (historyFence(row.account, "live:${row.roomId}") ?: -1)) insertLive(row)
    }
    @Transaction suspend fun deleteHistory(account: String, bvid: String, cid: Long, cutoff: Long = System.currentTimeMillis()) {
        fence(HistoryFenceRow(account, "video:$bvid:$cid", maxOf(cutoff, historyFence(account, "video:$bvid:$cid") ?: -1)))
        removeHistory(account, bvid, cid)
    }
    @Transaction suspend fun deleteLive(account: String, room: Long, cutoff: Long = System.currentTimeMillis()) {
        fence(HistoryFenceRow(account, "live:$room", maxOf(cutoff, historyFence(account, "live:$room") ?: -1)))
        removeLive(account, room)
    }
    @Query("DELETE FROM history_fences WHERE account=:account AND entryKey!='*'") suspend fun removeEntryFences(account: String)
    @Transaction suspend fun clearHistory(account: String, cutoff: Long = System.currentTimeMillis()) {
        fence(HistoryFenceRow(account, "*", maxOf(cutoff, historyFence(account, "*") ?: -1)))
        removeAllHistory(account); removeAllLive(account); removeEntryFences(account)
    }
    @Transaction suspend fun prune(account: String, oldest: Long, limit: Int) {
        val videos = allHistory(account)
        val lives = allLiveHistory(account)
        val timeline = (videos.map { "video:${it.bvid}:${it.cid}" to it.playedAt } + lives.map { "live:${it.roomId}" to it.playedAt })
            .sortedWith(compareByDescending<Pair<String,Long>> { it.second }.thenBy { it.first })
        val removed = timeline.filterIndexed { index, row -> index >= limit || row.second < oldest }.map { it.first }.toSet()
        videos.filter { "video:${it.bvid}:${it.cid}" in removed }.forEach { deleteHistory(account,it.bvid,it.cid,it.playedAt) }
        lives.filter { "live:${it.roomId}" in removed }.forEach { deleteLive(account,it.roomId,it.playedAt) }
    }
    @Query("SELECT * FROM collections WHERE account=:account AND sourceKey=:key") suspend fun collection(account: String, key: String): CollectionRow?
    @Query("SELECT * FROM collections WHERE account=:account") suspend fun collections(account: String): List<CollectionRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun collection(row: CollectionRow)
    @Transaction suspend fun saveCollectionIfNewer(row: CollectionRow) {
        if ((collection(row.account, row.sourceKey)?.revision ?: -1) < row.revision) collection(row)
    }
    @Query("SELECT * FROM mutations WHERE account=:account") suspend fun mutation(account: String): MutationRow?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun mutation(row: MutationRow)
    @Query("DELETE FROM mutations WHERE account=:account") suspend fun clearMutation(account: String)
    @Query("SELECT * FROM playback_meta WHERE account=:account") suspend fun meta(account: String): PlaybackMetaRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun meta(row: PlaybackMetaRow)
    @Query("SELECT * FROM queue_entries WHERE account=:account ORDER BY orderIndex") suspend fun queue(account: String): List<QueueRow>
    @Query("DELETE FROM queue_entries WHERE account=:account") suspend fun clearQueue(account: String)
    @Query("DELETE FROM resume WHERE account=:account") suspend fun deleteResume(account: String)
    @Query("DELETE FROM playback_meta WHERE account=:account") suspend fun deletePlaybackMeta(account: String)
    @Transaction suspend fun clearPlayback(account: String) { deleteResume(account); deletePlaybackMeta(account); clearQueue(account) }
    @Insert suspend fun queue(rows: List<QueueRow>)
    @Query("SELECT * FROM source_updates WHERE account=:account AND sourceKey=:key") suspend fun updates(account: String, key: String): UpdateRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun writeUpdate(row: UpdateRow)
    @Transaction suspend fun updates(row: UpdateRow) {
        val incoming = Json.decodeFromString<SourceCheckpoint>(row.payload)
        val prior = updates(row.account, row.sourceKey)?.let { Json.decodeFromString<SourceCheckpoint>(it.payload) }
        writeUpdate(row.copy(payload = Json.encodeToString(incoming.copy(heard = incoming.heard + prior?.heard.orEmpty()))))
    }
    @Transaction suspend fun markHeard(account: String, key: String, bvid: String) {
        val prior = updates(account, key)?.let { Json.decodeFromString<SourceCheckpoint>(it.payload) }
            ?: SourceCheckpoint(account, SourceCodec.decode(key), emptySet(), completeBaseline = false)
        writeUpdate(UpdateRow(account, key, Json.encodeToString(prior.copy(heard = prior.heard + bvid))))
    }
    @Transaction suspend fun persistPlayback(snapshot: ResumeRow, history: HistoryRow?, meta: PlaybackMetaRow, queue: List<QueueRow>) {
        require(snapshot.account == meta.account && queue.all { it.account == snapshot.account } && (history == null || history.account == snapshot.account))
        checkpoint(snapshot, history); meta(meta); clearQueue(snapshot.account); queue(queue)
    }
}
@Database(entities = [ResumeRow::class, HistoryRow::class, CollectionRow::class, MutationRow::class, PlaybackMetaRow::class, QueueRow::class, UpdateRow::class, LiveHistoryRow::class, HistoryFenceRow::class,OrganizerRow::class,RemoteHistoryRow::class], version = 6, exportSchema = true)
abstract class ListenDatabase : RoomDatabase() {
    abstract fun listenDao(): ListenDao
    companion object {
        val MIGRATION_5_6=object:Migration(5,6){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS remote_history (account TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, epoch INTEGER NOT NULL, payload TEXT NOT NULL)")
        }}
        val MIGRATION_4_5=object:Migration(4,5){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS organizer_local (account TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
        }}
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN origin TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("CREATE TABLE IF NOT EXISTS live_history (account TEXT NOT NULL, roomId INTEGER NOT NULL, title TEXT NOT NULL, playedAt INTEGER NOT NULL, PRIMARY KEY(account,roomId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS history_fences (account TEXT NOT NULL, entryKey TEXT NOT NULL, deletedThrough INTEGER NOT NULL, PRIMARY KEY(account,entryKey))")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE history ADD COLUMN sourceJson TEXT") }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_account_playedAt ON history(account, playedAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS collections (account TEXT NOT NULL, sourceKey TEXT NOT NULL, payload TEXT NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(account,sourceKey))")
                db.execSQL("CREATE TABLE IF NOT EXISTS mutations (account TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS playback_meta (account TEXT NOT NULL PRIMARY KEY, savedAt INTEGER NOT NULL, pauseReason TEXT NOT NULL, shuffleVersion INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS queue_entries (account TEXT NOT NULL, entryId TEXT NOT NULL, orderIndex INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(account,entryId))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_queue_entries_account_orderIndex ON queue_entries(account,orderIndex)")
                db.execSQL("CREATE TABLE IF NOT EXISTS source_updates (account TEXT NOT NULL, sourceKey TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(account,sourceKey))")
            }
        }
    }
}
