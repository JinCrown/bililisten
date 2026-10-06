package app.bililisten.shared

import kotlinx.serialization.Serializable

@Serializable enum class HistoryOrigin(val label: String) {
    UNKNOWN("早期记录"), SEARCH("搜索"), RECOMMENDATION("推荐"), SHARE("分享"), LINK("链接"),
    FAVORITES("收藏夹"), COLLECTION("追更合集"), UP_UPLOADS("UP 投稿"), BILIBILI_SYNC("B站同步")
}
@Serializable data class LiveHistoryEntry(val account: String, val roomId: Long, val title: String, val playedAt: Long) {
    init { require(roomId > 0 && playedAt >= 0) }
}
/** Deliberately uses structural types, never a music heuristic that hides other listening. */
enum class HistoryType(val label: String) { ALL("全部"), VIDEO("视频"), LIVE("直播") }
data class HistoryItem(val video: LocalHistoryEntry? = null, val live: LiveHistoryEntry? = null) {
    init { require((video == null) != (live == null)) }
    val key get() = video?.let { "video:${it.video.bvid}:${it.video.cid}" } ?: "live:${live!!.roomId}"
    val title get() = video?.title ?: live!!.title
    val playedAt get() = video?.playedAt ?: live!!.playedAt
    val origin get() = video?.entry("history")?.resolvedOrigin()
}
object HistorySearch {
    fun filter(videos: List<LocalHistoryEntry>, lives: List<LiveHistoryEntry>, query: String = "", type: HistoryType = HistoryType.ALL,
        origin: HistoryOrigin? = null): List<HistoryItem> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return (videos.map { HistoryItem(video = it) } + lives.map { HistoryItem(live = it) })
            .filter { (type != HistoryType.VIDEO || it.video != null) && (type != HistoryType.LIVE || it.live != null) }
            .filter { origin == null || it.origin == origin }
            .filter { row -> words.all { word -> "${row.title} ${row.video?.video?.bvid.orEmpty()} ${row.live?.roomId ?: ""}".contains(word, ignoreCase = true) } }
            .sortedWith(compareByDescending<HistoryItem> { it.playedAt }.thenBy { it.key })
    }
}
fun UserSettings.historyPolicy() = HistoryRetentionPolicy(historyLimit, historyDays.toLong() * 86400000, historyKeepAll)
object LongListening {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    const val SEEK_MS = 15_000L
    const val MAX_TIMER_SECONDS = 180 * 60
    fun speed(snapshot: ResumeSnapshot?, live: Boolean): Float = if (live) 1f else
        snapshot?.entries?.firstOrNull { it.id == snapshot.currentId }?.bvid?.let { snapshot.speeds[it] } ?: 1f
}
/** Monotonic time: changing the phone clock cannot extend or expire the timer. */
class SleepDeadline {
    private var deadline: Long? = null
    fun set(seconds: Int, now: Long) {
        require(seconds in 0..LongListening.MAX_TIMER_SECONDS)
        deadline = if (seconds == 0) null else now + seconds * 1000L
    }
    fun remaining(now: Long): Long = deadline?.let { (it - now).coerceAtLeast(0) } ?: 0
    fun consumeExpired(now: Long): Boolean {
        if (deadline == null || now < deadline!!) return false
        deadline = null
        return true
    }
}
