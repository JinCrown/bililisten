package app.bililisten.shared

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

private fun JsonObject.text(key:String)=(this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.number(key:String)=(this[key] as? JsonPrimitive)?.longOrNull ?: 0
private fun JsonObject.obj(key:String)=this[key] as? JsonObject
private fun JsonElement.objectValue()=this as? JsonObject ?: throw PlatformFailure("平台消息格式变化")
private fun JsonObject.rows(key:String):List<JsonObject> {
    val value=this[key] ?: throw PlatformFailure("平台消息列表格式变化")
    if(value==JsonNull)return emptyList()
    val rows=value as? JsonArray ?: throw PlatformFailure("平台消息列表格式变化")
    if(rows.size>100)throw PlatformFailure("平台消息数量异常")
    return rows.map{it.objectValue()}
}
private fun digit(value:String)=value.isNotEmpty()&&value.length<=20&&value.all{it in '0'..'9'}&&value.any{it!='0'}
private fun JsonObject.identity(key:String)=text(key).takeIf(::digit) ?: throw PlatformFailure("平台消息标识格式变化")
private fun contentJson(value:String)=try{Json.parseToJsonElement(value)}catch(_:Exception){null}
private fun JsonObject.safeText(key:String)=text(key).also{if(it.length>131072)throw PlatformFailure("消息内容过长，请在 B站查看")}
internal fun messageBody(row:JsonObject):MessageBody {
    if(row.number("msg_status") in listOf(1L,2L))return MessageBody("消息已撤回","撤回")
    val type=row.number("msg_type").toInt()
    if(type==5) {
        val content=contentJson(row.text("content"))
        val target=(content as? JsonPrimitive)?.contentOrNull ?: (content as? JsonObject)?.text("content")
        return MessageBody("消息已撤回","撤回",target?.takeIf(::digit))
    }
    if(row.number("msg_status")!=0L)return MessageBody("消息已失效","失效")
    val body=contentJson(row.safeText("content")) as? JsonObject ?: return MessageBody("暂不支持的消息，请在 B站查看","其他")
    fun joined(vararg keys:String)=keys.map{body.safeText(it)}.filter{it.isNotBlank()}.distinct().joinToString("\n")
    return when(type) {
        1->MessageBody(body.safeText("content").ifBlank{"空文字消息"})
        2,6->MessageBody(if(type==2)"图片消息 · 可在 B站查看原图" else "自定义表情 · 可在 B站查看","图片")
        7,9,11,14->MessageBody(joined("headline","title","name","desc","attach_msg").ifBlank{"分享内容 · 可在 B站查看"},"分享")
        10->{val modules=(body["modules"] as? JsonArray).orEmpty().mapNotNull{it as? JsonObject}.take(30)
            MessageBody((listOf(joined("title","text"))+modules.map{listOf(it.safeText("title"),it.safeText("detail")).filter{v->v.isNotBlank()}.joinToString("：")}).filter{it.isNotBlank()}.joinToString("\n").ifBlank{"通知消息 · 可在 B站查看"},"通知")}
        18,51->MessageBody(joined("content","text","title").ifBlank{"系统提示"},"提示")
        else->MessageBody("暂不支持的消息类型 $type，请在 B站查看","其他")
    }
}

/** Uses the logged-in user's web APIs, never stores private text or acknowledges a message. */
class BiliMessageRepository(private val api:BiliApi):MessageRepository {
    private val web=mapOf("build" to "0","mobi_app" to "web")
    private fun check(cursor:MessageCursor?){if(cursor!=null)require(digit(cursor.id)&&(cursor.time.isEmpty()||digit(cursor.time)))}
    private suspend fun read(path:String,params:Map<String,String> = emptyMap())=api.messageRead(path,web+params)
    override suspend fun unread():Map<MessageCategory,Int> {
        val d=read("/x/msgfeed/unread").objectValue()
        return mapOf(MessageCategory.REPLY to d.number("reply"),MessageCategory.AT to d.number("at"),MessageCategory.LIKE to d.number("like"),MessageCategory.SYSTEM to d.number("sys_msg")).mapValues{it.value.coerceIn(0,Int.MAX_VALUE.toLong()).toInt()}
    }
    override suspend fun sessions(cursor:MessageCursor?):MessagePage<MessageSession> {
        check(cursor)
        val revision=api.sessionGeneration
        val d=read("/session_svr/v1/session_svr/get_sessions",mapOf("session_type" to "1","group_fold" to "0","unfollow_fold" to "0","sort_rule" to "2","size" to "20")+(cursor?.let{mapOf("end_ts" to it.id)} ?: emptyMap())).objectValue()
        val raw=d.rows("session_list")
        val rows=raw.map{row->
            val mid=row.number("talker_id");val type=row.number("session_type").toInt();require(mid>0&&type in 1..2)
            val info=row.obj("account_info")
            MessageSession(mid,type,if(type==2)row.text("group_name").ifBlank{"粉丝团 $mid"} else info?.text("name").orEmpty().ifBlank{"用户 $mid"},
                if(type==2)row.text("group_cover") else info?.text("pic_url").orEmpty(),row.number("session_ts"),row.number("unread_count").coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),row.obj("last_msg")?.let(::messageBody) ?: MessageBody("暂无消息"))
        }.distinctBy{it.key}
        val mids=rows.filter{it.type==1&&it.name=="用户 ${it.talker}"}.map{it.talker}
        val profiles=if(mids.isEmpty())emptyMap() else try {
            val cards=read("/x/polymer/pc-electron/v1/user/cards",mapOf("uids" to mids.joinToString(","))).objectValue()
            cards.entries.mapNotNull{(id,value)->(value as? JsonObject)?.let{id.toLongOrNull()?.let{mid->mid to it}}}.toMap()
        }catch(e:CancellationException){throw e}catch(e:PlatformFailure){if(e.code==-101)throw e else emptyMap()}catch(_:Exception){emptyMap()}
        if(api.sessionGeneration!=revision)throw PlatformFailure("账号会话已变化，旧结果已丢弃")
        val enriched=rows.map{row->profiles[row.talker]?.let{card->row.copy(name=card.text("name").ifBlank{row.name},avatar=card.text("face").ifBlank{row.avatar})} ?: row}
        // Pinned sessions may be repeated on every page and must not move the history cursor backwards.
        val next=if(d.number("has_more")==1L)raw.lastOrNull{it.number("top_ts")==0L}?.number("session_ts")?.takeIf{it>0}?.toString()?.let{MessageCursor(it)} ?: throw PlatformFailure("消息分页格式变化") else null
        if(next!=null&&cursor!=null&&next.id.toLong()>=cursor.id.toLong())throw PlatformFailure("消息分页没有推进")
        return MessagePage(enriched,next)
    }
    override suspend fun conversation(session:MessageSession,cursor:MessageCursor?):MessagePage<PrivateMessage> {
        require(session.talker>0&&session.type in 1..2);check(cursor)
        val d=read("/svr_sync/v1/svr_sync/fetch_session_msgs",mapOf("talker_id" to "${session.talker}","session_type" to "${session.type}","size" to "30")+(cursor?.let{mapOf("end_seqno" to it.id)} ?: emptyMap())).objectValue()
        val rows=d.rows("messages").map{row->
            if(session.type==2&&row.number("receiver_id")!=session.talker)throw PlatformFailure("消息会话不匹配")
            if(session.type==1&&row.number("sender_uid")!=session.talker&&row.number("receiver_id")!=session.talker)throw PlatformFailure("消息会话不匹配")
            PrivateMessage(row.identity("msg_key"),row.identity("msg_seqno"),row.number("sender_uid"),row.number("timestamp")*1000,messageBody(row))
        }.distinctBy{it.key}
        val next=if(d.number("has_more")==1L)d.identity("min_seqno").let{MessageCursor(it)} else null
        return MessagePage(rows,next)
    }
    override suspend fun notices(category:MessageCategory,cursor:MessageCursor?):MessagePage<MessageNotice> {
        require(category!=MessageCategory.PRIVATE);check(cursor)
        if(category==MessageCategory.SYSTEM)return system(cursor)
        val name=when(category){MessageCategory.REPLY->"reply";MessageCategory.AT->"at";else->"like"}
        val d=read("/x/msgfeed/$name",mapOf("platform" to "web")+(cursor?.let{mapOf("id" to it.id,"${name}_time" to it.time)} ?: emptyMap())).objectValue()
        val total=if(category==MessageCategory.LIKE)d.obj("total") ?: throw PlatformFailure("点赞消息格式变化") else d
        val raw=(if(cursor==null&&category==MessageCategory.LIKE)d.obj("latest")?.takeIf{"items" in it}?.rows("items").orEmpty() else emptyList())+total.rows("items")
        val rows=raw.map{row->
            val item=row.obj("item") ?: throw PlatformFailure("通知内容格式变化")
            val users=if(category==MessageCategory.LIKE)row.rows("users") else listOfNotNull(row.obj("user"))
            val actor=users.map{it.text("nickname")}.filter{it.isNotBlank()}.joinToString("、").ifBlank{"B站用户"}
            val action=when(category){MessageCategory.REPLY->"回复了我";MessageCategory.AT->"@了我";else->"赞了我的${item.text("business").ifBlank{"内容"}}"}
            val body=listOf(item.safeText("source_content"),item.safeText("title"),item.safeText("desc"),item.safeText("target_reply_content").takeIf{it.isNotBlank()}?.let{"原文：$it"}.orEmpty()).filter{it.isNotBlank()}.distinct().joinToString("\n\n")
            MessageNotice(row.identity("id"),"$actor${if(row.number("counts")>users.size)" 等 ${row.number("counts")} 人" else ""}$action",body,actor,users.firstOrNull()?.text("avatar").orEmpty(),sentAt=row.number("${name}_time")*1000)
        }.distinctBy{it.id}
        val c=total.obj("cursor") ?: throw PlatformFailure("通知分页格式变化")
        val end=(c["is_end"] as? JsonPrimitive)?.booleanOrNull ?: throw PlatformFailure("通知分页格式变化")
        val next=if(end)null else MessageCursor(c.identity("id"),c.identity("time"))
        return MessagePage(rows,next)
    }
    private suspend fun system(cursor:MessageCursor?):MessagePage<MessageNotice> {
        val raw=if(cursor==null)coroutineScope {
            listOf("query_unified_notify","query_user_notify").map{name->async{read("/x/sys-msg/$name",mapOf("page_size" to if(name=="query_unified_notify")"10" else "20")).objectValue().rows("system_notify_list")}}.awaitAll().flatten()
        } else {
            val d=read("/x/sys-msg/query_notify_list",mapOf("cursor" to cursor.id,"data_type" to "1"))
            if(d==JsonNull)emptyList() else (d as? JsonArray ?: throw PlatformFailure("系统通知格式变化")).map{it.objectValue()}.also{if(it.size>100)throw PlatformFailure("系统通知数量异常")}
        }
        val sorted=raw.distinctBy{it.identity("id")}.sortedWith(compareByDescending<JsonObject>{it.identity("cursor").length}.thenByDescending{it.identity("cursor")})
        val rows=sorted.map{MessageNotice(it.identity("id"),it.safeText("title").ifBlank{"系统通知"},it.safeText("content"),timeText=it.text("time_at"),html=true)}
        return MessagePage(rows,sorted.lastOrNull()?.identity("cursor")?.let{MessageCursor(it)})
    }
}
