package app.bililisten.shared

import kotlinx.serialization.Serializable

@Serializable data class StorageSettings(
    val automaticAudio: Boolean = false,
    val cacheOnMetered: Boolean = false,
    val downloadOnMetered: Boolean = false,
    val cacheMiB: Int = 256,
) {
    fun checked(): StorageSettings { require(cacheMiB in 32..2048); return this }
}

@Serializable enum class DownloadPhase(val label: String) {
    QUEUED("等待下载"), CHECKING("核对下载范围"), RUNNING("下载中"),
    PAUSED("已暂停"), FAILED("下载失败"), COMPLETE("已完成"), BLOCKED("暂不能下载")
}
@Serializable data class AudioDownload(
    val id: String, val account: String, val video: VideoRef, val title: String,
    val phase: DownloadPhase = DownloadPhase.QUEUED,
    val bytes: Long = 0, val total: Long? = null, val trackId: Int = 0,
    val codec: String = "", val etag: String? = null, val digest: String = "",
    val message: String = "", val createdAt: Long = 0,
) {
    fun entry() = QueueEntry(id, video.bvid, video.cid, video.part, title, offline = true)
    fun checked(): AudioDownload {
        require(Regex("[a-zA-Z0-9-]{1,80}").matches(id)); AccountRef(account)
        require(bytes in 0..OfflineAudioRules.MAX_BYTES && (total == null || total in 1..OfflineAudioRules.MAX_BYTES))
        require(title.length <= 1024 && message.length <= 512 && codec.length <= 80)
        require(etag == null || etag.length <= 512)
        if (phase == DownloadPhase.COMPLETE) require(bytes > 0 && Regex("[0-9a-f]{64}").matches(digest))
        return this
    }
}
data class StorageUsage(val records: Long = 0, val pictures: Long = 0, val automatic: Long = 0, val downloads: Long = 0)
data class DownloadAudioSource(val track:AudioTrack,val durationMs:Long)

object OfflineAudioRules {
    const val MAX_BYTES = 512L * 1024 * 1024
    const val RESERVE_BYTES = 32L * 1024 * 1024
    fun admitted(video: Video, ref: VideoRef, probe: AudioProbe, track: AudioTrack): Boolean {
        val part=video.parts.firstOrNull { it.cid == ref.cid && it.number == ref.part } ?: return false
        val duration=part.durationSeconds.takeIf{it>0} ?: video.duration.takeIf{video.parts.size==1&&it>0} ?: return false
        val observed=probe.durationMs ?: return false
        return video.bvid == ref.bvid && observed>0 && kotlin.math.abs(observed-duration*1000)<=2000 &&
            video.downloadAllowed == true && !video.paidContent && probe.offlineFull &&
            probe.preview!=true && probe.drm!=true &&
            track in probe.tracks && track.id in setOf(30216,30232,30280) &&
            track.codec.startsWith("mp4a", true) && track.mime == "audio/mp4"
    }
    fun networkAllowed(kind: NetworkKind, metered: Boolean) = kind == NetworkKind.UNMETERED || kind == NetworkKind.METERED && metered
    fun resumeResponse(offset: Long, status: Int, start: Long?, previousEtag: String?, etag: String?): Boolean =
        offset > 0 && status == 206 && start == offset && previousEtag != null &&
            !previousEtag.startsWith("W/") && previousEtag == etag
    // Completed ordinary audio has no application-imposed expiry. Login generations do not expire it.
    fun usable(record: AudioDownload, account: String) = record.account == account && record.phase == DownloadPhase.COMPLETE && record.bytes > 0
}
