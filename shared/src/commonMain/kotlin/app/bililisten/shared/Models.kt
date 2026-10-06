package app.bililisten.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
data class VideoPart(val cid: Long, val number: Int, val title: String, val durationSeconds:Long = 0)
@Serializable
data class Video(val bvid: String, val aid: Long, val title: String, val parts: List<VideoPart>, val cover: String = "", val author: String = "", val avatar: String = "", val owner: Long = 0, val duration: Long = 0, val views: Long? = null, val danmaku: Long? = null, val published: Long? = null,
    val zoneId: Int = 0, val zoneName: String = "", val downloadAllowed: Boolean? = null, val paidContent: Boolean = false,
    val description: String = "", val likes: Long? = null, val coins: Long? = null, val favorites: Long? = null,
    val shares: Long? = null, val replies: Long? = null, val copyright: Int = 0, val collaborators:Set<Long> = emptySet()) {
    fun hasCreator(mid:Long)=mid>0&&(owner==mid||mid in collaborators)
}
@Serializable
data class QueueEntry(
    val id: String,
    val bvid: String,
    val cid: Long,
    val part: Int,
    val title: String,
    val sourceFolder: Long? = null,
    val source: SourceRef? = null,
    val origin: HistoryOrigin = HistoryOrigin.UNKNOWN,
    val offline: Boolean = false,
) {
    fun resolvedOrigin(): HistoryOrigin = when (source?.kind) {
        SourceKind.UP_COLLECTION -> HistoryOrigin.COLLECTION
        SourceKind.UP_UPLOADS -> HistoryOrigin.UP_UPLOADS
        SourceKind.OWN_FAVORITES, SourceKind.PUBLIC_FAVORITES -> HistoryOrigin.FAVORITES
        else -> if (sourceFolder != null) HistoryOrigin.FAVORITES else origin
    }
    fun resolvedSource(account: String): SourceRef? = source ?: sourceFolder?.let { folder ->
        account.toLongOrNull()?.takeIf { it > 0 }?.let { SourceRef(SourceKind.OWN_FAVORITES, folder, it).checked() }
    }
}
@Serializable
enum class PlayMode { SEQUENTIAL, REPEAT_ALL, REPEAT_ONE, SHUFFLE }
@Serializable
data class ResumeSnapshot(
    val schema: Int = 1,
    val account: String = "guest",
    val entries: List<QueueEntry>,
    val order: List<String>,
    val currentId: String,
    val positionMs: Long,
    val mode: PlayMode = PlayMode.SEQUENTIAL,
    val queueVersion: Long = 1,
    val speeds: Map<String, Float> = emptyMap(),
) {
    fun checked(): ResumeSnapshot {
        require(speeds.all { Bvid.parse(it.key) == it.key && it.value in LongListening.speeds })
        require(schema == 1 && account.isNotBlank() && queueVersion > 0)
        require(entries.isNotEmpty())
        val ids = entries.map { it.id }
        require(ids.distinct().size == ids.size)
        require(order.size == ids.size && order.toSet() == ids.toSet())
        require(currentId in ids && positionMs >= 0)
        require(entries.all { it.id.isNotBlank() && Bvid.parse(it.bvid) == it.bvid && it.part > 0 &&
            (it.cid > 0 || it.cid == 0L && it.part == 1 && !it.offline && it.source?.kind==SourceKind.UP_UPLOADS) })
        entries.forEach { entry -> entry.source?.checked()?.let { source ->
            if (source.kind == SourceKind.OWN_FAVORITES) require(source.owner.toString() == account)
            require(entry.sourceFolder == null || source.kind == SourceKind.OWN_FAVORITES && source.id == entry.sourceFolder)
        } }
        return this
    }
    fun playbackEntries(): List<QueueEntry> { val byId=entries.associateBy{it.id};return order.map{byId.getValue(it)} }
    fun withMode(next: PlayMode, random: Random = Random.Default): ResumeSnapshot {
        if (next == mode) return checked()
        val ids = entries.map { it.id }
        val newOrder = if (next == PlayMode.SHUFFLE) listOf(currentId) + ids.filterNot { it == currentId }.shuffled(random) else ids
        return copy(mode = next, order = newOrder, queueVersion = queueVersion + 1).checked()
    }
}

object SnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(value: ResumeSnapshot): String = json.encodeToString(value.checked())
    fun decode(value: String): ResumeSnapshot = json.decodeFromString<ResumeSnapshot>(value).checked()
}

object Bvid {
    private val id = Regex("BV[0-9A-Za-z]{10}")
    private val url = Regex("https://(?:www\\.|m\\.)?bilibili\\.com/video/(BV[0-9A-Za-z]{10})(?:[/#?].*)?")
    fun parse(input: String): String? {
        val text = input.trim()
        return if (id.matches(text)) text else url.matchEntire(text)?.groupValues?.get(1)
    }
}

enum class Membership { ORDINARY, VIP, UNKNOWN }
data class Account(val id: Long, val name: String, val membership: Membership = Membership.UNKNOWN, val vipExpiresAt: Long? = null, val avatar: String = "", val level: Int? = null, val coinBalance:Double?=null)
data class FavoriteFolder(val id: Long, val title: String, val count: Int, val contains: Boolean? = null, val attr: Int? = null, val cover: String = "", val description: String = "")
data class FavoriteItem(val bvid: String, val title: String, val cover: String = "", val author: String = "", val duration: Long = 0)
data class FavoritePage(val items: List<FavoriteItem>, val hasMore: Boolean)

class PlatformFailure(val category: String, val code: Int? = null) : Exception(category)

data class SearchPage(val items: List<FavoriteItem>, val page: Int, val pages: Int, val total: Int) {
    val hasMore: Boolean get() = page < pages
}
data class AudioTrack(val id: Int, val url: String, val mime: String, val codec: String, val bandwidth: Long,
    val sampleRate: Int? = null, val channels: Int? = null, val bitDepth: Int? = null)
data class AudioProbe(val tracks: List<AudioTrack>, val mixedAvailable: Boolean, val durationMs: Long?, val offlineFull: Boolean = false, val preview:Boolean?=null, val drm:Boolean?=null)
enum class MutationOutcome { CONFIRMED, UNCHANGED, UNKNOWN }
@Serializable
data class Recommendation(val bvid: String, val title: String, val tags: List<String>?, val cover: String = "", val author: String = "", val duration: Long = 0, val owner: Long = 0, val avatar: String = "")
/** Only explicit raw tags get a boost; missing tags preserve server order. Never applied to search or queues. */
object RecommendationPolicy {
    fun rank(candidates: List<Recommendation>): List<Recommendation> = candidates.distinctBy { it.bvid }
        .sortedByDescending { item -> item.tags?.any { it in setOf("音乐", "演奏", "翻唱", "纯音乐") } == true }
}
data class LiveRoom(val requestedId: Long, val roomId: Long, val uid: Long, val status: Int, val title: String, val anchor: String,
    val cover:String="",val area:String="",val description:String="") {
    val state get()=LiveRoomStatus.from(status)
}
data class LiveStream(val url: String, val format: String, val codec: String, val quality: Int,
    val kind:StreamKind=StreamKind.MIXED,val mime:String=if(format=="flv")"video/x-flv" else if(format=="ts")"application/x-mpegURL" else "video/mp4")

object VideoJump {
    fun url(entry: QueueEntry, positionMs: Long): String =
        "https://www.bilibili.com/video/${entry.bvid}/?p=${entry.part}&t=${positionMs.coerceAtLeast(0) / 1000}"
}
