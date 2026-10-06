package app.bililisten.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

data class VideoRelations(val liked: Boolean, val coins: Int, val favorite: Boolean) {
    init { require(coins in 0..2) }
}
@Serializable enum class EngagementAction { LIKE, COIN, TRIPLE }
@Serializable enum class EngagementStep { LIKE, COIN, TRIPLE, TRIPLE_LIKE, TRIPLE_COIN, TRIPLE_FAVORITE }
@Serializable data class EngagementIntent(val bvid: String, val action: EngagementAction, val step: EngagementStep,
    val liked: Boolean, val coinGoal: Int) {
    init { require(Bvid.parse(bvid)==bvid && coinGoal in 0..2) }
}
data class EngagementResult(val message: String, val pending: Boolean = false, val unavailableFolder:Long?=null)
data class TripleReceipt(val liked:Boolean,val coined:Boolean,val favorited:Boolean,val amount:Int)
interface VideoEngagementPort {
    suspend fun folders(account:Long,aid:Long):List<FavoriteFolder> = throw PlatformFailure("收藏位置暂不可用")
    suspend fun favorite(account:Long,aid:Long,folder:Long):MutationOutcome = throw PlatformFailure("收藏功能暂不可用")
    suspend fun coinBalance(account:String):Double?=null
    suspend fun relations(bvid: String): VideoRelations
    suspend fun tags(bvid: String): List<String>
    suspend fun like(account: String, bvid: String, liked: Boolean): MutationOutcome
    suspend fun coin(account: String, bvid: String, amount: Int): MutationOutcome
    suspend fun triple(account:String,bvid:String):TripleReceipt?
}
class BiliVideoEngagementPort(private val api: BiliApi) : VideoEngagementPort {
    override suspend fun folders(account:Long,aid:Long)=api.folders(account,aid)
    override suspend fun favorite(account:Long,aid:Long,folder:Long)=api.changeFavorite(account,aid,folder,true)
    override suspend fun coinBalance(account:String):Double? {
        val current=api.account()
        if(current.id.toString()!=account)throw PlatformFailure("账号会话已变化，旧余额已丢弃")
        return current.coinBalance
    }
    override suspend fun relations(bvid: String) = api.videoRelations(bvid)
    override suspend fun tags(bvid: String) = api.videoTags(bvid)
    override suspend fun like(account: String,bvid: String,liked: Boolean)=api.likeVideo(account,bvid,liked)
    override suspend fun coin(account: String,bvid: String,amount: Int)=api.coinVideo(account,bvid,amount)
    override suspend fun triple(account:String,bvid:String)=api.tripleVideo(account,bvid)
}

/** Durable intent before every POST; uncertain spending is resolved with reads, never replayed. */
class VideoEngagementRepository(private val port: VideoEngagementPort,private val accounts: AccountRepository,
    private val store: CollectionStore,private val clock: Clock,private val newId: ()->String) {
    private val writes=Mutex()
    suspend fun tags(bvid: String)=port.tags(bvid)
    suspend fun relations(bvid: String)=port.relations(bvid)
    suspend fun coinBalance(stamp:SessionStamp):Double? {
        accounts.requireCurrent(stamp)
        if(accounts.session.value.status!=SessionStatus.AUTHENTICATED)throw PlatformFailure("请先登录 B站账户",-101)
        val value=port.coinBalance(stamp.account)
        accounts.requireCurrent(stamp)
        return value?.takeIf{it.isFinite()&&it>=0}
    }
    suspend fun change(stamp: SessionStamp,video: Video,action: EngagementAction,amount: Int=0,favoriteFolder:Long?=null):EngagementResult=writes.withLock {
        accounts.requireCurrent(stamp)
        if(accounts.session.value.status!=SessionStatus.AUTHENTICATED)throw PlatformFailure("请先登录 B站账户",-101)
        if(store.mutation(stamp.account)!=null)throw PlatformFailure("上次操作结果待核对，请先核对")
        require(video.aid>0&&Bvid.parse(video.bvid)==video.bvid)
        val before=port.relations(video.bvid);accounts.requireCurrent(stamp)
        if(action==EngagementAction.TRIPLE)return@withLock tripleInFolder(port,accounts,store,clock,newId,stamp,video,favoriteFolder,before)
        if(action==EngagementAction.COIN) {
            if(amount !in 1..2)throw PlatformFailure("请选择投币枚数")
            if(video.owner.toString()==stamp.account)throw PlatformFailure("不能给自己的视频投币")
            val limit=when(video.copyright){1->2;2->1;else->throw PlatformFailure("未能核对稿件投币上限，请重新读取详情")}
            if(before.coins+amount>limit)throw PlatformFailure("超过该视频的投币上限")
        }
        val wantedLike=!before.liked
        val step=if(action==EngagementAction.LIKE)EngagementStep.LIKE else EngagementStep.COIN
        val intent=EngagementIntent(video.bvid,action,step,wantedLike,if(action==EngagementAction.COIN)before.coins+amount else before.coins)
        store.saveMutation(MutationRecord(newId(),stamp.account,video.aid,0,true,clock.nowMs(),engagement=intent))
        accounts.requireCurrent(stamp)
        val outcome=try {
            if(action==EngagementAction.LIKE)port.like(stamp.account,video.bvid,wantedLike) else port.coin(stamp.account,video.bvid,amount)
        }catch(e:CancellationException){throw e}catch(e:PlatformFailure){
            if(e.code!=null&&e.code !in 500..599){store.removeMutation(stamp.account);throw e}
            return@withLock EngagementResult("本次结果待核对，不会重复提交",true)
        }catch(_:Exception){return@withLock EngagementResult("本次结果待核对，不会重复提交",true)}
        accounts.requireCurrent(stamp)
        if(outcome==MutationOutcome.UNKNOWN)return@withLock EngagementResult("本次结果待核对，不会重复提交",true)
        store.removeMutation(stamp.account)
        EngagementResult(if(action==EngagementAction.COIN)"已投币 $amount 枚" else if(wantedLike)"已点赞" else "已取消点赞")
    }

    suspend fun reconcile(stamp: SessionStamp):EngagementResult=writes.withLock {
        accounts.requireCurrent(stamp)
        val record=store.mutation(stamp.account) ?: return@withLock EngagementResult("没有待核对的操作")
        val intent=record.engagement ?: throw PlatformFailure("待核对的是收藏或追更操作")
        val relation=port.relations(intent.bvid)
        accounts.requireCurrent(stamp)
        if(intent.step.folderTriple)return@withLock reconcileFolderTriple(port,accounts,store,stamp,record,relation)
        val confirmed=when(intent.step){EngagementStep.LIKE->relation.liked==intent.liked;EngagementStep.COIN->relation.coins>=intent.coinGoal;EngagementStep.TRIPLE->relation.liked&&relation.coins>0&&relation.favorite;else->false}
        accounts.requireCurrent(stamp)
        if(!confirmed)return@withLock EngagementResult("暂未确认成功，请稍后再次核对或前往 B站查看；不会重复投币",true)
        store.removeMutation(stamp.account)
        EngagementResult("操作结果已确认")
    }
}

data class VideoDetailsView(val bvid:String?=null,val video:Video?=null,val loading:Boolean=false,val error:String?=null,
    val tags:List<String> = emptyList(),val relations:VideoRelations?=null,val relationError:String?=null,val message:String="",
    val coinBalance:Double?=null,val coinBalanceLoading:Boolean=false)
