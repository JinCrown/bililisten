package app.bililisten.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.roundToLong

@Serializable enum class SubtitleKind(val label: String) { HUMAN("人工字幕"), AI("AI 字幕"), UNKNOWN("来源未标明") }
@Serializable data class SubtitleOption(val id: String, val language: String, val label: String, val kind: SubtitleKind)
/** Signed URLs stay inside the adapter/controller and are never serialized into UI or session state. */
data class SubtitleTrack(val option: SubtitleOption, val url: String)
data class SubtitleTracks(val video: VideoRef, val tracks: List<SubtitleTrack>, val needsLogin: Boolean)
@Serializable data class SubtitleCue(val fromMs: Long, val toMs: Long, val content: String)
@Serializable enum class SubtitleStatus { IDLE, LOADING, READY, EMPTY, LOGIN_REQUIRED, RESTRICTED, EXPIRED, FAILED, UNSUPPORTED }
data class SubtitleView(val video: VideoRef? = null, val status: SubtitleStatus = SubtitleStatus.IDLE,
    val options: List<SubtitleOption> = emptyList(), val selected: SubtitleOption? = null,
    val cues: List<SubtitleCue> = emptyList(), val message: String = "", val checkedAt: Long = 0)
class SubtitleFailure(val status: SubtitleStatus, val detail: String) : Exception(detail)
interface SubtitleRepository {
    suspend fun tracks(video: VideoRef): SubtitleTracks
    suspend fun cues(track: SubtitleTrack): List<SubtitleCue>
}

object SubtitleParser {
    fun tracks(video: VideoRef, data: JsonObject): SubtitleTracks {
        val cid = data["cid"]?.jsonPrimitive?.longOrNull
        if (cid != null && cid != video.cid) throw SubtitleFailure(SubtitleStatus.FAILED,"字幕分 P 身份不匹配")
        val login = data["need_login_subtitle"]?.jsonPrimitive?.booleanOrNull
        val subtitle = data["subtitle"] as? JsonObject
        val items = subtitle?.get("subtitles") as? JsonArray
        if (items == null) {
            if (login == true) return SubtitleTracks(video,emptyList(),true)
            throw SubtitleFailure(SubtitleStatus.FAILED,"字幕响应格式变化，尚未确认是否有字幕")
        }
        if (items.size > 100) throw SubtitleFailure(SubtitleStatus.FAILED,"字幕轨道数量异常")
        val tracks = items.map { value ->
            val item = value as? JsonObject ?: throw SubtitleFailure(SubtitleStatus.FAILED,"字幕轨道格式变化")
            fun str(key: String) = item[key]?.jsonPrimitive?.contentOrNull.orEmpty()
            val id = str("id_str").ifBlank { str("id") }
            val language = str("lan")
            val url = str("subtitle_url")
            if (id.isBlank() || language.isBlank() || url.isBlank() || id.length > 128 || language.length > 32)
                throw SubtitleFailure(SubtitleStatus.FAILED,"字幕轨道信息不完整")
            val ai = item["ai_type"]?.jsonPrimitive?.intOrNull
            val kind = when { language.startsWith("ai-",true) || (ai ?: 0) > 0 -> SubtitleKind.AI; ai == 0 -> SubtitleKind.HUMAN; else -> SubtitleKind.UNKNOWN }
            SubtitleTrack(SubtitleOption(id,language,str("lan_doc").ifBlank { language }.take(80),kind),url)
        }.distinctBy { it.option.id }
        return SubtitleTracks(video,tracks,login == true)
    }
    fun cues(text: String): List<SubtitleCue> {
        val root = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
        val body = root?.get("body") as? JsonArray ?: throw SubtitleFailure(SubtitleStatus.FAILED,"字幕文件格式变化，不能当作暂无字幕")
        if (body.size > 20000) throw SubtitleFailure(SubtitleStatus.FAILED,"字幕条目过多，暂无法读取")
        return body.map { value ->
            val item = value as? JsonObject ?: throw SubtitleFailure(SubtitleStatus.FAILED,"字幕时间轴格式变化")
            val from = item["from"]?.jsonPrimitive?.doubleOrNull
            val to = item["to"]?.jsonPrimitive?.doubleOrNull
            val content = item["content"]?.jsonPrimitive?.contentOrNull
            if (from == null || to == null || !from.isFinite() || !to.isFinite() || from < 0 || to <= from || to > 31_536_000 || content == null || content.length > 20000)
                throw SubtitleFailure(SubtitleStatus.FAILED,"字幕时间轴或文本无效")
            SubtitleCue((from*1000).roundToLong(),(to*1000).roundToLong(),content)
        }.filter { it.content.isNotBlank() && it.toMs > it.fromMs }.sortedBy { it.fromMs }
    }
}

/** Half-open intervals, including overlaps and gaps. Playback position already accounts for speed. */
class SubtitleTimeline(val cues: List<SubtitleCue>) {
    private val maximumEnds = LongArray(cues.size).also { ends ->
        var maximum=0L; cues.forEachIndexed { index,cue -> maximum=maxOf(maximum,cue.toMs);ends[index]=maximum }
    }
    init { require(cues.zipWithNext().all { (a,b) -> a.fromMs <= b.fromMs }) }
    fun active(positionMs: Long): List<Int> {
        var low=0; var high=cues.size
        while(low<high) { val middle=(low+high)/2; if(cues[middle].fromMs<=positionMs)low=middle+1 else high=middle }
        val result=mutableListOf<Int>();var index=low-1
        while(index>=0 && maximumEnds[index]>positionMs) {
            if(positionMs>=cues[index].fromMs && positionMs<cues[index].toMs)result+=index
            index--
        }
        return result.reversed()
    }
    fun nearby(positionMs: Long): Int {
        if(cues.isEmpty())return 0
        var low=0;var high=cues.size
        while(low<high) { val middle=(low+high)/2;if(cues[middle].fromMs<=positionMs)low=middle+1 else high=middle }
        return (low-1).coerceIn(0,cues.lastIndex)
    }
}
