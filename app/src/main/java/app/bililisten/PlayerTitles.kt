package app.bililisten

import app.bililisten.shared.QueueEntry
import app.bililisten.shared.Video

/** Resolve against this exact video/CID; queue metadata may arrive after its row is visible. */
internal fun playbackEntryTitle(video:Video?,entry:QueueEntry?,fallback:String):String {
    val matching=video?.takeIf{entry==null||it.bvid==entry.bvid}
    if((matching?.parts?.size ?: 0)>1) {
        matching?.parts?.firstOrNull{it.cid==entry?.cid}?.title?.takeIf{it.isNotBlank()}?.let{return it}
        entry?.title?.removePrefix(matching!!.title+" · ")?.takeIf{it.isNotBlank()}?.let{return it}
    }
    return matching?.title?.takeIf{it.isNotBlank()} ?: entry?.title?.takeIf{it.isNotBlank()} ?: fallback
}
