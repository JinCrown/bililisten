package app.bililisten.platform

import app.bililisten.shared.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File

class RoomStores(private val dao: ListenDao, private val legacyMutation: File? = null) : LocalHistoryRepository, PlaybackStateStore, CollectionStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val importGate = Mutex()
    override suspend fun bookmarks(account: String) = dao.collections(account).map { json.decodeFromString<CollectionSnapshot>(it.payload) }.filter { it.bookmarked }
    override suspend fun bookmark(account: String, source: ContentSource, saved: Boolean) {
        val previous = collection(account, source.ref)
        val next = previous?.copy(bookmarked = saved, title = source.title, ownerName = source.ownerName, total = source.count, revision = previous.revision + 1,cover=source.cover)
            ?: CollectionSnapshot(account, source.ref, emptyList(), false, 0, 1, source.title, source.ownerName, source.count, saved,source.cover)
        saveCollection(next)
    }
    override fun observe(account: String) = dao.history(account).map { rows -> rows.map { r -> LocalHistoryEntry(r.account, VideoRef(r.bvid, r.cid, r.part), r.title, r.entry("history").source, r.positionMs, r.playedAt, r.entry("history").resolvedOrigin()) } }
    override suspend fun latestVideo(account:String,bvid:String):LocalHistoryEntry? = dao.latestVideoHistory(account,bvid)?.let { r ->
        LocalHistoryEntry(r.account,VideoRef(r.bvid,r.cid,r.part),r.title,r.entry("history").source,r.positionMs,r.playedAt,r.entry("history").resolvedOrigin())
    }
    override fun observeLive(account: String) = dao.liveHistory(account).map { rows -> rows.map { LiveHistoryEntry(it.account,it.roomId,it.title,it.playedAt) } }
    override suspend fun deleteLive(account: String, roomId: Long) { dao.deleteLive(account,roomId) }
    suspend fun checkpointLive(heard: LiveHistoryEntry) { dao.playedLive(LiveHistoryRow(heard.account,heard.roomId,heard.title,heard.playedAt)) }
    override suspend fun delete(account: String, video: VideoRef?) { if (video == null) dao.clearHistory(account) else dao.deleteHistory(account, video.bvid, video.cid) }
    override suspend fun prune(account: String, policy: HistoryRetentionPolicy, now: Long) { if (!policy.keepAll) dao.prune(account, (now - policy.maxAgeMs).coerceAtLeast(0), policy.maxEntries) }
    override suspend fun load(account: String): PlaybackSnapshot? {
        val row = dao.resume(account) ?: return null
        val queue = SnapshotCodec.decode(row.payload)
        require(queue.account == account)
        val meta = dao.meta(account)
        return PlaybackSnapshot(queue, meta?.savedAt ?: 0, meta?.pauseReason?.let { runCatching { PauseReason.valueOf(it) }.getOrNull() } ?: PauseReason.UNKNOWN, meta?.shuffleVersion ?: 1).checked()
    }
    override suspend fun checkpoint(snapshot: PlaybackSnapshot, heard: LocalHistoryEntry?) {
        snapshot.checked()
        val account = snapshot.queue.account
        val history = heard?.let { require(it.account == account); HistoryRow(account, it.video.bvid, it.video.cid, it.title, it.video.part, null, it.positionMs, it.playedAt, it.source?.let(SourceCodec::encode), it.origin.name) }
        val rows = snapshot.queue.playbackEntries().mapIndexed { i, e -> QueueRow(account, e.id, i, json.encodeToString(e)) }
        dao.persistPlayback(ResumeRow(account, SnapshotCodec.encode(snapshot.queue)), history, PlaybackMetaRow(account, snapshot.savedAt, snapshot.pauseReason.name, snapshot.shuffleVersion), rows)
    }
    override suspend fun collection(account: String, source: SourceRef) = dao.collection(account, SourceCodec.encode(source))?.let { json.decodeFromString<CollectionSnapshot>(it.payload) }
    override suspend fun saveCollection(snapshot: CollectionSnapshot) { dao.saveCollectionIfNewer(CollectionRow(snapshot.account, SourceCodec.encode(snapshot.source), json.encodeToString(snapshot), snapshot.revision)) }
    override suspend fun mutation(account: String): MutationRecord? = withContext(Dispatchers.IO) {
        importGate.withLock {
            legacyMutation?.takeIf { it.exists() }?.let { file ->
                val old = json.parseToJsonElement(file.readText()).jsonObject
                val owner = old["account"]!!.jsonPrimitive.content
                if (dao.mutation(owner) == null) {
                    val record = MutationRecord("legacy", owner, old["aid"]!!.jsonPrimitive.long, old["folder"]!!.jsonPrimitive.long, old["add"]!!.jsonPrimitive.boolean, 0)
                    dao.mutation(MutationRow(owner, json.encodeToString(record)))
                }
                check(file.delete())
            }
        }
        dao.mutation(account)?.let { json.decodeFromString(it.payload) }
    }
    override suspend fun saveMutation(record: MutationRecord) { dao.mutation(MutationRow(record.account, json.encodeToString(record))) }
    override suspend fun removeMutation(account: String) { dao.clearMutation(account) }
    override suspend fun updateCheckpoint(account: String, source: SourceRef) = dao.updates(account, SourceCodec.encode(source))?.let { json.decodeFromString<SourceCheckpoint>(it.payload) }
    override suspend fun saveUpdate(checkpoint: SourceCheckpoint) { dao.updates(UpdateRow(checkpoint.account, SourceCodec.encode(checkpoint.source), json.encodeToString(checkpoint))) }
    override suspend fun markHeard(account: String, source: SourceRef, bvid: String) { dao.markHeard(account, SourceCodec.encode(source), bvid) }
}
