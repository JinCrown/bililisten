package app.bililisten.shared

import kotlinx.serialization.Serializable

@Serializable sealed interface QueueEdit {
    @Serializable data class Add(val entries: List<QueueEntry>, val next: Boolean = false) : QueueEdit
    @Serializable data class Move(val id: String, val index: Int) : QueueEdit
    @Serializable data class Remove(val id: String) : QueueEdit
    @Serializable data object Clear : QueueEdit
}

/** Stable entry IDs and explicit play order; editing never touches remote favorites. */
object QueueEditor {
    fun apply(current: ResumeSnapshot, edit: QueueEdit, expectedVersion: Long): ResumeSnapshot? {
        current.checked(); require(current.queueVersion == expectedVersion) { "队列已变化，请刷新后操作" }
        if (edit == QueueEdit.Clear) return null
        val entries = current.entries.toMutableList(); val order = current.order.toMutableList()
        when (edit) {
            is QueueEdit.Add -> {
                require(edit.entries.isNotEmpty() && entries.size + edit.entries.size <= 1000)
                val ids = edit.entries.map { it.id }; require(ids.distinct().size == ids.size && ids.none { it in order })
                val baseIndex = if (edit.next) entries.indexOfFirst { it.id == current.currentId } + 1 else entries.size
                entries.addAll(baseIndex, edit.entries)
                order.addAll(if (edit.next) order.indexOf(current.currentId) + 1 else order.size, ids)
            }
            is QueueEdit.Move -> {
                require(edit.id in order && edit.index in order.indices)
                order.remove(edit.id); order.add(edit.index, edit.id)
                // A manual sort becomes the canonical order when leaving shuffle mode.
                val byId = entries.associateBy { it.id }; entries.clear(); entries.addAll(order.map { byId.getValue(it) })
            }
            is QueueEdit.Remove -> { require(edit.id in order); order.remove(edit.id); entries.removeAll { it.id == edit.id } }
            QueueEdit.Clear -> error("handled")
        }
        if (entries.isEmpty()) return null
        val keptCurrent = current.currentId in order
        val next = if (keptCurrent) current.currentId else order[current.order.indexOf(current.currentId).coerceAtMost(order.lastIndex)]
        return current.copy(entries = entries, order = order, currentId = next,
            positionMs = if (keptCurrent) current.positionMs else 0, queueVersion = current.queueVersion + 1).checked()
    }
}

enum class NetworkKind { OFFLINE, UNMETERED, METERED }
enum class PlaybackIssue(val message: String) {
    LOCAL_FILE("本机音频不可用或不属于当前账号，请在下载管理中核对"),
    OFFLINE("网络已断开，请联网后手动继续"), MOBILE_CHOICE("请先选择是否允许移动数据收听"), MOBILE_DISABLED("移动数据收听已关闭"),
    CONTENT_UNAVAILABLE("当前内容不可播放，可手动选择下一条"), SESSION_EXPIRED("登录已失效，请重新检查账号"),
    PLATFORM_LIMITED("平台限制了当前请求，请稍后手动重试"), NETWORK_ERROR("取流或网络失败，已停止自动重试"), UNSUPPORTED("设备暂不支持这个音轨")
}
object PlaybackNetworkPolicy {
    fun blocked(network: NetworkKind, mobileChoice: Boolean?): PlaybackIssue? = when {
        network == NetworkKind.OFFLINE -> PlaybackIssue.OFFLINE
        network == NetworkKind.METERED && mobileChoice == null -> PlaybackIssue.MOBILE_CHOICE
        network == NetworkKind.METERED && mobileChoice == false -> PlaybackIssue.MOBILE_DISABLED
        else -> null
    }
}

/** Periodic writes are throttled; critical nodes are explicit. Stale item events cannot advance a queue. */
class CheckpointPolicy(private val intervalMs: Long = 5000) {
    private var lastPeriodic = Long.MIN_VALUE
    fun periodic(now: Long, playing: Boolean): Boolean {
        if (!playing || lastPeriodic != Long.MIN_VALUE && now - lastPeriodic < intervalMs) return false
        lastPeriodic = now; return true
    }
    fun accepts(queue: ResumeSnapshot, eventVersion: Long, entryId: String) = queue.queueVersion == eventVersion && queue.currentId == entryId
}

/** Audio effects are a platform capability, never advertised as enabled without an engine. */
data class AudioEffectsCapability(val available: Boolean, val audioSessionId: Int?)
interface AudioEffectsPort { fun capability(): AudioEffectsCapability }

object StreamRetryPolicy {
    fun retryDelayMs(errorCount: Int, online: Boolean, status: Int?, transient: Boolean): Long? {
        if (!online || errorCount > 1) return null
        return if (status in setOf(403, 410) || transient) 500L else null
    }
}
