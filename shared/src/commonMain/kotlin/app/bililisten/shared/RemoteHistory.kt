package app.bililisten.shared

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable data class RemoteHistoryItem(val aid:Long=0,val bvid:String="",val cid:Long=0,val page:Int=1,val title:String="",val partTitle:String="",val positionMs:Long=0,val viewedAt:Long=0,val finished:Boolean=false,val roomId:Long=0) {
    val key get()=if(roomId>0)"live_$roomId" else "archive_$bvid"
    fun video(account:String)=if(roomId==0L)LocalHistoryEntry(account,VideoRef(bvid,cid,page),if(partTitle.isBlank())title else "$title · $partTitle",null,positionMs,viewedAt,HistoryOrigin.BILIBILI_SYNC,finished) else null
    fun live(account:String)=if(roomId>0)LiveHistoryEntry(account,roomId,title,viewedAt) else null
}
@Serializable data class HistoryCursor(val max:Long=0,val viewedAt:Long=0,val business:String="")
data class RemoteHistoryPage(val items:List<RemoteHistoryItem>,val next:HistoryCursor?,val unsupported:Int=0)
@Serializable enum class HistoryReportPhase { QUEUED, SENDING, UNKNOWN }
@Serializable data class HistoryReport(val item:RemoteHistoryItem,val phase:HistoryReportPhase=HistoryReportPhase.QUEUED,val attemptedAt:Long=0)
@Serializable data class HistoryDeletion(val key:String?=null)
@Serializable data class RemoteHistoryData(val enabled:Boolean=false,val epoch:Long=0,val items:List<RemoteHistoryItem> = emptyList(),val reports:List<HistoryReport> = emptyList(),val deletion:HistoryDeletion?=null,val refreshedAt:Long=0,val unsupported:Int=0,val enabledAt:Long=0) {
    fun checked():RemoteHistoryData {
        require(epoch>=0&&refreshedAt>=0&&items.size<=50000&&reports.size<=5000)
        require(items.distinctBy{it.key}.size==items.size&&reports.distinctBy{it.item.key}.size==reports.size)
        (items+reports.map{it.item}).forEach{require(it.positionMs>=0&&it.viewedAt>=0&&it.title.length<=2000);if(it.roomId==0L)VideoRef(it.bvid,it.cid,it.page) else require(it.roomId>0)}
        return this
    }
    fun visible()=(reports.map{it.item}+items).groupBy{it.key}.values.map{group->group.maxBy{it.viewedAt}}.sortedByDescending{it.viewedAt}
}
data class RemoteHistoryView(val enabled:Boolean=false,val refreshing:Boolean=false,val pending:Int=0,val lastSync:Long=0,val error:String?=null,val unsupported:Int=0,val account:String="")
interface RemoteHistoryStore {
    fun observe(account:String):Flow<RemoteHistoryData>
    suspend fun read(account:String):RemoteHistoryData
    /** Enabling clears only this account's local video/live history in the same transaction. */
    suspend fun changeMode(account:String,data:RemoteHistoryData,cutoff:Long)
    suspend fun save(account:String,data:RemoteHistoryData,expectedEpoch:Long)
}
interface RemoteHistoryPort {
    suspend fun paused():Boolean
    suspend fun page(cursor:HistoryCursor?=null):RemoteHistoryPage
    suspend fun report(item:RemoteHistoryItem,account:String):RemoteHistoryItem
    suspend fun delete(key:String?,account:String)
}
class HistoryWriteUncertain:Exception("同步写入结果待核对")

/** Account-bound mirror and durable reports. Reads never replay an uncertain write. */
class RemoteHistorySync(private val remote:RemoteHistoryPort,private val store:RemoteHistoryStore,private val accounts:AccountRepository,private val clock:Clock) {
    private val gate=Mutex()
    private val status=MutableStateFlow<Map<String,Pair<Boolean,String?>>>(emptyMap())
    fun observe(account:String):Flow<RemoteHistoryView> = combine(store.observe(account),status){data,states->
        RemoteHistoryView(data.enabled,states[account]?.first==true,data.reports.size+(if(data.deletion!=null)1 else 0),data.refreshedAt,states[account]?.second,data.unsupported,account)
    }
    suspend fun enabled(account:String)=store.read(account).enabled
    private fun require(stamp:SessionStamp) {
        accounts.requireCurrent(stamp)
        if(accounts.session.value.status!=SessionStatus.AUTHENTICATED||stamp.account.toLongOrNull()?.let{it>0}!=true)throw PlatformFailure("请先登录并核验 B 站账号")
    }
    private fun note(account:String,loading:Boolean,error:String?=null){status.value=status.value+(account to (loading to error))}
    private suspend fun fetch(stamp:SessionStamp):Pair<List<RemoteHistoryItem>,Int> {
        val rows=linkedMapOf<String,RemoteHistoryItem>();val seen=mutableSetOf<HistoryCursor>();var cursor:HistoryCursor?=null;var skipped=0
        repeat(2000) {
            require(stamp);val page=remote.page(cursor);require(stamp);skipped+=page.unsupported
            page.items.forEach{item->if(rows[item.key]?.viewedAt?.let{it>=item.viewedAt}!=true)rows[item.key]=item}
            if(rows.size>50000)throw PlatformFailure("B站记录数量超出当前可同步范围，本机记录保留")
            val next=page.next ?: return rows.values.sortedByDescending{it.viewedAt} to skipped
            if(!seen.add(next))throw PlatformFailure("B站历史分页重复，旧记录保留")
            cursor=next
        }
        throw PlatformFailure("B站历史读取未完成，旧记录保留")
    }
    suspend fun setEnabled(stamp:SessionStamp,value:Boolean)=gate.withLock {
        fun sameOwner(){if(accounts.session.value.stamp!=stamp)throw PlatformFailure("账号会话已变化")}
        if(value)require(stamp) else sameOwner()
        val prior=store.read(stamp.account)
        if(prior.enabled==value)return@withLock
        note(stamp.account,true)
        try {
            val data=if(value) {
                if(remote.paused())throw PlatformFailure("B站已暂停历史记录，请先在官方恢复记录")
                val (rows,skipped)=fetch(stamp)
                RemoteHistoryData(true,prior.epoch+1,rows,refreshedAt=clock.nowMs(),unsupported=skipped,enabledAt=clock.nowMs())
            }else RemoteHistoryData(epoch=prior.epoch+1)
            if(value)require(stamp) else sameOwner()
            store.changeMode(stamp.account,data,clock.nowMs());note(stamp.account,false)
        }catch(e:CancellationException){note(stamp.account,false);throw e}
        catch(e:Exception){note(stamp.account,false,(e as? PlatformFailure)?.category ?: "切换失败，原记录保留");throw e}
    }
    suspend fun refresh(stamp:SessionStamp)=gate.withLock {
        require(stamp);var data=store.read(stamp.account);if(!data.enabled)return@withLock
        note(stamp.account,true)
        try {
            val (rows,skipped)=fetch(stamp)
            // Report acknowledgement is read-only, including recovery after an interrupted POST.
            val pending=data.reports.filterNot{p->rows.any{r->r.key==p.item.key&&(r.viewedAt>p.item.viewedAt+1000 || r.viewedAt>=p.item.viewedAt-1000&&
                (r.roomId>0 || r.cid==p.item.cid&&(r.finished||r.positionMs>=p.item.positionMs/1000*1000)))}}
            val deletion=data.deletion?.takeUnless{d->if(d.key==null)rows.isEmpty()&&skipped==0 else rows.none{it.key==d.key}}
            data=data.copy(items=rows,reports=pending,deletion=deletion,refreshedAt=clock.nowMs(),unsupported=skipped)
            require(stamp);store.save(stamp.account,data,data.epoch)
            drain(stamp)
            val final=store.read(stamp.account)
            note(stamp.account,false,if(final.deletion!=null||final.reports.any{it.phase!=HistoryReportPhase.QUEUED})"上次写入结果待核对；不会自动重发" else null)
        }catch(e:CancellationException){note(stamp.account,false);throw e}
        catch(e:Exception){note(stamp.account,false,(e as? PlatformFailure)?.category ?: "同步未完成，已读记录保留");throw e}
    }
    suspend fun record(item:RemoteHistoryItem,account:String)=gate.withLock {
        val stamp=accounts.session.value.stamp
        if(stamp.account!=account||account.toLongOrNull()?.let{it>0}!=true||accounts.session.value.status==SessionStatus.GUEST)return@withLock
        val data=store.read(account);if(!data.enabled||item.viewedAt<=data.enabledAt)return@withLock
        val previous=(data.reports.map{it.item}+data.items).filter{it.key==item.key}.maxByOrNull{it.viewedAt}
        if(previous!=null&&(previous.viewedAt>item.viewedAt || previous.cid==item.cid&&previous.positionMs/1000==item.positionMs/1000&&previous.roomId==item.roomId&&(item.roomId==0L||item.viewedAt-previous.viewedAt<60000)))return@withLock
        val reportItem=item.copy(aid=item.aid.takeIf{it>0} ?: previous?.aid ?: 0)
        val reports=data.reports.filterNot{it.item.key==item.key}+HistoryReport(reportItem)
        if(accounts.session.value.stamp!=stamp)return@withLock
        store.save(account,data.copy(reports=reports),data.epoch)
        if(accounts.session.value.status!=SessionStatus.AUTHENTICATED){note(account,false,"进度已保留，等待账号联网核验后同步");return@withLock}
        try {drain(stamp);note(account,false)}catch(e:CancellationException){throw e}catch(e:Exception){note(account,false,(e as? PlatformFailure)?.category ?: "进度等待同步；可刷新核对")}
    }
    private suspend fun drain(stamp:SessionStamp) {
        var data=store.read(stamp.account)
        if(!data.enabled||data.deletion!=null)return
        for(p in data.reports.filter{it.phase==HistoryReportPhase.QUEUED}) {
            require(stamp)
            val sent=p.copy(phase=HistoryReportPhase.SENDING,attemptedAt=clock.nowMs())
            data=data.copy(reports=data.reports.map{if(it.item.key==p.item.key)sent else it});store.save(stamp.account,data,data.epoch)
            var confirmed=p.item
            try {confirmed=remote.report(p.item,stamp.account);require(stamp)}
            catch(e:CancellationException){throw e}
            catch(e:Exception) {
                require(stamp)
                val phase=if(e is HistoryWriteUncertain)HistoryReportPhase.UNKNOWN else HistoryReportPhase.QUEUED
                data=data.copy(reports=data.reports.map{if(it.item.key==p.item.key)sent.copy(phase=phase) else it})
                store.save(stamp.account,data,data.epoch);throw e
            }
            data=data.copy(reports=data.reports.filterNot{it.item.key==p.item.key},items=(listOf(confirmed)+data.items.filterNot{it.key==p.item.key}).sortedByDescending{it.viewedAt}.take(50000))
            store.save(stamp.account,data,data.epoch)
        }
    }
    suspend fun delete(stamp:SessionStamp,key:String?)=gate.withLock {
        require(stamp);var data=store.read(stamp.account);if(!data.enabled)throw PlatformFailure("当前使用本机记录")
        if(data.deletion!=null)throw PlatformFailure("上次删除待核对，请先刷新；不会重复删除")
        // Resolve aid from the bound mirror; never accept an arbitrary cloud deletion id from UI.
        val target=key?.let{k->data.visible().firstOrNull{it.key==k} ?: throw PlatformFailure("记录已变化，请刷新")}
        val serverKey=target?.let{if(it.roomId>0)"live_${it.roomId}" else "archive_${it.aid.takeIf{aid->aid>0} ?: throw PlatformFailure("记录身份待核对")}"}
        data=data.copy(deletion=HistoryDeletion(key));store.save(stamp.account,data,data.epoch)
        try {remote.delete(serverKey,stamp.account);require(stamp)}catch(e:CancellationException){throw e}catch(e:Exception){
            require(stamp);if(e !is HistoryWriteUncertain)store.save(stamp.account,data.copy(deletion=null),data.epoch)
            note(stamp.account,false,"删除未确认，请刷新核对；不会自动重试");throw e
        }
        data=data.copy(items=if(key==null)emptyList() else data.items.filterNot{it.key==key},reports=if(key==null)emptyList() else data.reports.filterNot{it.item.key==key},deletion=null)
        store.save(stamp.account,data,data.epoch);note(stamp.account,false)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SyncedHistoryRepository(private val local:LocalHistoryRepository,private val cloud:RemoteHistoryStore):LocalHistoryRepository {
    override fun observe(account:String):Flow<List<LocalHistoryEntry>> = cloud.observe(account).flatMapLatest{data->if(data.enabled)flowOf(data.visible().mapNotNull{it.video(account)}) else local.observe(account)}
    override fun observeLive(account:String):Flow<List<LiveHistoryEntry>> = cloud.observe(account).flatMapLatest{data->if(data.enabled)flowOf(data.visible().mapNotNull{it.live(account)}) else local.observeLive(account)}
    override suspend fun latestVideo(account:String,bvid:String):LocalHistoryEntry? {
        val data=cloud.read(account)
        return if(data.enabled)data.visible().firstOrNull{it.bvid==bvid}?.video(account) else local.latestVideo(account,bvid)
    }
    // Housekeeping/logout must never delete the official account's history.
    override suspend fun delete(account:String,video:VideoRef?)=local.delete(account,video)
    override suspend fun deleteLive(account:String,roomId:Long)=local.deleteLive(account,roomId)
    override suspend fun prune(account:String,policy:HistoryRetentionPolicy,now:Long)=local.prune(account,policy,now)
}
