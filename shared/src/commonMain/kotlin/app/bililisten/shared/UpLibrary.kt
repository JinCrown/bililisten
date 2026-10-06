package app.bililisten.shared

import io.ktor.http.Url
import kotlin.random.Random

data class UpProfile(val mid:Long,val name:String,val avatar:String="",val signature:String="",val videos:Int=0,val fans:Long?=null) {
    init { require(mid>0) }
    val source get()=SourceRef(SourceKind.UP_UPLOADS,mid,mid)
}
data class UpSearchPage(val items:List<UpProfile>,val page:Int,val total:Int,val hasMore:Boolean)
enum class UploadOrder(val label:String,val apiValue:String) { NEWEST("最新发布","pubdate"), OLDEST("最早发布","pubdate_asc"), POPULAR("最多播放","click") }
interface UpLibraryRepository {
    suspend fun search(keyword:String,page:Int=1):UpSearchPage
    suspend fun profile(mid:Long):UpProfile
    suspend fun uploads(mid:Long,page:Int=1,order:UploadOrder=UploadOrder.NEWEST,keyword:String=""):SourceContentPage
}
object UpLinks {
    fun mid(input:String):Long?=runCatching {
        val value=input.trim()
        if(value.matches(Regex("[0-9]{1,19}")))return@runCatching value.toLong().takeIf{it>0}
        val u=Url(value)
        require(u.protocol.name=="https" && u.host=="space.bilibili.com" && u.port==443 && u.user.isNullOrEmpty() && u.password.isNullOrEmpty())
        val path=u.encodedPath.trim('/').split('/')
        require(path.size==1 || path.size==2 && path[1] in setOf("video","upload","search"))
        path.first().toLong().takeIf{it>0}
    }.getOrNull()
}
class BiliUpLibraryRepository(private val api:BiliApi,private val clock:Clock,private val md5:(ByteArray)->String):UpLibraryRepository {
    override suspend fun search(keyword:String,page:Int)=api.upSearch(keyword,page,clock.nowMs(),md5)
    override suspend fun profile(mid:Long)=api.upProfile(mid,clock.nowMs(),md5)
    override suspend fun uploads(mid:Long,page:Int,order:UploadOrder,keyword:String)=api.upUploads(mid,page,order,keyword,clock.nowMs(),md5)
}

/** Real music-ranking owners only; names never stand in for account identity. */
fun musicCreatorCandidates(rows: List<Recommendation>): List<UpProfile> = rows
    .filter { it.owner > 0 && it.author.isNotBlank() }
    .distinctBy { it.owner }
    .map { UpProfile(it.owner, it.author, it.avatar) }
fun selectRecommendedCreators(rows:List<UpProfile>,previous:Set<Long> = emptySet(),random:Random = Random.Default):List<UpProfile> {
    val candidates=rows.filter{it.name.isNotBlank()}.distinctBy{it.mid}
    val fresh=candidates.filter{it.mid !in previous}.shuffled(random).take(10)
    return fresh+candidates.filter{it.mid in previous}.shuffled(random).take(10-fresh.size)
}
fun recommendedCreators(rows:List<Recommendation>)=selectRecommendedCreators(musicCreatorCandidates(rows))
