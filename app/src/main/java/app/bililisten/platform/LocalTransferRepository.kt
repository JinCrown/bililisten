package app.bililisten.platform

import androidx.room.withTransaction
import app.bililisten.shared.*
import kotlinx.serialization.json.Json

data class TransferResult(val histories:Int,val bookmarks:Int,val heardSources:Int,val aliases:Int,val orders:Int)

class LocalTransferRepository(private val db:ListenDatabase,private val organizer:RoomOrganizerStore,
    private val identity:()->SessionStamp,private val now:()->Long) {
    private val dao=db.listenDao()
    private fun check(stamp:SessionStamp){require(identity()==stamp){"账号已变化，请重新选择迁移文件"}}
    suspend fun export(stamp:SessionStamp):LocalTransfer {
        check(stamp);organizer.read(stamp.account);check(stamp)
        return db.withTransaction {
            check(stamp);val account=stamp.account
            val local=Json.decodeFromString<OrganizerData>(dao.organizer(account)!!.payload)
            val history=dao.allHistory(account).map{LocalHistoryEntry(account,VideoRef(it.bvid,it.cid,it.part),it.title,it.sourceJson?.let(SourceCodec::decode),it.positionMs,it.playedAt,runCatching{HistoryOrigin.valueOf(it.origin)}.getOrDefault(HistoryOrigin.UNKNOWN))}
            val lives=dao.allLiveHistory(account).map{LiveHistoryEntry(account,it.roomId,it.title,it.playedAt)}
            val bookmarks=dao.collections(account).map{Json.decodeFromString<CollectionSnapshot>(it.payload)}.filter{it.bookmarked&&it.source.kind!=SourceKind.OWN_FAVORITES}.map{TransferBookmark(it.source,it.title,it.ownerName,it.total,it.cover)}
            val heard=dao.allUpdates(account).map{Json.decodeFromString<SourceCheckpoint>(it.payload)}.filter{it.heard.isNotEmpty()}.map{TransferHeard(it.source,it.heard)}
            LocalTransfer(createdAt=maxOf(now(),(history.map{it.playedAt}+lives.map{it.playedAt}).maxOrNull() ?: 1,local.library.layouts.maxOfOrNull{it.updatedAt} ?: 1),account=account,history=history,liveHistory=lives,bookmarks=bookmarks,heard=heard,aliases=local.aliases,orders=local.orders,library=local.library).checked().also{check(stamp)}
        }
    }
    /** All portable records merge in one transaction. Failure, cancellation and changed identity roll back. */
    suspend fun import(data:LocalTransfer,stamp:SessionStamp,policy:HistoryRetentionPolicy):TransferResult {
        data.checked();check(stamp);require(data.account==stamp.account){"请先登录导出文件对应的 B 站账号；游客文件请在游客模式导入"}
        organizer.read(stamp.account);check(stamp)
        return db.withTransaction {
            val account=stamp.account;check(stamp)
            val local=Json.decodeFromString<OrganizerData>(dao.organizer(account)!!.payload)
            require(dao.mutation(account)==null&&local.pending==null&&local.batch?.rows?.any{it.outcome in setOf(RowOutcome.RUNNING,RowOutcome.UNKNOWN)}!=true){"请先核对本机未确认的收藏整理结果"}
            var histories=0
            val priorHistory=dao.allHistory(account).associateBy{it.bvid to it.cid}
            data.history.forEach { item->
                if(item.playedAt>(priorHistory[item.video.bvid to item.video.cid]?.playedAt ?: -1)&&item.playedAt>(dao.historyFence(account,"video:${item.video.bvid}:${item.video.cid}") ?: -1)){
                    dao.played(HistoryRow(account,item.video.bvid,item.video.cid,item.title,item.video.part,null,item.positionMs,item.playedAt,item.source?.let(SourceCodec::encode),item.origin.name));histories++
                }
            }
            val priorLive=dao.allLiveHistory(account).associateBy{it.roomId}
            data.liveHistory.forEach{item->if(item.playedAt>(priorLive[item.roomId]?.playedAt ?: -1)&&item.playedAt>(dao.historyFence(account,"live:${item.roomId}") ?: -1)){
                dao.insertLive(LiveHistoryRow(account,item.roomId,item.title,item.playedAt));histories++
            }}
            var bookmarks=0
            data.bookmarks.forEach { item->
                val key=SourceCodec.encode(item.source);val prior=dao.collection(account,key)?.let{Json.decodeFromString<CollectionSnapshot>(it.payload)}
                if(prior?.bookmarked!=true){
                    val merged=prior?.copy(bookmarked=true,revision=prior.revision+1,cover=prior.cover.ifBlank{item.cover}) ?: CollectionSnapshot(account,item.source,emptyList(),false,0,0,item.title,item.ownerName,item.total,true,item.cover)
                    dao.collection(CollectionRow(account,key,Json.encodeToString(merged),merged.revision));bookmarks++
                }
            }
            data.heard.forEach{item->
                val key=SourceCodec.encode(item.source);val prior=dao.updates(account,key)?.let{Json.decodeFromString<SourceCheckpoint>(it.payload)} ?: SourceCheckpoint(account,item.source,emptySet(),completeBaseline=false)
                dao.writeUpdate(UpdateRow(account,key,Json.encodeToString(prior.copy(heard=prior.heard+item.videos))))
            }
            val aliases=data.aliases+local.aliases;val orders=data.orders+local.orders
            dao.organizer(OrganizerRow(account,Json.encodeToString(local.copy(aliases=aliases,orders=orders,library=local.library.merge(data.library)))))
            if(!policy.keepAll)dao.prune(account,(now()-policy.maxAgeMs).coerceAtLeast(0),policy.maxEntries)
            check(stamp)
            TransferResult(histories,bookmarks,data.heard.size,aliases.size-local.aliases.size,orders.size-local.orders.size)
        }
    }
}
