package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Only this adapter knows endpoint paths. No API credentials enter media requests. */
class BiliApi(private val client: HttpClient, private val governor: RequestGovernor? = null, private val generation: () -> Long = { 0 },
    private val searchClock:Clock?=null,private val searchMd5:(ByteArray)->String={throw PlatformFailure("请求签名暂不可用")},private val credentials: () -> String?) {
    internal val sessionGeneration get()=generation()
    private enum class ReadHost(val domain:String) { API("api.bilibili.com"), APP("app.bilibili.com"), IM("api.vc.bilibili.com"), MESSAGE("message.bilibili.com") }
    private suspend fun data(path: String, params: Map<String, String> = emptyMap(), authenticated: Boolean = false, candidateCookie: String? = null, live: Boolean = false, host:ReadHost=ReadHost.API, bounded:Boolean=false, responseLimitBytes:Int=2*1024*1024): JsonElement {
        val cookie = if (authenticated) candidateCookie ?: credentials() else null
        val requestGeneration = generation()
        // Current player/subtitle and IM reads use the same public WBI key scheme.
        val requestParams = if (("/wbi/" in path || host == ReadHost.IM) && "w_rid" !in params)
            upSigned(params,searchClock?.nowMs() ?: throw PlatformFailure("请求签名暂不可用"),searchMd5) else params
        if (candidateCookie == null && generation() != requestGeneration) throw PlatformFailure("账号会话已变化，旧结果已丢弃")
        val action: suspend () -> JsonElement = {
        val response = try { client.get("https://${if (live) "api.live.bilibili.com" else host.domain}$path") {
            header("Referer", if(live)"https://live.bilibili.com/" else if(host in setOf(ReadHost.API,ReadHost.APP))"https://www.bilibili.com/" else "https://message.bilibili.com/")
            header("User-Agent", "Mozilla/5.0 BiliListen/0.0.4")
            cookie?.let { header("Cookie", it) }
            requestParams.forEach { (key, value) -> parameter(key, value) }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw PlatformFailure("网络请求失败") }
        val body = try { if(!bounded)response.bodyAsText() else {
            val channel=response.bodyAsChannel();val bytes=ByteArray(responseLimitBytes+1);var size=0
            while(size<bytes.size){val n=channel.readAvailable(bytes,size,bytes.size-size);if(n<0)break;if(n>0)size+=n}
            if(size==bytes.size){channel.cancel(null);throw PlatformFailure("平台响应过大，请在 B站查看")}
            bytes.decodeToString(0,size)
        } } catch (e: CancellationException) { throw e } catch(e:PlatformFailure){throw e}
        catch (_: Exception) { throw PlatformFailure("网络请求失败") }
        if (candidateCookie == null && generation() != requestGeneration) throw PlatformFailure("账号会话已变化，旧结果已丢弃")
        decodeResponse(response.status.value, body)
        }
        // Candidate login cookies must never share a response with the active account.
        return if (governor == null || candidateCookie != null) action() else governor.read(
            RequestKey(requestGeneration, path, "${if (live) "live" else host.name}:$authenticated:" + requestParams.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }), action)
    }
    /** Read-only allowlist. In particular, update_ack/update_cursor and msgfeed writes are absent. */
    internal suspend fun messageRead(path:String,params:Map<String,String> = emptyMap()):JsonElement {
        if(credentials().isNullOrBlank())throw PlatformFailure("请先登录",-101)
        val host=when(path) {
            "/session_svr/v1/session_svr/get_sessions", "/svr_sync/v1/svr_sync/fetch_session_msgs" -> ReadHost.IM
            "/x/sys-msg/query_unified_notify", "/x/sys-msg/query_user_notify", "/x/sys-msg/query_notify_list" -> ReadHost.MESSAGE
            "/x/msgfeed/reply", "/x/msgfeed/at", "/x/msgfeed/like", "/x/msgfeed/unread", "/x/polymer/pc-electron/v1/user/cards" -> ReadHost.API
            else -> throw IllegalArgumentException("Not a message read endpoint")
        }
        val currentParams=params+mapOf("build" to "0","mobi_app" to "web")+
            if(path=="/svr_sync/v1/svr_sync/fetch_session_msgs")mapOf("sender_device_id" to "1") else emptyMap()
        return data(path,currentParams,authenticated=true,host=host,bounded=true)
    }
    internal suspend fun searchAssistRead(path:String,params:Map<String,String>):JsonObject {
        require(path in setOf("/x/web-interface/suggest","/x/web-interface/wbi/search/square"))
        return data(path,params,bounded=true,responseLimitBytes=512*1024) as? JsonObject
            ?: throw PlatformFailure("平台搜索提示格式变化")
    }
    private fun decodeResponse(status: Int, text: String): JsonElement {
        if (status != 200) throw PlatformFailure(if (status in listOf(403, 412, 429)) "平台限制或需要验证" else "HTTP 请求失败", status)
        val root = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) {
            throw PlatformFailure("平台响应格式变化")
        }
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: throw PlatformFailure("缺少响应状态")
        if (code != 0) throw PlatformFailure(when (code) {
            -101 -> "登录已失效"
            -403, -412, -352 -> "平台限制或需要验证"
            -404, 62002 -> "内容不存在或不可访问"
            else -> "平台返回错误"
        }, code)
        return root["data"] ?: JsonNull
    }
    private suspend fun get(path: String, params: Map<String, String> = emptyMap(), authenticated: Boolean = false, candidateCookie: String? = null): JsonObject =
        data(path, params, authenticated, candidateCookie) as? JsonObject ?: throw PlatformFailure("缺少响应数据")
    private suspend fun videoData(bvid:String,now:Long=searchClock?.nowMs() ?: throw PlatformFailure("请求签名暂不可用"),md5:(ByteArray)->String=searchMd5):JsonObject = videoData(mapOf("bvid" to bvid),now,md5)
    private suspend fun videoData(identity:Map<String,String>,now:Long,md5:(ByteArray)->String):JsonObject =
        get("/x/web-interface/wbi/view/detail",upSigned(identity+mapOf("web_location" to "1315873"),now,md5),true)["View"] as? JsonObject
            ?: throw PlatformFailure("缺少视频详情")

    suspend fun accountHomePage(cursor:Long,window:RecentRecommendationWindow,md5:(ByteArray)->String):AccountHomePage {
        require(cursor>=0)
        val d=data("/x/v2/feed/index",mapOf("mobi_app" to "android","platform" to "android","idx" to "$cursor",
            "pull" to if(cursor==0L)"1" else "0","ps" to "12","inline_sound" to "0","video_mode" to "0"),
            authenticated=true,host=ReadHost.APP,bounded=true) as? JsonObject ?: throw PlatformFailure("B站首页推荐格式变化")
        val items=d["items"] as? JsonArray ?: throw PlatformFailure("B站首页推荐格式变化")
        val next=items.mapNotNull{(it as? JsonObject)?.get("idx")?.jsonPrimitive?.longOrNull}.lastOrNull()?.takeIf{it>0&&it!=cursor}
        val ids=items.mapNotNull{value->
            val r=value as? JsonObject ?: return@mapNotNull null
            if(r["card_goto"]?.jsonPrimitive?.contentOrNull!="av" || r["goto"]?.jsonPrimitive?.contentOrNull!="av" ||
                r["ad_info"] !in listOf(null,JsonNull) || r["can_play"]?.jsonPrimitive?.intOrNull!=1)return@mapNotNull null
            val aid=r["param"]?.jsonPrimitive?.longOrNull?.takeIf{it>0} ?: return@mapNotNull null
            val args=r["args"] as? JsonObject
            if(args?.get("aid")?.jsonPrimitive?.longOrNull?.let{it!=aid}==true)return@mapNotNull null
            aid
        }.distinct().take(12)
        val rows=mutableListOf<PopularMusic>()
        for(batch in ids.chunked(2)) {
            // Verification is concurrent, but feed order is never shuffled or ranked locally.
            val verified=coroutineScope {batch.map{aid->async {
                val v=try{videoData(mapOf("aid" to "$aid"),window.nowMs,md5)}catch(e:PlatformFailure){if(e.code in listOf(-404,62002))null else throw e}
                if(v==null || v["aid"]?.jsonPrimitive?.longOrNull!=aid)return@async null
                val bv=v["bvid"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val owner=v["owner"] as? JsonObject ?: return@async null
                val pages=v["pages"] as? JsonArray ?: return@async null
                val views=(v["stat"] as? JsonObject)?.get("view")?.jsonPrimitive?.longOrNull ?: return@async null
                PopularMusic(bv,v.string("title"),v.string("pic"),owner.string("name"),views,pages.size,
                    authorId=owner.long("mid"),avatar=owner["face"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }}.awaitAll().filterNotNull()}
            rows+=verified
        }
        return AccountHomePage(PopularMusic.valid(rows,false),next)
    }

    suspend fun account(candidateCookie: String? = null): Account {
        val accountGeneration = generation()
        val data = get("/x/web-interface/nav", authenticated = true, candidateCookie = candidateCookie)
        if (data["isLogin"]?.jsonPrimitive?.booleanOrNull != true) throw PlatformFailure("登录已失效", -101)
        val vip = data["vip"] as? JsonObject
        val status = vip?.get("status")?.jsonPrimitive?.intOrNull ?: data["vipStatus"]?.jsonPrimitive?.intOrNull
        if (candidateCookie == null && generation() != accountGeneration) throw PlatformFailure("账号会话已变化，旧结果已丢弃")
        return Account(data.long("mid"), data.string("uname"), when(status) { 1 -> Membership.VIP; 0 -> Membership.ORDINARY; else -> Membership.UNKNOWN },
            vip?.get("due_date")?.jsonPrimitive?.longOrNull ?: data["vipDueDate"]?.jsonPrimitive?.longOrNull,
            data["face"]?.jsonPrimitive?.contentOrNull.orEmpty(), (data["level_info"] as? JsonObject)?.get("current_level")?.jsonPrimitive?.intOrNull,
            (data["money"] as? JsonPrimitive)?.doubleOrNull?.takeIf{it.isFinite()&&it>=0})
    }

    suspend fun historyPaused() = data("/x/v2/history/shadow",authenticated=true).jsonPrimitive.booleanOrNull
        ?: throw PlatformFailure("平台响应格式变化")
    suspend fun historyPage(cursor:HistoryCursor?=null):RemoteHistoryPage {
        val d=get("/x/web-interface/history/cursor",mapOf("ps" to "30","type" to "all","max" to "${cursor?.max ?: 0}","view_at" to "${cursor?.viewedAt ?: 0}","business" to cursor?.business.orEmpty()),true)
        val raw=d["list"] as? JsonArray ?: throw PlatformFailure("平台响应格式变化")
        var skipped=0
        val rows=raw.mapNotNull{value->
            val row=value as? JsonObject ?: throw PlatformFailure("平台响应格式变化")
            val h=row["history"] as? JsonObject ?: throw PlatformFailure("平台响应格式变化")
            val at=(row["view_at"]?.jsonPrimitive?.longOrNull ?: 0)*1000
            val progress=row["progress"]?.jsonPrimitive?.longOrNull ?: 0
            when(h["business"]?.jsonPrimitive?.contentOrNull) {
                "archive"->{
                    val bv=h["bvid"]?.jsonPrimitive?.contentOrNull.orEmpty();val cid=h["cid"]?.jsonPrimitive?.longOrNull ?: 0;val part=h["page"]?.jsonPrimitive?.intOrNull ?: 1
                    if(Bvid.parse(bv)!=bv||cid<=0||part<=0){skipped++;null}else RemoteHistoryItem(h["oid"]?.jsonPrimitive?.longOrNull ?: 0,bv,cid,part,row.string("title"),h.string("part"),(progress.coerceAtLeast(0))*1000,at,progress==-1L)
                }
                "live"->{val id=h["oid"]?.jsonPrimitive?.longOrNull ?: 0;if(id<=0){skipped++;null}else RemoteHistoryItem(title=row.string("title"),viewedAt=at,roomId=id)}
                else->{skipped++;null}
            }
        }
        val c=d["cursor"] as? JsonObject
        if(raw.isNotEmpty()&&c==null)throw PlatformFailure("平台历史分页格式变化，原记录保留")
        val next=if(raw.isEmpty()||c?.get("max")?.jsonPrimitive?.longOrNull==0L)null else HistoryCursor(c!!.long("max"),c.long("view_at"),c.string("business"))
        return RemoteHistoryPage(rows,next,skipped)
    }
    /** Single POST, with a distinct uncertain result after transport interruption. */
    private suspend fun historyPost(path:String,fields:Map<String,String>,live:Boolean=false,expectedCookie:String?=credentials(),revision:Long=generation(),account:String?=null) {
        val cookie=expectedCookie ?: throw PlatformFailure("请先登录",-101)
        if(account!=null&&cookie.split(';').none{it.trim().substringBefore('=')=="DedeUserID"&&it.trim().substringAfter('=')==account})throw PlatformFailure("账号会话已变化")
        val csrf=cookie.split(';').map{it.trim()}.firstOrNull{it.substringBefore('=')=="bili_jct"}?.substringAfter('=')?.takeIf{it.isNotBlank()} ?: throw PlatformFailure("请重新登录以取得同步凭据")
        if(cookie!=credentials()||generation()!=revision)throw PlatformFailure("账号会话已变化")
        val response=try{client.submitForm("https://${if(live)"api.live.bilibili.com" else "api.bilibili.com"}$path",Parameters.build{
            fields.forEach{(key,value)->append(key,value)};append("csrf",csrf);if(live)append("csrf_token",csrf)
        }){header("Cookie",cookie);header("User-Agent","Mozilla/5.0 BiliListen/0.0.4");header("Referer",if(live)"https://live.bilibili.com/" else "https://www.bilibili.com/")}}
        catch(e:CancellationException){throw e}catch(_:Exception){throw HistoryWriteUncertain()}
        val body=try{response.bodyAsText()}catch(e:CancellationException){throw e}catch(_:Exception){throw HistoryWriteUncertain()}
        if(cookie!=credentials()||generation()!=revision)throw HistoryWriteUncertain()
        try{decodeResponse(response.status.value,body)}catch(e:PlatformFailure){if(e.code==null||e.code in 500..599)throw HistoryWriteUncertain() else throw e}
    }
    suspend fun reportHistory(item:RemoteHistoryItem,account:String?=null):RemoteHistoryItem {
        val cookie=credentials();val revision=generation()
        if(historyPaused())throw PlatformFailure("B站已暂停历史记录，请在官方恢复记录")
        if(item.roomId>0) {
            historyPost("/xlive/web-room/v1/index/roomEntryAction",mapOf("room_id" to "${item.roomId}","platform" to "pc","visit_id" to ""),true,cookie,revision,account)
            // Room entry success alone does not establish a history write.
            if(historyPage().items.none{it.roomId==item.roomId&&it.viewedAt>=item.viewedAt-1000})throw HistoryWriteUncertain()
            return item
        }
        val video=video(item.bvid)
        val part=video.parts.firstOrNull{it.cid==item.cid} ?: throw PlatformFailure("原分 P 已不可用，不能同步旧进度")
        historyPost("/x/v2/history/report",mapOf("aid" to "${video.aid}","cid" to "${part.cid}","progress" to "${item.positionMs/1000}","platform" to "android"),expectedCookie=cookie,revision=revision,account=account)
        return item.copy(aid=video.aid,title=video.title,partTitle=part.title,page=part.number)
    }
    suspend fun deleteHistory(key:String?,account:String?=null) {
        require(key==null||Regex("(archive|live)_[1-9][0-9]*").matches(key))
        historyPost(if(key==null)"/x/v2/history/clear" else "/x/v2/history/delete",if(key==null)emptyMap() else mapOf("kid" to key),account=account)
    }

    suspend fun video(bvid: String): Video {
        require(Bvid.parse(bvid) == bvid)
        val data = videoData(bvid)
        val parts = data["pages"]?.jsonArray?.map {
            val part = it.jsonObject
            VideoPart(part.long("cid"), part.int("page"), part.string("part"), part["duration"]?.jsonPrimitive?.longOrNull ?: 0)
        }.orEmpty()
        if (parts.isEmpty()) throw PlatformFailure("没有可播放的分 P")
        val owner = data["owner"] as? JsonObject
        val stat = data["stat"] as? JsonObject
        val rights = data["rights"] as? JsonObject
        return Video(data.string("bvid"), data.long("aid"), data.string("title"), parts,
            data["pic"]?.jsonPrimitive?.contentOrNull.orEmpty(), owner?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
            owner?.get("face")?.jsonPrimitive?.contentOrNull.orEmpty(), owner?.get("mid")?.jsonPrimitive?.longOrNull ?: 0,
            data["duration"]?.jsonPrimitive?.longOrNull ?: 0, stat?.get("view")?.jsonPrimitive?.longOrNull,
            stat?.get("danmaku")?.jsonPrimitive?.longOrNull, data["pubdate"]?.jsonPrimitive?.longOrNull,
            data["tid"]?.jsonPrimitive?.intOrNull ?: 0,
            data["tname_v2"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: data["tname"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            when(rights?.get("download")?.jsonPrimitive?.intOrNull) {1->true;0->false;else->null},
            listOf("pay","ugc_pay","is_upower_exclusive","is_chargeable_season").any { key ->
                listOf(rights?.get(key),data[key]).any { it?.jsonPrimitive?.let{p->p.intOrNull==1||p.booleanOrNull==true}==true }
            },data["desc"]?.jsonPrimitive?.contentOrNull.orEmpty(),stat?.get("like")?.jsonPrimitive?.longOrNull,
            stat?.get("coin")?.jsonPrimitive?.longOrNull,stat?.get("favorite")?.jsonPrimitive?.longOrNull,
            stat?.get("share")?.jsonPrimitive?.longOrNull,stat?.get("reply")?.jsonPrimitive?.longOrNull,
            data["copyright"]?.jsonPrimitive?.intOrNull ?: 0,
            (data["staff"] as? JsonArray).orEmpty().mapNotNull{(it as? JsonObject)?.get("mid")?.jsonPrimitive?.longOrNull?.takeIf{it>0}}.toSet())
    }

    suspend fun videoTags(bvid: String): List<String> {
        require(Bvid.parse(bvid)==bvid)
        val rows=data("/x/tag/archive/tags",mapOf("bvid" to bvid)) as? JsonArray ?: throw PlatformFailure("标签响应格式变化")
        return rows.mapNotNull{(it as? JsonObject)?.get("tag_name")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)}.distinct().take(40)
    }
    suspend fun videoRelations(bvid: String): VideoRelations {
        require(Bvid.parse(bvid)==bvid)
        val relation=get("/x/web-interface/archive/relation",mapOf("bvid" to bvid),true)
        val liked=relation["like"]?.jsonPrimitive?.booleanOrNull
        val coins=relation["coin"]?.jsonPrimitive?.intOrNull
        val favorite=relation["favorite"]?.jsonPrimitive?.booleanOrNull
        if(liked==null||coins==null||coins !in 0..2||favorite==null)throw PlatformFailure("互动状态暂无法核对")
        return VideoRelations(liked,coins,favorite)
    }

    private suspend fun engagementPost(account: String,bvid: String,path: String,fields:Map<String,String>):MutationOutcome {
        require(account.toLongOrNull()?.let{it>0}==true&&Bvid.parse(bvid)==bvid)
        val cookie=credentials() ?: throw PlatformFailure("请先登录",-101)
        val revision=generation()
        val csrf=cookie.split(';').map{it.trim()}.firstOrNull{it.substringBefore('=')=="bili_jct"}?.substringAfter('=')?.takeIf{it.isNotBlank()}
            ?: throw PlatformFailure("缺少互动凭据，请重新登录",-111)
        if(this.account().id.toString()!=account||credentials()!=cookie||generation()!=revision)throw PlatformFailure("账号会话已变化，已停止写入")
        return try {
            val response=client.submitForm("https://api.bilibili.com$path",Parameters.build {
                append("bvid",bvid);append("csrf",csrf);fields.forEach{(key,value)->append(key,value)}
            }){header("Cookie",cookie);header("Referer","https://www.bilibili.com/video/$bvid/");header("User-Agent","Mozilla/5.0 BiliListen/0.8.21")}
            decodeResponse(response.status.value,response.bodyAsText())
            if(credentials()!=cookie||generation()!=revision)MutationOutcome.UNKNOWN else MutationOutcome.CONFIRMED
        }catch(e:CancellationException){throw e}catch(e:PlatformFailure){
            if(e.code!=null&&e.code !in 500..599)throw PlatformFailure(when(e.code){
                -104->"硬币不足";34002->"不能给自己的视频投币";34003,34005->"超过该视频的投币上限";34004->"投币过于频繁，请稍后再试";65006->"该视频已点赞，请刷新互动状态";-111->"互动凭据已失效，请重新登录";else->e.category
            },e.code)
            MutationOutcome.UNKNOWN
        }catch(_:Exception){MutationOutcome.UNKNOWN}
    }
    suspend fun likeVideo(account:String,bvid:String,liked:Boolean)=engagementPost(account,bvid,"/x/web-interface/archive/like",mapOf("like" to if(liked)"1" else "2"))
    suspend fun coinVideo(account:String,bvid:String,amount:Int):MutationOutcome {
        require(amount in 1..2)
        return engagementPost(account,bvid,"/x/web-interface/coin/add",mapOf("multiply" to "$amount","select_like" to "0"))
    }
    suspend fun tripleVideo(account:String,bvid:String):TripleReceipt? {
        require(account.toLongOrNull()?.let{it>0}==true&&Bvid.parse(bvid)==bvid)
        val cookie=credentials() ?: throw PlatformFailure("请先登录",-101);val revision=generation()
        val csrf=cookie.split(';').map{it.trim()}.firstOrNull{it.substringBefore('=')=="bili_jct"}?.substringAfter('=')?.takeIf{it.isNotBlank()} ?: throw PlatformFailure("缺少互动凭据，请重新登录",-111)
        if(this.account().id.toString()!=account||credentials()!=cookie||generation()!=revision)throw PlatformFailure("账号会话已变化，已停止写入")
        return try {
            val response=client.submitForm("https://api.bilibili.com/x/web-interface/archive/like/triple",Parameters.build{append("bvid",bvid);append("csrf",csrf)}){
                header("Cookie",cookie);header("Referer","https://www.bilibili.com/video/$bvid/");header("User-Agent","Mozilla/5.0 BiliListen/0.8.21")
            }
            val d=decodeResponse(response.status.value,response.bodyAsText()) as? JsonObject ?: return null
            if(credentials()!=cookie||generation()!=revision)return null
            val like=d["like"]?.jsonPrimitive?.booleanOrNull ?: return null
            val coin=d["coin"]?.jsonPrimitive?.booleanOrNull ?: return null
            val fav=d["fav"]?.jsonPrimitive?.booleanOrNull ?: return null
            val amount=d["multiply"]?.jsonPrimitive?.intOrNull ?: if(coin) return null else 0
            if(amount !in 0..2)return null
            TripleReceipt(like,coin,fav,amount)
        }catch(e:CancellationException){throw e}catch(e:PlatformFailure){if(e.code!=null&&e.code !in 500..599)throw e;null}catch(_:Exception){null}
    }
    suspend fun followingCount(): Long? = get("/x/web-interface/nav/stat", authenticated = true)["following"]?.jsonPrimitive?.longOrNull

    private var upWbiKeys: Triple<Long,String,String>? = null
    private val wbiKeyGate=Mutex()
    private suspend fun upSigned(params:Map<String,String>,now:Long,md5:(ByteArray)->String):Map<String,String> {
        val keys=wbiKeyGate.withLock { upWbiKeys?.takeIf{now-it.first in 0..300000} ?: run {
            // Public signing keys are also returned for a guest nav response (code -101).
            // This read carries no account cookie and never marks a guest as logged in.
            val generationAtStart=generation()
            val response=try {client.get("https://api.bilibili.com/x/web-interface/nav") {
                header("Referer","https://www.bilibili.com/");header("User-Agent","Mozilla/5.0 BiliListen/0.8.12")
            }}catch(e:CancellationException){throw e}catch(_:Exception){throw PlatformFailure("签名信息网络请求失败")}
            if(response.status.value!=200)throw PlatformFailure(if(response.status.value in listOf(403,412,429))"平台限制或需要验证" else "签名信息读取失败",response.status.value)
            val body=try{response.bodyAsText()}catch(e:CancellationException){throw e}catch(_:Exception){throw PlatformFailure("签名信息网络请求失败")}
            if(body.length>512*1024)throw PlatformFailure("平台响应格式变化")
            if(generation()!=generationAtStart)throw PlatformFailure("账号会话已变化，旧结果已丢弃")
            val root=try{Json.parseToJsonElement(body).jsonObject}catch(_:Exception){throw PlatformFailure("平台响应格式变化")}
            if(root["code"]?.jsonPrimitive?.intOrNull !in setOf(0,-101))decodeResponse(response.status.value,body)
            val nav=(root["data"] as? JsonObject)?.get("wbi_img") as? JsonObject ?: throw PlatformFailure("平台未返回签名信息")
            Triple(now,nav.string("img_url"),nav.string("sub_url")).also{upWbiKeys=it}
        } }
        return WbiQuery.sign(params,keys.second,keys.third,now,md5)
    }
    private fun plain(value:String)=value.replace(Regex("<[^>]*>"),"").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"")
    suspend fun upSearch(keyword:String,page:Int,now:Long,md5:(ByteArray)->String):UpSearchPage {
        require(keyword.trim().length in 1..100 && page>0)
        val params=mapOf("search_type" to "bili_user","keyword" to keyword.trim(),"page" to "$page","page_size" to "20")
        val d=get("/x/web-interface/wbi/search/type",upSigned(params,now,md5),true)
        if(d.int("page")!=page)throw PlatformFailure("平台返回页码不匹配")
        val rows=(d["result"] as? JsonArray).orEmpty().map { value->
            val row=value.jsonObject
            UpProfile(row.long("mid"),plain(row.string("uname")),row["upic"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                plain(row["usign"]?.jsonPrimitive?.contentOrNull.orEmpty()),row["videos"]?.jsonPrimitive?.intOrNull ?: 0,row["fans"]?.jsonPrimitive?.longOrNull)
        }.distinctBy{it.mid}
        return UpSearchPage(rows,page,d.int("numResults"),page<d.int("numPages"))
    }
    suspend fun upProfile(mid:Long,now:Long,md5:(ByteArray)->String):UpProfile {
        require(mid>0)
        val d=get("/x/space/wbi/acc/info",upSigned(mapOf("mid" to "$mid"),now,md5),true)
        if(d.long("mid")!=mid)throw PlatformFailure("UP 主身份不匹配")
        return UpProfile(mid,plain(d.string("name")),d["face"]?.jsonPrimitive?.contentOrNull.orEmpty(),plain(d["sign"]?.jsonPrimitive?.contentOrNull.orEmpty()))
    }
    suspend fun upUploads(mid:Long,page:Int,order:UploadOrder,keyword:String,now:Long,md5:(ByteArray)->String):SourceContentPage {
        require(mid>0&&page>0&&keyword.length<=100)
        // Oldest order is applied to the complete local snapshot rather than an undocumented query value.
        val params=mapOf("mid" to "$mid","pn" to "$page","ps" to "30","order" to (if(order==UploadOrder.OLDEST)"pubdate" else order.apiValue),"keyword" to keyword.trim())
        val d=get("/x/space/wbi/arc/search",upSigned(params,now,md5),true)
        val paging=d["page"]?.jsonObject ?: throw PlatformFailure("缺少分页状态")
        if(paging.int("pn")!=page||paging.int("ps")!=30)throw PlatformFailure("平台返回页码不匹配")
        val count=paging.int("count");if(count<0)throw PlatformFailure("平台响应格式变化")
        val list=d["list"]?.jsonObject?.get("vlist") as? JsonArray ?: throw PlatformFailure("缺少投稿列表")
        val items=list.map { value->
            val row=value.jsonObject
            val union=row["is_union_video"]?.jsonPrimitive?.let{it.booleanOrNull==true||it.intOrNull==1}==true
            if(row.long("mid")!=mid&&!union)throw PlatformFailure("投稿作者身份不匹配")
            val bv=row.string("bvid");if(Bvid.parse(bv)!=bv)throw PlatformFailure("平台响应格式变化")
            val length=row["length"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val duration=length.split(':').fold(0L){n,v->n*60+(v.toLongOrNull() ?: 0)}
            favoriteItem(row,bv).copy(title=plain(row.string("title")),duration=duration)
        }.distinctBy{it.bvid}
        if(items.isEmpty() && page.toLong()*30<count)throw PlatformFailure("投稿分页没有推进")
        val creator=list.mapNotNull{it as? JsonObject}.firstOrNull{it["mid"]?.jsonPrimitive?.longOrNull==mid}
            ?.get("author")?.jsonPrimitive?.contentOrNull?.let(::plain).orEmpty()
        return SourceContentPage(ContentSource(SourceRef(SourceKind.UP_UPLOADS,mid,mid),"${creator.ifBlank{"UP $mid"}}的投稿",creator,count),items,page,page.toLong()*30<count)
    }

    suspend fun subtitleTracks(video: VideoRef): SubtitleTracks = SubtitleParser.tracks(video,
        get("/x/player/wbi/v2",mapOf("bvid" to video.bvid,"cid" to video.cid.toString()),authenticated=true))

    suspend fun subtitleCues(track: SubtitleTrack): List<SubtitleCue> {
        val target=if(track.url.startsWith("//"))"https:${track.url}" else track.url
        val url=try { Url(target) } catch(_:Exception) { throw SubtitleFailure(SubtitleStatus.FAILED,"字幕地址格式无效") }
        val allowed=url.host=="hdslb.com" || url.host.endsWith(".hdslb.com") || url.host=="bilibili.com" || url.host.endsWith(".bilibili.com")
        if(url.protocol.name!="https" || url.port!=443 || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || !allowed)
            throw SubtitleFailure(SubtitleStatus.RESTRICTED,"字幕地址不是支持的官方 HTTPS 地址")
        val identity=generation()
        try {
            val response=client.get(target) {
                header("Referer","https://www.bilibili.com/");header("User-Agent","Mozilla/5.0 BiliListen/0.5.0")
                // Subtitle CDN requests are independent from authenticated API requests.
            }
            when(response.status.value) {
                200 -> Unit
                401,403,404,410 -> throw SubtitleFailure(SubtitleStatus.EXPIRED,"字幕地址已失效或不可访问，请重新读取轨道")
                412,429 -> throw SubtitleFailure(SubtitleStatus.RESTRICTED,"字幕请求受到平台限制或需要验证")
                else -> throw SubtitleFailure(SubtitleStatus.FAILED,"字幕文件请求失败")
            }
            val limit=4*1024*1024
            if((response.headers["Content-Length"]?.toLongOrNull() ?: 0)>limit)throw SubtitleFailure(SubtitleStatus.FAILED,"字幕文件过大，暂无法读取")
            val bytes=ByteArray(limit+1);val channel=response.bodyAsChannel();var size=0
            while(size<bytes.size) { val n=channel.readAvailable(bytes,size,bytes.size-size);if(n<0)break;if(n==0)continue;size+=n }
            if(size>limit) { channel.cancel(null);throw SubtitleFailure(SubtitleStatus.FAILED,"字幕文件过大，暂无法读取") }
            if(generation()!=identity)throw PlatformFailure("账号会话已变化，旧结果已丢弃")
            return SubtitleParser.cues(bytes.copyOf(size).decodeToString())
        } catch(e:CancellationException) {throw e}
        catch(e:SubtitleFailure) {throw e}
        catch(e:PlatformFailure) {throw e}
        catch(_:Exception) {throw SubtitleFailure(SubtitleStatus.FAILED,"字幕网络读取失败")}
    }

    /** Resolve only collection links; short-link reads never use account credentials. */
    suspend fun resolveCollectionInput(input:String):SourceLink {
        var target=SourceLinks.inputUrl(input)
        repeat(4){hop->
            SourceLinks.parse(target)?.takeIf{it.collectionKind==CollectionKind.SEASON}?.let{return it}
            val url=try{Url(target)}catch(_:Exception){throw PlatformFailure("链接格式不正确")}
            if(url.protocol.name!="https"||url.host!="b23.tv"||url.port!=443||!url.user.isNullOrEmpty()||!url.password.isNullOrEmpty())throw PlatformFailure("请输入 B 站合集的完整分享链接")
            if(hop==3)throw PlatformFailure("短链接跳转次数过多")
            val response=try{client.get(target){header("User-Agent","Mozilla/5.0")}}catch(e:CancellationException){throw e}catch(_:Exception){throw PlatformFailure("短链接读取失败")}
            if(response.status.value !in setOf(301,302,303,307,308))throw PlatformFailure("短链接未返回有效合集")
            target=response.headers["Location"] ?: throw PlatformFailure("短链接缺少目标")
        }
        throw PlatformFailure("请输入 B 站合集的完整分享链接")
    }

    /** Anonymous short-link resolution: validate every destination before sending a request. */
    suspend fun resolveSharedInput(input: String): SharedTarget {
        if(input.length > 8192) throw PlatformFailure("分享文字过长")
        SharedInput.direct(input.trim())?.let { return it }
        var target = SharedInput.url(input)
        repeat(4) { hop ->
            SharedInput.direct(target)?.let { return it }
            val url = try { Url(target) } catch (_: Exception) { throw PlatformFailure("链接格式不正确") }
            if(url.protocol.name != "https" || url.host != "b23.tv" || url.port != 443 || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) throw PlatformFailure("仅支持 B 站视频或直播分享")
            if(hop==3) throw PlatformFailure("短链接跳转次数过多")
            val response = client.get(target) { header("User-Agent", "Mozilla/5.0") }
            if(response.status.value !in setOf(301,302,303,307,308)) throw PlatformFailure("短链接未返回有效目标")
            target = response.headers["Location"] ?: throw PlatformFailure("短链接缺少目标")
        }
        throw PlatformFailure("无法解析分享")
    }

    suspend fun resolveVideoInput(input: String): String {
        Bvid.parse(input)?.let { return it }
        var target = Regex("https://(?:b23\\.tv|(?:www\\.|m\\.)?bilibili\\.com)/[^\\s]+")
            .find(input.trim())?.value ?: throw PlatformFailure("请输入 B 站视频链接或 BV 号")
        repeat(4) { hop ->
            Bvid.parse(target)?.let { return it }
            val url = try { Url(target) } catch (_: Exception) { throw PlatformFailure("链接格式不正确") }
            if(url.protocol.name != "https" || url.host != "b23.tv" || url.port != 443 || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty())
                throw PlatformFailure("只支持 B 站视频和 b23.tv 短链接")
            if(hop == 3) throw PlatformFailure("短链接跳转次数过多")
            val response = try { client.get(target) { header("User-Agent", "Mozilla/5.0") } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { throw PlatformFailure("短链接读取失败") }
            if(response.status.value !in setOf(301,302,303,307,308)) throw PlatformFailure("短链接未指向视频")
            target = response.headers["Location"] ?: throw PlatformFailure("短链接缺少目标")
        }
        throw PlatformFailure("短链接无效")
    }

    suspend fun audio(bvid: String, cid: Long, supported: (AudioTrack) -> Boolean = { true }): String {
        val tracks = audioProbe(bvid, cid, extended = true).tracks
        if (tracks.isEmpty()) throw PlatformFailure("未返回独立音轨；验证版不会自动使用混流")
        return AudioQuality.best(tracks, supported)?.url ?: throw PlatformFailure("没有设备支持的独立音轨")
    }
    suspend fun audioProbe(bvid: String, cid: Long, extended: Boolean = false): AudioProbe {
        require(Bvid.parse(bvid) == bvid && cid > 0)
        val data = get("/x/player/wbi/playurl", mapOf("bvid" to bvid, "cid" to cid.toString(), "fnval" to if (extended) "4048" else "16", "fnver" to "0", "fourk" to "0",
            "from_client" to "BROWSER","web_location" to "1315873","support_multi_audio" to "true"), true)
        val dash = data["dash"] as? JsonObject
        val audio = (dash?.get("audio") as? JsonArray).orEmpty() +
            ((dash?.get("dolby") as? JsonObject)?.get("audio") as? JsonArray).orEmpty() +
            listOfNotNull((dash?.get("flac") as? JsonObject)?.get("audio") as? JsonObject)
        val tracks = audio.map { it.jsonObject }.map { track ->
            AudioTrack(track.int("id"), track["baseUrl"]?.jsonPrimitive?.contentOrNull ?: track.string("base_url"),
                track["mimeType"]?.jsonPrimitive?.contentOrNull ?: track["mime_type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                track["codecs"]?.jsonPrimitive?.contentOrNull.orEmpty(), track["bandwidth"]?.jsonPrimitive?.longOrNull ?: 0,
                track["audioSamplingRate"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
                track["channels"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
                (track["bitDepth"] ?: track["bit_depth"])?.jsonPrimitive?.intOrNull?.takeIf { it > 0 })
        }.distinctBy { it.id to it.codec }
        fun flag(key:String):Boolean? = data[key]?.jsonPrimitive?.let { it.booleanOrNull ?: when(it.intOrNull){0->false;1->true;else->null} }
        val preview=flag("is_preview");val drm=flag("is_drm")
        return AudioProbe(tracks, (data["durl"] as? JsonArray)?.isNotEmpty() == true, data["timelength"]?.jsonPrimitive?.longOrNull,
            preview!=true && drm!=true && (data["timelength"]?.jsonPrimitive?.longOrNull ?: 0)>0,preview,drm)
    }

    suspend fun folders(account: Long, aid: Long? = null): List<FavoriteFolder> {
        val params = mutableMapOf("up_mid" to account.toString(), "type" to "2")
        aid?.let { params["rid"] = it.toString() }
        val data = get("/x/v3/fav/folder/created/list-all", params, true)
        return (data["list"] as? JsonArray)?.map { element ->
            val item = element.jsonObject
            FavoriteFolder(item.long("id"), item.string("title"), item.int("media_count"),
                item["fav_state"]?.jsonPrimitive?.intOrNull?.let { it == 1 }, item["attr"]?.jsonPrimitive?.intOrNull,
                item["cover"]?.jsonPrimitive?.contentOrNull.orEmpty(), item["intro"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }.orEmpty()
    }

    suspend fun favorites(folder: Long, page: Int, keyword: String = "", all: Boolean = false, order: String = "mtime", tid: Int = 0): FavoritePage {
        require(page > 0)
        require(keyword.length <= 100 && order in setOf("mtime", "view", "pubtime") && tid >= 0)
        val data = get("/x/v3/fav/resource/list", mapOf("media_id" to folder.toString(), "pn" to page.toString(), "ps" to "20", "order" to order, "type" to if(all) "1" else "0", "keyword" to keyword, "tid" to "$tid", "platform" to "web"), true)
        val items = (data["medias"] as? JsonArray)?.mapNotNull {
            val row = it.jsonObject
            val bv = row["bvid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (Bvid.parse(bv) == null) null else favoriteItem(row, bv)
        }.orEmpty()
        return FavoritePage(items, data["has_more"]?.jsonPrimitive?.booleanOrNull == true)
    }

    suspend fun search(keyword: String, page: Int = 1, order: String = "totalrank", tid: Int = 0): SearchPage {
        require(keyword.isNotBlank() && keyword.length <= 100 && page in 1..200)
        require(order in setOf("totalrank", "click", "pubdate") && tid >= 0)
        val now=searchClock?.nowMs() ?: throw PlatformFailure("搜索签名暂不可用")
        val params=mapOf("search_type" to "video", "keyword" to keyword.trim(), "page" to page.toString(), "page_size" to "20", "order" to order, "tids" to "$tid",
            "web_location" to "1430654","platform" to "pc","highlight" to "1")
        val data = get("/x/web-interface/wbi/search/type",upSigned(params,now,searchMd5),true)
        if(data.int("page")!=page)throw PlatformFailure("平台返回页码不匹配")
        if(data.int("numPages")<0||data.int("numResults")<0)throw PlatformFailure("平台响应格式变化")
        val result=data["result"] as? JsonArray ?: throw PlatformFailure("缺少搜索结果")
        val items = result.mapNotNull {
            val row = it.jsonObject
            val bv = row["bvid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (Bvid.parse(bv) != bv) return@mapNotNull null
            favoriteItem(row,bv).copy(title=row.string("title").replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"))
        }
        return SearchPage(items.distinctBy { it.bvid }, data.int("page"), data.int("numPages"), data.int("numResults"))
    }

    suspend fun publicFolders(owner: Long, page: Int = 1): SourceListPage {
        require(owner > 0 && page in 1..200)
        val d = get("/x/v3/fav/folder/created/list", mapOf("up_mid" to "$owner", "pn" to "$page", "ps" to "20"))
        val rows = (d["list"] as? JsonArray).orEmpty().map { it.jsonObject }
        return SourceListPage(rows.map { r ->
            val upper = r["upper"]?.jsonObject ?: throw PlatformFailure("缺少来源所有者")
            val actualOwner = upper.long("mid")
            if (actualOwner != owner) throw PlatformFailure("来源所有者不匹配")
            ContentSource(SourceRef(SourceKind.PUBLIC_FAVORITES, r.long("id"), actualOwner), r.string("title"), upper.string("name"), r.int("media_count"),r["cover"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }, page, d["has_more"]?.jsonPrimitive?.booleanOrNull ?: throw PlatformFailure("缺少分页状态"))
    }

    suspend fun favoriteSource(folder: Long, page: Int = 1, account: Long? = null, expectedOwner: Long? = null): SourceContentPage {
        require(folder > 0 && page in 1..200)
        val d = try {
            get("/x/v3/fav/resource/list", mapOf("media_id" to "$folder", "pn" to "$page", "ps" to "20", "order" to "mtime", "type" to "0"), true)
        } catch (failure: PlatformFailure) {
            if (failure.code == -403) throw PlatformFailure("收藏夹无权访问或已转为私有", -403)
            throw failure
        }
        val info = d["info"]?.jsonObject ?: throw PlatformFailure("缺少收藏夹信息")
        val upper = info["upper"]?.jsonObject ?: throw PlatformFailure("缺少来源所有者")
        val owner = upper.long("mid")
        if (info.long("id") != folder || expectedOwner != null && expectedOwner != owner) throw PlatformFailure("来源身份不匹配")
        val ref = SourceRef(if (owner == account) SourceKind.OWN_FAVORITES else SourceKind.PUBLIC_FAVORITES, folder, owner)
        return SourceContentPage(ContentSource(ref, info.string("title"), upper.string("name"), info.int("media_count"),info["cover"]?.jsonPrimitive?.contentOrNull.orEmpty()),
            sourceItems(d, "medias"), page, d["has_more"]?.jsonPrimitive?.booleanOrNull ?: throw PlatformFailure("缺少分页状态"))
    }

    /** Collected-list contains both type 11 favorite folders and type 21 UP seasons. */
    suspend fun followedSources(account: Long, page: Int = 1): SourceListPage {
        require(account > 0 && page in 1..200)
        val d = get("/x/v3/fav/folder/collected/list", mapOf("up_mid" to "$account", "pn" to "$page", "ps" to "20", "platform" to "web"), true)
        val unsupported = mutableSetOf<Int>()
        val unavailable = mutableSetOf<Long>()
        val sources = (d["list"] as? JsonArray).orEmpty().mapNotNull {
            val r = it.jsonObject
            val type = r.int("type")
            if (type !in setOf(11, 21)) { unsupported += type; return@mapNotNull null }
            val upper = r["upper"]?.jsonObject ?: throw PlatformFailure("缺少来源所有者")
            if (upper.long("mid") <= 0 || r.long("id") <= 0) {
                if (type == 21) unavailable += r.long("id")
                return@mapNotNull null
            }
            val ref = SourceRef(if (type == 21) SourceKind.UP_COLLECTION else SourceKind.PUBLIC_FAVORITES,
                r.long("id"), upper.long("mid"), if (type == 21) CollectionKind.SEASON else null)
            ContentSource(ref.checked(), r.string("title"), upper.string("name"), r.int("media_count"), r["cover"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        val more = d["has_more"]?.jsonPrimitive?.booleanOrNull ?: (page.toLong() * 20 < d.long("count"))
        return SourceListPage(sources, page, more, unsupported, unavailable)
    }

    suspend fun seasonSource(season: Long, page: Int = 1, expectedOwner: Long? = null): SourceContentPage {
        require(season > 0 && page in 1..200)
        val requestedOwner = expectedOwner ?: throw PlatformFailure("请提供合集所有者")
        val d = get("/x/polymer/web-space/seasons_archives_list", mapOf("season_id" to "$season", "mid" to "$requestedOwner", "page_num" to "$page", "page_size" to "20"), true)
        val info = d["meta"]?.jsonObject ?: throw PlatformFailure("缺少合集信息")
        val owner = info.long("mid")
        if (info.long("season_id") != season || owner != requestedOwner) throw PlatformFailure("来源身份不匹配")
        val pagination = d["page"]?.jsonObject ?: throw PlatformFailure("缺少分页状态")
        if (pagination.int("page_num") != page) throw PlatformFailure("平台返回页码不匹配")
        val count = pagination.int("total")
        val ref = SourceRef(SourceKind.UP_COLLECTION, season, owner, CollectionKind.SEASON)
        return SourceContentPage(ContentSource(ref, info.string("name"), "UP $owner", count, info["cover"]?.jsonPrimitive?.contentOrNull.orEmpty()), sourceItems(d, "archives"), page,
            page.toLong() * pagination.int("page_size") < count)
    }

    suspend fun seriesSource(series: Long, owner: Long, page: Int = 1): SourceContentPage {
        require(series > 0 && owner > 0 && page in 1..200)
        val info = get("/x/series/series", mapOf("series_id" to "$series"), true)["meta"]?.jsonObject ?: throw PlatformFailure("缺少系列信息")
        if (info.long("series_id") != series || info.long("mid") != owner) throw PlatformFailure("来源身份不匹配")
        val d = get("/x/series/archives", mapOf("series_id" to "$series", "mid" to "$owner", "pn" to "$page", "ps" to "20"), true)
        val pagination = d["page"]?.jsonObject ?: throw PlatformFailure("缺少分页状态")
        if (pagination.int("num") != page) throw PlatformFailure("平台返回页码不匹配")
        val count = pagination.int("total")
        return SourceContentPage(ContentSource(SourceRef(SourceKind.UP_COLLECTION, series, owner, CollectionKind.SERIES), info.string("name"), "UP $owner", count),
            sourceItems(d, "archives"), page, page.toLong() * pagination.int("size") < count)
    }

    private fun sourceItems(d: JsonObject, field: String): List<FavoriteItem> = (d[field] as? JsonArray).orEmpty().mapNotNull {
        val row = it.jsonObject
        val bv = row["bvid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        if (Bvid.parse(bv) != bv) null else favoriteItem(row, bv)
    }.distinctBy { it.bvid }

    private fun favoriteItem(row: JsonObject, bv: String): FavoriteItem {
        val owner = (row["upper"] as? JsonObject) ?: (row["owner"] as? JsonObject)
        val duration = row["duration"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val seconds = duration.toLongOrNull() ?: duration.split(':').fold(0L) { n, v -> n * 60 + (v.toLongOrNull() ?: 0) }
        val cover=listOf("cover","pic").firstNotNullOfOrNull{key->row[key]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}}.orEmpty()
        return FavoriteItem(bv,row.string("title"),cover,
            owner?.get("name")?.jsonPrimitive?.contentOrNull ?: row["author"]?.jsonPrimitive?.contentOrNull.orEmpty(),seconds)
    }

    suspend fun seasonFollowed(account: Long, source: SourceRef): Boolean {
        require(source.checked().collectionKind == CollectionKind.SEASON)
        val seen = mutableSetOf<SourceRef>()
        for (page in 1..10) {
            val result = followedSources(account, page)
            if (result.sources.any { it.ref == source }) return true
            if (source.id in result.unavailableSeasonIds) throw PlatformFailure("该追更来源已失效，关系待核对")
            if (!result.hasMore) return false
            if (result.sources.map { seen.add(it.ref) }.none { it }) throw PlatformFailure("追更分页没有推进，关系待核对")
        }
        throw PlatformFailure("追更列表尚未读取完整，关系待核对")
    }

    /** Caller journals intent before invoking; one POST only, ambiguous outcomes require read-only reconciliation. */
    suspend fun changeSeasonFollow(account: Long, source: SourceRef, follow: Boolean): MutationOutcome {
        require(source.checked().collectionKind == CollectionKind.SEASON)
        val cookie = credentials() ?: throw PlatformFailure("请先登录", -101)
        if (this.account().id != account) throw PlatformFailure("账号已变化")
        if (seasonFollowed(account, source) == follow) return MutationOutcome.UNCHANGED
        // Verify source identity before a write, rather than trusting an imported numeric ID.
        seasonSource(source.id, 1, source.owner)
        val csrf = cookie.split(';').map { it.trim() }.firstOrNull { it.substringBefore('=') == "bili_jct" }?.substringAfter('=')?.takeIf { it.isNotBlank() }
            ?: throw PlatformFailure("缺少追更写入凭据，请重新登录")
        if (credentials() != cookie) throw PlatformFailure("账号已变化，已停止写入")
        try {
            val path = if (follow) "fav" else "unfav"
            val r = client.submitForm("https://api.bilibili.com/x/v3/fav/season/$path", Parameters.build {
                append("season_id", source.id.toString()); append("platform", "web"); append("csrf", csrf)
            }) { header("Cookie", cookie); header("Referer", "https://www.bilibili.com/") }
            decodeResponse(r.status.value, r.bodyAsText())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: PlatformFailure) { if (e.code != null) throw e }
        catch (_: Exception) { /* Read only; never replay. */ }
        if (credentials() != cookie) return MutationOutcome.UNKNOWN
        return try { if (seasonFollowed(account, source) == follow) MutationOutcome.CONFIRMED else MutationOutcome.UNKNOWN }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MutationOutcome.UNKNOWN }
    }

    /** Exactly one POST, then read back the specific relationship. Never retries a write. */
    suspend fun ownFolder(account: Long, id: Long): FavoriteFolder {
        if (folders(account).none { it.id == id }) throw PlatformFailure("只能整理本人的收藏夹")
        val d = get("/x/v3/fav/folder/info", mapOf("media_id" to "$id"), true)
        val owner = (d["upper"] as? JsonObject)?.get("mid")?.jsonPrimitive?.longOrNull ?: d["mid"]?.jsonPrimitive?.longOrNull
        if (owner != account || d.long("id") != id) throw PlatformFailure("收藏夹所属账户不匹配")
        return FavoriteFolder(id, d.string("title"), d.int("media_count"), attr = d.int("attr"), cover = d.string("cover"), description = d.string("intro"))
    }

    suspend fun writeFolder(account: Long, draft: FolderDraft): Long? {
        val cookie = credentials() ?: throw PlatformFailure("请先登录", -101)
        val requestGeneration = generation()
        if (this.account().id != account) throw PlatformFailure("账户已变化")
        val original = if (draft.action != FolderAction.CREATE) ownFolder(account, draft.id) else null
        if (draft.action == FolderAction.DELETE && (original?.attr == null || original.attr and 2 == 0)) throw PlatformFailure("默认收藏夹不能删除")
        if (draft.action == FolderAction.DELETE && (draft.confirmedTitle != null && original?.title != draft.confirmedTitle || draft.confirmedCount != null && original?.count != draft.confirmedCount)) throw PlatformFailure("收藏夹信息已变化，请刷新后重新确认删除", -400)
        val csrf = cookie.split(';').map { it.trim() }.firstOrNull { it.substringBefore('=') == "bili_jct" }?.substringAfter('=')?.takeIf { it.isNotBlank() } ?: throw PlatformFailure("请重新登录以取得写入凭据")
        if (credentials() != cookie || generation() != requestGeneration) throw PlatformFailure("账号会话已变化，已停止写入")
        val path = when(draft.action) { FolderAction.CREATE -> "add"; FolderAction.EDIT -> "edit"; FolderAction.DELETE -> "del" }
        val response = client.submitForm("https://api.bilibili.com/x/v3/fav/folder/$path", Parameters.build {
            append("csrf", csrf)
            if (draft.action == FolderAction.DELETE) append("media_ids", "${draft.id}") else {
                require(draft.title.isNotBlank() && draft.title.length <= 20)
                append("title", draft.title); append("privacy", if(draft.private) "1" else "0")
                append("intro", original?.description.orEmpty()); append("cover", original?.cover.orEmpty())
                if (original != null) append("media_id", "${draft.id}")
            }
        }) { header("Cookie", cookie); header("Referer", "https://www.bilibili.com/") }
        val result = decodeResponse(response.status.value, response.bodyAsText())
        return (result as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull
    }

    suspend fun changeFavorite(account: Long, aid: Long, folder: Long, add: Boolean): MutationOutcome {
        require(aid > 0 && folder > 0)
        val cookie = credentials() ?: throw PlatformFailure("请先登录", -101)
        if (this.account().id != account) throw PlatformFailure("账号已变化")
        val target = folders(account, aid).firstOrNull { it.id == folder } ?: throw PlatformFailure("只能修改本人收藏夹")
        val before = target.contains ?: throw PlatformFailure("平台未返回收藏关系，已停止写入")
        if (before == add) return MutationOutcome.UNCHANGED
        val csrf = cookie.split(';').map { it.trim() }.firstOrNull { it.substringBefore('=') == "bili_jct" }?.substringAfter('=')
            ?.takeIf { it.isNotBlank() } ?: throw PlatformFailure("缺少收藏写入凭据，请重新登录")
        if (credentials() != cookie) throw PlatformFailure("账号已变化，已停止写入")
        try {
            val response = client.submitForm("https://api.bilibili.com/x/v3/fav/resource/deal", Parameters.build {
                append("rid", aid.toString()); append("type", "2"); append("csrf", csrf)
                append("add_media_ids", if (add) folder.toString() else "")
                append("del_media_ids", if (add) "" else folder.toString())
            }) { header("Cookie", cookie); header("Referer", "https://www.bilibili.com/") }
            decodeResponse(response.status.value, response.bodyAsText())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: PlatformFailure) {
            if (failure.code != null && failure.code !in 500..599) throw failure
        } catch (_: Exception) { /* Read back, never replay the POST. */ }
        if (credentials() != cookie) return MutationOutcome.UNKNOWN
        return try {
            repeat(4) { attempt ->
                if (attempt > 0) delay(500L * attempt)
                if (credentials() != cookie) return MutationOutcome.UNKNOWN
                val after = folders(account, aid).firstOrNull { it.id == folder }?.contains
                if (after == add) return MutationOutcome.CONFIRMED
            }
            MutationOutcome.UNKNOWN
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MutationOutcome.UNKNOWN }
    }

    private suspend fun homeRankingRows(rid:Int,window:RecentRecommendationWindow,md5:(ByteArray)->String):List<Recommendation> {
        val params=upSigned(mapOf("rid" to "$rid","type" to "all"),window.nowMs,md5)
        val d=get("/x/web-interface/ranking/v2",params,authenticated=true)
        return (d["list"] as? JsonArray).orEmpty().mapNotNull{value->
            val row=value.jsonObject;val bv=row["bvid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if(Bvid.parse(bv)!=bv)return@mapNotNull null
            val item=favoriteItem(row,bv);val owner=row["owner"] as? JsonObject
            Recommendation(bv,item.title,null,item.cover,item.author,item.duration,owner?.get("mid")?.jsonPrimitive?.longOrNull ?: 0,owner?.get("face")?.jsonPrimitive?.contentOrNull.orEmpty())
        }.distinctBy{it.bvid}
    }
    suspend fun homeCreatorCandidates(window:RecentRecommendationWindow,md5:(ByteArray)->String):List<UpProfile> =
        musicCreatorCandidates(homeRankingRows(requireNotNull(HomeCategory.MUSIC.rid),window,md5)).take(200)
    suspend fun homeCreators(window:RecentRecommendationWindow,md5:(ByteArray)->String):List<UpProfile> =
        selectRecommendedCreators(homeCreatorCandidates(window,md5))
    /** Bounded all-time music search; counts and collection identities come from video detail. */
    suspend fun popularMusic(window:RecentRecommendationWindow,md5:(ByteArray)->String):List<PopularMusic> {
        return PopularMusic.select(popularMusicBatch(window,0,emptySet(),{},md5))
    }
    suspend fun popularMusicBatch(window:RecentRecommendationWindow,round:Int,excluded:Set<String>,ready:suspend (List<PopularMusic>)->Unit,md5:(ByteArray)->String):List<PopularMusic> = coroutineScope {
        val candidates=linkedMapOf<String,Long>()
        val keywords=listOf("音乐","歌曲合集","音乐合集","经典歌曲","纯音乐","音乐现场")
        val page=1+round/2
        val searches=Channel<JsonObject>(3)
        (0..2).forEach { index->launch {
            val keyword=keywords[(round*3+index)%keywords.size]
            val params=upSigned(mapOf("search_type" to "video","keyword" to keyword,"page" to "$page","page_size" to "20","order" to "click","tids" to "3"),window.nowMs,md5)
            searches.send(get("/x/web-interface/wbi/search/type",params,true))
        }}
        fun collect(d:JsonObject) {
            if(d.int("page")!=page)throw PlatformFailure("平台返回页码不匹配")
            val rows=d["result"] as? JsonArray ?: throw PlatformFailure("平台推荐格式变化")
            for(value in rows) {
                val row=value as? JsonObject ?: continue
                val bv=row["bvid"]?.jsonPrimitive?.contentOrNull ?: continue
                val plays=row["play"]?.jsonPrimitive?.longOrNull ?: continue
                if(Bvid.parse(bv)==bv && plays>=0 && bv !in excluded)candidates[bv]=maxOf(plays,candidates[bv] ?: 0)
            }
        }
        fun music(row:JsonObject):Boolean {
            val tid=row["tid"]?.jsonPrimitive?.intOrNull
            val names=listOf("音乐","原创音乐","翻唱","演奏","音乐现场","音乐综合","音乐教学","乐评盘点","VOCALOID·UTAU","MV","电音","音乐粉丝饭拍")
            return tid in setOf(3,28,29,30,31,59,130,193,244) ||
                listOf("tname","tname_v2").any { row[it]?.jsonPrimitive?.contentOrNull in names }
        }
        val result=mutableListOf<PopularMusic>()
        var checked=0
        var failures=0
        val inspected=mutableSetOf<String>()
        // Start checking the first completed search while the other searches are still in flight.
        repeat(3) {
        collect(searches.receive())
        val ids=(candidates.filterValues{it>=PopularMusic.MIN_PLAYS}.keys.shuffled()+
            candidates.filterValues{it<PopularMusic.MIN_PLAYS}.keys.shuffled()).filter{it !in inspected}.take(18-inspected.size)
        inspected.addAll(ids)
        for(batch in ids.chunked(2)) {
            val details=coroutineScope { batch.map { bv->async {
                bv to try { videoData(bv,window.nowMs,md5) }
                    catch(e:CancellationException){throw e}
                    catch(e:PlatformFailure){if(e.code !in listOf(-404,62002)){throw e};null}
            }}.awaitAll() }
            for((bv,detail) in details) {
            if(detail==null){failures++;continue}
            val d=detail
            checked++
            if(d.string("bvid")!=bv || !music(d))continue
            val owner=d["owner"] as? JsonObject ?: continue
            val pages=d["pages"] as? JsonArray ?: continue
            if(pages.isEmpty())continue
            val plays=(d["stat"] as? JsonObject)?.get("view")?.jsonPrimitive?.longOrNull ?: 0
            val season=d["ugc_season"] as? JsonObject
            val seasonId=season?.get("id")?.jsonPrimitive?.longOrNull ?: 0
            val seasonOwner=season?.get("mid")?.jsonPrimitive?.longOrNull ?: 0
            val seasonCount=season?.get("ep_count")?.jsonPrimitive?.intOrNull ?: 0
            val seasonPlays=(season?.get("stat") as? JsonObject)?.get("view")?.jsonPrimitive?.longOrNull ?: 0
            val seasonTitle=season?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
            // A member's views never stand in for the collection's total.
            if(seasonId>0 && seasonOwner==owner.long("mid") && seasonCount>0 && seasonTitle.isNotBlank() && seasonPlays>=PopularMusic.MIN_PLAYS) {
                val source=ContentSource(SourceRef(SourceKind.UP_COLLECTION,seasonId,seasonOwner,CollectionKind.SEASON),seasonTitle,owner.string("name"),seasonCount,
                    season?.get("cover")?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()} ?: d.string("pic"))
                result+=PopularMusic(bv,source.title,source.cover,source.ownerName,seasonPlays,pages.size,source)
            } else if(plays>=PopularMusic.MIN_PLAYS) {
                result+=PopularMusic(bv,d.string("title"),d.string("pic"),owner.string("name"),plays,pages.size)
            }
            }
            val fresh=PopularMusic.valid(result).filter{it.key !in excluded}
            if(fresh.isNotEmpty())ready(fresh)
        }
        }
        searches.close()
        if(checked==0 && failures>0)throw PlatformFailure("候选视频暂不可访问")
        PopularMusic.valid(result).filter{it.key !in excluded}
    }
    /** Each homepage tab has its own source. Ranking order is kept exactly as returned. */
    suspend fun homeRecommendations(category:HomeCategory,window:RecentRecommendationWindow,md5:(ByteArray)->String):List<Recommendation>{
        require(category!=HomeCategory.LIVE){"Live rooms use their own ranking identity"}
        category.rid?.let{return homeRankingRows(it,window,md5).take(5)}
        val period=window
        val collected=linkedMapOf<String,Pair<Recommendation,Long>>()
        for(page in 1..3){
            val params=mapOf("search_type" to "video","keyword" to requireNotNull(category.keyword),"page" to "$page","page_size" to "20","order" to "click","pubtime_begin_s" to period.cutoffSeconds.toString(),"pubtime_end_s" to (period.nowMs/1000).toString())
            val d=get("/x/web-interface/wbi/search/type",upSigned(params,window.nowMs,md5),true)
            if(d.int("page")!=page)throw PlatformFailure("平台返回页码不匹配")
            val rows=(d["result"] as? JsonArray).orEmpty()
            for(value in rows){
                val row=value.jsonObject;val bv=row["bvid"]?.jsonPrimitive?.contentOrNull ?: continue
                if(Bvid.parse(bv)!=bv)continue
                val published=row["pubdate"]?.jsonPrimitive?.longOrNull ?: continue
                if(published !in period.cutoffSeconds..period.nowMs/1000)continue
                val tags=row["tag"]?.jsonPrimitive?.contentOrNull?.split(',')?.map{it.trim()}
                val item=favoriteItem(row,bv);val title=item.title.replace(Regex("<[^>]*>"),"").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">")
                if(category.related.none{term->title.contains(term)||tags.orEmpty().any{it.contains(term)}})continue
                val plays=row["play"]?.jsonPrimitive?.longOrNull?.takeIf{it>=0} ?: continue
                if(bv !in collected)collected[bv]=Recommendation(bv,title,tags,item.cover,item.author,item.duration) to plays
            }
            if(collected.size>=5||rows.isEmpty()||page>=d.int("numPages"))break
        }
        return collected.values.sortedByDescending{it.second}.take(5).map{it.first}
    }
    /** Legacy popular-page probe, retained for historical diagnostics only. */
    suspend fun recommendations(musicBoost: Boolean = true): List<Recommendation> {
        val candidates = (get("/x/web-interface/popular", mapOf("ps" to "5", "pn" to "1"))["list"] as? JsonArray).orEmpty().take(5)
        val result = candidates.map { element ->
            val item = element.jsonObject
            val bvid = item.string("bvid")
            val tags = try {
                (data("/x/tag/archive/tags", mapOf("bvid" to bvid)) as? JsonArray)?.map { it.jsonObject.string("tag_name") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: PlatformFailure) { if (failure.code in listOf(-412,-352,-403,403,412,429)) throw failure else null }
            catch (_: Exception) { null }
            val metadata = favoriteItem(item, bvid)
            Recommendation(bvid, item.string("title"), tags, metadata.cover, metadata.author, metadata.duration)
        }
        return if (musicBoost) RecommendationPolicy.rank(result) else result.distinctBy { it.bvid }
    }

    suspend fun liveRoom(id: Long): LiveRoom {
        require(id > 0)
        val detail = data("/xlive/web-room/v1/index/getInfoByRoom", mapOf("room_id" to id.toString()), authenticated=true,live = true).jsonObject
        val info=detail["room_info"] as? JsonObject ?: throw PlatformFailure("缺少直播房间详情")
        if(listOf("is_locked","is_hidden","lock_status","hidden_status").any{info[it]?.jsonPrimitive?.booleanOrNull==true || (info[it]?.jsonPrimitive?.intOrNull ?: 0)>0})throw PlatformFailure("直播房间受限，请在 B站查看",-403)
        val roomId = info.long("room_id")
        if(roomId<=0)throw PlatformFailure("直播房间不存在或无法访问",-404)
        if(roomId!=id && info["short_id"]?.jsonPrimitive?.longOrNull!=id)throw PlatformFailure("房间身份不匹配")
        val anchor=(detail["anchor_info"] as? JsonObject)?.get("base_info") as? JsonObject
            ?: throw PlatformFailure("缺少主播详情")
        fun field(name:String)=info[name]?.jsonPrimitive?.contentOrNull.orEmpty()
        return LiveRoom(id, roomId, info.long("uid"), info["live_status"]?.jsonPrimitive?.intOrNull ?: -1,
            info.string("title"), anchor["uname"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            field("cover").ifBlank{field("user_cover")}.ifBlank{field("keyframe")},field("area_name"),field("description").replace(Regex("<[^>]*>"),"").take(2000))
    }
    suspend fun liveRanking():List<LiveRanking> {
        val d=data("/xlive/web-interface/v1/index/getHotRankList",authenticated=true,live=true).jsonObject
        val rows=d["list"] as? JsonArray ?: throw PlatformFailure("直播排行响应格式变化")
        return rows.take(20).mapNotNull { value ->
            val row=value as? JsonObject ?: return@mapNotNull null
            val room=row["roomid"]?.jsonPrimitive?.longOrNull ?: row["room_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if(room<=0)return@mapNotNull null
            fun field(name:String)=row[name]?.jsonPrimitive?.contentOrNull.orEmpty()
            LiveRanking(room,row["uid"]?.jsonPrimitive?.longOrNull ?: 0,field("title"),field("uname"),field("cover").ifBlank{field("user_cover")}.ifBlank{field("face")},
                row["score"]?.jsonPrimitive?.longOrNull?.takeIf{it>=0},field("area_v2_name").ifBlank{field("area_name")})
        }.distinctBy{it.roomId}.take(5)
    }
    suspend fun liveStreams(room: Long): List<LiveStream> {
        require(room>0)
        val d = data("/xlive/web-room/v2/index/getRoomPlayInfo", mapOf("room_id" to room.toString(), "qn" to "80", "codec" to "0", "format" to "0,1,2", "protocol" to "0,1", "platform" to "web"), authenticated=true,live = true).jsonObject
        if(d["room_id"]?.jsonPrimitive?.longOrNull?.let{it!=room}==true)throw PlatformFailure("直播流房间身份不匹配")
        val streams = d["playurl_info"]?.takeUnless { it is JsonNull }?.jsonObject?.get("playurl")?.jsonObject?.get("stream") as? JsonArray
        return streams.orEmpty().flatMap { stream ->
            (stream.jsonObject["format"] as? JsonArray).orEmpty().flatMap { format ->
                val f = format.jsonObject
                (f["codec"] as? JsonArray).orEmpty().flatMap { codec ->
                    val c = codec.jsonObject
                    val name=c.string("codec_name");val formatName=f.string("format_name")
                    val audio=name.lowercase() in setOf("aac","mp4a","mp4a.40.2","opus","mp3")
                    val hls=stream.jsonObject["protocol_name"]?.jsonPrimitive?.contentOrNull=="http_hls"
                    val mime=if(hls)"application/x-mpegURL" else if(formatName=="flv")"video/x-flv" else if(audio)when(formatName){"mp3"->"audio/mpeg";"mp4","m4a","fmp4"->"audio/mp4";"opus"->"audio/ogg";else->"audio/aac"} else "video/mp4"
                    (c["url_info"] as? JsonArray).orEmpty().take(3).mapNotNull { value ->
                        val host=value as? JsonObject ?: return@mapNotNull null
                        val url=host.string("host")+c.string("base_url")+host.string("extra")
                        val u=runCatching{Url(url)}.getOrNull() ?: return@mapNotNull null
                        if(u.protocol.name!="https" || u.port!=443 || !u.user.isNullOrEmpty() || !u.password.isNullOrEmpty() || listOf("bilivideo.com","bilivideo.cn","akamaized.net").none{u.host==it||u.host.endsWith(".$it")})return@mapNotNull null
                        LiveStream(url,formatName,name,c["current_qn"]?.jsonPrimitive?.intOrNull ?: 0,if(audio)StreamKind.AUDIO_ONLY else StreamKind.MIXED,mime)
                    }
                }
            }
        }.distinctBy{it.url}.take(12)
    }
}

private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: throw PlatformFailure("缺少字段 $key")
private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: throw PlatformFailure("缺少字段 $key")
private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: throw PlatformFailure("缺少字段 $key")
