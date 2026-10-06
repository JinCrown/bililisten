package app.bililisten.shared

data class VideoStart(val parts:List<VideoPart>,val currentIndex:Int,val positionMs:Long,val resumed:Boolean=false,val missingPart:Boolean=false)

/** Match stable CID: part numbers may change when the uploader edits the video. */
object VideoContinuation {
    fun prepare(video:Video,previous:VideoRef?,positionMs:Long,continuous:Boolean,finished:Boolean=false):VideoStart {
        require(video.parts.isNotEmpty())
        val found=if(previous?.bvid==video.bvid)video.parts.indexOfFirst{it.cid==previous.cid} else -1
        if(found<0)return VideoStart(if(continuous)video.parts else video.parts.take(1),0,0,missingPart=previous?.bvid==video.bvid)
        val position=positionMs.coerceAtLeast(0)
        val part=video.parts[found]
        val duration=part.durationSeconds.takeIf{it>0} ?: video.duration.takeIf{video.parts.size==1&&it>0}
        // A fully finished part continues at the next part; a finished video starts anew.
        val ended=finished || duration!=null && position/1000>=duration
        val index=if(ended)if(continuous)if(found<video.parts.lastIndex)found+1 else 0 else found else found
        val startPosition=if(ended)0 else position
        return VideoStart(if(continuous)video.parts else listOf(video.parts[index]),if(continuous)index else 0,startPosition,resumed=!ended||index>0)
    }
}
