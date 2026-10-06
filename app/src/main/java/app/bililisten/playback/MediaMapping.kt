package app.bililisten.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.bililisten.shared.QueueEntry
import app.bililisten.shared.PlayMode
import app.bililisten.shared.SourceCodec

fun QueueEntry.mediaItem(mode: PlayMode = PlayMode.SEQUENTIAL, epoch: Long = 0): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(if(offline) "bililisten://offline/$id" else "bililisten://video/$bvid/$cid"+if(cid==0L)"?owner=${source?.owner}" else "")
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("P$part · B站视频")
        .setExtras(Bundle().apply {
            putString("bvid", bvid); putLong("cid", cid); putInt("part", part)
            putString("queueTitle",title)
            putBoolean("offline", offline)
            putString("mode", mode.name); putString("origin", origin.name)
            putLong("epoch", epoch)
            sourceFolder?.let { putLong("folder", it) }
            source?.let { putString("source", SourceCodec.encode(it)) }
        }).build()).build()

fun MediaItem.queueEntry(): QueueEntry? {
    val data = mediaMetadata.extras ?: return null
    return QueueEntry(mediaId, data.getString("bvid") ?: return null, data.getLong("cid"), data.getInt("part"), data.getString("queueTitle") ?: mediaMetadata.title.toString(),
        if (data.containsKey("folder")) data.getLong("folder") else null,
        data.getString("source")?.let(SourceCodec::decode), data.getString("origin")?.let { runCatching { app.bililisten.shared.HistoryOrigin.valueOf(it) }.getOrNull() } ?: app.bililisten.shared.HistoryOrigin.UNKNOWN, data.getBoolean("offline"))
}
