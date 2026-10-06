package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.roundToLong

object MusicContent {
    fun songCandidate(video: Video?): Boolean {
        val name=video?.zoneName.orEmpty()
        if(listOf("演奏","教学","乐评","电台").any(name::contains))return false
        // Legacy tid 31 is verified against the actual cover sample and its official page.
        // Unknown numeric ids are not guessed, including the newer tid_v2 namespace.
        if(video?.zoneId==31)return true
        return listOf("MV","翻唱","音乐现场","原创音乐","VOCALOID","AI音乐","说唱").any{name.contains(it,true)}
    }
}
@Serializable data class LyricsCandidate(val id: Long,val name: String,val artist: String,val album: String,val durationMs: Long,val instrumental: Boolean=false,val source: String="LRCLIB")
data class LyricsDocument(val candidate: LyricsCandidate,val plain: String,val cues: List<SubtitleCue>,val words:Map<Int,List<LyricWord>> = emptyMap(),val timingNote:String?=null)
enum class LyricsStatus { IDLE, SEARCHING, CANDIDATES, LOADING, PREVIEW, READY, EMPTY, FAILED }
data class LyricsView(val video: VideoRef?=null,val status: LyricsStatus=LyricsStatus.IDLE,val candidates: List<LyricsCandidate> = emptyList(),
    val document: LyricsDocument?=null,val offsetMs: Long=0,val confirmed: Boolean=false,val message: String="输入准确歌名和歌手，再查找对应版本")
interface LyricsRepository {suspend fun search(name: String,artist: String): List<LyricsCandidate>;suspend fun get(id: Long): LyricsDocument}

object LrcParser {
    private val time=Regex("\\[(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
    fun parse(text: String,durationMs: Long): List<SubtitleCue> {
        require(text.length<=512000)
        val offset=Regex("\\[offset:([+-]?\\d+)]",RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-60000,60000) ?: 0L
        val lines=mutableListOf<Pair<Long,String>>()
        for(line in text.lineSequence().take(20001)) {
            val stamps=time.findAll(line).toList()
            val content=line.replace(time,"").replace(Regex("<\\d{1,3}:[0-5]\\d(?:\\.\\d{1,3})?>"),"").trim()
            for(match in stamps) {
                val fraction=match.groupValues[3].padEnd(3,'0').take(3).toLongOrNull() ?: 0
                // LRC offset advances positive values; the UI adjustment uses a separate delay.
                val at=((match.groupValues[1].toLong()*60+match.groupValues[2].toLong())*1000+fraction-offset).coerceAtLeast(0)
                lines+=at to content
            }
        }
        if(lines.size>20000)throw PlatformFailure("歌词条目过多")
        val grouped=lines.sortedBy{it.first}.groupBy{it.first}.map{(start,items)->start to items.map{it.second}.filter{it.isNotBlank()}.distinct().joinToString("\n")}
        return grouped.mapIndexedNotNull {index,(start,content) ->
            val end=grouped.getOrNull(index+1)?.first ?: durationMs
            if(content.isBlank() || end<=start)null else SubtitleCue(start,end,content)
        }
    }
}

/** Dedicated public client: never receives Bilibili credentials, titles, or queries until explicit search. */
class LrclibRepository(private val client: HttpClient): LyricsRepository {
    private suspend fun request(path: String,params: Map<String,String> = emptyMap()): JsonElement {
        val response=try {client.get("https://lrclib.net/api/$path") {
            header("User-Agent","BiliListen/0.5.0 (Android; lyrics lookup)");header("Lrclib-Client","BiliListen/0.5.0")
            params.forEach{(key,value)->parameter(key,value)}
        }}catch(e:CancellationException){throw e}catch(_:Exception){throw PlatformFailure("歌词网络读取失败")}
        if(response.status.value!=200)throw PlatformFailure(when(response.status.value){404->"歌词记录已失效或不存在";429,503->"歌词服务繁忙，请稍后手动重试";else->"歌词服务读取失败"})
        val limit=4*1024*1024
        if((response.headers["Content-Length"]?.toLongOrNull() ?: 0)>limit)throw PlatformFailure("歌词响应过大")
        val bytes=ByteArray(limit+1);val channel=response.bodyAsChannel();var size=0
        while(size<bytes.size){val n=channel.readAvailable(bytes,size,bytes.size-size);if(n<0)break;if(n==0)continue;size+=n}
        if(size>limit){channel.cancel(null);throw PlatformFailure("歌词响应过大")}
        val text=bytes.copyOf(size).decodeToString()
        return try{Json.parseToJsonElement(text)}catch(_:Exception){throw PlatformFailure("歌词服务响应格式变化")}
    }
    private fun candidate(item: JsonObject): LyricsCandidate {
        fun str(key:String)=item[key]?.jsonPrimitive?.contentOrNull.orEmpty().take(200)
        val id=item["id"]?.jsonPrimitive?.longOrNull?.takeIf{it>0} ?: throw PlatformFailure("歌词记录身份无效")
        val duration=item["duration"]?.jsonPrimitive?.doubleOrNull?.takeIf{it.isFinite()&&it>0&&it<86400} ?: 0.0
        return LyricsCandidate(id,str("trackName"),str("artistName"),str("albumName"),(duration*1000).roundToLong(),item["instrumental"]?.jsonPrimitive?.booleanOrNull==true)
    }
    override suspend fun search(name: String,artist: String): List<LyricsCandidate> {
        require(name.isNotBlank()&&name.length<=200&&artist.length<=200)
        val params=if(artist.isBlank())mapOf("q" to name.trim()) else mapOf("track_name" to name.trim(),"artist_name" to artist.trim())
        val data=request("search",params) as? JsonArray ?: throw PlatformFailure("歌词候选列表格式变化")
        return data.take(20).map{candidate(it.jsonObject)}.distinctBy{it.id}
    }
    override suspend fun get(id: Long): LyricsDocument {
        require(id>0)
        val data=request("get/$id") as? JsonObject ?: throw PlatformFailure("歌词记录格式变化")
        val chosen=candidate(data)
        if(chosen.id!=id)throw PlatformFailure("歌词记录身份不匹配")
        val plain=data["plainLyrics"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val synced=data["syncedLyrics"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val lyricsfile=data["lyricsfile"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if(plain.length>512000 || synced.length>512000 || lyricsfile.length>512000)throw PlatformFailure("歌词文本过长")
        if(lyricsfile.isNotBlank()) {
            try {
                val timing=LyricsfileParser.parse(lyricsfile,chosen.durationMs)
                if(timing.cues.isNotEmpty())return LyricsDocument(chosen,timing.plain,timing.cues,timing.words)
            }catch(e:CancellationException){throw e}catch(_:Exception){ /* Unsupported timing falls back to the original LRC. */ }
        }
        val cues=LrcParser.parse(synced,chosen.durationMs)
        return LyricsDocument(chosen,plain.ifBlank{cues.joinToString("\n"){it.content}},cues,timingNote=if(lyricsfile.isNotBlank())"逐词时间不可用，已回退到逐行时间" else null)
    }
}

/** Candidates are never accepted automatically. Version and timing confirmation belong to the user. */
class LyricsController(private val scope: CoroutineScope,private val repository: LyricsRepository,private val accounts: AccountRepository) {
    private val mutable=MutableStateFlow(LyricsView());val state=mutable.asStateFlow()
    private var stamp:SessionStamp?=null;private var video:VideoRef?=null;private var job:Job?=null;private var revision=0L
    private var visible=false
    fun show(target:VideoRef?,identity:SessionStamp) {
        visible=true
        if(video!=target || stamp!=identity){job?.cancel();revision++;video=target;stamp=identity;mutable.value=LyricsView(target)}
    }
    fun hide(){visible=false;revision++;job?.cancel();if(mutable.value.status in setOf(LyricsStatus.SEARCHING,LyricsStatus.LOADING))mutable.value=mutable.value.copy(status=LyricsStatus.IDLE,document=null,confirmed=false)}
    fun identityChanged(identity:SessionStamp){if(stamp!=identity){hide();video=null;stamp=identity;mutable.value=LyricsView()}}
    private fun valid(ticket:Long,target:VideoRef,identity:SessionStamp)=visible&&ticket==revision&&video==target&&stamp==identity&&accounts.session.value.stamp==identity
    fun search(name:String,artist:String) {
        val target=video ?: return;val identity=stamp ?: return
        if(name.isBlank() || name.length>200 || artist.length>200){mutable.value=mutable.value.copy(message="请输入准确歌名，歌名和歌手分别不超过 200 字");return}
        job?.cancel();val ticket=++revision
        mutable.value=LyricsView(target,LyricsStatus.SEARCHING,message="正在查询 LRCLIB 候选，尚未匹配版本")
        job=scope.launch {
            try {val matches=repository.search(name,artist);ensureActive();accounts.requireCurrent(identity)
                if(valid(ticket,target,identity))mutable.value=LyricsView(target,if(matches.isEmpty())LyricsStatus.EMPTY else LyricsStatus.CANDIDATES,matches,message=if(matches.isEmpty())"未找到候选；可调整歌名、歌手或版本关键词" else "选择实际演唱版本；相似标题和时长不能证明匹配")
            }catch(e:CancellationException){throw e}catch(e:Exception){fail(e,ticket,target,identity)}
        }
    }
    fun select(id:Long) {
        if(mutable.value.candidates.none{it.id==id})return
        val target=video ?: return;val identity=stamp ?: return;job?.cancel();val ticket=++revision
        mutable.value=mutable.value.copy(status=LyricsStatus.LOADING,document=null,confirmed=false,offsetMs=0,message="正在读取选定歌词，版本仍待核对")
        job=scope.launch {
            try {val document=repository.get(id);ensureActive();accounts.requireCurrent(identity)
                if(valid(ticket,target,identity))mutable.value=mutable.value.copy(document=document,status=if(document.plain.isBlank())LyricsStatus.EMPTY else LyricsStatus.PREVIEW,
                    message=if(document.candidate.instrumental)"该候选标记为纯音乐，请核对是否为正确版本" else if(document.plain.isBlank())"该候选暂无歌词" else "来源：LRCLIB；请核对演唱版本和时间轴，确认前只展示文本")
            }catch(e:CancellationException){throw e}catch(e:Exception){fail(e,ticket,target,identity)}
        }
    }
    fun offset(value:Long){mutable.value=mutable.value.copy(offsetMs=value.coerceIn(-60000,60000),confirmed=false,status=if(mutable.value.document!=null)LyricsStatus.PREVIEW else mutable.value.status)}
    fun confirm(){val document=mutable.value.document ?: return;if(document.cues.isNotEmpty())mutable.value=mutable.value.copy(confirmed=true,status=LyricsStatus.READY,message=document.timingNote ?: if(document.words.isNotEmpty())"已确认版本；有逐词时间的行按源时间高亮" else "已确认版本；来源仅提供逐行时间")}
    private fun fail(e:Exception,ticket:Long,target:VideoRef,identity:SessionStamp) {
        if(valid(ticket,target,identity))mutable.value=mutable.value.copy(status=LyricsStatus.FAILED,document=null,confirmed=false,message=(e as? PlatformFailure)?.category ?: "歌词读取失败，字幕和收听仍可继续")
    }
}
