package app.bililisten.shared

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface AudioResponse {
    val status: Int
    val start: Long?
    val total: Long?
    val etag: String?
    suspend fun read(buffer: ByteArray): Int
    fun close()
}
interface AudioTransferDisk {
    fun size(): Long
    fun reset()
    fun append(buffer: ByteArray, count: Int)
    fun freeBytes(): Long
}
class TransferFailure(val explanation: String) : Exception(explanation)
data class TransferProgress(val bytes: Long, val total: Long?, val etag: String?)

/** Resuming always obtains a fresh URL outside this engine; neither URLs nor credentials are persisted. */
class AudioTransfer {
    suspend fun copy(disk: AudioTransferDisk, previousEtag: String?, open: suspend (Long, String?) -> AudioResponse,
        allowed: () -> Boolean, progress: suspend (TransferProgress) -> Unit): TransferProgress {
        var offset = disk.size()
        var response = open(offset, previousEtag)
        if (offset > 0 && !OfflineAudioRules.resumeResponse(offset, response.status, response.start, previousEtag, response.etag)) {
            response.close(); disk.reset(); offset = 0; response = open(0, null)
        }
        try {
            if (offset == 0L && !(response.status == 200 || response.status == 206 && response.start == 0L)) throw TransferFailure("音频请求未返回完整起点")
            val total = response.total
            if (total != null && (total <= 0 || total > OfflineAudioRules.MAX_BYTES || total < offset)) throw TransferFailure("音频文件过大或长度无效")
            var bytes = offset
            progress(TransferProgress(bytes, total, response.etag))
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!allowed()) throw TransferFailure("网络条件或账号已变化，请手动继续")
                val n = response.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                require(n <= buffer.size)
                currentCoroutineContext().ensureActive()
                if (!allowed()) throw TransferFailure("网络条件或账号已变化，请手动继续")
                if (bytes + n > OfflineAudioRules.MAX_BYTES || total != null && bytes + n > total) throw TransferFailure("音频长度超出限制")
                if (disk.freeBytes() < OfflineAudioRules.RESERVE_BYTES + n) throw TransferFailure("可用空间不足，已保留未完成部分")
                disk.append(buffer, n); bytes += n
                progress(TransferProgress(bytes, total, response.etag))
            }
            if (bytes == 0L || total != null && bytes != total) throw TransferFailure("音频未完整下载，请手动继续")
            return TransferProgress(bytes, total, response.etag)
        } finally { response.close() }
    }
}
