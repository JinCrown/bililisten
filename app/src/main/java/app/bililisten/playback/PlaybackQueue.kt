package app.bililisten.playback

import androidx.media3.common.Player
import app.bililisten.shared.*

/** Service-owned queue. UI sends one command rather than several partially visible mutations. */
class PlaybackQueue(private val player: Player) {
    private var base: ResumeSnapshot? = null
    private var epoch = 0L
    private var highestVersion = 0L
    var changing: Boolean = false; private set
    fun accepts(item: androidx.media3.common.MediaItem): Boolean = item.mediaMetadata.extras?.getLong("epoch") == epoch && base?.entries?.any { it.id == item.mediaId } == true

    fun snapshot(): ResumeSnapshot? {
        val saved = base ?: return null
        val current = player.currentMediaItem?.mediaId ?: return null
        if (saved.entries.none { it.id == current }) return null
        return saved.copy(currentId = current, positionMs = player.currentPosition.coerceAtLeast(0)).checked()
    }

    /** Resolve only metadata; keep the original media URI so the active decoder is retained. */
    fun resolveVideo(video:Video):Boolean {
        val saved=base ?: return false
        val pending=saved.entries.filter{it.bvid==video.bvid&&it.cid==0L}
        if(pending.isEmpty())return false
        require(pending.all{it.source?.kind==SourceKind.UP_UPLOADS&&video.hasCreator(it.source?.owner ?: 0)})
        val part=video.parts.firstOrNull{it.number==1} ?: error("Missing P1")
        require(part.cid>0)
        val updates=pending.associate{it.id to it.copy(cid=part.cid,title=video.title)}
        changing=true
        try {
            base=saved.copy(entries=saved.entries.map{updates[it.id] ?: it}).checked()
            for(index in 0 until player.mediaItemCount) {
                val old=player.getMediaItemAt(index)
                val entry=updates[old.mediaId] ?: continue
                player.replaceMediaItem(index,old.buildUpon().setMediaMetadata(entry.mediaItem(saved.mode,epoch).mediaMetadata).build())
            }
        } finally {changing=false}
        return true
    }

    fun replace(snapshot: ResumeSnapshot, play: Boolean) {
        snapshot.checked()
        val accepted = if (highestVersion == 0L || snapshot.queueVersion > highestVersion) snapshot else snapshot.copy(queueVersion = highestVersion + 1).checked()
        highestVersion = accepted.queueVersion
        changing = true
        try {
            epoch++
            base = accepted
            player.pause()
            player.shuffleModeEnabled = false // The explicit order is the single source of truth.
            player.setMediaItems(accepted.playbackEntries().map { it.mediaItem(accepted.mode, epoch) }, accepted.order.indexOf(accepted.currentId), accepted.positionMs)
            player.repeatMode = when (accepted.mode) {
                PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
                PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            if (play) { player.prepare(); player.play() }
        } finally { changing = false }
    }

    fun speed(value: Float) {
        require(value in LongListening.speeds)
        val current = requireNotNull(snapshot())
        val bvid = current.entries.first { it.id == current.currentId }.bvid
        base = current.copy(speeds = current.speeds + (bvid to value), queueVersion = current.queueVersion + 1)
        highestVersion = requireNotNull(base).queueVersion
    }
    fun changeMode(mode: PlayMode) {
        val current = snapshot() ?: return
        if (current.mode == mode) return
        reconcile(current.withMode(mode))
    }

    fun edit(edit: QueueEdit, expectedVersion: Long): Boolean {
        val current = requireNotNull(snapshot()) { "请先建立播放队列" }
        val updated = QueueEditor.apply(current, edit, expectedVersion)
        if (updated == null) { clear(); return true }
        reconcile(updated); return false
    }

    private fun reconcile(updated: ResumeSnapshot) {
        val previousId = player.currentMediaItem?.mediaId
        val requested = player.playWhenReady
        changing = true
        try {
            // Native playlist moves/adds preserve the currently playing decoder and position.
            updated.playbackEntries().forEachIndexed { index, entry ->
                val found = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == entry.id }
                if (found == null) player.addMediaItem(index, entry.mediaItem(updated.mode, epoch))
                else if (found != index) player.moveMediaItem(found, index)
            }
            for (index in player.mediaItemCount - 1 downTo updated.entries.size) player.removeMediaItem(index)
            base = updated
            highestVersion = updated.queueVersion
            player.repeatMode = when(updated.mode) { PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE; PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
            if (previousId != updated.currentId) {
                player.seekTo(updated.order.indexOf(updated.currentId), updated.positionMs)
                if (requested) { player.prepare(); player.play() }
            }
        } finally { changing = false }
    }

    fun clear() {
        changing = true
        try { player.stop(); player.clearMediaItems(); base = null }
        finally { changing = false }
    }
}
