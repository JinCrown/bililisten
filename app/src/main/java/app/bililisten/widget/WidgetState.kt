package app.bililisten.widget

import app.bililisten.shared.PlaybackIssue

/** A projection of the service, never a second queue or player. */
data class WidgetState(
    val account: String = "guest",
    val mediaId: String = "",
    val version: Long = 0,
    val title: String = "尚未播放",
    val part: Int = 0,
    val bvid: String = "",
    val live: Boolean = false,
    val requested: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    val canSeek: Boolean = false,
    val restored: Boolean = false,
    val issue: PlaybackIssue? = null,
    val notice: String? = null,
) {
    val hasContent get() = mediaId.isNotBlank()
    val status: String get() = when {
        notice != null -> notice
        !hasContent -> "选择一段声音开始收听"
        issue != null -> issue.message
        live -> if (requested) "直播 · ${if (buffering) "正在连接" else "正在收听"}" else "直播 · 已暂停"
        restored -> "继续收听 · P$part"
        buffering -> if (requested) "P$part · 正在缓冲" else "P$part · 已暂停"
        else -> "P$part · ${if (requested) "正在播放" else "已暂停"}"
    }
    fun allows(action: String): Boolean = hasContent && when (action) {
        "play", "pause" -> true
        "previous" -> !live && !restored && canPrevious
        "next" -> !live && !restored && canNext
        "back", "forward" -> !live && !restored && canSeek
        else -> false
    }
    fun matches(owner: String, id: String, revision: Long) = account == owner && mediaId == id && version == revision
}

internal fun widgetTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
