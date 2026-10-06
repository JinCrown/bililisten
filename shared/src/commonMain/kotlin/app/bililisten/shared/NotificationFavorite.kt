package app.bililisten.shared

data class NotificationFavoriteResult(val message:String,val chooseFolder:Boolean=false)

/** Toggle only the configured folder after a fresh read; never retry unknown writes. */
class NotificationFavorite(private val accounts:AccountRepository,private val settings:SettingsRepository,
    private val content:ContentRepository,private val favorites:FavoriteRepository) {
    suspend fun toggle(bvid:String,stillCurrent:()->Boolean):NotificationFavoriteResult {
        val identity=accounts.session.value
        if(identity.status!=SessionStatus.AUTHENTICATED)return NotificationFavoriteResult("请登录后选择收藏夹",true)
        val account=identity.account ?: return NotificationFavoriteResult("请登录后选择收藏夹",true)
        val selected=settings.current().defaultFavoriteFolders[account.id.toString()]
            ?: return NotificationFavoriteResult("请选择默认收藏夹",true)
        fun checkTarget(){accounts.requireCurrent(identity.stamp);if(!stillCurrent())throw PlatformFailure("歌曲已切换，收藏未提交")}
        checkTarget()
        val video=content.video(bvid)
        require(video.bvid==bvid && video.aid>0)
        checkTarget()
        val folder=favorites.folders(account.id,video.aid).firstOrNull{it.id==selected.id}
            ?: return NotificationFavoriteResult("默认收藏夹已不可用，请重新选择",true)
        checkTarget()
        if(settings.current().defaultFavoriteFolders[account.id.toString()]?.id!=selected.id)throw PlatformFailure("默认收藏夹已改变，收藏未提交")
        checkTarget()
        if(folder.contains==null)throw PlatformFailure("暂时无法核对收藏关系，请稍后再试")
        if(favorites.pending(identity.stamp.account)!=null)throw PlatformFailure("上次收藏操作尚待核对，不会重复提交")
        checkTarget()
        val add=folder.contains!=true
        val outcome=favorites.change(account.id,video.aid,folder.id,add)
        return NotificationFavoriteResult(when(outcome) {
            MutationOutcome.CONFIRMED->if(add)"已收藏到「${folder.title}」" else "已从「${folder.title}」取消收藏"
            MutationOutcome.UNCHANGED->if(add)"已在「${folder.title}」中" else "已不在「${folder.title}」中"
            MutationOutcome.UNKNOWN->"收藏操作结果待核对，不会重复提交"
        })
    }
}
