package app.bililisten.shared

import io.ktor.http.Url
import kotlinx.serialization.Serializable

enum class LiveRoomStatus(val label:String) {
    LIVE("正在直播"), OFFLINE("未开播"), REPLAY("轮播中"), UNKNOWN("状态未知");
    companion object { fun from(raw:Int)=when(raw){1->LIVE;0->OFFLINE;2->REPLAY;else->UNKNOWN} }
}

object LiveInput {
    fun room(input:String):Long? {
        val text=input.trim()
        text.toLongOrNull()?.takeIf{it>0}?.let{return it}
        val link=runCatching{SharedInput.url(text)}.getOrNull() ?: return null
        val url=runCatching{Url(link)}.getOrNull() ?: return null
        if(url.host!="live.bilibili.com" || url.protocol.name!="https" || url.port!=443 || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty())return null
        val segments=url.encodedPath.trim('/').split('/')
        val id=when {
            segments.size==1->segments[0]
            segments.size==2 && segments[0] in setOf("blanc","h5")->segments[1]
            else->return null
        }
        return id.toLongOrNull()?.takeIf{it>0}
    }
    fun official(room:Long):String { require(room>0);return "https://live.bilibili.com/$room" }
}

@Serializable data class LiveBookmark(val roomId:Long,val title:String,val anchor:String="",val cover:String="") {
    fun checked():LiveBookmark {require(roomId>0 && title.length<=500 && anchor.length<=200 && cover.length<=2048);return this}
}
data class LiveRanking(val roomId:Long,val uid:Long,val title:String,val anchor:String,val cover:String="",val heat:Long?=null,val area:String="")

object LiveStreams {
    fun supported(stream:LiveStream):Boolean = when {
        stream.kind==StreamKind.AUDIO_ONLY->stream.codec.lowercase() in setOf("aac","mp4a","mp4a.40.2","opus","mp3")
        else->stream.codec.lowercase()=="avc" && (stream.format=="flv" || stream.mime=="application/x-mpegURL")
    }
    fun choose(streams:List<LiveStream>,mixedConsent:Boolean):LiveStream {
        val usable=streams.filter(::supported)
        usable.firstOrNull{it.kind==StreamKind.AUDIO_ONLY}?.let{return it}
        if(usable.any{it.kind==StreamKind.MIXED} && !mixedConsent)throw PlatformFailure("该直播只有混流，包含视频数据；请确认后收听")
        return usable.firstOrNull{it.kind==StreamKind.MIXED} ?: throw PlatformFailure("未取得本设备支持的直播流，请在 B站查看")
    }
}

@Serializable enum class LivePhase { IDLE, CONNECTING, PLAYING, PAUSED, RECONNECTING, ENDED, FAILED }
@Serializable data class LiveExperience(val roomId:Long=0,val title:String="",val anchor:String="",val cover:String="",
    val phase:LivePhase=LivePhase.IDLE,val kind:StreamKind=StreamKind.MIXED,val attempt:Int=0,val notice:String="",
    val canSeekWindow:Boolean=false,val windowMs:Long=0,val windowPositionMs:Long=0,val canReturnVod:Boolean=false)

enum class LiveFailure { NETWORK, EXPIRED_URL, WINDOW_EXPIRED, PLATFORM, SESSION, OFFLINE_ROOM, UNSUPPORTED }
object LiveReconnect {
    fun delayMs(attempt:Int,failure:LiveFailure,requested:Boolean,networkAllowed:Boolean):Long? {
        if(!requested || !networkAllowed || failure !in setOf(LiveFailure.NETWORK,LiveFailure.EXPIRED_URL,LiveFailure.WINDOW_EXPIRED))return null
        return when(attempt){1->1000;2->3000;3->8000;else->null}
    }
}
