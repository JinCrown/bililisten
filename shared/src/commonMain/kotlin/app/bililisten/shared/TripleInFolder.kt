package app.bililisten.shared

import kotlinx.coroutines.CancellationException

/** Complete only missing actions, with the exact user-selected folder in every durable intent. */
internal suspend fun tripleInFolder(port:VideoEngagementPort,accounts:AccountRepository,store:CollectionStore,
    clock:Clock,newId:()->String,stamp:SessionStamp,video:Video,folderId:Long?,before:VideoRelations):EngagementResult {
    if(folderId==null||folderId<=0)throw PlatformFailure("请先设置默认收藏夹")
    val folder=port.folders(stamp.account.toLong(),video.aid).firstOrNull{it.id==folderId}
    accounts.requireCurrent(stamp)
    if(folder==null)return EngagementResult("默认收藏夹已不可用，请重新选择",unavailableFolder=folderId)
    val present=folder.contains ?: throw PlatformFailure("暂时无法核对默认收藏夹，请稍后重试")
    if(before.liked&&before.coins>0&&present)return EngagementResult("已完成三连，无需重复操作")
    if(before.coins==0) {
        if(video.owner.toString()==stamp.account)throw PlatformFailure("自己的投稿不能投币")
        if(video.copyright !in 1..2)throw PlatformFailure("未能核对稿件投币上限，请重新读取详情")
    }
    val coinGoal=if(before.coins>0)before.coins else if(video.copyright==1)2 else 1
    val completed=mutableListOf<String>().apply{if(before.liked)add("点赞");if(before.coins>0)add("投币");if(present)add("收藏")}
    fun partial(reason:String)=EngagementResult("三连未全部完成：${if(completed.isEmpty())"暂无已确认操作" else "已确认"+completed.joinToString("、")}；$reason")
    suspend fun post(step:EngagementStep,label:String,write:suspend ()->MutationOutcome):EngagementResult? {
        accounts.requireCurrent(stamp)
        val intent=EngagementIntent(video.bvid,EngagementAction.TRIPLE,step,true,coinGoal)
        store.saveMutation(MutationRecord(newId(),stamp.account,video.aid,folderId,true,clock.nowMs(),engagement=intent))
        accounts.requireCurrent(stamp)
        val result=try{write()}catch(e:CancellationException){throw e}catch(e:PlatformFailure){
            accounts.requireCurrent(stamp)
            if(e.code!=null&&e.code !in 500..599){store.removeMutation(stamp.account);return partial(e.category)}
            MutationOutcome.UNKNOWN
        }catch(_:Exception){MutationOutcome.UNKNOWN}
        accounts.requireCurrent(stamp)
        if(result==MutationOutcome.UNKNOWN)return EngagementResult("三连中的$label 结果待核对，不会重复提交",pending=true)
        // This step is confirmed. A later hold will re-read state if interrupted between steps.
        store.removeMutation(stamp.account)
        completed+=label
        return null
    }
    if(!before.liked)post(EngagementStep.TRIPLE_LIKE,"点赞"){port.like(stamp.account,video.bvid,true)}?.let{return it}
    if(before.coins==0)post(EngagementStep.TRIPLE_COIN,"投币"){port.coin(stamp.account,video.bvid,coinGoal)}?.let{return it}
    if(!present)post(EngagementStep.TRIPLE_FAVORITE,"收藏"){port.favorite(stamp.account.toLong(),video.aid,folderId)}?.let{return it}
    return EngagementResult("三连完成 · 已收藏到「${folder.title}」")
}

internal val EngagementStep.folderTriple:Boolean get()=this in setOf(EngagementStep.TRIPLE_LIKE,EngagementStep.TRIPLE_COIN,EngagementStep.TRIPLE_FAVORITE)

/** Reconciliation only reads. Confirming one step never repeats spending or resumes further writes. */
internal suspend fun reconcileFolderTriple(port:VideoEngagementPort,accounts:AccountRepository,store:CollectionStore,
    stamp:SessionStamp,record:MutationRecord,relation:VideoRelations):EngagementResult {
    val intent=requireNotNull(record.engagement)
    val folder=port.folders(stamp.account.toLong(),record.aid).firstOrNull{it.id==record.folder}
    accounts.requireCurrent(stamp)
    val confirmed=when(intent.step) {
        EngagementStep.TRIPLE_LIKE->relation.liked
        EngagementStep.TRIPLE_COIN->relation.coins>=intent.coinGoal
        EngagementStep.TRIPLE_FAVORITE->folder?.contains==true
        else->false
    }
    if(folder==null&&intent.step==EngagementStep.TRIPLE_FAVORITE) {
        store.removeMutation(stamp.account)
        return EngagementResult("目标收藏夹已删除，请重新设置默认收藏夹；已完成的点赞和投币保留",unavailableFolder=record.folder)
    }
    if(!confirmed)return EngagementResult("暂未确认成功，请稍后再次核对或前往 B站查看；不会重复提交",pending=true)
    store.removeMutation(stamp.account)
    val complete=relation.liked&&relation.coins>=intent.coinGoal&&folder?.contains==true
    return EngagementResult(if(complete)"三连结果已确认" else "上次操作已确认，三连尚未全部完成；再次长按点赞可补齐")
}
